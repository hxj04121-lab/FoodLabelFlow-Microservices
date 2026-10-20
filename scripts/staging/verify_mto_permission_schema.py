#!/usr/bin/env python3
"""Verify Permission.userReference against the pinned Topology Operator CRD."""

from __future__ import annotations

import argparse
from pathlib import Path
import sys

import yaml


def validate_permission_schema(path: Path) -> None:
    for document in yaml.safe_load_all(path.read_text()):
        if not isinstance(document, dict) or document.get("kind") != "CustomResourceDefinition":
            continue
        if document.get("metadata", {}).get("name") != "permissions.rabbitmq.com":
            continue
        if document.get("spec", {}).get("names", {}).get("kind") != "Permission":
            raise ValueError("permissions.rabbitmq.com CRD does not define Permission objects.")
        versions = document.get("spec", {}).get("versions", [])
        for version in versions:
            if version.get("name") != "v1beta1":
                continue
            properties = version.get("schema", {}).get("openAPIV3Schema", {}).get("properties", {})
            spec = properties.get("spec", {}).get("properties", {})
            reference = spec.get("userReference", {})
            name = reference.get("properties", {}).get("name", {})
            if reference.get("type") != "object" or name.get("type") != "string":
                raise ValueError("Permission.spec.userReference.name must be an object field with a string name.")
            return
        raise ValueError("Permission CRD does not expose the v1beta1 schema.")
    raise ValueError("Locked bundle does not contain permissions.rabbitmq.com CRD.")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("manifest", type=Path)
    args = parser.parse_args()
    try:
        validate_permission_schema(args.manifest)
    except (OSError, ValueError, yaml.YAMLError) as error:
        print(f"MTO Permission schema validation failed: {error}", file=sys.stderr)
        return 1
    print("MTO Permission.userReference v1beta1 CRD schema: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
