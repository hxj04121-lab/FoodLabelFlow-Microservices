#!/usr/bin/env python3
"""Render Frontend TargetGroupBinding and exact ALB-subnet ingress values."""
import ipaddress
import json
import os
import re
import sys


def build_values(env=os.environ):
    account = env.get("AWS_ACCOUNT_ID", "").strip()
    region = env.get("AWS_REGION", "").strip()
    target_group = env.get("FRONTEND_TARGET_GROUP_ARN", "").strip()
    if not re.fullmatch(r"[0-9]{12}", account) or not region:
        raise ValueError("AWS_ACCOUNT_ID and AWS_REGION must come from the protected staging Environment")
    expected_arn = (
        rf"arn:aws:elasticloadbalancing:{re.escape(region)}:{account}:"
        r"targetgroup/foodlabelflow-staging-frontend/[a-f0-9]{16}"
    )
    if not re.fullmatch(expected_arn, target_group):
        raise ValueError("FRONTEND_TARGET_GROUP_ARN must belong to the protected account and region")

    raw_cidrs = env.get("FRONTEND_ALB_SUBNET_CIDRS", "").split(",")
    cidrs = []
    for raw in raw_cidrs:
        value = raw.strip()
        try:
            network = ipaddress.ip_network(value, strict=True)
        except ValueError as exc:
            raise ValueError("FRONTEND_ALB_SUBNET_CIDRS must contain canonical IPv4 subnet CIDRs") from exc
        if network.version != 4 or network.prefixlen < 16:
            raise ValueError("ALB ingress must use IPv4 subnet CIDRs of /16 or narrower scope")
        if value in cidrs:
            raise ValueError("FRONTEND_ALB_SUBNET_CIDRS must not contain duplicates")
        cidrs.append(value)
    if len(cidrs) < 2:
        raise ValueError("At least two exact ALB subnet CIDRs are required")

    return {
        "targetGroupArn": target_group,
        "networkPolicy": {"ingressFromCIDRs": cidrs},
    }


if __name__ == "__main__":
    try:
        json.dump(build_values(), sys.stdout, separators=(",", ":"))
        sys.stdout.write("\n")
    except ValueError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        raise SystemExit(2)
