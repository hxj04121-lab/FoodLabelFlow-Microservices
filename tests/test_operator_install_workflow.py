import json
import os
import re
import subprocess
import sys
import unittest
from pathlib import Path

from scripts.staging.validate_operator_install_preflight import (
    validate_operator_caller_arn,
    validate_operator_identity,
    validate_private_staging_cluster,
)

WORKFLOW = Path(__file__).resolve().parents[1] / ".github/workflows/install-rabbitmq-operators.yml"
LOCK = Path(__file__).resolve().parents[1] / "deploy/operators/rabbitmq/operator-manifests.lock"
READINESS = Path(__file__).resolve().parents[1] / "scripts/staging/check_operator_manifest_ownership.py"
RENDERER = Path(__file__).resolve().parents[1] / "scripts/staging/render_operator_install_manifest.py"
OFFLINE = Path(__file__).resolve().parents[1] / ".github/workflows/m5-platform-validation.yml"
ROOT = Path(__file__).resolve().parents[1]
PREFLIGHT = ROOT / "scripts/staging/validate_operator_install_preflight.py"
DEPLOY_ROLE_ARN = "arn:aws:iam::679857346122:role/FoodLabelFlowStagingDeploy"
ACCOUNT_ID = "679857346122"
ROLE_USERNAME = "arn:aws:sts::679857346122:assumed-role/FoodLabelFlowStagingDeploy/gh-runner"


class OperatorInstallWorkflowTest(unittest.TestCase):
    def setUp(self):
        self.workflow = WORKFLOW.read_text()

    def test_workflow_is_manual_main_only_and_protected_on_private_runner(self):
        self.assertIn("workflow_dispatch:", self.workflow)
        self.assertIn("github.ref == 'refs/heads/main'", self.workflow)
        self.assertIn("github.ref != 'refs/heads/main'", self.workflow)
        self.assertIn("runs-on: [self-hosted, linux, staging-vpc]", self.workflow)
        self.assertIn("environment: staging", self.workflow)
        self.assertIn("id-token: write", self.workflow)

    def test_dedicated_identity_budget_and_exact_cluster_are_required(self):
        self.assertIn("AWS_ROLE_ARN: ${{ vars.AWS_DEPLOY_ROLE_ARN }}", self.workflow)
        self.assertNotIn("AWS_RABBITMQ_OPERATOR_INSTALL_ROLE_ARN", self.workflow)
        self.assertIn("test \"$AWS_ROLE_ARN\" = arn:aws:iam::679857346122:role/FoodLabelFlowStagingDeploy", self.workflow)
        self.assertIn("test \"$AWS_ROLE_ARN\" != \"$AWS_PLAN_ROLE_ARN\"", self.workflow)
        self.assertIn("test \"$AWS_ACCOUNT_ID\" = 679857346122", self.workflow)
        self.assertIn("test \"$AWS_REGION\" = ap-southeast-1", self.workflow)
        self.assertIn("test \"$EKS_CLUSTER_NAME\" = spectrace-staging", self.workflow)
        preflight = PREFLIGHT.read_text()
        self.assertIn("endpointPrivateAccess", preflight)
        self.assertIn("endpointPublicAccess", preflight)
        self.assertIn("aws sts get-caller-identity", self.workflow)
        self.assertIn("kubectl auth whoami", self.workflow)
        self.assertIn("aws_staging_budget.py --require-gate", self.workflow)
        self.assertIn("test \"$AWS_ROLE_ARN\" = arn:aws:iam::679857346122:role/FoodLabelFlowStagingDeploy", self.workflow)
        self.assertIn("test \"$AWS_ACCOUNT_ID\" = 679857346122", self.workflow)

    def test_operator_identity_checks_fail_closed_for_wrong_account_role_or_session(self):
        self.assertTrue(validate_operator_caller_arn(ROLE_USERNAME, ACCOUNT_ID, DEPLOY_ROLE_ARN))
        self.assertTrue(
            validate_operator_identity(
                {"status": {"userInfo": {"username": ROLE_USERNAME}}}, ACCOUNT_ID, DEPLOY_ROLE_ARN
            )
        )
        for username, account, role in (
            ("arn:aws:sts::111122223333:assumed-role/FoodLabelFlowStagingDeploy/gh-runner", ACCOUNT_ID, DEPLOY_ROLE_ARN),
            ("arn:aws:sts::679857346122:assumed-role/OtherRole/gh-runner", ACCOUNT_ID, DEPLOY_ROLE_ARN),
            ("arn:aws:sts::679857346122:assumed-role/FoodLabelFlowStagingDeploy/", ACCOUNT_ID, DEPLOY_ROLE_ARN),
            ("arn:aws:sts::679857346122:assumed-role/FoodLabelFlowStagingDeploy/a/session", ACCOUNT_ID, DEPLOY_ROLE_ARN),
            ("FoodLabelFlowStagingDeploy", ACCOUNT_ID, DEPLOY_ROLE_ARN),
            (ROLE_USERNAME, "not-an-account", DEPLOY_ROLE_ARN),
            (ROLE_USERNAME, ACCOUNT_ID, "arn:aws:iam::111122223333:role/FoodLabelFlowStagingDeploy"),
        ):
            with self.subTest(username=username, account=account, role=role):
                self.assertFalse(validate_operator_caller_arn(username, account, role))
                self.assertFalse(
                    validate_operator_identity({"status": {"userInfo": {"username": username}}}, account, role)
                )

    def test_exact_active_cluster_requires_private_endpoint_and_public_endpoint_disabled(self):
        cluster = {
            "cluster": {
                "status": "ACTIVE",
                "arn": "arn:aws:eks:ap-southeast-1:679857346122:cluster/spectrace-staging",
                "resourcesVpcConfig": {"endpointPrivateAccess": True, "endpointPublicAccess": False},
            }
        }
        args = (ACCOUNT_ID, "ap-southeast-1", "spectrace-staging")
        self.assertTrue(validate_private_staging_cluster(cluster, *args))

        for mutation in (
            {"endpointPrivateAccess": True, "endpointPublicAccess": True},
            {"endpointPrivateAccess": True},
            {"endpointPublicAccess": False},
        ):
            bad_cluster = json.loads(json.dumps(cluster))
            bad_cluster["cluster"]["resourcesVpcConfig"] = mutation
            with self.subTest(vpc_config=mutation):
                self.assertFalse(validate_private_staging_cluster(bad_cluster, *args))

    def test_operator_preflight_cli_runs_as_a_module_without_pythonpath(self):
        env = os.environ.copy()
        env.pop("PYTHONPATH", None)
        env.update(
            AWS_ACCOUNT_ID=ACCOUNT_ID,
            AWS_ROLE_ARN=DEPLOY_ROLE_ARN,
            AWS_REGION="ap-southeast-1",
            EKS_CLUSTER_NAME="spectrace-staging",
        )
        user = {"status": {"userInfo": {"username": ROLE_USERNAME}}}
        completed = subprocess.run(
            [sys.executable, "-m", "scripts.staging.validate_operator_install_preflight", "identity"],
            input=json.dumps(user),
            cwd=ROOT,
            env=env,
            capture_output=True,
            text=True,
            timeout=10,
            check=False,
        )
        self.assertEqual(completed.returncode, 0, completed.stderr)
        self.assertIn("approved Operator Deploy role", completed.stdout)

    def test_operator_preflight_cli_checks_caller_arn_and_private_only_cluster(self):
        env = os.environ.copy()
        env.pop("PYTHONPATH", None)
        env.update(
            AWS_ACCOUNT_ID=ACCOUNT_ID,
            AWS_ROLE_ARN=DEPLOY_ROLE_ARN,
            AWS_REGION="ap-southeast-1",
            EKS_CLUSTER_NAME="spectrace-staging",
        )

        def run_check(check, payload):
            return subprocess.run(
                [sys.executable, "-m", "scripts.staging.validate_operator_install_preflight", check],
                input=payload,
                cwd=ROOT,
                env=env,
                capture_output=True,
                text=True,
                timeout=10,
                check=False,
            )

        caller = run_check("caller-arn", ROLE_USERNAME)
        self.assertEqual(caller.returncode, 0, caller.stderr)
        rejected_caller = run_check(
            "caller-arn", "arn:aws:sts::679857346122:assumed-role/OtherRole/gh-runner"
        )
        self.assertNotEqual(rejected_caller.returncode, 0)

        cluster = {
            "cluster": {
                "status": "ACTIVE",
                "arn": "arn:aws:eks:ap-southeast-1:679857346122:cluster/spectrace-staging",
                "resourcesVpcConfig": {"endpointPrivateAccess": True, "endpointPublicAccess": False},
            }
        }
        accepted_cluster = run_check("cluster", json.dumps(cluster))
        self.assertEqual(accepted_cluster.returncode, 0, accepted_cluster.stderr)
        cluster["cluster"]["resourcesVpcConfig"]["endpointPublicAccess"] = True
        rejected_cluster = run_check("cluster", json.dumps(cluster))
        self.assertNotEqual(rejected_cluster.returncode, 0)

    def test_operator_manifest_clis_help_from_clean_module_environment(self):
        env = os.environ.copy()
        env.pop("PYTHONPATH", None)
        for module in (
            "scripts.staging.check_operator_manifest_ownership",
            "scripts.staging.render_operator_install_manifest",
        ):
            with self.subTest(module=module):
                completed = subprocess.run(
                    [sys.executable, "-m", module, "--help"],
                    cwd=ROOT,
                    env=env,
                    capture_output=True,
                    text=True,
                    timeout=10,
                    check=False,
                )
                self.assertEqual(completed.returncode, 0, completed.stderr)
                self.assertIn("usage:", completed.stdout.lower())

    def test_only_reviewed_lock_and_hash_pinned_manifests_are_installed(self):
        self.assertIn("c79ee4386fc120dc022f613da6808154a4171f1518f473e1f6d1a723d34a4517", self.workflow)
        self.assertIn("verify_operator_manifest_lock.py", self.workflow)
        self.assertIn("scripts.staging.check_operator_manifest_ownership", self.workflow)
        self.assertIn("scripts.staging.render_operator_install_manifest", self.workflow)
        self.assertEqual(self.workflow.count("kubectl create -f"), 3)
        self.assertNotRegex(self.workflow, r"kubectl\s+(?:apply|replace|patch|delete)\b")
        self.assertIn("python3 -m scripts.staging.check_operator_manifest_ownership", self.workflow)
        self.assertIn("python3 -m scripts.staging.render_operator_install_manifest", self.workflow)
        self.assertNotIn("python3 scripts/staging/check_operator_manifest_ownership.py", self.workflow)
        self.assertNotIn("python3 scripts/staging/render_operator_install_manifest.py", self.workflow)
        self.assertNotIn("keycloak-operator", self.workflow)
        lock = LOCK.read_text()
        for filename in ("cert-manager.yaml", "rabbitmq-cluster-operator.yaml", "messaging-topology-operator.yaml"):
            self.assertIn(filename, lock)
        self.assertLess(self.workflow.index("kubectl create -f \"$manifest_dir/cert-manager.yaml\""), self.workflow.index("kubectl create -f \"$manifest_dir/rabbitmq-cluster-operator.yaml\""))
        self.assertLess(self.workflow.index("kubectl create -f \"$manifest_dir/rabbitmq-cluster-operator.yaml\""), self.workflow.index("kubectl create -f \"$manifest_dir/messaging-topology-operator-install.yaml\""))

    def test_preexisting_or_unreadable_target_stops_and_cleanup_is_always(self):
        self.assertIn("Pre-existing target", READINESS.read_text())
        self.assertIn("Cannot verify ownership/absence", READINESS.read_text())
        self.assertIn("if: always()", self.workflow)
        self.assertIn("rm -rf \"$RUNNER_TEMP/rabbitmq-operator-manifests\"", self.workflow)

    def test_actionlint_targets_only_workflows_relevant_to_this_change(self):
        offline = OFFLINE.read_text()
        match = re.search(r"workflows=\((.*?)\n\s*\)", offline, re.S)
        self.assertIsNotNone(match)
        targets = match.group(1)
        self.assertIn(".github/workflows/install-rabbitmq-operators.yml", targets)
        self.assertNotIn(".github/workflows/terraform-validate.yml", targets)

    def test_shared_namespace_is_removed_only_after_verified_lock_validation(self):
        renderer = RENDERER.read_text()
        self.assertIn("rows = verify(lock_path, manifest_dir)", renderer)
        self.assertIn('document.get("metadata", {}).get("name") == "rabbitmq-system"', renderer)
        self.assertIn("must declare rabbitmq-system exactly once", renderer)


if __name__ == "__main__":
    unittest.main()
