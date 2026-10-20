#!/usr/bin/env python3
"""Fail closed unless the Compliance node group has healthy dual-AZ capacity."""

import json
import re
import sys
from pathlib import Path


INSTANCE_TYPE = "c6i.large"
ZONE_LABEL = "topology.kubernetes.io/zone"
INSTANCE_TYPE_LABELS = ("node.kubernetes.io/instance-type", "beta.kubernetes.io/instance-type")
MIN_ALLOCATABLE_CPU = 1
MIN_ALLOCATABLE_MEMORY_BYTES = 2 * 1024**3


def _ready(node):
    return any(
        condition.get("type") == "Ready" and condition.get("status") == "True"
        for condition in node.get("status", {}).get("conditions", [])
    )


def _is_c6i_large(node):
    labels = node.get("metadata", {}).get("labels", {})
    return any(labels.get(key) == INSTANCE_TYPE for key in INSTANCE_TYPE_LABELS)


def _cpu_cores(value):
    return int(value[:-1]) / 1000 if value.endswith("m") else float(value)


def _memory_bytes(value):
    match = re.fullmatch(r"([0-9.]+)(Ki|Mi|Gi|Ti)?", value)
    if not match:
        raise ValueError("Unknown Kubernetes memory quantity.")
    factor = {None: 1, "Ki": 1024, "Mi": 1024**2, "Gi": 1024**3, "Ti": 1024**4}[match.group(2)]
    return float(match.group(1)) * factor


def validate(group, nodes):
    scaling = group.get("scalingConfig", {})
    if (
        group.get("status") != "ACTIVE"
        or group.get("instanceTypes") != [INSTANCE_TYPE]
        or scaling.get("minSize", 0) < 2
        or scaling.get("desiredSize", 0) < 2
        or group.get("health", {}).get("issues")
    ):
        raise ValueError("Compliance node group must be healthy c6i.large capacity with minSize and desiredSize of at least two.")

    ready = [node for node in nodes if _ready(node)]
    ready_c6i_large = [node for node in ready if _is_c6i_large(node)]
    if len(ready_c6i_large) < 2:
        raise ValueError("Compliance needs at least two Ready c6i.large nodes.")

    zones = {node.get("metadata", {}).get("labels", {}).get(ZONE_LABEL) for node in ready_c6i_large}
    if len(zones) < 2 or None in zones:
        raise ValueError("Ready Compliance c6i.large nodes must span at least two Availability Zones.")

    cpu = 0
    memory = 0
    for node in ready:
        allocatable = node["status"]["allocatable"]
        cpu += _cpu_cores(allocatable["cpu"])
        memory += _memory_bytes(allocatable["memory"])
        taints = node.get("spec", {}).get("taints", [])
        if not any(
            taint.get("key") == "dedicated"
            and taint.get("value") == "compliance"
            and taint.get("effect") == "NoSchedule"
            for taint in taints
        ):
            raise ValueError("Ready Compliance node is missing its dedicated taint.")

    if cpu < MIN_ALLOCATABLE_CPU or memory < MIN_ALLOCATABLE_MEMORY_BYTES:
        raise ValueError("Compliance nodes do not have the allocatable CPU and memory requested by the service.")

    return {
        "readyC6iLargeNodes": len(ready_c6i_large),
        "availabilityZones": sorted(zones),
        "allocatableCpu": cpu,
        "allocatableMemoryBytes": memory,
    }


def main():
    if len(sys.argv) != 3:
        raise SystemExit("usage: verify_compliance_node_group.py NODEGROUP_JSON NODES_JSON")
    group = json.loads(Path(sys.argv[1]).read_text())["nodegroup"]
    nodes = json.loads(Path(sys.argv[2]).read_text())["items"]
    try:
        result = validate(group, nodes)
    except (KeyError, TypeError, ValueError) as exc:
        raise SystemExit(str(exc)) from exc
    print(
        "Compliance group ready: "
        f"{result['readyC6iLargeNodes']} Ready c6i.large node(s) across "
        f"{len(result['availabilityZones'])} Availability Zones, "
        f"{result['allocatableCpu']:g} allocatable CPU, "
        f"{result['allocatableMemoryBytes'] / 1024**3:.2f} GiB allocatable memory."
    )


if __name__ == "__main__":
    main()
