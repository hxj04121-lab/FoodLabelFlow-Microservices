#!/usr/bin/env python3
"""Fail closed unless the assumed role and selected EKS context match protected inputs."""
import argparse
import re


def validate(account_id, role_arn, caller_arn, region, cluster, cluster_arn, context):
    role = re.fullmatch(r"arn:aws:iam::(\d{12}):role/(?:.*/)?([^/]+)", role_arn)
    assumed = re.fullmatch(r"arn:aws:sts::(\d{12}):assumed-role/([^/]+)/[^/]+", caller_arn)
    if not re.fullmatch(r"\d{12}", account_id) or not role or not assumed:
        raise ValueError("AWS account or role ARN is malformed")
    if role.group(1) != account_id or assumed.group(1) != account_id or assumed.group(2) != role.group(2):
        raise ValueError("assumed AWS identity does not match the protected account and deploy role")
    expected_cluster = f"arn:aws:eks:{region}:{account_id}:cluster/{cluster}"
    if not region or not cluster or cluster_arn != expected_cluster or context != expected_cluster:
        raise ValueError("EKS cluster ARN or kube context does not match the protected staging target")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    for name in ("account-id", "role-arn", "caller-arn", "region", "cluster", "cluster-arn", "context"):
        parser.add_argument(f"--{name}", required=True)
    try:
        validate(**{name.replace("-", "_"): value for name, value in vars(parser.parse_args()).items()})
    except ValueError as exc:
        parser.error(str(exc))
    print("AWS identity and EKS target match the protected staging configuration.")
