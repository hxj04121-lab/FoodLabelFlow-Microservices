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
assert len(queues) == 14, "the six service queues plus the isolated smoke queue each need a durable queue and one DLQ"
assert len(bindings) == 15 and len(policies) == 7, \
    "seven queues need seven event and seven DLQ bindings, plus the second LabelPublished routing key"
assert not users and not permissions, "service users are owned by deploy/operators/rabbitmq/users.yaml"
resource_ids = [(x["apiVersion"], x["kind"], x["metadata"].get("namespace", ""), x["metadata"]["name"]) for x in resources]
assert len(set(resource_ids)) == len(resource_ids), "each topology resource must have one rendered authority"
assert {x["metadata"]["name"] for x in policies} == {
    f"{service}-{queue}-delivery-policy" for service, names in expected.items() for queue in names
} | {"staging-smoke-smoke-delivery-policy"}
for service, namespace, names in [
    (service, service, names) for service, names in expected.items()
] + [("staging-smoke", "rabbitmq", {"smoke"})]:
    entries = [q for q in queues if q["metadata"]["namespace"] == namespace]
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

users = [item for item in yaml.safe_load_all((ROOT / "deploy/operators/rabbitmq/users.yaml").read_text()) if item]
permissions = [item for item in yaml.safe_load_all((ROOT / "deploy/operators/rabbitmq/permissions.yaml").read_text()) if item]
assert len(users) == len(permissions) == 5
assert all(item["kind"] == "User" for item in users)
assert all(item["kind"] == "Permission" for item in permissions)
smoke_user = next(item for item in users if item["metadata"]["name"] == "staging-smoke")
assert smoke_user["spec"]["tags"] == ["management"], "Only the HTTP API smoke user needs the Management tag."
assert all(item["spec"]["tags"] == [] for item in users if item is not smoke_user), \
    "Business service users must keep the default least-privilege tags."
assert all("importCredentialsSecret" not in item["spec"] for item in users)
for service in ("specification", "formulation", "compliance", "label-workflow"):
    permission = next(item for item in permissions if item["metadata"]["name"] == f"{service}-rabbitmq")
    assert permission["metadata"]["namespace"] == service
    assert permission["spec"]["userReference"] == {"name": f"{service}-rabbitmq"}
    matching_user = next(item for item in users if item["metadata"]["namespace"] == service
                         and item["metadata"]["name"] == f"{service}-rabbitmq")
    assert "importCredentialsSecret" not in matching_user["spec"]
    assert permission["spec"]["permissions"]["write"] == "^spectrace\\.events$"
    read = permission["spec"]["permissions"]["read"]
    if service == "specification":
        assert read == "^$"
    else:
        assert read.startswith(f"^{service}\\.") and read.count("|") + 1 == len(expected[service])
assert next(item for item in permissions if item["metadata"]["name"] == "specification-rabbitmq")["spec"]["permissions"]["read"] == "^$"
smoke_permission = next(item for item in permissions if item["metadata"]["name"] == "staging-smoke")
assert smoke_permission["metadata"]["namespace"] == "rabbitmq"
assert smoke_permission["spec"]["userReference"] == {"name": "staging-smoke"}
assert smoke_permission["spec"]["permissions"] == {
    "configure": "^$", "write": "^spectrace\\.events$", "read": "^staging-smoke\\.smoke$"
}

cluster = yaml.safe_load((ROOT / "deploy/operators/rabbitmq/rabbitmq-cluster.yaml").read_text())
assert cluster["spec"]["replicas"] == 3
assert cluster["spec"]["persistence"] == {"storageClassName": "ebs-gp3", "storage": "8Gi"}
assert "default_queue_type = quorum" in cluster["spec"]["rabbitmq"]["additionalConfig"]
assert cluster["spec"]["override"]["statefulSet"]["spec"]["persistentVolumeClaimRetentionPolicy"] == {
    "whenDeleted": "Retain", "whenScaled": "Retain"
}

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
assert {"build_context", "maven_reactor_project", "values_file", "environment_values_file", "kind_values_file"} <= set(workflow_inputs)
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
assert 'values_args+=(--values "$KIND_VALUES_FILE")' in steps["kind"]["Deploy service chart to kind"]
publish = steps["publish"]["Push image and capture registry digest"]
assert 'docker push "$image"' in publish
assert 'ref=ghcr.io/${GITHUB_REPOSITORY,,}/${SERVICE_NAME}@${digest}' in publish
deploy = steps["deploy"]["Configure EKS context and deploy commit-pinned image"]
assert 'IMAGE_DIGEST="${IMAGE#*@}"' in deploy
assert '--set-string image.digest="$IMAGE_DIGEST"' in deploy
assert 'helm template "$RELEASE" "$CHART"' in deploy
assert 'grep -Fq -- "$IMAGE_REPOSITORY@$IMAGE_DIGEST"' in deploy
assert '--set image.tag=' not in deploy
assert service_workflow["jobs"]["deploy"]["runs-on"] == ["self-hosted", "linux", "staging-vpc"]
assert service_workflow["jobs"]["deploy"]["env"]["AWS_REGION"] == "${{ vars.STAGING_AWS_REGION }}"
target = steps["deploy"]["Verify and configure the staging EKS target"]
assert 'aws sts get-caller-identity' in target
assert 'aws eks describe-cluster' in target
assert 'test "$(kubectl config current-context)" = "$expected_cluster_arn"' in target
runtime_secret = steps["deploy"]["Create the service runtime Secret without logging values"]
assert "stringData" in runtime_secret and "kubectl apply" in runtime_secret
assert ">/dev/null" in runtime_secret and "echo" not in runtime_secret
record = steps["deploy"]["Record deployment commit, digest, workflow and revision"]
assert record.count('--image "$PUBLISHED_IMAGE"') == 1
assert record.count('--publish-image "$PUBLISHED_IMAGE"') == 1
record_step = next(step for step in service_workflow["jobs"]["deploy"]["steps"]
                   if step.get("name") == "Record deployment commit, digest, workflow and revision")
assert record_step["env"]["PUBLISHED_IMAGE"] == "${{ needs.publish.outputs.image }}"
assert '--expected-account-id "$EXPECTED_ACCOUNT_ID"' in record
assert '--expected-cluster-name "$EXPECTED_CLUSTER_NAME"' in record
assert '--region "$AWS_REGION"' in record
assert service_workflow["jobs"]["deploy"]["steps"][-1]["uses"] == "actions/upload-artifact@v4"
deploy_steps = service_workflow["jobs"]["deploy"]["steps"]
assert next(i for i, step in enumerate(deploy_steps) if step.get("name") == "Create the service runtime Secret without logging values") < next(
    i for i, step in enumerate(deploy_steps) if step.get("name") == "Configure EKS context and deploy commit-pinned image"
)
addons = (ROOT / "infra/terraform/modules/eks-addons/addons.tf").read_text()
assert "aws-ebs-csi-driver" in addons
assert "var.manage_vpc_cni ? 1 : 0" in addons
assert "enableNetworkPolicy" not in addons, "Storage add-on planning must not rewrite the existing aws-node network policy configuration."
assert "default     = false" in (ROOT / "infra/terraform/variables.tf").read_text()
assert "-var=manage_vpc_cni=false" in (ROOT / ".github/workflows/terraform-validate.yml").read_text()

compliance_caller = yaml.load((ROOT / ".github/workflows/compliance-kind-ci.yml").read_text(), Loader=yaml.BaseLoader)
assert compliance_caller["jobs"]["compliance"]["permissions"] == {
    "contents": "read", "packages": "write", "id-token": "write"
}
compliance_inputs = compliance_caller["jobs"]["compliance"]["with"]
assert compliance_inputs["build_context"] == "."
assert compliance_inputs["maven_reactor_project"] == "services/compliance"
assert compliance_inputs["kind_values_file"] == "deploy/helm/values/compliance-kind.yaml"
assert "topology.kubernetes.io/zone=kind" in compliance_inputs["kind_node_labels"]
assert compliance_inputs["kind_node_taints"] == "dedicated=compliance:NoSchedule"
assert "Start the Compliance MySQL dependency" in steps["kind"]
assert "rollout status deployment/compliance-mysql" in steps["kind"]["Start the Compliance MySQL dependency"]
staging_gateway = yaml.safe_load((ROOT / "deploy/helm/values/staging-gateway.yaml").read_text())
gateway_peers = [peer for rule in staging_gateway["networkPolicy"]["egressRules"] for peer in rule["to"]
                 if peer.get("namespaceSelector", {}).get("matchLabels", {}).get("kubernetes.io/metadata.name")
                 in {"specification", "formulation", "compliance", "label-workflow"}]
assert {peer["namespaceSelector"]["matchLabels"]["kubernetes.io/metadata.name"] for peer in gateway_peers} == {
    "specification", "formulation", "compliance", "label-workflow"
}
for job in service_workflow["jobs"].values():
    for step in job.get("steps", []):
        if step.get("run"):
            subprocess.run(["bash", "-n"], input=step["run"], text=True, check=True)
print("M5 offline declarations and reusable service CI inputs: PASS")
