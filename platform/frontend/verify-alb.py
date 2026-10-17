#!/usr/bin/env python3
"""Fail closed if the existing HTTPS listener and target groups do not match staging."""
import json
import sys


def one(rows, description):
    if len(rows) != 1:
        raise ValueError(f"expected one {description}, found {len(rows)}")
    return rows[0]


def target_group_arns(actions):
    result = []
    for action in actions:
        if action.get("Type") != "forward":
            continue
        if action.get("TargetGroupArn"):
            result.append(action["TargetGroupArn"])
        else:
            result.extend(
                row["TargetGroupArn"]
                for row in action.get("ForwardConfig", {}).get("TargetGroups", [])
                if row.get("TargetGroupArn")
            )
    return result


def path_patterns(rule):
    return [
        value
        for condition in rule.get("Conditions", [])
        if condition.get("Field") == "path-pattern"
        for value in (
            condition.get("PathPatternConfig", {}).get("Values")
            or condition.get("Values", [])
        )
    ]


def verify_target_group(target_groups, arn, vpc_id, alb_arn):
    group = one(
        [row for row in target_groups.get("TargetGroups", []) if row.get("TargetGroupArn") == arn],
        "Frontend target group",
    )
    expected = {
        "VpcId": vpc_id,
        "Protocol": "HTTP",
        "Port": 8080,
        "TargetType": "ip",
        "HealthCheckProtocol": "HTTP",
        "HealthCheckPath": "/healthz",
    }
    for key, value in expected.items():
        if group.get(key) != value:
            raise ValueError(f"Frontend target group {key} must be {value!r}")
    if group.get("HealthCheckPort") not in ("traffic-port", "8080"):
        raise ValueError("Frontend target group health checks must use port 8080")
    if group.get("Matcher", {}).get("HttpCode") != "200":
        raise ValueError("Frontend target group success code must be exactly 200")
    if any(item != alb_arn for item in group.get("LoadBalancerArns", [])):
        raise ValueError("Frontend target group is associated with a different load balancer")
    return group


def verify_alb_routes(albs, listeners, rules, vpc_id, gateway_arn, keycloak_arn):
    alb = one(albs.get("LoadBalancers", []), "staging load balancer")
    if alb.get("State", {}).get("Code") != "active" or alb.get("Scheme") != "internet-facing":
        raise ValueError("Staging ALB must be active and internet-facing")
    if alb.get("VpcId") != vpc_id:
        raise ValueError("Staging ALB and EKS cluster must use the same VPC")
    subnet_ids = sorted({item["SubnetId"] for item in alb.get("AvailabilityZones", []) if item.get("SubnetId")})
    if len(subnet_ids) < 2:
        raise ValueError("Staging ALB must use at least two subnets")

    listener = one(
        [row for row in listeners.get("Listeners", []) if row.get("Port") == 443],
        "HTTPS/443 listener",
    )
    if listener.get("Protocol") != "HTTPS" or not listener.get("Certificates"):
        raise ValueError("HTTPS/443 listener must retain its TLS certificate")
    rules_list = rules.get("Rules", [])
    one([row for row in rules_list if row.get("IsDefault")], "default listener rule")
    required = {"/api/*": gateway_arn, "/auth/*": keycloak_arn}
    for pattern, expected_arn in required.items():
        matches = [
            rule for rule in rules_list
            if not rule.get("IsDefault") and pattern in path_patterns(rule)
        ]
        rule = one(matches, f"{pattern} listener rule")
        if target_group_arns(rule.get("Actions", [])) != [expected_arn]:
            raise ValueError(f"{pattern} must continue forwarding only to its existing target group")
    return {
        "loadBalancerArn": alb["LoadBalancerArn"],
        "listenerArn": listener["ListenerArn"],
        "vpcId": vpc_id,
        "subnetIds": subnet_ids,
        "defaultActions": listener.get("DefaultActions", []),
        "nonDefaultRules": sorted(
            [
                {
                    "Priority": row.get("Priority"),
                    "Conditions": row.get("Conditions", []),
                    "Actions": row.get("Actions", []),
                }
                for row in rules_list if not row.get("IsDefault")
            ],
            key=lambda row: str(row["Priority"]),
        ),
    }


def verify_rules_unchanged(before, after):
    def normalized(document):
        return sorted(
            [
                {
                    "Priority": row.get("Priority"),
                    "Conditions": row.get("Conditions", []),
                    "Actions": row.get("Actions", []),
                }
                for row in document.get("Rules", []) if not row.get("IsDefault")
            ],
            key=lambda row: str(row["Priority"]),
        )

    if normalized(before) != normalized(after):
        raise ValueError("A non-default HTTPS listener rule changed during Frontend cutover")


def verify_default_forward(listener_document, frontend_arn):
    listener = one(
        [row for row in listener_document.get("Listeners", []) if row.get("Port") == 443],
        "HTTPS/443 listener",
    )
    actions = listener.get("DefaultActions", [])
    if len(actions) != 1 or actions[0].get("Type") != "forward" or actions[0].get("TargetGroupArn") != frontend_arn:
        raise ValueError("HTTPS/443 default action must forward only to the Frontend target group")


def verify_healthy_targets(document, description):
    targets = document.get("TargetHealthDescriptions", [])
    if not targets or any(row.get("TargetHealth", {}).get("State") != "healthy" for row in targets):
        raise ValueError(f"{description} must have at least one target and every registered target Healthy")


def main():
    if len(sys.argv) != 10:
        raise SystemExit(
            "Usage: verify-alb.py ALBS.json LISTENERS.json RULES.json FRONTEND-TG.json "
            "VPC_ID ALB_ARN GATEWAY_TG_ARN KEYCLOAK_TG_ARN FRONTEND_TG_ARN"
        )
    alb_path, listener_path, rules_path, target_path, vpc_id, alb_arn, gateway_arn, keycloak_arn, frontend_arn = sys.argv[1:]
    files = [json.load(open(path)) for path in (alb_path, listener_path, rules_path, target_path)]
    summary = verify_alb_routes(files[0], files[1], files[2], vpc_id, gateway_arn, keycloak_arn)
    verify_target_group(files[3], frontend_arn, vpc_id, alb_arn)
    summary["frontendTargetGroupArn"] = frontend_arn
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
