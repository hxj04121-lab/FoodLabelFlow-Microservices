import unittest
from pathlib import Path


WORKFLOW = Path(__file__).resolve().parents[1] / ".github/workflows/terraform-validate.yml"


class TerraformPlanWorkflowGuardTest(unittest.TestCase):
    def test_plan_is_main_only_protected_and_read_only(self):
        workflow = WORKFLOW.read_text()

        self.assertIn("github.ref == 'refs/heads/main' && inputs.run_plan", workflow)
        self.assertIn("environment: staging", workflow)
        self.assertIn("runs-on: [self-hosted, linux, staging-vpc]", workflow)
        self.assertIn("id-token: write", workflow)
        self.assertIn("terraform plan -input=false", workflow)
        self.assertNotIn("terraform apply", workflow)
        self.assertNotIn("terraform workspace new", workflow)
        self.assertNotIn("terraform import", workflow)

    def test_plan_pins_state_version_workspace_and_existing_ownership(self):
        workflow = WORKFLOW.read_text()

        self.assertIn("expected_state_version_id:", workflow)
        self.assertIn('test "$actual_version_id" = "$EXPECTED_STATE_VERSION_ID"', workflow)
        self.assertIn('test "$after_version_id" = "$EXPECTED_STATE_VERSION_ID"', workflow)
        self.assertIn("workspace_key_prefix=spectrace", workflow)
        self.assertIn('terraform workspace select "$STAGING_TF_WORKSPACE"', workflow)
        self.assertIn('test "$(terraform workspace show)" = "$STAGING_TF_WORKSPACE"', workflow)
        for address in (
            "module.eks[0].aws_eks_cluster.this",
            "module.network.aws_vpc.this",
            "module.eks[0].aws_eks_node_group.general",
            "module.rds[0].aws_db_instance.this",
            "module.edge[0].aws_lb.this",
            "module.edge[0].aws_lb_target_group.gateway",
        ):
            self.assertIn(address, workflow)
        self.assertIn("does not prove there is no AWS drift", workflow)


if __name__ == "__main__":
    unittest.main()
