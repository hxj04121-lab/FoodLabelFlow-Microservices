"""Offline command fixtures; these tests do not establish live EKS readiness."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
CERT = "arn:aws:acm:ap-southeast-1:000000000000:certificate/test"
TG = "arn:aws:elasticloadbalancing:ap-southeast-1:000000000000:targetgroup/test/0123456789abcdef"


class VerifyAddonsTest(unittest.TestCase):
    def run_check(self, mode="foundations", *, enforced=True, encrypted=True, healthy=True):
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
            "targetgroupbinding": {"spec": {"targetGroupARN": TG, "targetType": "ip"}},
            "describe-listeners": {"Listeners": [{"Protocol": "HTTPS", "Port": 443,
                "Certificates": [{"CertificateArn": CERT}], "DefaultActions": [{"Type": "forward", "TargetGroupArn": TG}]}]},
            "describe-target-health": {"TargetHealthDescriptions": [{"TargetHealth": {"State": "healthy" if healthy else "unhealthy"}}]},
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
else:
    key = next(key for key in fixtures if key in args)
    if key == 'targetgroupbinding' and 'gateway-gateway' not in args:
        sys.exit(1)
    print(json.dumps(fixtures[key]))
"""
            for command in ("aws", "kubectl"):
                path = directory / command
                path.write_text(stub)
                path.chmod(0o700)
            env = dict(os.environ, PATH=f"{tmp}:{os.environ['PATH']}",
                       EKS_CLUSTER_NAME="offline-test", AWS_REGION="ap-southeast-1")
            env.pop("STCN_GATEWAY_BINDING", None)
            env.pop("STCN_ACM_CERTIFICATE_ARN", None)
            if mode == "deploy":
                env.update(STCN_ACM_CERTIFICATE_ARN=CERT, STCN_ALB_TARGET_GROUP_ARN=TG,
                           STCN_ALB_ARN="offline-test")
            return subprocess.run(["bash", str(ROOT / "scripts/verify_eks_addons.sh"), mode],
                                  env=env, capture_output=True, text=True)

    def test_foundations_without_certificate(self):
        result = self.run_check()
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_disabled_policy_agent_is_rejected(self):
        self.assertNotEqual(self.run_check(enforced=False).returncode, 0)

    def test_unencrypted_storage_is_rejected(self):
        self.assertNotEqual(self.run_check(encrypted=False).returncode, 0)

    def test_https_and_actual_binding_name(self):
        result = self.run_check("deploy")
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_unhealthy_targets_are_rejected(self):
        self.assertNotEqual(self.run_check("deploy", healthy=False).returncode, 0)


if __name__ == "__main__":
    unittest.main()
