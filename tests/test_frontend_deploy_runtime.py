import runpy
import unittest
from pathlib import Path

build_values = runpy.run_path(
    str(Path(__file__).parents[1] / "platform/frontend/deploy-runtime-values.py")
)["build_values"]


def config():
    return {
        "AWS_ACCOUNT_ID": "123456789012",
        "AWS_REGION": "ap-southeast-1",
        "FRONTEND_TARGET_GROUP_ARN": (
            "arn:aws:elasticloadbalancing:ap-southeast-1:123456789012:"
            "targetgroup/foodlabelflow-staging-frontend/0123456789abcdef"
        ),
        "FRONTEND_ALB_SUBNET_CIDRS": "10.20.1.0/24,10.20.2.0/24",
    }


class FrontendDeployRuntimeTests(unittest.TestCase):
    def test_renders_only_target_group_and_exact_alb_subnet_cidrs(self):
        self.assertEqual(build_values(config()), {
            "targetGroupArn": config()["FRONTEND_TARGET_GROUP_ARN"],
            "networkPolicy": {"ingressFromCIDRs": ["10.20.1.0/24", "10.20.2.0/24"]},
        })

    def test_rejects_wrong_account_or_region_target_group(self):
        for key, value in (
            ("AWS_ACCOUNT_ID", "999999999999"),
            ("AWS_REGION", "us-east-1"),
            ("FRONTEND_TARGET_GROUP_ARN", "not-an-arn"),
        ):
            bad = config()
            bad[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                build_values(bad)

    def test_rejects_public_or_broad_or_noncanonical_cidrs(self):
        for value in ("0.0.0.0/0,10.20.2.0/24", "10.0.0.0/8,10.20.2.0/24",
                      "10.20.1.1/24,10.20.2.0/24", "10.20.1.0/24"):
            bad = config()
            bad["FRONTEND_ALB_SUBNET_CIDRS"] = value
            with self.subTest(value=value), self.assertRaises(ValueError):
                build_values(bad)

    def test_rejects_invalid_and_duplicate_cidrs(self):
        for value in ("999.20.1.0/24,10.20.2.0/24", "10.20.1.0/24,10.20.1.0/24"):
            bad = config()
            bad["FRONTEND_ALB_SUBNET_CIDRS"] = value
            with self.subTest(value=value), self.assertRaises(ValueError):
                build_values(bad)


if __name__ == "__main__":
    unittest.main()
