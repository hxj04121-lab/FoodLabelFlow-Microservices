import unittest

from scripts.staging.guard_staging_plan_actions import PROTECTED_ADDRESSES, check_plan


class StagingPlanActionGuardTest(unittest.TestCase):
    def test_noop_on_existing_protected_address_is_allowed(self):
        address = sorted(PROTECTED_ADDRESSES)[0]
        self.assertEqual(check_plan({"resource_changes": [{"address": address, "change": {"actions": ["no-op"]}}]}), [])

    def test_any_non_noop_lbc_iam_or_existing_namespace_action_is_rejected(self):
        for address in sorted(PROTECTED_ADDRESSES):
            for action in (["create"], ["update"], ["delete"], ["delete", "create"]):
                with self.subTest(address=address, action=action):
                    issues = check_plan({"resource_changes": [{"address": address, "change": {"actions": action}}]})
                    self.assertEqual(len(issues), 1)
                    self.assertIn("forbidden", issues[0])

    def test_missing_or_malformed_changes_fail_closed(self):
        with self.assertRaisesRegex(ValueError, "resource_changes"):
            check_plan({})
        with self.assertRaisesRegex(ValueError, "malformed"):
            check_plan({"resource_changes": [None]})


if __name__ == "__main__":
    unittest.main()
