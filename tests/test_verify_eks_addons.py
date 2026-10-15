"""Offline command fixtures; these tests do not establish live EKS readiness."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
CERT = "arn:aws:acm:ap-southeast-1:000000000000:certificate/test"
TG = "arn:aws:elasticloadbalancing:ap-southeast-1:000000000000:targetgroup/test-gateway/0123456789abcdef"
KEYCLOAK_TG = "arn:aws:elasticloadbalancing:ap-southeast-1:000000000000:targetgroup/test-keycloak/fedcba9876543210"
VPC = "vpc-0123456789abcdef0"


class VerifyAddonsTest(unittest.TestCase):
    def run_check(self, mode="foundations", *, enforced=True, encrypted=True,
                  gateway_healthy=True, keycloak_healthy=True, correct_route=True,
                  correct_gateway_route=True, correct_vpc=True, keycloak_binding=True,
                  gateway_has_targets=True, keycloak_has_targets=True):
        rules = [
            {"Conditions": [{"Field": "path-pattern", "Values": ["/auth/*"]}],
             "Actions": [{"Type": "forward", "TargetGroupArn": KEYCLOAK_TG}]},
            {"Conditions": [{"Field": "path-pattern", "Values": ["/api/*"]}],
             "Actions": [{"Type": "forward", "TargetGroupArn": TG}]},
        ]
        if not correct_route:
            rules[0]["Actions"][0]["TargetGroupArn"] = "wrong-target-group"
        if not correct_gateway_route:
            rules[1]["Actions"][0]["TargetGroupArn"] = "wrong-target-group"
        fixtures = {
            "daemonset": {"spec": {"template": {"spec": {"containers": [
                {"name": "aws-eks-nodeagent", "args": [f"--enable-network-policy={str(enforced).lower()}"]},
                {"name": "aws-node", "env": [{"name": "NETWORK_POLICY_ENFORCING_MODE", "value": "strict"}]},
            ]}}}},
            "apiservice": {"status": {"conditions": [{"type": "Available", "status": "True"}]}},
            "storageclass": {"provisioner": "ebs.csi.aws.com", "volumeBindingMode": "WaitForFirstConsumer",
                             "reclaimPolicy": "Delete", "allowVolumeExpansion": True,
                             "parameters": {"type": "gp3", "encrypted": str(encrypted).lower()}},
            "networkpolicy": {"items": [{"metadata": {"name": name}} for name in
                                         ["stcn-kube-system-required-traffic", "stcn-default-deny-gateway"]]},
            "targetgroupbinding-gateway-gateway": {"spec": {"targetGroupARN": TG, "targetType": "ip",
                "serviceRef": {"name": "gateway-gateway", "port": 8080}}},
            "targetgroupbinding-spectrace-keycloak": {"spec": {"targetGroupARN": KEYCLOAK_TG if keycloak_binding else "wrong-target-group",
                "targetType": "ip", "serviceRef": {"name": "spectrace-keycloak-service", "port": 8080}}},
            "describe-listeners": {"Listeners": [{"ListenerArn": "offline-https-listener", "Protocol": "HTTPS", "Port": 443,
                "Certificates": [{"CertificateArn": CERT}], "DefaultActions": [{"Type": "fixed-response"}]}]},
            "describe-rules": {"Rules": rules},
            "describe-target-groups": {"TargetGroups": [
                {"TargetGroupArn": TG, "TargetType": "ip", "VpcId": VPC if correct_vpc else "wrong-vpc"},
                {"TargetGroupArn": KEYCLOAK_TG, "TargetType": "ip", "VpcId": VPC if correct_vpc else "wrong-vpc"},
            ]},
            "describe-target-health": {
                TG: {"TargetHealthDescriptions": ([{"TargetHealth": {"State": "healthy" if gateway_healthy else "unhealthy"}}] if gateway_has_targets else [])},
                KEYCLOAK_TG: {"TargetHealthDescriptions": ([{"TargetHealth": {"State": "healthy" if keycloak_healthy else "unhealthy"}}] if keycloak_has_targets else [])},
            },
        }
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            (directory / "fixtures.json").write_text(json.dumps(fixtures))
            stub = """#!/usr/bin/env python3
import json, os, sys
from pathlib import Path
args = sys.argv[1:]
fixtures = json.loads((Path(__file__).parent / 'fixtures.json').read_text())
if 'describe-addon' in args:
    print('ACTIVE')
elif 'rollout' in args:
    print('ready')
elif 'describe-target-health' in args:
    arn = args[args.index('--target-group-arn') + 1]
    print(json.dumps(fixtures['describe-target-health'][arn]))
elif 'targetgroupbinding' in args:
    name = args[args.index('targetgroupbinding') + 1]
    print(json.dumps(fixtures['targetgroupbinding-' + name]))
else:
    key = next(key for key in fixtures if key in args)
    print(json.dumps(fixtures[key]))
"""
            for command in ("aws", "kubectl"):
                path = directory / command
                path.write_text(stub)
                path.chmod(0o700)
            env = dict(os.environ, PATH=f"{tmp}:{os.environ['PATH']}",
                       STCN_EKS_CLUSTER_NAME="offline-test", AWS_REGION="ap-southeast-1")
            env.pop("STCN_GATEWAY_BINDING", None)
            env.pop("STCN_ACM_CERTIFICATE_ARN", None)
            if mode == "deploy":
                env.update(STCN_ACM_CERTIFICATE_ARN=CERT, STCN_ALB_TARGET_GROUP_ARN=TG,
                           STCN_KEYCLOAK_TARGET_GROUP_ARN=KEYCLOAK_TG, STCN_ALB_ARN="offline-test",
                           STCN_VPC_ID=VPC)
            return subprocess.run(["bash", str(ROOT / "scripts/verify_eks_addons.sh"), mode],
                                  env=env, capture_output=True, text=True)

    def test_foundations_without_certificate(self):
        result = self.run_check()
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_disabled_policy_agent_is_rejected(self):
        self.assertNotEqual(self.run_check(enforced=False).returncode, 0)

    def test_unencrypted_storage_is_rejected(self):
        self.assertNotEqual(self.run_check(encrypted=False).returncode, 0)

    def test_https_path_routes_and_both_bindings(self):
        result = self.run_check("deploy")
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_wrong_listener_path_target_is_rejected(self):
        self.assertNotEqual(self.run_check("deploy", correct_route=False).returncode, 0)

    def test_wrong_api_path_target_is_rejected(self):
        self.assertNotEqual(self.run_check("deploy", correct_gateway_route=False).returncode, 0)

    def test_target_groups_outside_staging_vpc_are_rejected(self):
        self.assertNotEqual(self.run_check("deploy", correct_vpc=False).returncode, 0)

    def test_missing_or_unhealthy_gateway_targets_are_rejected(self):
        self.assertNotEqual(self.run_check("deploy", gateway_healthy=False).returncode, 0)

    def test_missing_or_unhealthy_keycloak_targets_are_rejected(self):
        self.assertNotEqual(self.run_check("deploy", keycloak_healthy=False).returncode, 0)

    def test_empty_target_groups_are_rejected(self):
        self.assertNotEqual(self.run_check("deploy", gateway_has_targets=False).returncode, 0)
        self.assertNotEqual(self.run_check("deploy", keycloak_has_targets=False).returncode, 0)

    def test_wrong_keycloak_binding_is_rejected(self):
        self.assertNotEqual(self.run_check("deploy", keycloak_binding=False).returncode, 0)


if __name__ == "__main__":
    unittest.main()
