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
        self.assertIn("Resource counts: create **1**, update **0**, replace **0**, delete **0**.", result.stdout)
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
        self.assertIn("Resource counts: create **0**, update **0**, replace **1**, delete **0**.", result.stdout)
        self.assertNotIn("must-not-print", result.stdout)

    def test_delete_and_create_are_not_counted_as_two_resources_for_replacement(self):
        result = summarize([{
            "address": "module.eks[0].aws_eks_node_group.general",
            "mode": "managed",
            "type": "aws_eks_node_group",
            "change": {"actions": ["create", "delete"]},
        }])

        self.assertEqual(result.returncode, 1)
        self.assertIn("Resource counts: create **0**, update **0**, replace **1**, delete **0**.", result.stdout)

    def test_plan_summary_groups_capacity_storage_access_and_affected_resources(self):
        result = summarize([
            {"address": "module.eks[0].aws_eks_node_group.general", "mode": "managed", "type": "aws_eks_node_group", "change": {"actions": ["update"]}},
            {"address": "module.eks[0].aws_eks_node_group.compliance[0]", "mode": "managed", "type": "aws_eks_node_group", "change": {"actions": ["create"]}},
            {"address": "aws_eks_access_entry.rabbitmq_operator_install", "mode": "managed", "type": "aws_eks_access_entry", "change": {"actions": ["create"]}},
            {"address": "module.eks_addons[0].aws_eks_addon.ebs_csi", "mode": "managed", "type": "aws_eks_addon", "change": {"actions": ["create"]}},
            {"address": "module.eks_addons[0].kubernetes_manifest.ebs_gp3_storage_class", "mode": "managed", "type": "kubernetes_manifest", "change": {"actions": ["create"]}},
            {"address": "module.eks_addons[0].kubernetes_manifest.workload_namespace[\"rabbitmq\"]", "mode": "managed", "type": "kubernetes_manifest", "change": {"actions": ["create"]}},
            {"address": "module.eks_addons[0].kubernetes_manifest.rabbitmq_deployer_role", "mode": "managed", "type": "kubernetes_manifest", "change": {"actions": ["create"]}},
            {"address": "module.edge[0].aws_lb_listener_rule.gateway", "mode": "managed", "type": "aws_lb_listener_rule", "change": {"actions": ["update"]}},
        ])

        self.assertEqual(result.returncode, 0)
        self.assertIn("### General and Compliance node groups", result.stdout)
        self.assertIn("### EBS CSI, StorageClass, and workload Namespaces", result.stdout)
        self.assertIn("### IAM, RBAC, and EKS Access Entries", result.stdout)
        self.assertIn("### Existing resources that may be affected", result.stdout)
        self.assertIn("`module.edge[0].aws_lb_listener_rule.gateway` — `update`", result.stdout)


if __name__ == "__main__":
    unittest.main()
