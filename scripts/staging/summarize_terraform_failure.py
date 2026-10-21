#!/usr/bin/env python3
"""Emit a bounded, allowlisted summary for a failed Terraform command."""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path


MAX_LOG_BYTES = 256 * 1024
SAFE_STEPS = frozenset({"terraform_plan"})
SAFE_CATEGORIES = frozenset(
    {
        "authorization",
        "configuration",
        "credentials",
        "network",
        "rate_limited",
        "resource_operation",
        "state_backend",
        "unclassified",
    }
)
SAFE_RESOURCE_TYPES = frozenset(
    {
        "aws_db_instance",
        "aws_eks_access_entry",
        "aws_eks_addon",
        "aws_eks_cluster",
        "aws_eks_node_group",
        "aws_iam_policy",
        "aws_iam_role",
        "aws_iam_role_policy_attachment",
        "aws_lb",
        "aws_lb_listener",
        "aws_lb_listener_rule",
        "aws_lb_target_group",
        "aws_route_table",
        "aws_security_group",
        "aws_subnet",
        "aws_vpc",
        "helm_release",
        "kubernetes_manifest",
    }
)

ANSI_ESCAPE_RE = re.compile(r"\x1b(?:[@-Z\\-_]|\[[0-?]*[ -/]*[@-~])")
ADDRESS_RE = re.compile(r"\bwith\s+([A-Za-z0-9_.\-\[\]\"']+)")
RESOURCE_DECLARATION_RE = re.compile(r"\bresource\s+['\"]([A-Za-z_][A-Za-z0-9_]*)['\"]")
CATEGORY_RULES = (
    (
        "credentials",
        re.compile(r"no valid credential sources|no credential providers|expiredtoken|invalidclienttokenid|unable to locate credentials"),
    ),
    (
        "authorization",
        re.compile(r"accessdenied|unauthorizedoperation|\bforbidden\b|permission denied|not authorized|(?:http |status code:? )403\b"),
    ),
    (
        "state_backend",
        re.compile(r"error acquiring the state lock|error loading state|error initializing backend|failed to get existing workspaces"),
    ),
    (
        "configuration",
        re.compile(r"unsupported argument|unsupported attribute|missing required argument|invalid value for|invalid configuration argument"),
    ),
    (
        "rate_limited",
        re.compile(r"throttl(?:e|ing)|requestlimitexceeded|toomanyrequests|too many requests|(?:http |status code:? )429\b"),
    ),
    (
        "network",
        re.compile(r"\btimeout\b|timed out|deadline exceeded|connection refused|no such host|temporary failure in name resolution"),
    ),
    (
        "resource_operation",
        re.compile(r"error (?:creating|updating|deleting|reading|waiting)|failed to (?:create|update|delete|read|wait)"),
    ),
)


def read_log_tail(path: Path) -> tuple[str | None, bool]:
    """Read only a bounded tail; never return or print the path or its contents."""
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


def classify_diagnostic(log_text: str | None) -> str:
    if not log_text:
        return "unclassified"
    normalized = ANSI_ESCAPE_RE.sub("", log_text).casefold()
    for category, pattern in CATEGORY_RULES:
        if pattern.search(normalized):
            return category
    return "unclassified"


def infer_resource_type(log_text: str | None) -> str:
    if not log_text:
        return "unknown"
    normalized = ANSI_ESCAPE_RE.sub("", log_text)
    candidates = [match.group(1) for match in ADDRESS_RE.finditer(normalized)]
    candidates.extend(match.group(1) for match in RESOURCE_DECLARATION_RE.finditer(normalized))
    for address in candidates:
        for segment in reversed(address.rstrip(",").split(".")):
            resource_type = segment.split("[", 1)[0].strip("\"'")
            if resource_type in SAFE_RESOURCE_TYPES:
                return resource_type
    return "unknown"


def summarize_failure(
    log_text: str | None, *, step: str, exit_code: int, log_truncated: bool = False
) -> dict[str, str | int | bool]:
    """Return only fixed labels, an exit code, and an allowlisted resource type."""
    if step not in SAFE_STEPS:
        raise ValueError("Unsupported Terraform failure step.")
    if type(exit_code) is not int or not 1 <= exit_code <= 255:
        raise ValueError("Terraform failure exit code must be between 1 and 255.")

    category = classify_diagnostic(log_text)
    if category not in SAFE_CATEGORIES:
        category = "unclassified"
    return {
        "event": "terraform_failure",
        "step": step,
        "exit_code": exit_code,
        "category": category,
        "resource_type": infer_resource_type(log_text),
        "log_available": log_text is not None,
        "log_truncated": bool(log_truncated),
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
    parser.add_argument("--step", choices=sorted(SAFE_STEPS), required=True)
    parser.add_argument("--exit-code", type=_parse_exit_code, required=True)
    parser.add_argument("--log-file", type=Path, required=True)
    args = parser.parse_args(argv)

    log_text, log_truncated = read_log_tail(args.log_file)
    summary = summarize_failure(
        log_text,
        step=args.step,
        exit_code=args.exit_code,
        log_truncated=log_truncated,
    )
    print("::error::Terraform failure summary " + json.dumps(summary, sort_keys=True, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
