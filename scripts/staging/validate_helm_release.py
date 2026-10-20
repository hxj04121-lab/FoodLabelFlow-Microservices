#!/usr/bin/env python3
"""Refuse Helm installs that would adopt unknown namespaced workloads."""

import argparse
import json
from pathlib import Path


def validate(releases, inventory, namespace, release_name, deployment_name):
    if len(releases) > 1 or any(
        row.get("name") != release_name or row.get("status") != "deployed" for row in releases
    ):
        raise ValueError("namespace contains an unexpected or failed Helm release")
    installed = bool(releases)
    objects = inventory.get("items", [])
    baseline_policies = {f"stcn-default-deny-{namespace}"}
    if namespace in {"specification", "formulation", "compliance", "label-workflow"}:
        baseline_policies.add(f"stcn-{namespace}-flows")
    owned_uids = set()
    owned_deployments = set()

    for obj in objects:
        metadata = obj.get("metadata", {})
        if metadata.get("namespace") != namespace:
            raise ValueError("inventory contains an object outside the target namespace")
        annotations = metadata.get("annotations", {})
        labels = metadata.get("labels", {})
        if (
            annotations.get("meta.helm.sh/release-name") == release_name
            and annotations.get("meta.helm.sh/release-namespace") == namespace
            and labels.get("app.kubernetes.io/instance") == release_name
        ):
            uid = metadata.get("uid")
            if uid:
                owned_uids.add(uid)
            if obj.get("kind") == "Deployment":
                owned_deployments.add(metadata.get("name"))

    changed = True
    while changed:
        changed = False
        for obj in objects:
            metadata = obj.get("metadata", {})
            if metadata.get("uid") in owned_uids:
                continue
            if metadata.get("labels", {}).get("app.kubernetes.io/instance") != release_name:
                continue
            if any(owner.get("uid") in owned_uids for owner in metadata.get("ownerReferences", [])):
                if metadata.get("uid"):
                    owned_uids.add(metadata["uid"])
                    changed = True

    for obj in objects:
        metadata = obj.get("metadata", {})
        name = metadata.get("name", "")
        if metadata.get("uid") in owned_uids:
            continue
        if obj.get("kind") == "ServiceAccount" and name == "default":
            continue
        if obj.get("kind") == "NetworkPolicy" and name in baseline_policies:
            continue
        raise ValueError(f"refusing unknown pre-existing {obj.get('kind')} {namespace}/{name}")

    if installed and deployment_name not in owned_deployments:
        raise ValueError("deployed Helm release does not own the expected Deployment")
    if not installed and owned_deployments:
        raise ValueError("resources exist without a deployed Helm release")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("release_json", type=Path)
    parser.add_argument("inventory_json", type=Path)
    parser.add_argument("--namespace", required=True)
    parser.add_argument("--release", required=True)
    parser.add_argument("--deployment", required=True)
    args = parser.parse_args()
    try:
        validate(json.loads(args.release_json.read_text()), json.loads(args.inventory_json.read_text()),
                 args.namespace, args.release, args.deployment)
    except (OSError, json.JSONDecodeError, ValueError) as error:
        parser.error(str(error))
    print(f"Helm release state is safe for {args.namespace}/{args.release}.")


if __name__ == "__main__":
    main()
