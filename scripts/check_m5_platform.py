#!/usr/bin/env python3
"""Offline acceptance checks for the M5 Helm and Kubernetes declarations."""

import json
import subprocess
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[1]


def render(chart):
    return subprocess.check_output(["helm", "template", "m5-check", str(ROOT / chart)], text=True)


topology = list(yaml.safe_load_all(render("deploy/helm/rabbitmq-topology")))
resources = [item for item in topology if item]
queues = [item for item in resources if item["kind"] == "Queue"]
bindings = [item for item in resources if item["kind"] == "Binding"]
policies = [item for item in resources if item["kind"] == "Policy"]
users = [item for item in resources if item["kind"] == "User"]
permissions = [item for item in resources if item["kind"] == "Permission"]
expected = {
    "compliance": {"specification-published", "formula-published", "label-published"},
    "formulation": {"specification-published"},
    "label-workflow": {"formula-published", "impact-finding"},
}
assert len(queues) == 12, "each of six consumer queues needs one durable queue and one DLQ"
assert len(bindings) == 12 and len(policies) == 6
assert not users and not permissions, "service users are owned by deploy/operators/rabbitmq/users.yaml"
assert {x["metadata"]["name"] for x in policies} == {
    f"{service}-{queue}-delivery-policy" for service, names in expected.items() for queue in names
}
for service, names in expected.items():
    entries = [q for q in queues if q["metadata"]["namespace"] == service]
    assert {q["spec"]["name"].split(".", 1)[1].removesuffix(".dlq") for q in entries} == names
user_resources = list(yaml.safe_load_all((ROOT / "deploy/operators/rabbitmq/users.yaml").read_text()))
user_resources = [item for item in user_resources if item]
users = [item for item in user_resources if item["kind"] == "User"]
permissions = [item for item in user_resources if item["kind"] == "Permission"]
assert len(users) == len(permissions) == 4
for service in ("specification", *expected):
    user = next(item for item in permissions if item["spec"]["user"] == f"{service}-rabbitmq")
    assert user["metadata"]["namespace"] == service
    assert user["spec"]["permissions"]["write"] == "^spectrace\\.events$"
    read = user["spec"]["permissions"]["read"]
    if service == "specification":
        assert read == "^$"
    else:
        assert read.startswith(f"^{service}\\.") and read.count("|") + 1 == len(expected[service])
assert next(item for item in permissions if item["spec"]["user"] == "specification-rabbitmq")["spec"]["permissions"]["read"] == "^$"

cluster = yaml.safe_load((ROOT / "deploy/operators/rabbitmq/rabbitmq-cluster.yaml").read_text())
assert cluster["spec"]["replicas"] == 3
assert cluster["spec"]["persistence"] == {"storageClassName": "ebs-gp3", "storage": "8Gi"}
assert "default_queue_type = quorum" in cluster["spec"]["rabbitmq"]["additionalConfig"]

network = list(yaml.safe_load_all(render("deploy/helm/platform-network-policies")))
names = {item["metadata"]["name"] for item in network if item}
for required in ("stcn-default-deny-gateway", "stcn-gateway-flows", "stcn-rabbitmq-flows", "stcn-observability-flows"):
    assert required in names, f"missing network policy {required}"

alloy_policy = yaml.safe_load((ROOT / "deploy/observability/alloy/networkpolicy.yaml").read_text())
assert alloy_policy["spec"]["egress"][-1]["ports"] == [{"protocol": "TCP", "port": 443}]
dashboard = json.loads((ROOT / "deploy/observability/dashboards/staging-overview.json").read_text())
assert len(dashboard["panels"]) >= 4
print("M5 offline declarations: PASS (six consumer queues/DLQs, permissions, network flows, Alloy and dashboard)")
