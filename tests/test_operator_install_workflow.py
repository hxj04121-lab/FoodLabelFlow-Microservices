from pathlib import Path
import re
import unittest

WORKFLOW = Path(__file__).resolve().parents[1] / ".github/workflows/install-rabbitmq-operators.yml"
LOCK = Path(__file__).resolve().parents[1] / "deploy/operators/rabbitmq/operator-manifests.lock"
READINESS = Path(__file__).resolve().parents[1] / "scripts/staging/check_operator_manifest_ownership.py"
RENDERER = Path(__file__).resolve().parents[1] / "scripts/staging/render_operator_install_manifest.py"
OFFLINE = Path(__file__).resolve().parents[1] / ".github/workflows/m5-platform-validation.yml"


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
        self.assertIn("endpointPrivateAccess", self.workflow)
        self.assertIn("aws sts get-caller-identity", self.workflow)
        self.assertIn("kubectl auth whoami", self.workflow)
        self.assertIn("aws_staging_budget.py --require-gate", self.workflow)

    def test_only_reviewed_lock_and_hash_pinned_manifests_are_installed(self):
        self.assertIn("c79ee4386fc120dc022f613da6808154a4171f1518f473e1f6d1a723d34a4517", self.workflow)
        self.assertIn("verify_operator_manifest_lock.py", self.workflow)
        self.assertIn("check_operator_manifest_ownership.py", self.workflow)
        self.assertIn("render_operator_install_manifest.py", self.workflow)
        self.assertEqual(self.workflow.count("kubectl create -f"), 3)
        self.assertNotRegex(self.workflow, r"kubectl\s+(?:apply|replace|patch|delete)\b")
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
