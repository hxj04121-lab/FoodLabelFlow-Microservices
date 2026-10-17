#!/usr/bin/env python3
"""Read-only checks for existing ALB-to-Pod TargetGroupBinding traffic."""
import argparse
import ipaddress
import json
from pathlib import Path


def load(path):
    return json.loads(Path(path).read_text())


def permits_port(rule, port):
    protocol = rule.get("IpProtocol")
    if protocol == "-1":
        return True
    return protocol == "tcp" and rule.get("FromPort", port + 1) <= port <= rule.get("ToPort", port - 1)


def http_matcher_includes_200(matcher):
    codes = matcher.get("HttpCode", "")
    for code in codes.split(","):
        parts = code.split("-", 1)
        try:
            start = int(parts[0])
            end = int(parts[1]) if len(parts) == 2 else start
        except ValueError:
            continue
        if start <= 200 <= end:
            return True
    return False


def is_ip_allowed(ip, cidr):
    try:
        return ipaddress.ip_address(ip) in ipaddress.ip_network(cidr, strict=False)
    except ValueError:
        return False


def check(directory, gateway_arn, keycloak_arn, vpc_id, alb_arn):
    directory = Path(directory)
    load_balancers = load(directory / "load-balancers.json").get("LoadBalancers", [])
    subnets = load(directory / "subnets.json").get("Subnets", [])
    target_groups = load(directory / "target-groups.json").get("TargetGroups", [])
    target_health = load(directory / "target-health.json")
    interfaces = load(directory / "network-interfaces.json").get("NetworkInterfaces", [])
    security_groups = {group["GroupId"]: group for group in load(directory / "security-groups.json").get("SecurityGroups", [])}
    network_policies = load(directory / "network-policies.json").get("items", [])

    if len(load_balancers) != 1:
        raise ValueError("expected exactly one existing ALB")
    alb = load_balancers[0]
    if alb.get("VpcId") != vpc_id or alb.get("LoadBalancerArn") != alb_arn or alb.get("Scheme") != "internet-facing":
        raise ValueError("existing public ALB does not match the staging VPC and ARN")
    alb_sgs = set(alb.get("SecurityGroups", []))
    alb_subnet_ids = {zone.get("SubnetId") for zone in alb.get("AvailabilityZones", []) if zone.get("SubnetId")}
    subnet_by_id = {subnet["SubnetId"]: subnet for subnet in subnets}
    if len(alb_sgs) == 0 or len(alb_subnet_ids) < 2 or not alb_subnet_ids <= subnet_by_id.keys():
        raise ValueError("ALB security groups or both ALB subnets could not be verified")
    if any(subnet_by_id[subnet_id].get("VpcId") != vpc_id for subnet_id in alb_subnet_ids):
        raise ValueError("ALB subnet VPC does not match the staging VPC")
    alb_cidrs = {subnet_by_id[subnet_id].get("CidrBlock") for subnet_id in alb_subnet_ids}
    if None in alb_cidrs:
        raise ValueError("an ALB subnet is missing its IPv4 CIDR")

    expected_checks = {
        gateway_arn: ("/actuator/health/readiness", {"traffic-port", "8080"}),
        keycloak_arn: ("/auth/", {"8080"}),
    }
    expected_arns = set(expected_checks)
    groups_by_arn = {group.get("TargetGroupArn"): group for group in target_groups}
    if groups_by_arn.keys() != expected_arns:
        raise ValueError("the target-group describe response did not contain exactly the two expected ARNs")
    target_ips = set()
    for arn in expected_arns:
        group = groups_by_arn[arn]
        expected_path, expected_ports = expected_checks[arn]
        if group.get("VpcId") != vpc_id or group.get("TargetType") != "ip":
            raise ValueError("a target group is not IP mode in the staging VPC")
        if alb_arn not in group.get("LoadBalancerArns", []):
            raise ValueError("a target group is not attached to the existing ALB")
        if (group.get("HealthCheckEnabled") is not True or group.get("HealthCheckProtocol") != "HTTP"
                or group.get("HealthCheckPort") not in expected_ports
                or group.get("HealthCheckPath") != expected_path
                or not http_matcher_includes_200(group.get("Matcher", {}))):
            raise ValueError("a target group has an incompatible HTTP health-check path, port, or matcher")
        descriptions = target_health.get(arn, {}).get("TargetHealthDescriptions", [])
        if not descriptions or any(row.get("TargetHealth", {}).get("State") != "healthy" for row in descriptions):
            raise ValueError("a target group is empty or has an unhealthy target")
        target_ips.update(row.get("Target", {}).get("Id") for row in descriptions if row.get("Target", {}).get("Id"))

    eni_sgs_by_ip = {}
    for interface in interfaces:
        groups = {group.get("GroupId") for group in interface.get("Groups", []) if group.get("GroupId")}
        ips = {interface.get("PrivateIpAddress")}
        ips.update(address.get("PrivateIpAddress") for address in interface.get("PrivateIpAddresses", []))
        for ip in ips & target_ips:
            eni_sgs_by_ip.setdefault(ip, set()).update(groups)
    if eni_sgs_by_ip.keys() != target_ips:
        raise ValueError("at least one healthy target IP could not be mapped to an ENI and security group")

    def backend_ingress_allows(group_ids):
        for group_id in group_ids:
            group = security_groups.get(group_id, {})
            for rule in group.get("IpPermissions", []):
                if not permits_port(rule, 8080):
                    continue
                if any(peer.get("GroupId") in alb_sgs for peer in rule.get("UserIdGroupPairs", [])):
                    return True
                if any(source.get("CidrIp") in alb_cidrs for source in rule.get("IpRanges", [])):
                    return True
        return False

    def alb_egress_allows(target_ip, target_group_ids):
        for group_id in alb_sgs:
            group = security_groups.get(group_id, {})
            for rule in group.get("IpPermissionsEgress", []):
                if not permits_port(rule, 8080):
                    continue
                if any(peer.get("GroupId") in target_group_ids for peer in rule.get("UserIdGroupPairs", [])):
                    return True
                if any(is_ip_allowed(target_ip, source.get("CidrIp", "")) for source in rule.get("IpRanges", [])):
                    return True
        return False

    for ip, group_ids in eni_sgs_by_ip.items():
        if not backend_ingress_allows(group_ids):
            raise ValueError("backend security groups do not allow TCP/8080 from the ALB security group or exact ALB subnet CIDRs")
        if not alb_egress_allows(ip, group_ids):
            raise ValueError("ALB security groups do not allow TCP/8080 to a healthy target")

    for namespace, label_key, label_value in (
        ("gateway", "app.kubernetes.io/name", "gateway"),
        ("keycloak", "app", "keycloak"),
    ):
        policies = [
            policy for policy in network_policies
            if policy.get("metadata", {}).get("namespace") == namespace
            and policy.get("spec", {}).get("podSelector", {}).get("matchLabels", {}).get(label_key) == label_value
            and "Ingress" in policy.get("spec", {}).get("policyTypes", [])
        ]
        for cidr in alb_cidrs:
            allowed = any(
                any(source.get("ipBlock", {}).get("cidr") == cidr and not source.get("ipBlock", {}).get("except")
                    for source in rule.get("from", []))
                and any(port.get("protocol", "TCP") == "TCP" and port.get("port") == 8080 for port in rule.get("ports", []))
                for policy in policies for rule in policy.get("spec", {}).get("ingress", [])
            )
            if not allowed:
                raise ValueError(f"NetworkPolicy does not allow ALB subnet {cidr} to {namespace} Pod TCP/8080")

    print(f"PASS: existing internet-facing ALB, two IP target groups, HTTP health checks, {len(target_ips)} healthy targets, ALB/backend SG paths, and Gateway/Keycloak NetworkPolicy ingress")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--directory", required=True)
    parser.add_argument("--gateway-target-group-arn", required=True)
    parser.add_argument("--keycloak-target-group-arn", required=True)
    parser.add_argument("--vpc-id", required=True)
    parser.add_argument("--load-balancer-arn", required=True)
    args = parser.parse_args()
    try:
        check(args.directory, args.gateway_target_group_arn, args.keycloak_target_group_arn, args.vpc_id, args.load_balancer_arn)
    except (OSError, KeyError, TypeError, ValueError, json.JSONDecodeError) as exc:
        parser.exit(1, f"FAIL: {exc}\n")
