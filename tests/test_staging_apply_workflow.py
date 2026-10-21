import json
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import unittest

from scripts.staging.summarize_terraform_apply_failure import summarize_failure

APPLY_WORKFLOW = Path(__file__).resolve().parents[1] / ".github/workflows/staging-terraform-apply.yml"
PLAN_WORKFLOW = Path(__file__).resolve().parents[1] / ".github/workflows/terraform-validate.yml"
OFFLINE_WORKFLOW = Path(__file__).resolve().parents[1] / ".github/workflows/m5-platform-validation.yml"
APPLY_FAILURE_SUMMARY = Path(__file__).resolve().parents[1] / "scripts/staging/summarize_terraform_apply_failure.py"
SUMMARY_PREFIX = "::error::Terraform Apply failure summary "


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


class ProviderLockOrderTest(unittest.TestCase):
    def test_lockfile_is_hashed_after_init_like_the_plan_job(self):
        apply = APPLY_WORKFLOW.read_text()
        init = apply.index("name: Initialize only the approved source commit and staging workspace")
        self.assertGreater(apply.index("name: Pin the provider lockfile used by the saved Plan"), init)
        self.assertGreater(apply.index("name: Decrypt and verify Plan bytes and approvals"), init)
        plan = PLAN_WORKFLOW.read_text()
        self.assertGreater(plan.index('provider_lock_sha256="$(sha256sum .terraform.lock.hcl'),
                           plan.index("name: Initialize the existing protected state"))


class ApplyToolingTest(unittest.TestCase):
    def test_apply_installs_kubectl_before_using_it(self):
        apply = APPLY_WORKFLOW.read_text()
        self.assertIn("uses: azure/setup-kubectl@v4", apply)
        self.assertLess(apply.index("uses: azure/setup-kubectl@v4"), apply.index("kubectl config current-context"))


class ApplyFailureSummaryTest(unittest.TestCase):
    def run_reporter(self, log_path: Path, exit_code: int = 37):
        return subprocess.run(
            [
                sys.executable,
                str(APPLY_FAILURE_SUMMARY),
                "--exit-code",
                str(exit_code),
                "--log-file",
                str(log_path),
            ],
            cwd=Path(__file__).resolve().parents[1],
            capture_output=True,
            text=True,
            check=False,
            timeout=10,
        )

    def parse_summary(self, result):
        self.assertTrue(result.stdout.startswith(SUMMARY_PREFIX), result.stdout)
        return json.loads(result.stdout[len(SUMMARY_PREFIX) :])

    def test_error_provider_tfvars_and_inline_value_canaries_are_not_echoed(self):
        markers = (
            "SYNTHETIC_ERROR_CANARY",
            "SYNTHETIC_PROVIDER_TOKEN_CANARY",
            "SYNTHETIC_TFVARS_VALUE_CANARY",
            "SYNTHETIC_INLINE_PASSWORD_CANARY",
        )
        log_text = """Error: Provider returned SYNTHETIC_ERROR_CANARY
  with module.rds.aws_db_instance.staging,
  provider registry.example.invalid/acme/synthetic 7.8.9: token=SYNTHETIC_PROVIDER_TOKEN_CANARY
  var.password = "SYNTHETIC_TFVARS_VALUE_CANARY"
  password = "SYNTHETIC_INLINE_PASSWORD_CANARY"
"""
        summary = summarize_failure(log_text, exit_code=37)
        output = json.dumps(summary)

        self.assertEqual(summary["exit_code"], 37)
        self.assertEqual(summary["failure_type"], "terraform_apply_failed")
        self.assertEqual(summary["completed_resources"]["resource_count"], 0)
        for marker in markers:
            with self.subTest(marker=marker):
                self.assertNotIn(marker, output)
        self.assertNotIn("registry.example.invalid", output)
        self.assertNotIn("module.rds", output)

    def test_adjacent_debug_and_annotation_canaries_are_not_echoed(self):
        markers = (
            "SYNTHETIC_ADJACENT_DEBUG_CANARY",
            "SYNTHETIC_ANNOTATION_CANARY",
            "SYNTHETIC_TRACEBACK_CANARY",
        )
        log_text = "\n".join(
            (
                "Terraform debug context SYNTHETIC_ADJACENT_DEBUG_CANARY",
                "::error::SYNTHETIC_ANNOTATION_CANARY arbitrary annotation text",
                "Traceback (most recent call last): SYNTHETIC_TRACEBACK_CANARY",
            )
        )
        summary = summarize_failure(log_text, exit_code=1)
        encoded = json.dumps(summary)

        for marker in markers:
            with self.subTest(marker=marker):
                self.assertNotIn(marker, encoded)
        self.assertNotIn("Traceback", encoded)
        self.assertNotIn("::error::", encoded)

    def test_multiline_ansi_canaries_are_not_echoed(self):
        markers = (
            "SYNTHETIC_MULTILINE_LEFT_CANARY",
            "SYNTHETIC_MULTILINE_RIGHT_CANARY",
            "SYNTHETIC_ANSI_CANARY",
        )
        log_text = (
            "Error: multi-line failure " + markers[0] + "\n"
            "  continuation " + markers[1] + "\n"
            "  \x1b[31m" + markers[2] + "\x1b[0m\n"
        )
        with tempfile.TemporaryDirectory() as directory:
            log_path = Path(directory) / "synthetic-multiline-apply.log"
            log_path.write_text(log_text, encoding="utf-8")
            result = self.run_reporter(log_path)

        self.assertEqual(result.returncode, 0, result.stderr)
        for marker in markers:
            with self.subTest(marker=marker):
                self.assertNotIn(marker, result.stdout + result.stderr)
        self.assertNotIn("\x1b", result.stdout + result.stderr)

    def test_one_megabyte_single_line_is_bounded_without_echoing_canaries(self):
        first_marker = "SYNTHETIC_1MB_PREFIX_CANARY"
        tail_marker = "SYNTHETIC_1MB_TAIL_CANARY"
        payload = first_marker + ("x" * (1024 * 1024)) + tail_marker
        with tempfile.TemporaryDirectory() as directory:
            log_path = Path(directory) / "synthetic-large-apply.log"
            log_path.write_text(payload, encoding="utf-8")
            result = self.run_reporter(log_path, exit_code=23)

        self.assertEqual(result.returncode, 0, result.stderr)
        summary = self.parse_summary(result)
        self.assertTrue(summary["log_truncated"])
        self.assertEqual(summary["exit_code"], 23)
        for marker in (first_marker, tail_marker):
            with self.subTest(marker=marker):
                self.assertNotIn(marker, result.stdout + result.stderr)

    def test_completed_resources_emit_only_allowlisted_type_action_counts(self):
        markers = (
            "SYNTHETIC_NODEGROUP_NAME_CANARY",
            "SYNTHETIC_RDS_ADDRESS_CANARY",
            "SYNTHETIC_RESOURCE_ID_CANARY",
            "SYNTHETIC_UNKNOWN_TYPE_NAME_CANARY",
        )
        log_text = "\n".join(
            (
                f"module.eks.aws_eks_node_group.{markers[0]}[0]: Creation complete after 2m [id={markers[2]}]",
                f"module.db.aws_db_instance.{markers[1]}: Destruction complete after 8s [id={markers[2]}]",
                f"kubernetes_manifest.keycloak_role: Modifications complete after 3s [id={markers[2]}]",
                f"custom_sensitive_resource.{markers[3]}: Creation complete after 1s [id={markers[2]}]",
            )
        )
        summary = summarize_failure(log_text, exit_code=19)
        output = json.dumps(summary, sort_keys=True)

        self.assertEqual(
            summary["completed_resources"],
            {
                "resource_count": 3,
                "by_type_and_action": [
                    {"type": "aws_db_instance", "action": "delete", "count": 1},
                    {"type": "aws_eks_node_group", "action": "create", "count": 1},
                    {"type": "kubernetes_manifest", "action": "update", "count": 1},
                ],
            },
        )
        for marker in markers:
            with self.subTest(marker=marker):
                self.assertNotIn(marker, output)
        self.assertNotIn("keycloak_role", output)
        self.assertNotIn("custom_sensitive_resource", output)

    def test_missing_log_path_is_not_printed_and_workflow_preserves_apply_exit_code(self):
        with tempfile.TemporaryDirectory() as directory:
            missing_path = Path(directory) / "SYNTHETIC_MISSING_PATH_CANARY.log"
            result = self.run_reporter(missing_path, exit_code=41)

        self.assertEqual(result.returncode, 0, result.stderr)
        summary = self.parse_summary(result)
        self.assertFalse(summary["log_available"])
        self.assertEqual(summary["exit_code"], 41)
        self.assertNotIn("SYNTHETIC_MISSING_PATH_CANARY", result.stdout + result.stderr)

        workflow = APPLY_WORKFLOW.read_text()
        apply_start = workflow.index("name: Apply only the exact reviewed saved Plan")
        cleanup_start = workflow.index("name: Remove decrypted Plan and private Apply files", apply_start)
        apply_step = workflow[apply_start:cleanup_start]
        self.assertIn('if terraform apply -input=false -no-color "$TF_PLAN_FILE"', apply_step)
        self.assertIn("apply_exit_code=$?", apply_step)
        self.assertIn('exit "$apply_exit_code"', apply_step)
        self.assertNotIn("if ! terraform apply", apply_step)
        self.assertIn("summarize_terraform_apply_failure.py", apply_step)
        self.assertIn('2> "$APPLY_TEMP_DIR/terraform-apply-summary.err"', apply_step)
        self.assertIn("::warning::Safe Apply failure summary could not be generated.", apply_step)
        self.assertIn("it may have partially applied", apply_step)
        self.assertIn("Re-plan before any rerun", apply_step)
        for raw_log_filter in ("awk", "grep", "sed"):
            with self.subTest(raw_log_filter=raw_log_filter):
                self.assertNotIn(raw_log_filter, apply_step)
        self.assertIn('if: ${{ always() }}', workflow[cleanup_start:])
