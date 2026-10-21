#!/usr/bin/env python3
"""Remove the duplicate rabbitmq-system Namespace from the topology install input."""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

import yaml

# Run as `python3 scripts/staging/<file>.py` in the workflow: make the repo root importable.
sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from scripts.staging.verify_operator_manifest_lock import verify  # noqa: E402


def render_topology_manifest(lock_path: Path, manifest_dir: Path) -> Path:
    rows = verify(lock_path, manifest_dir)
    row = next(row for row in rows if row["name"] == "messaging-topology-operator")
    source = manifest_dir / row["file"]
    try:
        documents = [document for document in yaml.safe_load_all(source.read_text(encoding="utf-8")) if document]
    except (OSError, yaml.YAMLError) as exc:
        raise ValueError("Unable to parse the verified topology manifest.") from exc
    namespaces = [
        document
        for document in documents
        if document.get("kind") == "Namespace" and document.get("metadata", {}).get("name") == "rabbitmq-system"
    ]
    if len(namespaces) != 1:
        raise ValueError("The locked topology manifest must declare rabbitmq-system exactly once.")
    kept = [document for document in documents if document not in namespaces]
    if not kept:
        raise ValueError("Removing the shared Namespace would leave an empty topology manifest.")
    output = manifest_dir / "messaging-topology-operator-install.yaml"
    output.write_text(yaml.safe_dump_all(kept, explicit_start=True, sort_keys=False), encoding="utf-8")
    return output


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--lock", type=Path, default=Path("deploy/operators/rabbitmq/operator-manifests.lock"))
    parser.add_argument("--manifest-dir", type=Path, required=True)
    args = parser.parse_args()
    try:
        output = render_topology_manifest(args.lock, args.manifest_dir)
    except (StopIteration, ValueError) as exc:
        parser.error(str(exc))
    print(f"Prepared verified topology objects without the already-created shared Namespace: {output.name}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
