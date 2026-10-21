#!/usr/bin/env python3
"""Reject unapproved actions against independently owned staging resources."""

from __future__ import annotations

import json
import sys


PROTECTED_ADDRESSES = {
    "module.eks_addons[0].helm_release.load_balancer_controller",
    "module.eks_addons[0].helm_release.load_balancer_controller[0]",
    'module.eks_addons[0].aws_iam_role.addons["aws_load_balancer_controller"]',
    "module.eks_addons[0].aws_iam_policy.load_balancer_controller",
    "module.eks_addons[0].aws_iam_policy.load_balancer_controller[0]",
    "module.eks_addons[0].aws_iam_role_policy_attachment.load_balancer_controller",
    "module.eks_addons[0].aws_iam_role_policy_attachment.load_balancer_controller[0]",
    *{
        f'module.eks_addons[0].kubernetes_manifest.workload_namespace["{namespace}"]'
        for namespace in ("gateway", "keycloak", "keycloak-system", "rabbitmq-system")
    },
}
PROTECTED_ALB_RESOURCE_TYPES = {
    "aws_lb",
    "aws_lb_listener",
    "aws_lb_listener_rule",
    "aws_lb_listener_certificate",
    "aws_lb_target_group",
    "aws_lb_target_group_attachment",
}
PROTECTED_MUTATING_ACTIONS = {"update", "delete", "forget"}
VALID_ACTIONS = {"no-op", "create", "read", "update", "delete", "forget"}
VALID_ACTION_LISTS = [[action] for action in VALID_ACTIONS] + [["delete", "create"], ["create", "delete"]]


def is_protected_alb_type(resource_type: str) -> bool:
    return resource_type in PROTECTED_ALB_RESOURCE_TYPES or resource_type.startswith("aws_acm_")


def changed_attribute_names(change: dict) -> list[str]:
    """Top-level attribute names whose planned value differs; values are never returned."""
    before = change.get("before") if isinstance(change.get("before"), dict) else {}
    after = change.get("after") if isinstance(change.get("after"), dict) else {}
    unknown = change.get("after_unknown") if isinstance(change.get("after_unknown"), dict) else {}
    names = {key for key in set(before) | set(after) if before.get(key) != after.get(key)}
    names |= {key for key, value in unknown.items() if value}
    return sorted(names)


TAG_ONLY_ATTRIBUTES = {"tags", "tags_all"}


def is_tag_only_update(actions: list, change: dict) -> bool:
    """M1 decision (21 Oct): an in-place update of only tags on a protected ALB/ACM resource is allowed."""
    names = changed_attribute_names(change)
    return actions == ["update"] and bool(names) and set(names) <= TAG_ONLY_ATTRIBUTES


def check_plan(plan: dict) -> list[str]:
    format_version = plan.get("format_version") if isinstance(plan, dict) else None
    format_major, separator, format_minor = format_version.partition(".") if isinstance(format_version, str) else ("", "", "")
    if (
        not isinstance(plan, dict)
        or not separator
        or not format_major.isdigit()
        or not format_minor.isdigit()
        or format_major != "1"
        or not isinstance(plan.get("terraform_version"), str)
        or not plan["terraform_version"]
        or not isinstance(plan.get("planned_values"), dict)
    ):
        raise ValueError("Terraform plan JSON has an unknown format or is missing required top-level fields")
    changes = plan.get("resource_changes")
    if not isinstance(changes, list):
        raise ValueError("Terraform plan JSON has no resource_changes array")
    violations = []
    for resource in changes:
        if not isinstance(resource, dict):
            raise ValueError("Terraform plan JSON contains a malformed resource change")
        address = resource.get("address")
        resource_type = resource.get("type")
        mode = resource.get("mode")
        change = resource.get("change")
        actions = change.get("actions") if isinstance(change, dict) else None
        if (
            not isinstance(address, str)
            or not isinstance(resource_type, str)
            or not isinstance(mode, str)
            or mode not in {"managed", "data"}
            or not isinstance(actions, list)
            or actions not in VALID_ACTION_LISTS
        ):
            raise ValueError("Terraform plan JSON contains an incomplete resource type, mode, address, or action list")

        if address in PROTECTED_ADDRESSES and actions != ["no-op"]:
            violations.append(f"{address}: planned actions {actions} are forbidden for this approved independent owner")
        if is_protected_alb_type(resource_type) and (
            PROTECTED_MUTATING_ACTIONS.intersection(actions)
            or set(actions) == {"create", "delete"}
        ) and not is_tag_only_update(actions, change):
            violations.append(
                f"{address} ({resource_type}): planned actions {actions} are forbidden for protected ALB/ACM resources; "
                f"changed attributes (names only): {', '.join(changed_attribute_names(change)) or 'none reported'}"
            )
    return violations


def main() -> int:
    try:
        plan = json.load(sys.stdin)
        violations = check_plan(plan)
    except (json.JSONDecodeError, OSError, ValueError) as exc:
        print(f"Protected staging Plan action guard failed closed: {exc}", file=sys.stderr)
        return 2
    if violations:
        for violation in violations:
            print(violation, file=sys.stderr)
        return 1
    print("No LBC, LBC IAM, or existing gateway/keycloak/keycloak-system namespace action is planned.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
