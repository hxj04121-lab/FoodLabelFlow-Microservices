#!/usr/bin/env python3
"""Build and check namespace-scoped readiness targets from rendered MTO YAML."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys

import yaml


def build_targets(manifest: Path, expected_kind: str) -> dict[str, object]:
    targets: dict[str, list[str]] = {}
    seen: set[tuple[str, str]] = set()
    for item in yaml.safe_load_all(manifest.read_text()):
        if item is None:
            continue
        if not isinstance(item, dict) or item.get("kind") != expected_kind:
            actual_kind = item.get("kind") if isinstance(item, dict) else type(item).__name__
            raise ValueError(f"Expected only {expected_kind} objects, found {actual_kind}.")
        metadata = item.get("metadata") or {}
        namespace = metadata.get("namespace")
        name = metadata.get("name")
        if not namespace or not name:
            raise ValueError(f"Each {expected_kind} target must have a namespace and name.")
        key = (namespace, name)
        if key in seen:
            raise ValueError(f"Duplicate {expected_kind} target {namespace}/{name}.")
        seen.add(key)
        targets.setdefault(namespace, []).append(name)
    if not targets:
        raise ValueError(f"No {expected_kind} targets were rendered.")
    for names in targets.values():
        names.sort()
    return {"kind": expected_kind, "targets": dict(sorted(targets.items()))}


def read_targets(path: Path) -> tuple[str, dict[str, list[str]]]:
    payload = json.loads(path.read_text())
    kind, targets = payload.get("kind"), payload.get("targets")
    if not isinstance(kind, str) or not isinstance(targets, dict) or not targets:
        raise ValueError("Target file must contain a kind and non-empty namespace map.")
    normalized: dict[str, list[str]] = {}
    for namespace, names in targets.items():
        if not isinstance(namespace, str) or not namespace or not isinstance(names, list) or not names:
            raise ValueError("Each target namespace must contain at least one name.")
        if any(not isinstance(name, str) or not name for name in names) or len(set(names)) != len(names):
            raise ValueError(f"Invalid or duplicate names in namespace {namespace}.")
        normalized[namespace] = names
    return kind, normalized


def ready_target_count(target_file: Path, namespace: str, actual_file: Path) -> int:
    _, targets = read_targets(target_file)
    if namespace not in targets:
        raise ValueError(f"Namespace {namespace} has no rendered targets.")
    payload = json.loads(actual_file.read_text())
    items = payload.get("items")
    if not isinstance(items, list):
        raise ValueError("Kubernetes list response must contain an items array.")
    actual: dict[str, dict] = {}
    for item in items:
        metadata = item.get("metadata") or {}
        name = metadata.get("name")
        if name in actual:
            raise ValueError(f"Kubernetes response contains duplicate object {name}.")
        if name:
            actual[name] = item
    names = targets[namespace]
    missing = [name for name in names if name not in actual]
    if missing:
        raise ValueError(f"Expected {len(names)} {read_targets(target_file)[0]} objects in {namespace}; missing {', '.join(missing)}.")
    not_ready = [
        name for name in names
        if not any(
            condition.get("type") == "Ready" and condition.get("status") == "True"
            for condition in actual[name].get("status", {}).get("conditions", [])
        )
    ]
    if not_ready:
        raise ValueError(f"{len(not_ready)} of {len(names)} expected objects are not Ready in {namespace}.")
    return len(names)


def main() -> int:
    parser = argparse.ArgumentParser()
    commands = parser.add_subparsers(dest="command", required=True)
    build = commands.add_parser("build")
    build.add_argument("--manifest", type=Path, required=True)
    build.add_argument("--kind", required=True)
    build.add_argument("--output", type=Path, required=True)
    count = commands.add_parser("count")
    count.add_argument("--targets", type=Path, required=True)
    namespaces = commands.add_parser("namespaces")
    namespaces.add_argument("--targets", type=Path, required=True)
    check = commands.add_parser("check")
    check.add_argument("--targets", type=Path, required=True)
    check.add_argument("--namespace", required=True)
    check.add_argument("--actual", type=Path, required=True)
    args = parser.parse_args()
    try:
        if args.command == "build":
            payload = build_targets(args.manifest, args.kind)
            args.output.write_text(json.dumps(payload, sort_keys=True) + "\n")
        elif args.command == "count":
            _, targets = read_targets(args.targets)
            print(sum(len(names) for names in targets.values()))
        elif args.command == "namespaces":
            _, targets = read_targets(args.targets)
            print("\n".join(sorted(targets)))
        else:
            count_ready = ready_target_count(args.targets, args.namespace, args.actual)
            print(f"{count_ready} expected {read_targets(args.targets)[0]} object(s) Ready in {args.namespace}.")
    except (OSError, ValueError, json.JSONDecodeError, yaml.YAMLError) as error:
        print(f"topology target validation failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
