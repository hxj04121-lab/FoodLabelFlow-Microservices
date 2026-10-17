import json
import subprocess
import sys
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "terraform_plan_summary.py"


def summarize(changes):
    return subprocess.run(
        [sys.executable, str(SCRIPT)],
        input=json.dumps({"resource_changes": changes}),
        capture_output=True,
        text=True,
        check=False,
    )


class TerraformPlanSummaryTest(unittest.TestCase):
    def test_create_reports_only_resource_address_and_action(self):
        result = summarize([{
            "address": "module.eks_addons[0].helm_release.load_balancer_controller",
            "mode": "managed",
            "type": "helm_release",
            "change": {"actions": ["create"], "after": {"set_sensitive": "must-not-print"}},
        }])

        self.assertEqual(result.returncode, 0)
        self.assertIn("`module.eks_addons[0].helm_release.load_balancer_controller` — `create`", result.stdout)
        self.assertNotIn("must-not-print", result.stdout)

    def test_in_place_foundation_update_is_reported_for_review(self):
        result = summarize([{
            "address": "module.eks[0].aws_eks_node_group.general",
            "mode": "managed",
            "type": "aws_eks_node_group",
            "change": {"actions": ["update"]},
        }])

        self.assertEqual(result.returncode, 0)
        self.assertIn("`module.eks[0].aws_eks_node_group.general` — `update`", result.stdout)
        self.assertIn("Protected foundation destroy/replacement check: **PASS**", result.stdout)

    def test_protected_replacement_fails_without_printing_values(self):
        result = summarize([{
            "address": "module.rds[0].aws_db_instance.this",
            "mode": "managed",
            "type": "aws_db_instance",
            "change": {"actions": ["delete", "create"], "after": {"password": "must-not-print"}},
        }])

        self.assertEqual(result.returncode, 1)
        self.assertIn("Protected foundation destroy/replacement check: **FAIL**", result.stdout)
        self.assertIn("`module.rds[0].aws_db_instance.this` — `replace`", result.stdout)
        self.assertNotIn("must-not-print", result.stdout)


if __name__ == "__main__":
    unittest.main()
