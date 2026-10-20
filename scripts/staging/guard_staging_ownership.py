#!/usr/bin/env python3
"""Fail a protected plan when live platform resources are out of Terraform State."""

from __future__ import annotations

import argparse
from pathlib import Path
import sys


RELEASES = {
    "aws-load-balancer-controller": "module.eks_addons[0].helm_release.load_balancer_controller",
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
MANAGED_IAM_RESOURCES = {
    ("role", "spectrace-staging-aws_load_balancer_controller"): 'module.eks_addons[0].aws_iam_role.addons["aws_load_balancer_controller"]',
    ("policy", "spectrace-staging-aws-load-balancer-controller"): "module.eks_addons[0].aws_iam_policy.load_balancer_controller",
    ("attachment", "spectrace-staging-aws-load-balancer-controller"): "module.eks_addons[0].aws_iam_role_policy_attachment.load_balancer_controller",
}
MANAGED_TGB_RBAC = {
    (kind, namespace): (
        f'module.eks_addons[0].kubernetes_manifest.deployer_custom_resource_{resource}["{namespace}"]'
    )
    for namespace in ("gateway", "keycloak", "frontend")
    for kind, resource in (("Role", "role"), ("RoleBinding", "rolebinding"))
}


def read_names(path: Path) -> set[str]:
    if not path.is_file():
        raise ValueError(f"inventory file is missing: {path}")
    return {
        line.strip().split()[0].split("/")[-1]
        for line in path.read_text().splitlines()
        if line.strip()
    }


def check_ownership(
    state_path: Path,
    deployment_path: Path,
    network_policy_path: Path,
    namespace_path: Path,
    iam_resource_path: Path,
    rbac_resource_path: Path,
) -> list[str]:
    state = set(state_path.read_text().splitlines())
    deployments = read_names(deployment_path)
    network_policies = read_names(network_policy_path)
    namespaces = read_names(namespace_path)
    iam_resources = set()
    for line in iam_resource_path.read_text().splitlines():
        if not line.strip():
            continue
        parts = line.split("\t", maxsplit=1)
        if len(parts) != 2 or (parts[0], parts[1]) not in MANAGED_IAM_RESOURCES:
            raise ValueError("IAM inventory contains an unexpected resource entry")
        iam_resources.add((parts[0], parts[1]))
    live_releases = {
        "aws-load-balancer-controller": "aws-load-balancer-controller" in deployments,
        "cluster-autoscaler": "cluster-autoscaler" in deployments,
        "metrics-server": "metrics-server" in deployments,
        # The platform policy chart renders only stcn-* NetworkPolicies. Treat any
        # such live object as an ownership conflict until State confirms the release.
        "spectrace-network-policies": any(name.startswith("stcn-") for name in network_policies),
    }
    problems = [
        f"{release}: live platform resources exist but Terraform State lacks {address}; stop for ownership review."
        for release, address in RELEASES.items()
        if live_releases[release] and address not in state
    ]
    for namespace in MANAGED_NAMESPACES:
        address = f'module.eks_addons[0].kubernetes_manifest.workload_namespace["{namespace}"]'
        if namespace in namespaces and address not in state:
            problems.append(
                f"namespace {namespace}: live namespace exists but Terraform State lacks {address}; stop for ownership review."
            )
    for resource, address in MANAGED_IAM_RESOURCES.items():
        if resource in iam_resources and address not in state:
            problems.append(
                f"live IAM {resource[0]} {resource[1]} exists but Terraform State lacks {address}; stop for ownership review."
            )
    for line in rbac_resource_path.read_text().splitlines():
        if not line.strip():
            continue
        parts = line.split("\t")
        if len(parts) != 2 or (parts[0], parts[1]) not in MANAGED_TGB_RBAC:
            raise ValueError("RBAC inventory contains an unexpected resource entry")
        kind, namespace = parts
        address = MANAGED_TGB_RBAC[(kind, namespace)]
        if address not in state:
            problems.append(
                f"live TGB deployer {kind} exists in namespace {namespace} but Terraform State lacks {address}; stop for ownership review."
            )
    return problems


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--state-addresses", type=Path, required=True)
    parser.add_argument("--deployments", type=Path, required=True)
    parser.add_argument("--network-policies", type=Path, required=True)
    parser.add_argument("--namespaces", type=Path, required=True)
    parser.add_argument("--iam-resources", type=Path, required=True)
    parser.add_argument("--rbac-resources", type=Path, required=True)
    args = parser.parse_args()

    try:
        problems = check_ownership(
            args.state_addresses,
            args.deployments,
            args.network_policies,
            args.namespaces,
            args.iam_resources,
            args.rbac_resources,
        )
    except (OSError, ValueError) as exc:
        print(f"Ownership guard failed closed: {exc}", file=sys.stderr)
        return 2

    if problems:
        for problem in problems:
            print(problem, file=sys.stderr)
        return 1

    print("Live platform inventory has no out-of-State namespace, Helm release, IAM, or TGB RBAC conflict.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
