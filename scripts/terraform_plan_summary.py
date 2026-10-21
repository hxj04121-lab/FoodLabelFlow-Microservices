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


def changed_fields(item):
    before, after = item.get("change", {}).get("before"), item.get("change", {}).get("after")
    if not isinstance(before, dict) or not isinstance(after, dict):
        return []
    return sorted(key for key in before.keys() | after.keys() if before.get(key) != after.get(key))


def review_note(item):
    resource_type, address = item["type"], item["address"].lower()
    actions = item["change"]["actions"]
    if resource_type == "aws_eks_node_group" and "update" in actions:
        fields = changed_fields(item)
        detail = "changed fields: " + ", ".join(fields) if fields else "inspect private plan for changed fields"
        return " (node update requires rolling-upgrade review; %s)" % detail
    if resource_type.startswith("aws_iam_"):
        return " (IAM permission-scope review required)"
    if resource_type in {"aws_eks_access_entry", "aws_eks_access_policy_association"}:
        return " (EKS access-scope review required)"
    if resource_type.startswith("aws_lb") or address.startswith("module.edge[") or "gateway_from_alb" in address:
        return " (existing ALB modification requires explicit review)"
    if resource_type == "helm_release" or any(word in address for word in ("keycloak", "network_policy", "network-polic")):
        return " (existing controller, identity, or network-policy ownership requires explicit review)"
    if resource_type == "kubernetes_manifest":
        return " (Kubernetes/RBAC change requires explicit review)"
    return ""


def format_change(item):
    return "`%s` — `%s`%s" % (
        item["address"], action_label(item["change"]["actions"]), review_note(item)
    )


def grouped_changes(changes):
    def is_access_control(item):
        address, resource_type = item["address"].lower(), item["type"]
        return (
            resource_type.startswith("aws_iam_")
            or resource_type in {"aws_eks_access_entry", "aws_eks_access_policy_association"}
            or (resource_type == "kubernetes_manifest" and any(word in address for word in ("role", "binding", "reader")))
        )

    return [
        ("General and Compliance node groups", [item for item in changes if item["type"] == "aws_eks_node_group"]),
        ("EBS CSI, StorageClass, and workload Namespaces", [
            item for item in changes
            if (item["type"] == "aws_eks_addon" and "ebs" in item["address"].lower())
            or (item["type"] == "kubernetes_manifest" and any(word in item["address"].lower() for word in ("storage_class", "workload_namespace")))
        ]),
        ("IAM, RBAC, and EKS Access Entries", [item for item in changes if is_access_control(item)]),
        ("Existing ALB, Helm controller, Keycloak, and network-policy resources", [
            item for item in changes
            if item["type"].startswith("aws_lb")
            or item["address"].lower().startswith("module.edge[")
            or "gateway_from_alb" in item["address"].lower()
            or item["type"] == "helm_release"
            or any(word in item["address"].lower() for word in ("keycloak", "network_policy", "network-polic"))
        ]),
        ("Existing resources that may be affected", [
            item for item in changes
            if any(action in item["change"]["actions"] for action in ("update", "delete", "forget"))
            or ("create" in item["change"]["actions"] and "delete" in item["change"]["actions"])
        ]),
    ]


def is_protected_resource(item):
    resource_type = item.get("type", "")
    return (
        resource_type in PROTECTED_TYPES
        or resource_type.startswith("aws_iam_")
        or resource_type in {
            "aws_eks_access_entry", "aws_eks_access_policy_association",
            "helm_release", "kubernetes_manifest",
        }
    )


# M1-reviewed (21 Oct): narrowing the control-plane -> node rule from 1025-65535 to 10250 (with separate
# 4443/9443 webhook rules) must replace it; create_before_destroy makes Terraform add the new rule first.
REVIEWED_CREATE_BEFORE_DESTROY = {"module.eks[0].aws_security_group_rule.nodes_from_cluster"}


def is_reviewed_create_before_destroy(item):
    return item.get("address") in REVIEWED_CREATE_BEFORE_DESTROY and item["change"]["actions"] == ["create", "delete"]


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
        if is_protected_resource(item)
        and any(action in item["change"]["actions"] for action in ("delete", "forget"))
        and not is_reviewed_create_before_destroy(item)
    ]
    counts = {"create": 0, "update": 0, "replace": 0, "delete": 0}
    for item in changes:
        actions = item["change"]["actions"]
        if "create" in actions and "delete" in actions:
            counts["replace"] += 1
        elif "delete" in actions or "forget" in actions:
            counts["delete"] += 1
        elif "create" in actions:
            counts["create"] += 1
        elif "update" in actions:
            counts["update"] += 1

    print("## Terraform resource actions")
    print()
    print("Addresses and actions only; resource values and outputs are omitted.")
    print()
    print("Resource counts: create **%d**, update **%d**, replace **%d**, delete **%d**." % (
        counts["create"], counts["update"], counts["replace"], counts["delete"]
    ))
    print()
    print("Protected foundation destroy/replacement check: **%s**" % (
        "FAIL" if protected_destructive else "PASS"
    ))
    print("Node group updates, IAM/EKS access changes, ALB changes, and existing controller/Keycloak/NetworkPolicy changes are marked for explicit review below.")
    print()
    if changes:
        for item in sorted(changes, key=lambda item: item["address"]):
            print("- " + format_change(item))
    else:
        print("No managed-resource changes.")

    for title, rows in grouped_changes(changes):
        print()
        print("### %s" % title)
        print()
        if rows:
            for item in sorted(rows, key=lambda item: item["address"]):
                print("- " + format_change(item))
        else:
            print("No changes.")

    if protected_destructive:
        print()
        print("Protected resources with delete/forget actions:")
        for item in sorted(protected_destructive, key=lambda item: item["address"]):
            print("- " + format_change(item))
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
