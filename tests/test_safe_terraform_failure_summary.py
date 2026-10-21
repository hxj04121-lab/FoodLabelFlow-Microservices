import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from scripts.staging.summarize_terraform_failure import summarize_failure


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts/staging/summarize_terraform_failure.py"
WORKFLOW = ROOT / ".github/workflows/terraform-validate.yml"
SUMMARY_PREFIX = "::error::Terraform failure summary "


class SafeTerraformFailureSummaryTest(unittest.TestCase):
    def run_reporter(self, log_path: Path, exit_code: int = 17):
        env = os.environ.copy()
        env.pop("PYTHONPATH", None)
        return subprocess.run(
            [
                sys.executable,
                "-m",
                "scripts.staging.summarize_terraform_failure",
                "--step",
                "terraform_plan",
                "--exit-code",
                str(exit_code),
                "--log-file",
                str(log_path),
            ],
            cwd=ROOT,
            env=env,
            capture_output=True,
            text=True,
            check=False,
            timeout=10,
        )

    def test_output_contains_only_allowlisted_structured_fields(self):
        synthetic_log = """Error: AccessDenied while creating resource
  with module.eks.aws_eks_node_group.compliance,
  password=SYNTHETIC_PASSWORD_DO_NOT_PRINT
  token=SYNTHETIC_TOKEN_DO_NOT_PRINT
"""
        summary = summarize_failure(synthetic_log, step="terraform_plan", exit_code=17)

        self.assertEqual(
            summary,
            {
                "event": "terraform_failure",
                "step": "terraform_plan",
                "exit_code": 17,
                "category": "authorization",
                "resource_type": "aws_eks_node_group",
                "log_available": True,
                "log_truncated": False,
            },
        )
        encoded = json.dumps(summary)
        self.assertNotIn("SYNTHETIC_PASSWORD_DO_NOT_PRINT", encoded)
        self.assertNotIn("SYNTHETIC_TOKEN_DO_NOT_PRINT", encoded)
        self.assertNotIn("compliance", encoded)

    def test_subprocess_never_echoes_password_token_multiline_ansi_or_truncated_neighbors(self):
        markers = (
            "SYNTHETIC_PASSWORD_DO_NOT_PRINT",
            "SYNTHETIC_TOKEN_DO_NOT_PRINT",
            "MULTILINE_SECRET_LEFT_DO_NOT_PRINT",
            "MULTILINE_SECRET_RIGHT_DO_NOT_PRINT",
            "ANSI_SECRET_DO_NOT_PRINT",
            "BOUNDARY_SECRET_DO_NOT_PRINT",
            "TRUNCATED_NEIGHBOR_SECRET_DO_NOT_PRINT",
        )
        lines = [
            "Error: AccessDenied while creating resource",
            "  with module.eks.aws_eks_node_group.compliance,",
            f"  password={markers[0]}",
            f"  token={markers[1]}",
            f"  multi-line value begins {markers[2]}",
            f"  {markers[3]}",
            f"  \x1b[31m{markers[4]}\x1b[0m",
        ]
        for block in range(8):
            lines.append(f"Error: creating another redacted resource block-{block}")
            for detail in range(11):
                marker = ""
                # The first sentinel lands at output row 79; the next is just after head -n 80.
                if block == 5 and detail == 10:
                    marker = markers[5]
                if block == 6 and detail == 0:
                    marker = markers[6]
                lines.append(f"  diagnostic context line {block}-{detail} {marker}")

        with tempfile.TemporaryDirectory() as directory:
            log_path = Path(directory) / "synthetic-terraform.log"
            log_path.write_text("\n".join(lines), encoding="utf-8")
            result = self.run_reporter(log_path)

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue(result.stdout.startswith(SUMMARY_PREFIX))
        summary = json.loads(result.stdout[len(SUMMARY_PREFIX) :])
        self.assertEqual(summary["category"], "authorization")
        self.assertEqual(summary["resource_type"], "aws_eks_node_group")
        self.assertEqual(summary["step"], "terraform_plan")
        self.assertEqual(summary["exit_code"], 17)
        output = result.stdout + result.stderr
        for marker in markers:
            with self.subTest(marker=marker):
                self.assertNotIn(marker, output)
        self.assertNotIn("\x1b", output)
        self.assertNotIn("diagnostic context line", output)
        self.assertNotIn("module.eks", output)

    def test_unknown_diagnostic_and_missing_log_use_safe_fallbacks(self):
        with tempfile.TemporaryDirectory() as directory:
            missing_path = Path(directory) / "MISSING_PATH_VALUE_DO_NOT_PRINT.log"
            result = self.run_reporter(missing_path, exit_code=1)

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertNotIn("MISSING_PATH_VALUE_DO_NOT_PRINT", result.stdout + result.stderr)
        summary = json.loads(result.stdout[len(SUMMARY_PREFIX) :])
        self.assertEqual(summary["category"], "unclassified")
        self.assertEqual(summary["resource_type"], "unknown")
        self.assertFalse(summary["log_available"])

        unknown = summarize_failure("Error: proprietary value=DO_NOT_PRINT", step="terraform_plan", exit_code=1)
        self.assertEqual(unknown["category"], "unclassified")
        self.assertEqual(unknown["resource_type"], "unknown")
        self.assertNotIn("DO_NOT_PRINT", json.dumps(unknown))

    def test_fixed_categories_cover_common_failure_classes_without_echoing_matches(self):
        cases = (
            ("No valid credential sources found SYNTHETIC_CREDENTIAL_VALUE", "credentials"),
            ("AccessDenied SYNTHETIC_AUTH_VALUE", "authorization"),
            ("Error acquiring the state lock SYNTHETIC_STATE_VALUE", "state_backend"),
            ("Unsupported argument SYNTHETIC_CONFIG_VALUE", "configuration"),
            ("TooManyRequests SYNTHETIC_RATE_VALUE", "rate_limited"),
            ("Request timed out SYNTHETIC_NETWORK_VALUE", "network"),
            ("Error creating resource SYNTHETIC_RESOURCE_VALUE", "resource_operation"),
        )
        for diagnostic, expected_category in cases:
            with self.subTest(category=expected_category):
                summary = summarize_failure(diagnostic, step="terraform_plan", exit_code=1)
                self.assertEqual(summary["category"], expected_category)
                self.assertNotIn("SYNTHETIC_", json.dumps(summary))

    def test_oversized_synthetic_log_reports_truncation_without_echoing_prefix(self):
        secret_prefix = "SYNTHETIC_SECRET_IN_TRUNCATED_PREFIX_DO_NOT_PRINT"
        with tempfile.TemporaryDirectory() as directory:
            log_path = Path(directory) / "large-synthetic.log"
            log_path.write_text(
                secret_prefix + ("safe filler\n" * 40_000) + "Error: Unsupported argument\n",
                encoding="utf-8",
            )
            result = self.run_reporter(log_path, exit_code=1)

        self.assertEqual(result.returncode, 0, result.stderr)
        summary = json.loads(result.stdout[len(SUMMARY_PREFIX) :])
        self.assertTrue(summary["log_truncated"])
        self.assertEqual(summary["category"], "configuration")
        self.assertNotIn(secret_prefix, result.stdout + result.stderr)

    def test_workflow_uses_safe_summary_and_preserves_original_exit_code(self):
        workflow = WORKFLOW.read_text()
        plan_step = workflow.split("name: Save the complete read-only plan privately", 1)[1].split(
            "name: Verify State version remained unchanged during Plan", 1
        )[0]

        self.assertIn("-m scripts.staging.summarize_terraform_failure", plan_step)
        self.assertIn("plan_exit_code=$?", plan_step)
        self.assertIn("if ! (", plan_step)
        self.assertIn("failure summary could not be generated.", plan_step)
        self.assertIn('exit "$plan_exit_code"', plan_step)
        self.assertNotIn("awk", plan_step)
        self.assertNotIn("tail", plan_step)
        self.assertNotIn("Terraform error blocks follow", plan_step)
        self.assertIn('"scripts/staging/summarize_terraform_failure.py"', workflow)
        self.assertIn('"tests/test_safe_terraform_failure_summary.py"', workflow)
        self.assertIn("-p test_safe_terraform_failure_summary.py", workflow)

    def test_summary_step_and_resource_type_are_allowlisted(self):
        with self.assertRaisesRegex(ValueError, "Unsupported Terraform failure step"):
            summarize_failure("anything", step="secret-step-value", exit_code=1)
        with self.assertRaisesRegex(ValueError, "exit code must be"):
            summarize_failure("anything", step="terraform_plan", exit_code=0)

        unknown_resource = summarize_failure(
            'Error: failure\n  with module.anything.secret_resource.secret_name,',
            step="terraform_plan",
            exit_code=1,
        )
        self.assertEqual(unknown_resource["resource_type"], "unknown")
        self.assertNotIn("secret_name", json.dumps(unknown_resource))


if __name__ == "__main__":
    unittest.main()
