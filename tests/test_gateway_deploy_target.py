import runpy
import unittest
from pathlib import Path

validate = runpy.run_path(str(Path(__file__).parents[1] / "platform/gateway/validate-deploy-target.py"))["validate"]


def target(**overrides):
    values = {
        "account_id": "123456789012",
        "role_arn": "arn:aws:iam::123456789012:role/FoodLabelFlowStagingDeploy",
        "caller_arn": "arn:aws:sts::123456789012:assumed-role/FoodLabelFlowStagingDeploy/gha-run-1",
        "region": "ap-southeast-1",
        "cluster": "spectrace-staging",
        "cluster_arn": "arn:aws:eks:ap-southeast-1:123456789012:cluster/spectrace-staging",
        "context": "arn:aws:eks:ap-southeast-1:123456789012:cluster/spectrace-staging",
    }
    values.update(overrides)
    return values


class GatewayDeployTargetTests(unittest.TestCase):
    def test_accepts_matching_protected_target(self):
        validate(**target())

    def test_rejects_account_or_role_mismatch(self):
        for overrides in (
            {"account_id": "999999999999"},
            {"caller_arn": "arn:aws:sts::999999999999:assumed-role/FoodLabelFlowStagingDeploy/gha-run-1"},
            {"caller_arn": "arn:aws:sts::123456789012:assumed-role/OtherRole/gha-run-1"},
        ):
            with self.subTest(overrides=overrides), self.assertRaises(ValueError):
                validate(**target(**overrides))

    def test_rejects_region_or_cluster_mismatch(self):
        for overrides in (
            {"region": "us-east-1"},
            {"cluster": "other-cluster"},
            {"cluster_arn": "arn:aws:eks:us-east-1:123456789012:cluster/spectrace-staging"},
            {"context": "arn:aws:eks:ap-southeast-1:123456789012:cluster/other-cluster"},
        ):
            with self.subTest(overrides=overrides), self.assertRaises(ValueError):
                validate(**target(**overrides))


if __name__ == "__main__":
    unittest.main()
