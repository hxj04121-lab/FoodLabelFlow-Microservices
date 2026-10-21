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
    r"^(?P<address>.+): (?P<verb>Creation|Modifications|Destruction) complete after(?:\s|$)"
)
ADDRESS_SEGMENT_RE = re.compile(
    r'(?P<label>[A-Za-z_][A-Za-z0-9_-]*)(?P<index>\[(?:[0-9]+|"(?:[^"\\]|\\.)*")\])?'
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


def _split_address_segments(address: str) -> list[str] | None:
    """Split Terraform address dots while respecting quoted instance keys."""
    if not address:
        return None

    segments: list[str] = []
    segment_start = 0
    in_index = False
    in_string = False
    escaped = False

    for position, character in enumerate(address):
        if in_string:
            if escaped:
                escaped = False
            elif character == "\\":
                escaped = True
            elif character == '"':
                in_string = False
            elif character in "\r\n":
                return None
            continue

        if character == "." and not in_index:
            segments.append(address[segment_start:position])
            segment_start = position + 1
        elif character == "[":
            if in_index:
                return None
            in_index = True
        elif character == "]":
            if not in_index:
                return None
            in_index = False
        elif character == '"':
            if not in_index:
                return None
            in_string = True

    if in_index or in_string or escaped:
        return None
    segments.append(address[segment_start:])
    if any(not segment for segment in segments):
        return None
    return segments


def _parse_address_segment(segment: str) -> tuple[str, bool] | None:
    match = ADDRESS_SEGMENT_RE.fullmatch(segment)
    if match is None:
        return None
    return match.group("label"), match.group("index") is not None


def _safe_resource_type(address: str) -> str | None:
    """Parse an address structurally; dots inside quoted keys are not separators."""
    segments = _split_address_segments(address)
    if segments is None:
        return None

    position = 0
    while position < len(segments) and segments[position] == "module":
        if position + 1 >= len(segments):
            return None
        module = _parse_address_segment(segments[position + 1])
        if module is None:
            return None
        position += 2

    if position < len(segments) and segments[position] in {"data", "ephemeral"}:
        position += 1

    if len(segments) - position != 2:
        return None

    resource_type = _parse_address_segment(segments[position])
    resource_name = _parse_address_segment(segments[position + 1])
    if resource_type is None or resource_name is None:
        return None
    if resource_type[1] or resource_type[0] not in SAFE_RESOURCE_TYPES:
        return None
    return resource_type[0]


def completed_resource_event_counts(log_text: str | None) -> dict[str, object]:
    """Count matching completion events by allowlisted type/action, not unique resources."""
    if not log_text:
        return {
            "count_scope": "best_effort_bounded_log_completion_events",
            "event_count": 0,
            "by_type_and_action": [],
            "unrecognized_address_event_count": 0,
        }

    counts: Counter[tuple[str, str]] = Counter()
    unrecognized_address_count = 0
    normalized = ANSI_ESCAPE_RE.sub("", log_text)
    for line in normalized.splitlines():
        match = COMPLETION_RE.match(line)
        if match is None:
            continue
        resource_type = _safe_resource_type(match.group("address"))
        action = SAFE_ACTIONS[match.group("verb")]
        if resource_type is not None:
            counts[(resource_type, action)] += 1
        else:
            unrecognized_address_count += 1

    entries = [
        {"type": resource_type, "action": action, "count": count}
        for (resource_type, action), count in sorted(counts.items())
    ]
    return {
        "count_scope": "best_effort_bounded_log_completion_events",
        "event_count": sum(counts.values()),
        "by_type_and_action": entries,
        "unrecognized_address_event_count": unrecognized_address_count,
    }


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
        "completed_resource_events": completed_resource_event_counts(log_text),
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
