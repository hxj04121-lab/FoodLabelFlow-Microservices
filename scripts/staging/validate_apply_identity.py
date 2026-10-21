#!/usr/bin/env python3
"""Fail closed unless kubectl auth whoami returns the expected EKS role username."""

import json
import os
import re
import sys
from typing import Optional


ACCOUNT_ID_RE = re.compile(r"[0-9]{12}\Z")
ROLE_ARN_RE = re.compile(
    r"arn:aws:iam::(?P<account>[0-9]{12}):role/(?:[^/]+/)*(?P<role_name>[^/]+)\Z"
)
# EKS's default role username is an assumed-role STS ARN and drops IAM role paths.
# https://docs.aws.amazon.com/eks/latest/userguide/set-custom-username.html
# STS RoleSessionName: 2-64 ASCII alphanumeric characters plus _+=,.@-
# https://docs.aws.amazon.com/STS/latest/APIReference/API_AssumeRole.html
SESSION_NAME_RE = re.compile(r"[A-Za-z0-9_+=,.@-]{2,64}\Z")


def expected_eks_username_prefix(account_id: str, role_arn: str) -> Optional[str]:
    """Return EKS's default assumed-role username prefix for a matching IAM role.

    EKS removes IAM role paths from its generated username, so use only the final
    role-name component. The workflow separately pins AWS_ROLE_ARN to the
    pathless, dedicated staging Apply role before it calls this validator.
    """
    if not isinstance(account_id, str) or not ACCOUNT_ID_RE.fullmatch(account_id):
        return None
    if not isinstance(role_arn, str):
        return None

    match = ROLE_ARN_RE.fullmatch(role_arn)
    if match is None or match.group("account") != account_id:
        return None

    return f"arn:aws:sts::{account_id}:assumed-role/{match.group('role_name')}/"


def is_expected_eks_username(username: object, account_id: str, role_arn: str) -> bool:
    prefix = expected_eks_username_prefix(account_id, role_arn)
    if prefix is None or not isinstance(username, str) or not username.startswith(prefix):
        return False

    session_name = username[len(prefix) :]
    return SESSION_NAME_RE.fullmatch(session_name) is not None


def validate_whoami_document(document: object, account_id: str, role_arn: str) -> bool:
    if not isinstance(document, dict):
        return False
    status = document.get("status")
    if not isinstance(status, dict):
        return False
    user_info = status.get("userInfo")
    if not isinstance(user_info, dict):
        return False
    return is_expected_eks_username(user_info.get("username"), account_id, role_arn)


def main() -> int:
    try:
        document = json.load(sys.stdin)
    except (json.JSONDecodeError, UnicodeDecodeError):
        print("::error::Could not parse kubectl auth whoami response.", file=sys.stderr)
        return 1

    if not validate_whoami_document(
        document,
        os.environ.get("AWS_ACCOUNT_ID", ""),
        os.environ.get("AWS_ROLE_ARN", ""),
    ):
        print(
            "::error::Kubernetes identity is not the expected EKS-generated username for the dedicated Apply role.",
            file=sys.stderr,
        )
        return 1

    print("Verified Kubernetes identity matches the dedicated Apply role.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
