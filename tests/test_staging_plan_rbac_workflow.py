import subprocess
import unittest
from pathlib import Path
import re

import yaml


ROOT = Path(__file__).resolve().parents[1]
WORKFLOW_PATH = ROOT / ".github/workflows/staging-plan-rbac-bootstrap.yml"
MANIFEST_PATH = "infra/terraform/manual/plan-readonly-rbac.yaml"
MANIFEST_SHA256 = "1733291f9f42f2dd242f7de91ce564c7e381d93a0b39570e16694d326aad12b3"


class StagingPlanRbacWorkflowTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.workflow_text = WORKFLOW_PATH.read_text()
        cls.workflow = yaml.load(cls.workflow_text, Loader=yaml.BaseLoader)
        cls.job = cls.workflow["jobs"]["bootstrap"]
        cls.steps = cls.job["steps"]

    def test_workflow_is_manual_main_only_and_uses_the_protected_vpc_environment(self):
        self.assertEqual(self.workflow["on"], {"workflow_dispatch": ""})
        self.assertEqual(self.job["if"], "github.event_name == 'workflow_dispatch' && github.ref == 'refs/heads/main'")
        self.assertEqual(self.job["runs-on"], ["self-hosted", "linux", "staging-vpc"])
        self.assertEqual(self.job["environment"], "staging")
        self.assertEqual(self.job["permissions"], {"contents": "read", "id-token": "write"})
        self.assertNotIn("inputs.", self.workflow_text)

    def test_only_the_reviewed_fixed_manifest_can_be_created(self):
        self.assertIn(MANIFEST_PATH, self.workflow_text)
        self.assertIn(MANIFEST_SHA256, self.workflow_text)
        self.assertIn('kubectl create --field-manager=foodlabelflow-plan-rbac-bootstrap -f "$manifest"', self.workflow_text)
        self.assertNotIn("kubectl apply ", self.workflow_text)
        self.assertNotIn("kubectl replace ", self.workflow_text)
        self.assertNotIn("kubectl delete ", self.workflow_text)
        self.assertNotIn("kubectl patch ", self.workflow_text)
        self.assertNotIn("terraform ", self.workflow_text)
        self.assertNotIn("helm ", self.workflow_text)
        self.assertNotIn("get secrets", self.workflow_text)
        self.assertNotIn("--force-conflicts", self.workflow_text)
        self.assertLess(
            self.workflow_text.index("for object in \"${objects[@]}\""),
            self.workflow_text.index('kubectl create --field-manager=foodlabelflow-plan-rbac-bootstrap -f "$manifest"'),
        )
        self.assertIn("if (( ${#existing[@]} > 0 )); then", self.workflow_text)
        for name in (
            "spectrace-staging-plan-readonly",
            "spectrace-staging-plan-lbc-deployment-reader",
            "spectrace-staging-github-plan-readonly",
            "kube-system",
        ):
            self.assertIn(name, self.workflow_text)

    def test_identity_and_private_cluster_are_pinned_and_existing_objects_fail_closed(self):
        self.assertIn("679857346122", self.workflow_text)
        self.assertIn("spectrace-staging", self.workflow_text)
        self.assertIn("FoodLabelFlowStagingDeploy", self.workflow_text)
        self.assertIn("platform/gateway/validate-deploy-target.py", self.workflow_text)
        self.assertIn("kubectl config current-context", self.workflow_text)
        self.assertIn("endpointPrivateAccess", self.workflow_text)
        self.assertIn("endpointPublicAccess", self.workflow_text)
        self.assertIn("--ignore-not-found", self.workflow_text)
        self.assertIn("At least one target RBAC object already exists", self.workflow_text)
        self.assertIn("AmazonEKSClusterAdminPolicy", self.workflow_text)
        self.assertIn("if: always()", self.workflow_text)

    def test_secret_reads_are_verified_as_denied(self):
        self.assertIn('for verb in get list watch; do check no "$verb" secrets kube-system; done', self.workflow_text)
        self.assertIn("check yes get deployments.apps/aws-load-balancer-controller kube-system", self.workflow_text)
        self.assertNotIn("check yes \"$verb\" secrets", self.workflow_text)

    def test_shell_steps_parse_as_bash(self):
        python_count = 0
        for step in self.steps:
            if step.get("run"):
                subprocess.run(
                    ["bash", "-n"], input=step["run"], text=True, check=True,
                    capture_output=True, timeout=15,
                )
                for script in re.findall(r"<<'PY'\n(.*?)\nPY(?:\n|$)", step["run"], re.DOTALL):
                    compile(script, str(WORKFLOW_PATH), "exec")
                    python_count += 1
        self.assertEqual(python_count, 3)
