#!/usr/bin/env python3
"""Fail a protected staging plan on unexpected out-of-State platform resources."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import sys


RELEASES = {
    "cluster-autoscaler": "module.eks_addons[0].helm_release.cluster_autoscaler",
    "metrics-server": "module.eks_addons[0].helm_release.metrics_server",
    "spectrace-network-policies": "module.eks_addons[0].helm_release.network_policies",
}
MANAGED_NAMESPACES = (
    "gateway",
    "keycloak",
    "rabbitmq",
    "observability",
    "specification",
    "formulation",
    "compliance",
    "label-workflow",
    "frontend",
    "rabbitmq-system",
    "keycloak-system",
)
LBC_STATE_ADDRESSES = (
    "module.eks_addons[0].helm_release.load_balancer_controller",
    "module.eks_addons[0].helm_release.load_balancer_controller[0]",
    'module.eks_addons[0].aws_iam_role.addons["aws_load_balancer_controller"]',
    "module.eks_addons[0].aws_iam_policy.load_balancer_controller",
    "module.eks_addons[0].aws_iam_policy.load_balancer_controller[0]",
    "module.eks_addons[0].aws_iam_role_policy_attachment.load_balancer_controller",
    "module.eks_addons[0].aws_iam_role_policy_attachment.load_balancer_controller[0]",
)
TGB_RBAC_NAME = "spectrace-staging-deployer-custom-resources"
KEYCLOAK_RBAC_NAME = "spectrace-staging-deployer-keycloak-resource"
MANAGED_RBAC = {
    (kind, name, namespace): (
        f'module.eks_addons[0].kubernetes_manifest.deployer_custom_resource_{resource}["{namespace}"]'
    )
    for namespace in ("gateway", "keycloak", "frontend")
    for kind, resource in (("Role", "role"), ("RoleBinding", "rolebinding"))
    for name in (TGB_RBAC_NAME,)
}
MANAGED_RBAC.update(
    {
        (kind, KEYCLOAK_RBAC_NAME, "keycloak"): f"module.eks_addons[0].kubernetes_manifest.keycloak_resource_{resource}"
        for kind, resource in (("Role", "role"), ("RoleBinding", "rolebinding"))
    }
)
MANAGED_RBAC.update(
    {
        (kind, "spectrace-staging-deployer-rabbitmq", "rabbitmq"): f"module.eks_addons[0].kubernetes_manifest.rabbitmq_deployer_{resource}"
        for kind, resource in (("Role", "role"), ("RoleBinding", "rolebinding"))
    }
)
MANAGED_RBAC.update(
    {
        (kind, "spectrace-staging-deployer-rabbitmq", namespace): (
            f'module.eks_addons[0].kubernetes_manifest.rabbitmq_service_deployer_{resource}["{namespace}"]'
        )
        for namespace in ("gateway", "keycloak", "observability", "specification", "formulation", "compliance", "label-workflow")
        for kind, resource in (("Role", "role"), ("RoleBinding", "rolebinding"))
    }
)
MANAGED_RBAC.update(
    {
        (kind, "spectrace-staging-node-reader", "cluster"): f"module.eks_addons[0].kubernetes_manifest.staging_deployer_node_reader{suffix}"
        for kind, suffix in (("ClusterRole", ""), ("ClusterRoleBinding", "_binding"))
    }
)
MANAGED_PLATFORM_RESOURCES = {
    ("eks-addon", "aws-ebs-csi-driver"): "module.eks_addons[0].aws_eks_addon.ebs_csi",
    ("storageclass", "ebs-gp3"): "module.eks_addons[0].kubernetes_manifest.ebs_gp3_storage_class",
}


def read_names(path: Path) -> set[str]:
    if not path.is_file():
        raise ValueError(f"inventory file is missing: {path}")
    return {
        line.strip().split()[0].split("/")[-1]
        for line in path.read_text().splitlines()
        if line.strip()
    }


def read_deployments(path: Path) -> dict[str, str | None]:
    if not path.is_file():
        raise ValueError(f"deployment inventory file is missing: {path}")
    deployments: dict[str, str | None] = {}
    for line in path.read_text().splitlines():
        fields = line.split()
        if not fields:
            continue
        name = fields[0].split("/")[-1]
        deployments[name] = fields[1] if len(fields) > 1 and re.fullmatch(r"\d+/\d+", fields[1]) else None
    return deployments


def read_lbc_helm_owner(path: Path, expected: dict) -> None:
    if not path.is_file():
        raise ValueError(f"LBC Deployment Helm ownership metadata is missing: {path}")
    content = path.read_text()
    if not content.strip():
        raise ValueError("LBC Deployment Helm ownership metadata is missing")
    lines = [line for line in content.splitlines() if line]
    if len(lines) != 1:
        raise ValueError("LBC Deployment Helm ownership metadata must contain exactly one row")
    fields = lines[0].split("\t")
    if len(fields) != 3 or not all(fields):
        raise ValueError("LBC Deployment Helm ownership metadata is malformed")
    managed_by, release_name, release_namespace = fields
    if (
        managed_by != expected["helm_managed_by_label"]
        or release_name != expected["release_name"]
        or release_namespace != expected["namespace"]
    ):
        raise ValueError("LBC Deployment Helm ownership metadata does not match the reviewed release")


def load_allowlist(path: Path, aws_account_id: str, cluster_name: str, manage_lbc: bool) -> dict:
    if not path.is_file():
        raise ValueError(f"reviewed ownership allowlist is missing: {path}")
    try:
        allowlist = json.loads(path.read_text())
    except json.JSONDecodeError as exc:
        raise ValueError(f"reviewed ownership allowlist is invalid JSON: {exc}") from exc

    fixed = {
        "schema_version": 1,
        "environment": "staging",
        "aws_account_id": "679857346122",
        "aws_region": "ap-southeast-1",
        "cluster_name": "spectrace-staging",
        "management_mode": "independent",
        "manage_load_balancer_controller": False,
        "maintenance_owner": "M1 / State owner",
        "window_end_utc": "2026-10-23T00:00:00Z",
        "decision_comment_id": 6287467427,
    }
    expected_keys = set(fixed) | {"load_balancer_controller", "existing_namespaces"}
    if set(allowlist) != expected_keys:
        raise ValueError("ownership allowlist contains missing or unreviewed fields")
    for key, expected in fixed.items():
        if allowlist.get(key) != expected:
            raise ValueError(f"ownership allowlist {key} does not match the reviewed staging decision")
    if aws_account_id != allowlist["aws_account_id"]:
        raise ValueError("AWS identity does not match the reviewed staging account")
    if cluster_name != allowlist["cluster_name"]:
        raise ValueError("Kubernetes target does not match the reviewed staging cluster")
    if manage_lbc != allowlist["manage_load_balancer_controller"]:
        raise ValueError("LBC management mode does not match the reviewed independent-management decision")

    controller = allowlist.get("load_balancer_controller", {})
    expected_controller = {
        "namespace": "kube-system",
        "release_name": "aws-load-balancer-controller",
        "deployment_name": "aws-load-balancer-controller",
        "deployment_ready": "1/1",
        "iam_role_name": "spectrace-staging-aws_load_balancer_controller",
        "iam_role_arn": "arn:aws:iam::679857346122:role/spectrace-staging-aws_load_balancer_controller",
        "iam_policy_name": "spectrace-staging-aws-load-balancer-controller",
        "iam_policy_arn": "arn:aws:iam::679857346122:policy/spectrace-staging-aws-load-balancer-controller",
        "helm_managed_by_label": "Helm",
    }
    if controller != expected_controller:
        raise ValueError("LBC allowlist must contain only the exact reviewed release and IAM identities")
    if allowlist.get("existing_namespaces") != ["gateway", "keycloak", "keycloak-system", "rabbitmq-system"]:
        raise ValueError("existing_namespaces must match the four reviewed live namespaces")
    return allowlist


def check_ownership(
    state_path: Path,
    deployment_path: Path,
    network_policy_path: Path,
    namespace_path: Path,
    iam_resource_path: Path,
    rbac_resource_path: Path,
    platform_resource_path: Path,
    lbc_helm_owner_path: Path,
    allowlist_path: Path,
    aws_account_id: str,
    cluster_name: str,
    manage_load_balancer_controller: bool,
) -> list[str]:
    allowlist = load_allowlist(allowlist_path, aws_account_id, cluster_name, manage_load_balancer_controller)
    if not state_path.is_file():
        raise ValueError(f"Terraform State address inventory is missing: {state_path}")
    state = set(state_path.read_text().splitlines())
    deployments = read_deployments(deployment_path)
    network_policies = read_names(network_policy_path)
    namespaces = read_names(namespace_path)
    controller = allowlist["load_balancer_controller"]
    read_lbc_helm_owner(lbc_helm_owner_path, controller)

    if not iam_resource_path.is_file() or not rbac_resource_path.is_file() or not platform_resource_path.is_file():
        raise ValueError("IAM, Kubernetes RBAC, or platform resource inventory is missing")
    iam_resources: set[tuple[str, str]] = set()
    for line in iam_resource_path.read_text().splitlines():
        if not line.strip():
            continue
        parts = line.split("\t")
        valid = (parts[0] in ("role", "policy") and len(parts) == 2) or (parts[0] == "attachment" and len(parts) == 3)
        if not valid:
            raise ValueError("IAM inventory contains an unexpected or malformed resource entry")
        iam_resources.add((parts[0], "\t".join(parts[1:])))

    problems: list[str] = []
    live_releases = {
        "cluster-autoscaler": "cluster-autoscaler" in deployments,
        "metrics-server": "metrics-server" in deployments,
        # The platform policy chart renders only stcn-* NetworkPolicies.
        "spectrace-network-policies": any(name.startswith("stcn-") for name in network_policies),
    }
    for release, address in RELEASES.items():
        if live_releases[release] and address not in state:
            problems.append(f"{release}: live platform resources exist but Terraform State lacks {address}; stop for ownership review.")

    existing_namespaces = set(allowlist["existing_namespaces"])
    for namespace in MANAGED_NAMESPACES:
        address = f'module.eks_addons[0].kubernetes_manifest.workload_namespace["{namespace}"]'
        if namespace in existing_namespaces:
            if namespace not in namespaces:
                problems.append(f"namespace {namespace}: reviewed out-of-band namespace is missing from the live cluster.")
            if address in state:
                problems.append(f"namespace {namespace}: State address {address} conflicts with the reviewed independent owner.")
        elif namespace in namespaces and address not in state:
            problems.append(f"namespace {namespace}: live namespace exists but Terraform State lacks {address}; stop for ownership review.")

    live_lbc = "aws-load-balancer-controller" in deployments
    lbc_addresses_in_state = [address for address in LBC_STATE_ADDRESSES if address in state]
    if lbc_addresses_in_state:
        problems.append(
            "LBC has Terraform State addresses despite the approved independent-management mode: "
            + ", ".join(lbc_addresses_in_state)
        )
    expected_iam = {
        ("role", controller["iam_role_arn"]),
        ("policy", controller["iam_policy_arn"]),
        ("attachment", f'{controller["iam_role_arn"]}\t{controller["iam_policy_arn"]}'),
    }
    normalized_iam = iam_resources
    if normalized_iam - expected_iam:
        raise ValueError("IAM inventory contains an object outside the reviewed LBC allowlist")
    if not live_lbc or deployments["aws-load-balancer-controller"] != controller["deployment_ready"]:
        problems.append("LBC deployment is missing or not at the reviewed healthy replica count; stop for fresh ownership evidence.")
    if normalized_iam != expected_iam:
        missing = sorted(expected_iam - normalized_iam)
        problems.append(f"LBC IAM role, policy, or attachment inventory is incomplete; stop for fresh ownership evidence ({missing}).")
    if not (live_lbc and normalized_iam == expected_iam) and not lbc_addresses_in_state:
        problems.append("approved out-of-band LBC evidence is incomplete; no plan may proceed.")

    for line in rbac_resource_path.read_text().splitlines():
        if not line.strip():
            continue
        parts = line.split("\t")
        if len(parts) != 3 or tuple(parts) not in MANAGED_RBAC:
            raise ValueError("RBAC inventory contains an unexpected resource entry")
        kind, name, namespace = parts
        address = MANAGED_RBAC[(kind, name, namespace)]
        if address not in state:
            problems.append(
                f"live deployer {kind} {name} exists in namespace {namespace} but Terraform State lacks {address}; stop for ownership review."
            )
    for line in platform_resource_path.read_text().splitlines():
        if not line.strip():
            continue
        parts = line.split("\t")
        if len(parts) != 2 or tuple(parts) not in MANAGED_PLATFORM_RESOURCES:
            raise ValueError("platform inventory contains an unexpected resource entry")
        kind, name = parts
        address = MANAGED_PLATFORM_RESOURCES[(kind, name)]
        if address not in state:
            problems.append(f"live {kind} {name} exists but Terraform State lacks {address}; stop for ownership review.")
    return problems


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--state-addresses", type=Path, required=True)
    parser.add_argument("--deployments", type=Path, required=True)
    parser.add_argument("--network-policies", type=Path, required=True)
    parser.add_argument("--namespaces", type=Path, required=True)
    parser.add_argument("--iam-resources", type=Path, required=True)
    parser.add_argument("--rbac-resources", type=Path, required=True)
    parser.add_argument("--platform-resources", type=Path, required=True)
    parser.add_argument("--lbc-helm-owner", type=Path, required=True)
    parser.add_argument("--allowlist", type=Path, required=True)
    parser.add_argument("--aws-account-id", required=True)
    parser.add_argument("--cluster-name", required=True)
    parser.add_argument("--manage-load-balancer-controller", choices=("true", "false"), required=True)
    args = parser.parse_args()

    try:
        manage_lbc = args.manage_load_balancer_controller == "true"
        problems = check_ownership(
            args.state_addresses,
            args.deployments,
            args.network_policies,
            args.namespaces,
            args.iam_resources,
            args.rbac_resources,
            args.platform_resources,
            args.lbc_helm_owner,
            args.allowlist,
            args.aws_account_id,
            args.cluster_name,
            manage_lbc,
        )
        allowlist = load_allowlist(args.allowlist, args.aws_account_id, args.cluster_name, manage_lbc)
    except (OSError, ValueError) as exc:
        print(f"Ownership guard failed closed: {exc}", file=sys.stderr)
        return 2

    if problems:
        for problem in problems:
            print(problem, file=sys.stderr)
        return 1

    print("Reviewed independent LBC ownership and exact live namespace inventory verified; other State ownership conflicts are absent.")
    print("Verified the LBC Deployment Helm ownership labels and annotations without reading Secret objects.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
