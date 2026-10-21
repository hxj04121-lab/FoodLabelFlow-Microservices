#!/usr/bin/env python3
"""Validate the protected RabbitMQ Operator install AWS/EKS preflight responses."""

from __future__ import annotations

import argparse
import json
import os
import re
import sys

from scripts.staging.validate_apply_identity import is_expected_eks_username, validate_whoami_document


ACCOUNT_ID_RE = re.compile(r"[0-9]{12}\Z")


def validate_operator_caller_arn(caller_arn: object, account_id: str, role_arn: str) -> bool:
    """Accept only the expected account/Deploy role with a valid STS session name."""
    return is_expected_eks_username(caller_arn, account_id, role_arn)


def validate_operator_identity(document: object, account_id: str, role_arn: str) -> bool:
    """Accept only the default EKS username generated for the pinned Deploy role."""
    return validate_whoami_document(document, account_id, role_arn)


def validate_private_staging_cluster(
    document: object, account_id: str, region: str, cluster_name: str
) -> bool:
    """Require the exact active staging cluster to be private-only."""
    if not isinstance(account_id, str) or ACCOUNT_ID_RE.fullmatch(account_id) is None:
        return False
    if not isinstance(region, str) or not region or not isinstance(cluster_name, str) or not cluster_name:
        return False
    if not isinstance(document, dict):
        return False
    cluster = document.get("cluster")
    if not isinstance(cluster, dict):
        return False
    if cluster.get("status") != "ACTIVE":
        return False
    if cluster.get("arn") != f"arn:aws:eks:{region}:{account_id}:cluster/{cluster_name}":
        return False
    vpc_config = cluster.get("resourcesVpcConfig")
    if not isinstance(vpc_config, dict):
        return False
    return vpc_config.get("endpointPrivateAccess") is True and vpc_config.get("endpointPublicAccess") is False


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("check", choices=("caller-arn", "identity", "cluster"))
    args = parser.parse_args(argv)

    account_id = os.environ.get("AWS_ACCOUNT_ID", "")
    role_arn = os.environ.get("AWS_ROLE_ARN", "")
    if args.check == "caller-arn":
        valid = validate_operator_caller_arn(sys.stdin.read(), account_id, role_arn)
        message = "AWS caller identity does not match the approved Operator Deploy role."
        success = "Verified AWS caller identity matches the approved Operator Deploy role."
    else:
        try:
            document = json.load(sys.stdin)
        except (json.JSONDecodeError, UnicodeDecodeError):
            print("::error::Could not parse the EKS preflight response.", file=sys.stderr)
            return 1

        if args.check == "identity":
            valid = validate_operator_identity(document, account_id, role_arn)
            message = "Kubernetes identity does not match the approved Operator Deploy role."
            success = "Verified Kubernetes identity matches the approved Operator Deploy role."
        else:
            valid = validate_private_staging_cluster(
                document,
                account_id,
                os.environ.get("AWS_REGION", ""),
                os.environ.get("EKS_CLUSTER_NAME", ""),
            )
            message = "EKS target is not the active private-only staging cluster."
            success = "Verified the exact active private-only staging cluster."

    if not valid:
        print(f"::error::{message}", file=sys.stderr)
        return 1
    print(success)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
