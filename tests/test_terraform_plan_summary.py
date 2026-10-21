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

    def test_controller_and_kubernetes_deletes_fail_closed(self):
        for resource_type, address in (
            ("helm_release", "module.eks_addons[0].helm_release.load_balancer_controller"),
            ("kubernetes_manifest", "module.eks_addons[0].kubernetes_manifest.workload_namespace[\"rabbitmq-system\"]"),
            ("aws_iam_role", "module.eks_addons[0].aws_iam_role.addons[\"aws_load_balancer_controller\"]"),
        ):
            with self.subTest(address=address):
                result = summarize([{
                    "address": address,
                    "mode": "managed",
                    "type": resource_type,
                    "change": {"actions": ["delete"]},
                }])
                self.assertEqual(result.returncode, 1)
                self.assertIn("Protected foundation destroy/replacement check: **FAIL**", result.stdout)
                self.assertIn(address, result.stdout)

    def test_node_group_iam_and_alb_changes_are_marked_without_values(self):
        result = summarize([
            {
                "address": "module.eks[0].aws_eks_node_group.general",
                "mode": "managed",
                "type": "aws_eks_node_group",
                "change": {"actions": ["update"], "before": {"scaling_config": {"desired_size": 2}}, "after": {"scaling_config": {"desired_size": 3}}},
            },
            {
                "address": "module.eks_addons[0].aws_iam_role.addons[\"ebs_csi\"]",
                "mode": "managed",
                "type": "aws_iam_role",
                "change": {"actions": ["update"], "before": {"assume_role_policy": "old-sensitive-policy"}, "after": {"assume_role_policy": "new-sensitive-policy"}},
            },
            {
                "address": "module.edge[0].aws_lb.this",
                "mode": "managed",
                "type": "aws_lb",
                "change": {"actions": ["update"]},
            },
            {
                "address": "aws_security_group_rule.gateway_from_alb[0]",
                "mode": "managed",
                "type": "aws_security_group_rule",
                "change": {"actions": ["update"]},
            },
        ])

        self.assertEqual(result.returncode, 0)
        self.assertIn("rolling-upgrade review; changed fields: scaling_config", result.stdout)
        self.assertIn("IAM permission-scope review required", result.stdout)
        self.assertIn("existing ALB modification requires explicit review", result.stdout)
        self.assertIn("`aws_security_group_rule.gateway_from_alb[0]` — `update` (existing ALB modification requires explicit review)", result.stdout)
        self.assertNotIn("old-sensitive-policy", result.stdout)
        self.assertNotIn("new-sensitive-policy", result.stdout)

    def test_plan_summary_groups_capacity_storage_access_and_affected_resources(self):
        result = summarize([
            {"address": "module.eks[0].aws_eks_node_group.general", "mode": "managed", "type": "aws_eks_node_group", "change": {"actions": ["update"]}},
            {"address": "module.eks[0].aws_eks_node_group.compliance[0]", "mode": "managed", "type": "aws_eks_node_group", "change": {"actions": ["create"]}},
            {"address": "aws_eks_access_entry.example", "mode": "managed", "type": "aws_eks_access_entry", "change": {"actions": ["create"]}},
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


class ReviewedCreateBeforeDestroyTest(unittest.TestCase):
    RULE = "module.eks[0].aws_security_group_rule.nodes_from_cluster"

    def change(self, address, actions):
        return [{"address": address, "mode": "managed", "type": "aws_security_group_rule", "change": {"actions": actions}}]

    def test_reviewed_rule_replaced_create_first_passes(self):
        result = summarize(self.change(self.RULE, ["create", "delete"]))
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertIn("Protected foundation destroy/replacement check: **PASS**", result.stdout)

    def test_destroy_first_or_other_rules_still_fail(self):
        self.assertEqual(summarize(self.change(self.RULE, ["delete", "create"])).returncode, 1)
        self.assertEqual(summarize(self.change(self.RULE, ["delete"])).returncode, 1)
        self.assertEqual(summarize(self.change("module.eks[0].aws_security_group_rule.nodes_self_dns_udp", ["create", "delete"])).returncode, 1)
