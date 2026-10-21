#!/usr/bin/env python3
"""Fail closed when the existing RabbitMQ StorageClass differs from its reviewed manifest."""

import json
import sys
from pathlib import Path

import yaml


ERROR = (
    "Could not verify ebs-gp3 against the reviewed manifest; stop for "
    "M1-approved protected recovery. The workflow will not delete or recreate it."
)
IDENTITY = ("storage.k8s.io/v1", "StorageClass", "ebs-gp3")


def read_document(path, loader):
    try:
        return loader(Path(path).read_text(encoding="utf-8"))
    except (OSError, UnicodeError, ValueError, yaml.YAMLError) as exc:
        raise ValueError(ERROR) from exc


def object_identity(document):
    if not isinstance(document, dict) or document.get("apiVersion") != IDENTITY[0] or document.get("kind") != IDENTITY[1]:
        return None
    metadata = document.get("metadata")
    if not isinstance(metadata, dict):
        return None
    return document["apiVersion"], document["kind"], metadata.get("name")


def verify_storageclass(actual, expected):
    if object_identity(actual) != IDENTITY or object_identity(expected) != IDENTITY:
        raise ValueError(ERROR)

    for document in (actual, expected):
        parameters = document.get("parameters")
        if not isinstance(parameters, dict) or any(not isinstance(key, str) or not isinstance(value, str)
                                                   for key, value in parameters.items()):
            raise ValueError(ERROR)
        if any(key.startswith("tagSpecification") and
               (value.startswith("kubernetes.io/") or "\x24{" in value)
               for key, value in parameters.items()):
            raise ValueError(ERROR)

    # Kubernetes adds metadata and status to the live object. All other top-level
    # fields are the reviewed StorageClass configuration and must match exactly.
    actual_spec = {key: value for key, value in actual.items() if key not in {"apiVersion", "kind", "metadata", "status"}}
    expected_spec = {key: value for key, value in expected.items() if key not in {"apiVersion", "kind", "metadata", "status"}}
    if actual_spec != expected_spec:
        raise ValueError(ERROR)


def main(argv=None):
    args = sys.argv[1:] if argv is None else argv
    if len(args) != 2:
        print(ERROR, file=sys.stderr)
        return 2
    try:
        actual = read_document(args[0], json.loads)
        expected = read_document(args[1], yaml.safe_load)
        verify_storageclass(actual, expected)
    except ValueError:
        print(ERROR, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
