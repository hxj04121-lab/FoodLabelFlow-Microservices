#!/usr/bin/env python3
"""Emit a safe, bounded summary of Terraform Apply failure output."""

from __future__ import annotations

import argparse
import json
import re
from collections import Counter
from pathlib import Path


MAX_LOG_BYTES = 256 * 1024
SAFE_RESOURCE_TYPES = frozenset(
    {
        "aws_acm_certificate",
        "aws_autoscaling_group_tag",
        "aws_cloudwatch_log_group",
        "aws_db_instance",
        "aws_db_subnet_group",
        "aws_eip",
        "aws_eks_access_entry",
        "aws_eks_access_policy_association",
        "aws_eks_addon",
        "aws_eks_cluster",
        "aws_eks_node_group",
        "aws_iam_openid_connect_provider",
        "aws_iam_policy",
        "aws_iam_role",
        "aws_iam_role_policy",
        "aws_iam_role_policy_attachment",
        "aws_internet_gateway",
        "aws_launch_template",
        "aws_lb",
        "aws_lb_listener",
        "aws_lb_target_group",
        "aws_nat_gateway",
        "aws_route",
        "aws_route_table",
        "aws_route_table_association",
        "aws_s3_bucket",
        "aws_s3_bucket_ownership_controls",
        "aws_s3_bucket_policy",
        "aws_s3_bucket_public_access_block",
        "aws_s3_bucket_server_side_encryption_configuration",
        "aws_s3_bucket_versioning",
        "aws_secretsmanager_secret",
        "aws_secretsmanager_secret_version",
        "aws_security_group",
        "aws_security_group_rule",
        "aws_subnet",
        "aws_vpc",
        "helm_release",
        "kubernetes_manifest",
        "random_password",
    }
)

ANSI_ESCAPE_RE = re.compile(r"\x1b(?:[@-Z\\-_]|\[[0-?]*[ -/]*[@-~])")
COMPLETION_RE = re.compile(
    r"^(?P<address>.+?): (?P<verb>Creation|Modifications|Destruction) complete after(?:\s|$)"
)
SAFE_ACTIONS = {
    "Creation": "create",
    "Modifications": "update",
    "Destruction": "delete",
}


def read_log_tail(path: Path) -> tuple[str | None, bool]:
    """Read a bounded tail without returning or printing the path or raw text."""
    try:
        with path.open("rb") as log_file:
            log_file.seek(0, 2)
            size = log_file.tell()
            truncated = size > MAX_LOG_BYTES
            log_file.seek(max(0, size - MAX_LOG_BYTES))
            content = log_file.read(MAX_LOG_BYTES)
    except OSError:
        return None, False
    return content.decode("utf-8", errors="replace"), truncated


def _safe_resource_type(address: str) -> str | None:
    segments = address.rsplit(".", 2)
    if len(segments) < 2:
        return None
    resource_type = segments[-2]
    return resource_type if resource_type in SAFE_RESOURCE_TYPES else None


def completed_resource_counts(log_text: str | None) -> dict[str, object]:
    """Return counts by allowlisted type/action; never return an address or ID."""
    if not log_text:
        return {"resource_count": 0, "by_type_and_action": []}

    counts: Counter[tuple[str, str]] = Counter()
    normalized = ANSI_ESCAPE_RE.sub("", log_text)
    for line in normalized.splitlines():
        match = COMPLETION_RE.match(line)
        if match is None:
            continue
        resource_type = _safe_resource_type(match.group("address"))
        action = SAFE_ACTIONS[match.group("verb")]
        if resource_type is not None:
            counts[(resource_type, action)] += 1

    entries = [
        {"type": resource_type, "action": action, "count": count}
        for (resource_type, action), count in sorted(counts.items())
    ]
    return {"resource_count": sum(counts.values()), "by_type_and_action": entries}


def summarize_failure(
    log_text: str | None, *, exit_code: int, log_truncated: bool = False
) -> dict[str, object]:
    """Return fixed labels, a numeric exit code, and allowlisted counts only."""
    if type(exit_code) is not int or not 1 <= exit_code <= 255:
        raise ValueError("Terraform Apply exit code must be between 1 and 255.")

    return {
        "event": "terraform_apply_failure",
        "failure_type": "terraform_apply_failed",
        "exit_code": exit_code,
        "log_available": log_text is not None,
        "log_truncated": bool(log_truncated),
        "completed_resources": completed_resource_counts(log_text),
    }


def _parse_exit_code(value: str) -> int:
    try:
        code = int(value)
    except ValueError as exc:
        raise argparse.ArgumentTypeError("exit code must be an integer") from exc
    if not 1 <= code <= 255:
        raise argparse.ArgumentTypeError("exit code must be between 1 and 255")
    return code


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--exit-code", type=_parse_exit_code, required=True)
    parser.add_argument("--log-file", type=Path, required=True)
    args = parser.parse_args(argv)

    log_text, log_truncated = read_log_tail(args.log_file)
    summary = summarize_failure(
        log_text, exit_code=args.exit_code, log_truncated=log_truncated
    )
    print(
        "::error::Terraform Apply failure summary "
        + json.dumps(summary, sort_keys=True, separators=(",", ":"))
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
