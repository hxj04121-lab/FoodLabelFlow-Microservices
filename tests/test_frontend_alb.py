import runpy
import unittest
from pathlib import Path

verify = runpy.run_path(str(Path(__file__).parents[1] / "platform/frontend/verify-alb.py"))
verify_alb_routes = verify["verify_alb_routes"]
verify_target_group = verify["verify_target_group"]
verify_rules_unchanged = verify["verify_rules_unchanged"]
verify_default_forward = verify["verify_default_forward"]
verify_healthy_targets = verify["verify_healthy_targets"]


def alb_config():
    gateway = "arn:aws:elasticloadbalancing:ap-southeast-1:123456789012:targetgroup/gateway/0123456789abcdef"
    keycloak = "arn:aws:elasticloadbalancing:ap-southeast-1:123456789012:targetgroup/keycloak/0123456789abcdef"
    frontend = "arn:aws:elasticloadbalancing:ap-southeast-1:123456789012:targetgroup/frontend/0123456789abcdef"
    lb = {
        "LoadBalancerArn": "arn:aws:elasticloadbalancing:ap-southeast-1:123456789012:loadbalancer/app/staging/0123456789abcdef",
        "State": {"Code": "active"}, "Scheme": "internet-facing", "VpcId": "vpc-123",
        "AvailabilityZones": [{"SubnetId": "subnet-a"}, {"SubnetId": "subnet-b"}],
    }
    listener = {
        "ListenerArn": "arn:aws:elasticloadbalancing:ap-southeast-1:123456789012:listener/app/staging/0123456789abcdef/abcdef0123456789",
        "Port": 443, "Protocol": "HTTPS", "Certificates": [{"CertificateArn": "arn:aws:acm:ap-southeast-1:123456789012:certificate/example"}],
        "DefaultActions": [{"Type": "forward", "TargetGroupArn": gateway}],
    }
    rules = {
        "Rules": [
            {"Priority": "1", "IsDefault": False,
             "Conditions": [{"Field": "path-pattern", "PathPatternConfig": {"Values": ["/api/*"]}}],
             "Actions": [{"Type": "forward", "TargetGroupArn": gateway}]},
            {"Priority": "2", "IsDefault": False,
             "Conditions": [{"Field": "path-pattern", "Values": ["/auth/*"]}],
             "Actions": [{"Type": "forward", "TargetGroupArn": keycloak}]},
            {"Priority": "default", "IsDefault": True, "Conditions": [],
             "Actions": listener["DefaultActions"]},
        ]
    }
    target_group = {
        "TargetGroups": [{
            "TargetGroupArn": frontend, "VpcId": "vpc-123", "Protocol": "HTTP", "Port": 8080,
            "TargetType": "ip", "HealthCheckProtocol": "HTTP", "HealthCheckPort": "traffic-port",
            "HealthCheckPath": "/healthz", "Matcher": {"HttpCode": "200"}, "LoadBalancerArns": [],
        }]
    }
    return {
        "gateway": gateway, "keycloak": keycloak, "frontend": frontend,
        "alb": {"LoadBalancers": [lb]}, "listener": {"Listeners": [listener]},
        "rules": rules, "target_group": target_group,
    }


class FrontendAlbTests(unittest.TestCase):
    def test_accepts_current_https_routes_and_valid_frontend_target_group(self):
        c = alb_config()
        checked = verify_alb_routes(c["alb"], c["listener"], c["rules"], "vpc-123", c["gateway"], c["keycloak"])
        verify_target_group(c["target_group"], c["frontend"], "vpc-123", checked["loadBalancerArn"])
        self.assertEqual(checked["subnetIds"], ["subnet-a", "subnet-b"])

    def test_rejects_missing_or_misrouted_api_and_auth_rules(self):
        c = alb_config()
        for rule_index, wrong_target in ((0, c["keycloak"]), (1, c["gateway"])):
            bad = {**c["rules"], "Rules": [dict(rule) for rule in c["rules"]["Rules"]]}
            bad["Rules"][rule_index]["Actions"] = [{"Type": "forward", "TargetGroupArn": wrong_target}]
            with self.subTest(rule=rule_index), self.assertRaises(ValueError):
                verify_alb_routes(c["alb"], c["listener"], bad, "vpc-123", c["gateway"], c["keycloak"])

    def test_rejects_wrong_vpc_listener_tls_or_frontend_health_contract(self):
        c = alb_config()
        with self.assertRaisesRegex(ValueError, "same VPC"):
            verify_alb_routes(c["alb"], c["listener"], c["rules"], "vpc-other", c["gateway"], c["keycloak"])
        with self.assertRaisesRegex(ValueError, "TLS certificate"):
            bad = {"Listeners": [{**c["listener"]["Listeners"][0], "Certificates": []}]}
            verify_alb_routes(c["alb"], bad, c["rules"], "vpc-123", c["gateway"], c["keycloak"])
        with self.assertRaisesRegex(ValueError, "HealthCheckPath"):
            bad = {"TargetGroups": [{**c["target_group"]["TargetGroups"][0], "HealthCheckPath": "/"}]}
            verify_target_group(bad, c["frontend"], "vpc-123", "alb-arn")

    def test_preserves_existing_rules_and_checks_new_default_target_and_health(self):
        c = alb_config()
        before = c["rules"]
        after = {**before, "Rules": [dict(row) for row in before["Rules"]]}
        after["Rules"][-1]["Actions"] = [{"Type": "forward", "TargetGroupArn": c["frontend"]}]
        verify_rules_unchanged(before, after)
        verify_default_forward({"Listeners": [{**c["listener"]["Listeners"][0],
            "DefaultActions": [{"Type": "forward", "TargetGroupArn": c["frontend"]}]}]}, c["frontend"])
        with self.assertRaises(ValueError):
            verify_default_forward({"Listeners": [{**c["listener"]["Listeners"][0],
                "DefaultActions": [{"Type": "authenticate-oidc"}, {"Type": "forward", "TargetGroupArn": c["frontend"]}]}]}, c["frontend"])
        verify_healthy_targets({"TargetHealthDescriptions": [
            {"TargetHealth": {"State": "healthy"}}
        ]}, "Frontend")
        with self.assertRaises(ValueError):
            verify_rules_unchanged(before, {"Rules": []})
        with self.assertRaises(ValueError):
            verify_healthy_targets({"TargetHealthDescriptions": [
                {"TargetHealth": {"State": "unhealthy"}}
            ]}, "Frontend")


if __name__ == "__main__":
    unittest.main()
