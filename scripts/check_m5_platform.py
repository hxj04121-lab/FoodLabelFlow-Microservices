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
assert {x["metadata"]["name"] for x in policies} == {
    f"{service}-{queue}-delivery-policy" for service, names in expected.items() for queue in names
} | {"staging-smoke-smoke-delivery-policy"}
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

users = [item for item in yaml.safe_load_all((ROOT / "deploy/operators/rabbitmq/users.yaml").read_text()) if item]
permissions = [item for item in yaml.safe_load_all((ROOT / "deploy/operators/rabbitmq/permissions.yaml").read_text()) if item]
assert len(users) == len(permissions) == 5
assert all(item["kind"] == "User" for item in users)
assert all(item["kind"] == "Permission" for item in permissions)
smoke_user = next(item for item in users if item["metadata"]["name"] == "staging-smoke")
assert smoke_user["spec"]["tags"] == ["management"], "Only the HTTP API smoke user needs the Management tag."
assert all(item["spec"]["tags"] == [] for item in users if item is not smoke_user), \
    "Business service users must keep the default least-privilege tags."
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
assert cluster["spec"]["override"]["statefulSet"]["spec"]["persistentVolumeClaimRetentionPolicy"] == {"whenDeleted": "Retain", "whenScaled": "Retain"}
assert users[-1]["metadata"]["name"] == permissions[-1]["metadata"]["name"] == "staging-smoke"

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
assert 'aws-ebs-csi-driver' in addons
assert 'helm_release' not in addons and 'aws_load_balancer_controller' not in addons
assert 'enableNetworkPolicy' not in addons, "Enabling storage foundations must not rewrite existing node network policy."

rabbitmq_workflow = yaml.load((ROOT / ".github/workflows/deploy-rabbitmq.yml").read_text(), Loader=yaml.BaseLoader)
assert list(rabbitmq_workflow["on"]) == ["workflow_dispatch"]
rabbitmq_job = rabbitmq_workflow["jobs"]["deploy"]
assert rabbitmq_job["if"] == "github.ref == 'refs/heads/main'"
assert rabbitmq_job["runs-on"] == ["self-hosted", "linux", "staging-vpc"]
assert rabbitmq_job["environment"] == "staging"
assert rabbitmq_job["permissions"] == {"contents": "read", "id-token": "write"}
assert rabbitmq_job["env"]["AWS_OPERATOR_INSTALL_ROLE_ARN"] == "${{ vars.AWS_OPERATOR_INSTALL_ROLE_ARN }}"
rabbitmq_steps = rabbitmq_job["steps"]
rabbitmq_script = "\n".join(step.get("run", "") for step in rabbitmq_steps)
assert "env -u AVAILABLE_CREDIT_USD" in rabbitmq_script and "--approved-spend-usd \"$APPROVED_STAGING_SPEND_USD\" --require-gate" in rabbitmq_script
assert "terraform apply" not in rabbitmq_script and "kubectl create namespace" not in rabbitmq_script
assert "kubectl apply" not in rabbitmq_script and "--all-namespaces" not in rabbitmq_script
assert "STAGING_WINDOW_VALIDATED=true" in rabbitmq_script
assert "apply_rabbitmq_owned.py" in rabbitmq_script
assert 'kubectl get secret "${user}-user-credentials" --namespace "$namespace" -o name' in rabbitmq_script
assert "refusing to adopt them" in rabbitmq_script and "statefulsets,pods,persistentvolumeclaims" in rabbitmq_script
assert all(url in rabbitmq_script for url in ("cert-manager/releases/download/v1.21.2", "cluster-operator/releases/download/v2.22.3", "messaging-topology-operator/releases/download/v1.20.2"))
topology_step = next(step["run"] for step in rabbitmq_steps if step.get("name") == "Apply durable topology from the reviewed chart")
assert topology_step.index("00-exchanges") < topology_step.index("10-queues") < topology_step.index("20-bindings") < topology_step.index("30-policies")
assert "namespaces=(rabbitmq specification formulation compliance label-workflow)" in topology_step
assert "staging-smoke.smoke" in rabbitmq_script and 'delivery_mode\\":2' in rabbitmq_script
rabbitmq_service_role = yaml.safe_load((ROOT / "infra/terraform/modules/eks-addons/manifests/rabbitmq-service-deployer-role.yaml.tmpl").read_text())
assert set(rabbitmq_service_role["rules"][0]["resources"]) == {"users", "permissions", "queues", "bindings", "policies"}
assert "exchanges.rabbitmq.com --namespace rabbitmq" in rabbitmq_script
assert set(rabbitmq_service_role["rules"][0]["verbs"]) == {"get", "list", "watch", "create", "patch"}
assert rabbitmq_service_role["rules"][1] == {
    "apiGroups": [""], "resources": ["secrets"],
    "resourceNames": ["${namespace}-rabbitmq-user-credentials"], "verbs": ["get"]
}
rabbitmq_role = yaml.safe_load((ROOT / "infra/terraform/modules/eks-addons/manifests/rabbitmq-deployer-role.yaml.tmpl").read_text())
assert all("delete" not in rule["verbs"] and "update" not in rule["verbs"] for rule in rabbitmq_role["rules"])
safe_apply = (ROOT / "scripts/staging/apply_rabbitmq_owned.py").read_text()
assert '"diff", "--server-side", "--force-conflicts"' in safe_apply and '"create", "--field-manager", FIELD_MANAGER' in safe_apply
assert "kubectl apply" not in safe_apply and "kubectl delete" not in safe_apply

terraform_workflow = yaml.load((ROOT / ".github/workflows/terraform-validate.yml").read_text(), Loader=yaml.BaseLoader)
terraform_plan = terraform_workflow["jobs"]["plan"]
assert terraform_plan["if"] == "${{ github.event_name == 'workflow_dispatch' && github.ref == 'refs/heads/main' && inputs.run_plan }}"
assert terraform_plan["environment"] == "staging"
terraform_plan_script = "\n".join(step.get("run", "") for step in terraform_plan["steps"])
assert "terraform state list" in terraform_plan_script and "terraform plan" in terraform_plan_script
assert "aws s3api head-object" in terraform_plan_script and "spectrace/staging/terraform.tfstate" in terraform_plan_script
assert "foodlabelflow-staging-tfstate-679857346122-ap-southeast-1" in terraform_plan_script
for planned_var in (
    "-var=enable_eks=true", "-var=enable_eks_addons=true", "-var=manage_vpc_cni=false",
    "-var=general_desired_size=3", "-var=enable_compliance_node_group=true", "-var=compliance_desired_size=2",
):
    assert planned_var in terraform_plan_script, f"Protected Plan must explicitly review {planned_var}."
assert "terraform apply" not in terraform_plan_script and "terraform import" not in terraform_plan_script
assert "scripts/aws_staging_budget.py --hours" in terraform_plan_script
for step in rabbitmq_steps:
    if step.get("run"):
        subprocess.run(["bash", "-n"], input=step["run"], text=True, check=True)

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
