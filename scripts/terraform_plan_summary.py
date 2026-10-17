#!/usr/bin/env python3
"""Print a redacted resource-action summary and reject foundation deletes/replacements."""
import json
import sys


PROTECTED_TYPES = {
    "aws_eks_cluster",
    "aws_eks_addon",
    "aws_eks_node_group",
    "aws_db_instance",
    "aws_eip",
    "aws_internet_gateway",
    "aws_lb",
    "aws_lb_listener",
    "aws_lb_listener_certificate",
    "aws_lb_listener_rule",
    "aws_lb_target_group",
    "aws_lb_target_group_attachment",
    "aws_nat_gateway",
    "aws_route",
    "aws_route_table",
    "aws_security_group",
    "aws_security_group_rule",
    "aws_subnet",
    "aws_vpc",
}


def action_label(actions):
    if "create" in actions and "delete" in actions:
        return "replace"
    return " -> ".join(actions)


def main():
    try:
        plan = json.load(sys.stdin)
        changes = [
            item
            for item in plan["resource_changes"]
            if item.get("mode") == "managed"
            and item.get("change", {}).get("actions") != ["no-op"]
        ]
    except (KeyError, TypeError, json.JSONDecodeError):
        print("Invalid Terraform plan JSON; no plan values were emitted.")
        return 2

    protected_destructive = [
        item
        for item in changes
        if item.get("type") in PROTECTED_TYPES
        and any(action in item["change"]["actions"] for action in ("delete", "forget"))
    ]

    print("## Terraform resource actions")
    print()
    print("Addresses and actions only; resource values and outputs are omitted.")
    print()
    print("Protected foundation destroy/replacement check: **%s**" % (
        "FAIL" if protected_destructive else "PASS"
    ))
    print()
    if changes:
        for item in sorted(changes, key=lambda item: item["address"]):
            print("- `%s` — `%s`" % (item["address"], action_label(item["change"]["actions"])))
    else:
        print("No managed-resource changes.")

    if protected_destructive:
        print()
        print("Protected resources with delete/forget actions:")
        for item in sorted(protected_destructive, key=lambda item: item["address"]):
            print("- `%s` — `%s`" % (item["address"], action_label(item["change"]["actions"])))
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
