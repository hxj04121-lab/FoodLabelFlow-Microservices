import json
from pathlib import Path
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
from verify_staging_target_network import check  # noqa: E402


GATEWAY = "arn:aws:elasticloadbalancing:ap-southeast-1:679857346122:targetgroup/foodlabelflow-staging-gateway/b8c46985507e8b58"
KEYCLOAK = "arn:aws:elasticloadbalancing:ap-southeast-1:679857346122:targetgroup/foodlabelflow-staging-keycloak/6c9f10642dd79519"
ALB = "arn:aws:elasticloadbalancing:ap-southeast-1:679857346122:loadbalancer/app/staging/0123456789abcdef"
VPC = "vpc-09f86efc8f1a76c6f"


class StagingTargetNetworkTest(unittest.TestCase):
    def fixtures(self):
        return {
            "load-balancers.json": {"LoadBalancers": [{
                "LoadBalancerArn": ALB,
                "VpcId": VPC,
                "Scheme": "internet-facing",
                "SecurityGroups": ["sg-alb"],
                "AvailabilityZones": [{"SubnetId": "subnet-public-a"}, {"SubnetId": "subnet-public-b"}],
            }]},
            "subnets.json": {"Subnets": [
                {"SubnetId": "subnet-public-a", "VpcId": VPC, "CidrBlock": "10.0.0.0/24"},
                {"SubnetId": "subnet-public-b", "VpcId": VPC, "CidrBlock": "10.0.1.0/24"},
            ]},
            "target-groups.json": {"TargetGroups": [
                {"TargetGroupArn": GATEWAY, "VpcId": VPC, "TargetType": "ip", "LoadBalancerArns": [ALB],
                 "HealthCheckEnabled": True, "HealthCheckProtocol": "HTTP", "HealthCheckPort": "traffic-port",
                 "HealthCheckPath": "/actuator/health/readiness", "Matcher": {"HttpCode": "200-399"}},
                {"TargetGroupArn": KEYCLOAK, "VpcId": VPC, "TargetType": "ip", "LoadBalancerArns": [ALB],
                 "HealthCheckEnabled": True, "HealthCheckProtocol": "HTTP", "HealthCheckPort": "8080",
                 "HealthCheckPath": "/auth/", "Matcher": {"HttpCode": "200"}},
            ]},
            "target-health.json": {
                GATEWAY: {"TargetHealthDescriptions": [{"Target": {"Id": "10.0.16.10", "Port": 8080}, "TargetHealth": {"State": "healthy"}}]},
                KEYCLOAK: {"TargetHealthDescriptions": [{"Target": {"Id": "10.0.16.11", "Port": 8080}, "TargetHealth": {"State": "healthy"}}]},
            },
            "network-interfaces.json": {"NetworkInterfaces": [
                {"PrivateIpAddress": "10.0.16.10", "Groups": [{"GroupId": "sg-node"}]},
                {"PrivateIpAddress": "10.0.16.11", "Groups": [{"GroupId": "sg-node"}]},
            ]},
            "security-groups.json": {"SecurityGroups": [
                {"GroupId": "sg-alb", "IpPermissionsEgress": [{"IpProtocol": "-1", "IpRanges": [{"CidrIp": "0.0.0.0/0"}]}]},
                {"GroupId": "sg-node", "IpPermissions": [{"IpProtocol": "tcp", "FromPort": 8080, "ToPort": 8080,
                 "UserIdGroupPairs": [{"GroupId": "sg-alb"}]}]},
            ]},
            "network-policies.json": {"items": [
                self.policy("gateway", "stcn-gateway-flows", {"app.kubernetes.io/name": "gateway"}),
                self.policy("keycloak", "stcn-keycloak-flows", {"app": "keycloak"}),
            ]},
        }

    @staticmethod
    def policy(namespace, name, selector):
        return {"metadata": {"namespace": namespace, "name": name}, "spec": {
            "podSelector": {"matchLabels": selector}, "policyTypes": ["Ingress"], "ingress": [
                {"from": [{"ipBlock": {"cidr": cidr}}], "ports": [{"protocol": "TCP", "port": 8080}]}
                for cidr in ("10.0.0.0/24", "10.0.1.0/24")
            ],
        }}

    def run_check(self, changes=None):
        docs = self.fixtures()
        if changes:
            changes(docs)
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for name, payload in docs.items():
                (root / name).write_text(json.dumps(payload))
            return check(root, GATEWAY, KEYCLOAK, VPC, ALB)

    def test_existing_alb_target_groups_and_pod_network_paths_pass(self):
        self.run_check()

    def test_rejects_missing_backend_security_group_ingress(self):
        def remove_rule(docs):
            docs["security-groups.json"]["SecurityGroups"][1]["IpPermissions"] = []
        with self.assertRaisesRegex(ValueError, "backend security groups"):
            self.run_check(remove_rule)

    def test_rejects_missing_alb_subnet_allowance_in_keycloak_policy(self):
        def remove_rule(docs):
            docs["network-policies.json"]["items"][1]["spec"]["ingress"].pop()
        with self.assertRaisesRegex(ValueError, "NetworkPolicy"):
            self.run_check(remove_rule)

    def test_rejects_bad_health_check_and_unhealthy_targets(self):
        def bad_path(docs):
            docs["target-groups.json"]["TargetGroups"][0]["HealthCheckPath"] = ""
        with self.assertRaisesRegex(ValueError, "health-check"):
            self.run_check(bad_path)

        def incompatible_keycloak_path(docs):
            docs["target-groups.json"]["TargetGroups"][1]["HealthCheckPath"] = "/health/ready"
        with self.assertRaisesRegex(ValueError, "health-check"):
            self.run_check(incompatible_keycloak_path)

        def unhealthy(docs):
            docs["target-health.json"][KEYCLOAK]["TargetHealthDescriptions"][0]["TargetHealth"]["State"] = "unhealthy"
        with self.assertRaisesRegex(ValueError, "unhealthy target"):
            self.run_check(unhealthy)


if __name__ == "__main__":
    unittest.main()
