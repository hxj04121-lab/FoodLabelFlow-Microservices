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
assert len(bindings) == 13 and len(policies) == 6, \
    "six queues need six event and six DLQ bindings, plus the second LabelPublished routing key"
assert not users and not permissions, "service users are owned by deploy/operators/rabbitmq/users.yaml"
assert {x["metadata"]["name"] for x in policies} == {
    f"{service}-{queue}-delivery-policy" for service, names in expected.items() for queue in names
}
for service, names in expected.items():
    entries = [q for q in queues if q["metadata"]["namespace"] == service]
    assert {q["spec"]["name"].split(".", 1)[1].removesuffix(".dlq") for q in entries} == names

label_bindings = [item["spec"] for item in bindings
                  if item["metadata"]["namespace"] == "compliance"
                  and item["spec"]["source"] == "spectrace.events"
                  and item["spec"]["destination"] == "compliance.label-published"]
assert len(label_bindings) == 2 and {binding["routingKey"] for binding in label_bindings} == {
    "label.published.v1", "label.published.v2"
}, "the Compliance projection must accept both frozen v1 and business-version v2 events"

# Frozen ImpactFinding.v1 uses M1 EventEnvelope's CamelCase -> dotted routing.
# Assert the rendered event binding, independently of the chart's input value.
impact_contract = json.loads((ROOT / "contracts/events/impact-finding.v1.schema.json").read_text())
assert any(profile.get("properties", {}).get("eventType", {}).get("const") == "ImpactFinding.v1"
           for profile in impact_contract["allOf"])
impact_bindings = [item["spec"] for item in bindings
                   if item["metadata"]["namespace"] == "label-workflow"
                   and item["spec"]["source"] == "spectrace.events"
                   and item["spec"]["destination"] == "label-workflow.impact-finding"]
assert len(impact_bindings) == 1
assert impact_bindings[0]["routingKey"] == "impact.finding.v1", \
    "ImpactFinding.v1 must bind to canonical impact.finding.v1, not the hyphenated queue name"

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

# Keep the reusable service workflow able to build and render services whose
# module directory is not their Docker build context and whose Helm values are
# supplied by the service or an approved environment overlay.
service_workflow = yaml.load(
    (ROOT / ".github/workflows/service-cicd-reusable.yml").read_text(),
    Loader=yaml.BaseLoader,
)
workflow_inputs = service_workflow["on"]["workflow_call"]["inputs"]
assert {"build_context", "maven_reactor_project", "values_file", "environment_values_file"} <= set(workflow_inputs)
steps = {
    job: {step.get("name", step.get("uses", "")): step.get("run", "") for step in config.get("steps", [])}
    for job, config in service_workflow["jobs"].items()
}
input_validation = steps["checks"]["Validate service inputs"]
assert '[[ "$DOCKERFILE" != /* && "$DOCKERFILE" != *..* ]]' in input_validation
assert 'test -f "$SERVICE_PATH/$DOCKERFILE"' in input_validation
assert 'mvn -B -ntp verify -pl "$MAVEN_REACTOR_PROJECT" -am' in steps["checks"]["Build and unit/integration test JVM service"]
assert 'mvn -B -ntp -pl "$MAVEN_REACTOR_PROJECT" -am org.owasp:dependency-check-maven:' in steps["security"]["OWASP Dependency-Check gate"]
for job, step_name in (("kind", "Build and load service image"), ("publish", "Build commit-pinned image locally")):
    assert '"$BUILD_CONTEXT"' in steps[job][step_name]
for job, step_name in (("kind", "Deploy service chart to kind"), ("deploy", "Configure EKS context and deploy commit-pinned image")):
    helm_run = steps[job][step_name]
    assert 'values_args+=(--values "$VALUES_FILE")' in helm_run
    assert 'values_args+=(--values "$ENVIRONMENT_VALUES_FILE")' in helm_run
for job in service_workflow["jobs"].values():
    for step in job.get("steps", []):
        if step.get("run"):
            subprocess.run(["bash", "-n"], input=step["run"], text=True, check=True)
print("M5 offline declarations and reusable service CI inputs: PASS")
