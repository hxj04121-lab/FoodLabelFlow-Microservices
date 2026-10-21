from pathlib import Path
import re
import unittest

APPLY_WORKFLOW = Path(__file__).resolve().parents[1] / ".github/workflows/staging-terraform-apply.yml"
PLAN_WORKFLOW = Path(__file__).resolve().parents[1] / ".github/workflows/terraform-validate.yml"
OFFLINE_WORKFLOW = Path(__file__).resolve().parents[1] / ".github/workflows/m5-platform-validation.yml"


class StagingTerraformApplyWorkflowTest(unittest.TestCase):
    def setUp(self):
        self.apply = APPLY_WORKFLOW.read_text()
        self.plan = PLAN_WORKFLOW.read_text()
        self.offline = OFFLINE_WORKFLOW.read_text()

    def test_apply_is_manual_main_only_protected_and_uses_private_runner(self):
        self.assertIn("workflow_dispatch:", self.apply)
        self.assertIn("github.ref == 'refs/heads/main'", self.apply)
        self.assertIn("github.ref != 'refs/heads/main'", self.apply)
        self.assertIn("runs-on: [self-hosted, linux, staging-vpc]", self.apply)
        self.assertIn("environment: staging", self.apply)
        self.assertIn("id-token: write", self.apply)
        self.assertIn("actions: read", self.apply)

    def test_uses_a_separate_apply_identity_and_fails_closed_on_target_mismatch(self):
        self.assertIn("vars.AWS_APPLY_ROLE_ARN", self.apply)
        self.assertIn('test "$AWS_ROLE_ARN" = arn:aws:iam::679857346122:role/spectrace-staging-github-apply', self.apply)
        self.assertNotIn("arn:aws:iam::679857346122:role/*)", self.apply)
        self.assertIn('assert c["resourcesVpcConfig"]["endpointPublicAccess"] is False', self.apply)
        self.assertIn("test \"$AWS_ROLE_ARN\" != \"$AWS_PLAN_ROLE_ARN\"", self.apply)
        self.assertIn("test \"$AWS_ROLE_ARN\" != \"$AWS_DEPLOY_ROLE_ARN\"", self.apply)
        self.assertIn("test \"$AWS_ACCOUNT_ID\" = 679857346122", self.apply)
        self.assertIn("test \"$AWS_REGION\" = ap-southeast-1", self.apply)
        self.assertIn("test \"$EKS_CLUSTER_NAME\" = spectrace-staging", self.apply)
        self.assertIn("endpointPrivateAccess", self.apply)
        self.assertIn("kubectl config current-context", self.apply)
        self.assertIn("APPROVED_STATE_VERSION_ID", self.apply)
        self.assertIn("aws s3api head-object", self.apply)

    def test_workspace_is_not_overridden_by_the_tf_workspace_environment_variable(self):
        # Terraform refuses `workspace select` while TF_WORKSPACE is set in the environment.
        self.assertNotRegex(self.apply, r"(?m)^\s+TF_WORKSPACE:")
        self.assertIn("STAGING_TF_WORKSPACE: ${{ vars.TF_WORKSPACE }}", self.apply)
        self.assertIn('terraform workspace select "$STAGING_TF_WORKSPACE"', self.apply)

    def test_only_a_successful_exact_main_plan_run_and_artifact_can_be_applied(self):
        self.assertIn("event !== 'workflow_dispatch'", self.apply)
        self.assertIn("run.head_branch !== 'main'", self.apply)
        self.assertIn("run.head_sha !== process.env.APPROVED_COMMIT_SHA", self.apply)
        self.assertIn("job.name === 'plan' && job.conclusion === 'success'", self.apply)
        self.assertIn("terraform-staging-plan-${id}", self.apply)
        self.assertIn("actions/download-artifact@v4", self.apply)
        self.assertIn("approved_plan_sha256", self.apply)
        self.assertIn("secure_tfplan.mjs\" decrypt", self.apply)
        self.assertIn("EXPECTED_PROVIDER_LOCK_SHA256", self.apply)

    def test_guard_is_rerun_before_apply_and_saved_plan_is_the_only_apply_input(self):
        guard = self.apply.index("name: Re-run protected resource action guard")
        apply = self.apply.index("terraform apply -input=false -no-color \"$TF_PLAN_FILE\"")
        self.assertLess(guard, apply)
        self.assertEqual(len(re.findall(r"terraform apply\b", self.apply)), 1)
        self.assertNotRegex(self.apply, r"terraform apply[^\n]*-var(?:=|\s)")
        self.assertNotIn("terraform import", self.apply)
        self.assertNotIn("terraform destroy", self.apply)
        self.assertNotIn("-auto-approve", self.apply)
        self.assertIn('path == workspace / "plan-source"', self.apply)

    def test_plan_file_is_authenticated_encrypted_and_short_lived(self):
        self.assertIn("TF_PLAN_ENCRYPTION_KEY", self.plan)
        self.assertIn("secure_tfplan.mjs\" encrypt", self.plan)
        self.assertIn("actions/upload-artifact@v4", self.plan)
        self.assertIn("retention-days: 1", self.plan)
        self.assertIn("staging.tfplan.enc", self.plan)
        self.assertIn("staging-plan-metadata.json", self.plan)
        self.assertIn("printf -- '- Source commit: `%s`\\n'", self.plan)
        self.assertIn('export M5_TF_PLAN_PROVIDER_LOCK_SHA256="$provider_lock_sha256"', self.plan)
        self.assertNotIn('echo "- Source commit: `', self.plan)
        self.assertIn("if: ${{ always() }}", self.plan)
        self.assertIn("terraform plan -input=false", self.plan)
        self.assertNotIn("terraform apply", self.plan)
        self.assertIn(".github/workflows/staging-terraform-apply.yml", self.offline)
        match = re.search(r"workflows=\((.*?)\n\s*\)", self.offline, re.S)
        self.assertIsNotNone(match)
        self.assertNotIn(".github/workflows/terraform-validate.yml", match.group(1))
        self.assertIn('if [[ -f "$workflow" ]]', self.offline)
        self.assertIn('"$RUNNER_TEMP/actionlint" "${present[@]}"', self.offline)


if __name__ == "__main__":
    unittest.main()
