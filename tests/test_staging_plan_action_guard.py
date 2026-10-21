import unittest

from scripts.staging.guard_staging_plan_actions import PROTECTED_ADDRESSES, check_plan


def plan(*resource_changes):
    return {
        "format_version": "1.2",
        "terraform_version": "1.10.0",
        "planned_values": {"root_module": {}},
        "resource_changes": list(resource_changes),
    }


def resource(address, actions, resource_type="kubernetes_manifest", mode="managed"):
    return {
        "address": address,
        "type": resource_type,
        "mode": mode,
        "change": {"actions": actions},
    }


class StagingPlanActionGuardTest(unittest.TestCase):
    def test_noop_on_existing_protected_address_is_allowed(self):
        address = sorted(PROTECTED_ADDRESSES)[0]
        self.assertEqual(check_plan(plan(resource(address, ["no-op"]))), [])

    def test_any_non_noop_lbc_iam_or_existing_namespace_action_is_rejected(self):
        for address in sorted(PROTECTED_ADDRESSES):
            for action in (["create"], ["update"], ["delete"], ["delete", "create"]):
                with self.subTest(address=address, action=action):
                    issues = check_plan(plan(resource(address, action)))
                    self.assertEqual(len(issues), 1)
                    self.assertIn("forbidden", issues[0])

    def test_alb_and_acm_mutations_fail_by_resource_type(self):
        protected_types = (
            "aws_lb",
            "aws_lb_listener",
            "aws_lb_listener_rule",
            "aws_lb_listener_certificate",
            "aws_lb_target_group",
            "aws_lb_target_group_attachment",
            "aws_acm_certificate",
            "aws_acm_certificate_validation",
            "aws_acm_certificate_authority",
        )
        risky_actions = (["update"], ["delete"], ["delete", "create"], ["create", "delete"], ["forget"])
        for resource_type in protected_types:
            for actions in risky_actions:
                with self.subTest(resource_type=resource_type, actions=actions):
                    address = f"module.edge[0].{resource_type}.existing"
                    issues = check_plan(plan(resource(address, actions, resource_type)))
                    self.assertEqual(len(issues), 1)
                    self.assertIn(resource_type, issues[0])

    def test_noop_existing_alb_and_acm_data_reads_are_allowed(self):
        changes = [
            resource("module.edge[0].aws_lb.this", ["no-op"], "aws_lb"),
            resource("data.aws_acm_certificate.internal", ["read"], "aws_acm_certificate", "data"),
        ]
        self.assertEqual(check_plan(plan(*changes)), [])

    def test_unrelated_infrastructure_creates_are_not_blocked(self):
        changes = [
            resource("module.eks[0].aws_eks_node_group.compliance", ["create"], "aws_eks_node_group"),
            resource("module.eks_addons[0].aws_eks_addon.ebs_csi", ["create"], "aws_eks_addon"),
        ]
        self.assertEqual(check_plan(plan(*changes)), [])

    def test_missing_or_malformed_changes_fail_closed(self):
        with self.assertRaisesRegex(ValueError, "resource_changes"):
            check_plan({"format_version": "1.2", "terraform_version": "1.10.0", "planned_values": {}})
        with self.assertRaisesRegex(ValueError, "malformed"):
            check_plan(plan(None))

    def test_incomplete_or_unknown_plan_fields_fail_closed(self):
        with self.assertRaisesRegex(ValueError, "unknown format"):
            check_plan({})
        for format_version in ("2.0", "invalid"):
            with self.subTest(format_version=format_version), self.assertRaisesRegex(ValueError, "unknown format"):
                malformed = plan()
                malformed["format_version"] = format_version
                check_plan(malformed)
        with self.assertRaisesRegex(ValueError, "incomplete"):
            check_plan(plan({"address": "aws_lb.this", "mode": "managed", "change": {"actions": ["update"]}}))
        with self.assertRaisesRegex(ValueError, "incomplete"):
            check_plan(plan(resource("aws_lb.this", ["mutate"], "aws_lb")))
        with self.assertRaisesRegex(ValueError, "incomplete"):
            check_plan(plan(resource("aws_lb.this", [], "aws_lb")))


if __name__ == "__main__":
    unittest.main()
