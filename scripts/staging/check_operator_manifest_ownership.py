#!/usr/bin/env python3
"""Fail closed if any object named by the locked Operator manifests already exists."""

from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path

import yaml

from scripts.staging.verify_operator_manifest_lock import load_and_validate


KIND_RESOURCES = {
    "Namespace": "namespaces",
    "CustomResourceDefinition": "customresourcedefinitions.apiextensions.k8s.io",
    "ServiceAccount": "serviceaccounts",
    "ClusterRole": "clusterroles",
    "ClusterRoleBinding": "clusterrolebindings",
    "Role": "roles",
    "RoleBinding": "rolebindings",
    "Service": "services",
    "Deployment": "deployments",
    "MutatingWebhookConfiguration": "mutatingwebhookconfigurations.admissionregistration.k8s.io",
    "ValidatingWebhookConfiguration": "validatingwebhookconfigurations.admissionregistration.k8s.io",
    "Certificate": "certificates.cert-manager.io",
    "Issuer": "issuers.cert-manager.io",
}
CUSTOM_RESOURCE_CRDS = {
    "Certificate": "certificates.cert-manager.io",
    "Issuer": "issuers.cert-manager.io",
}


def load_targets(lock_path: Path, manifest_dir: Path) -> list[dict]:
    targets = []
    seen = {}
    for row in load_and_validate(lock_path):
        path = manifest_dir / row["file"]
        try:
            documents = yaml.safe_load_all(path.read_text(encoding="utf-8"))
            for document in documents:
                if document is None:
                    continue
                if not isinstance(document, dict):
                    raise ValueError(f"Malformed resource in {row['file']}.")
                kind = document.get("kind")
                api_version = document.get("apiVersion")
                metadata = document.get("metadata")
                name = metadata.get("name") if isinstance(metadata, dict) else None
                namespace = metadata.get("namespace", "") if isinstance(metadata, dict) else ""
                if kind not in KIND_RESOURCES or not isinstance(api_version, str) or not name or not isinstance(namespace, str):
                    raise ValueError(f"Unsupported or incomplete target in {row['file']}.")
                key = (api_version, kind, namespace, name)
                if key in seen:
                    if kind == "Namespace" and name == "rabbitmq-system":
                        continue
                    raise ValueError(f"Duplicate or conflicting target resource across locked manifests: {kind}/{name}.")
                seen[key] = document
                targets.append({"kind": kind, "resource": KIND_RESOURCES[kind], "name": name, "namespace": namespace})
        except (OSError, yaml.YAMLError) as exc:
            raise ValueError(f"Unable to parse locked manifest {row['file']}.") from exc
    if not targets:
        raise ValueError("Locked Operator manifests contain no resource targets.")
    return targets


def get_named_resource(resource: str, name: str, namespace: str = "", run=subprocess.run) -> str:
    command = ["kubectl", "get", resource, name, "--ignore-not-found", "-o", "name"]
    if namespace:
        command.extend(("--namespace", namespace))
    try:
        result = run(command, capture_output=True, text=True, check=False)
    except OSError as exc:
        raise ValueError(f"Cannot run kubectl to verify {resource}/{name}; stop without installing.") from exc
    if result.returncode:
        raise ValueError(f"Cannot verify ownership/absence for {resource}/{name}; stop without installing.")
    return result.stdout.strip()


def check_ownership(lock_path: Path, manifest_dir: Path, run=subprocess.run) -> dict[str, int]:
    targets = load_targets(lock_path, manifest_dir)
    target_namespaces = {target["name"] for target in targets if target["kind"] == "Namespace"}
    missing_namespaces = set()
    counts = {"Namespace": 0, "CustomResourceDefinition": 0, "Webhook": 0, "RBAC": 0, "Other": 0}

    for namespace in sorted(target_namespaces):
        existing = get_named_resource("namespaces", namespace, run=run)
        if existing:
            raise ValueError(f"Pre-existing target Namespace {namespace}; stop for owner review.")
        missing_namespaces.add(namespace)
        counts["Namespace"] += 1

    for target in targets:
        kind = target["kind"]
        if kind == "Namespace":
            continue
        namespace = target["namespace"]
        if namespace in missing_namespaces:
            continue
        if kind in CUSTOM_RESOURCE_CRDS:
            crd = CUSTOM_RESOURCE_CRDS[kind]
            if not get_named_resource("customresourcedefinitions.apiextensions.k8s.io", crd, run=run):
                continue
        existing = get_named_resource(target["resource"], target["name"], namespace, run=run)
        if existing:
            raise ValueError(f"Pre-existing target {kind}/{target['name']}{' in ' + namespace if namespace else ''}; stop for owner review.")
        if kind == "CustomResourceDefinition":
            counts[kind] += 1
        elif kind in {"MutatingWebhookConfiguration", "ValidatingWebhookConfiguration"}:
            counts["Webhook"] += 1
        elif kind in {"ClusterRole", "ClusterRoleBinding", "Role", "RoleBinding"}:
            counts["RBAC"] += 1
        else:
            counts["Other"] += 1
    return counts


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--lock", type=Path, default=Path("deploy/operators/rabbitmq/operator-manifests.lock"))
    parser.add_argument("--manifest-dir", type=Path, required=True)
    args = parser.parse_args()
    try:
        counts = check_ownership(args.lock, args.manifest_dir)
    except ValueError as exc:
        print(str(exc), file=sys.stderr)
        return 1
    print("No locked Operator target object exists; metadata-only ownership inventory passed: " + ", ".join(f"{key}={value}" for key, value in counts.items()))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
