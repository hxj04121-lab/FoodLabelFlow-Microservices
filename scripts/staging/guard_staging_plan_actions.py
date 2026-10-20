#!/usr/bin/env python3
"""Reject any non-no-op action against the independent LBC or existing namespaces."""

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
        for namespace in ("gateway", "keycloak", "keycloak-system")
    },
}


def check_plan(plan: dict) -> list[str]:
    changes = plan.get("resource_changes")
    if not isinstance(changes, list):
        raise ValueError("Terraform plan JSON has no resource_changes array")
    violations = []
    for resource in changes:
        if not isinstance(resource, dict):
            raise ValueError("Terraform plan JSON contains a malformed resource change")
        address = resource.get("address")
        change = resource.get("change")
        actions = change.get("actions") if isinstance(change, dict) else None
        if not isinstance(address, str) or not isinstance(actions, list) or not all(isinstance(a, str) for a in actions):
            raise ValueError("Terraform plan JSON contains a malformed address or action list")
        if address in PROTECTED_ADDRESSES and actions != ["no-op"]:
            violations.append(f"{address}: planned actions {actions} are forbidden for this approved independent owner")
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
