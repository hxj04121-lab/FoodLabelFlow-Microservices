#!/usr/bin/env python3
"""Verify downloaded, version-pinned cluster operator manifests against the reviewed lock."""

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path

import yaml

EXPECTED = {
    "cert-manager": (
        "v1.21.2",
        "cert-manager.yaml",
        "https://github.com/cert-manager/cert-manager/releases/download/v1.21.2/cert-manager.yaml",
    ),
    "rabbitmq-cluster-operator": (
        "v2.22.3",
        "rabbitmq-cluster-operator.yaml",
        "https://github.com/rabbitmq/cluster-operator/releases/download/v2.22.3/cluster-operator.yml",
    ),
    "messaging-topology-operator": (
        "v1.20.2",
        "messaging-topology-operator.yaml",
        "https://github.com/rabbitmq/messaging-topology-operator/releases/download/v1.20.2/messaging-topology-operator-with-certmanager.yaml",
    ),
}


def load_and_validate(lock_path: Path):
    try:
        document = json.loads(lock_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise ValueError("Operator manifest lock is missing or invalid JSON.") from exc
    if document.get("schemaVersion") != 1 or not isinstance(document.get("manifests"), list):
        raise ValueError("Operator manifest lock must use schemaVersion 1 and a manifests list.")
    rows = document["manifests"]
    if len(rows) != len(EXPECTED):
        raise ValueError("Operator manifest lock must contain exactly three pinned manifests.")

    by_name = {}
    for row in rows:
        if not isinstance(row, dict):
            raise ValueError("Every locked manifest must be an object.")
        name = row.get("name")
        if name not in EXPECTED or name in by_name:
            raise ValueError("Operator manifest lock contains an unknown or duplicate name.")
        version, filename, expected_url = EXPECTED[name]
        if row.get("version") != version or row.get("file") != filename:
            raise ValueError(f"{name} must pin {version} to {filename}.")
        url = row.get("url", "")
        if url != expected_url:
            raise ValueError(f"{name} must use its exact reviewed HTTPS GitHub release URL.")
        if Path(filename).name != filename:
            raise ValueError(f"{name} must use a local filename, not a path.")
        digest = row.get("sha256", "")
        if not re.fullmatch(r"[0-9a-f]{64}", digest):
            raise ValueError(f"{name} must have a lowercase SHA-256 digest.")
        size = row.get("bytes")
        if not isinstance(size, int) or isinstance(size, bool) or size <= 0:
            raise ValueError(f"{name} must have a positive integer byte size.")
        by_name[name] = row
    return [by_name[name] for name in EXPECTED]


def verify(lock_path: Path, manifest_dir: Path):
    rows = load_and_validate(lock_path)
    for row in rows:
        path = manifest_dir / row["file"]
        try:
            content = path.read_bytes()
        except OSError as exc:
            raise ValueError(f"Missing locked manifest: {row['file']}.") from exc
        if len(content) != row["bytes"]:
            raise ValueError(f"Byte-size mismatch for {row['name']}.")
        if hashlib.sha256(content).hexdigest() != row["sha256"]:
            raise ValueError(f"SHA-256 mismatch for {row['name']}.")
        if content.lstrip().startswith((b"<!DOCTYPE html", b"<html")):
            raise ValueError(f"{row['name']} downloaded an HTML error page, not a Kubernetes manifest.")
        if b"apiVersion:" not in content or b"kind:" not in content:
            raise ValueError(f"{row['name']} does not appear to contain Kubernetes resources.")
        if content.lstrip().startswith(b"<"):
            raise ValueError(f"{row['name']} does not appear to be a Kubernetes YAML manifest.")
        try:
            documents = [document for document in yaml.safe_load_all(content) if document is not None]
        except yaml.YAMLError as exc:
            raise ValueError(f"{row['name']} is not valid YAML.") from exc
        if not documents or any(
            not isinstance(document, dict) or not document.get("apiVersion") or not document.get("kind")
            for document in documents
        ):
            raise ValueError(f"{row['name']} must contain only Kubernetes resource documents.")
    return rows


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--lock", type=Path, default=Path("deploy/operators/rabbitmq/operator-manifests.lock"))
    parser.add_argument("--manifest-dir", type=Path, required=True)
    args = parser.parse_args()
    try:
        rows = verify(args.lock, args.manifest_dir)
    except ValueError as exc:
        print(str(exc), file=sys.stderr)
        return 1
    for row in rows:
        print(f"Verified {row['name']} {row['version']}: {row['bytes']} bytes sha256:{row['sha256']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
