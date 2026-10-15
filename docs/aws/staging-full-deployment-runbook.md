# Staging Full Deployment Runbook

This is a prepared sequence, not authorization to change cloud or Kubernetes resources. See [readiness](staging-deployment-readiness.md), [remaining blockers](staging-remaining-blockers.md), and [plan safety](terraform-state-and-plan-safety.md) for the current high-level status.

## Target topology

- Keep the Kubernetes API private and use an authorized VPC-connected path.
- Keep application services and platform operators in their chart-defined namespaces.
- Reuse existing network, database, and load-balancing resources only when the reviewed state-backed plan confirms no unintended replacement or data change.
- Preserve the charts' resource requests, Compliance workload isolation, and two-zone scheduling rules.
- Preserve existing persistent-volume data. Keep messaging replicas and durable queues as declared by the reviewed charts.
- Do not enable external ingress until the owner approves the domain, certificate, and source ranges.

## Gate 0 — inputs and read-only inventory

1. Supply the approved deployment window, experiment duration, load-test duration, and verified available-credit input. Run the existing fail-closed staging gate; do not edit or bypass it. Public price metadata is refreshed. Do not publish estimates or account-credit details.
2. Use the verified protected backend and workspace. Inventory state without printing state or Secret values.
3. From the authorized VPC path, verify identity and endpoint mode. Inspect only the namespaces allowed by the current permissions. Treat every Forbidden or inaccessible scope as UNKNOWN.
4. Confirm the private access model or provide owner-approved routing inputs for an internal application endpoint. Do not expose the Kubernetes API.
5. Verify protected release controls, required reviewers, VPC-connected runner, runtime Secret names, and immutable service image digests. Add Secret values only through protected stores.

Read-only command template (set the two environment variables through the approved local configuration):

```sh
aws sts get-caller-identity
aws eks describe-cluster --region "$AWS_REGION" --name "$EKS_CLUSTER_NAME"
aws eks list-nodegroups --region "$AWS_REGION" --cluster-name "$EKS_CLUSTER_NAME"
aws eks list-addons --region "$AWS_REGION" --cluster-name "$EKS_CLUSTER_NAME"
umask 077
kubeconfig_path="${TMPDIR:-/tmp}/staging-kubeconfig.$$.yaml"
aws eks update-kubeconfig --region "$AWS_REGION" --name "$EKS_CLUSTER_NAME" --kubeconfig "$kubeconfig_path"
for namespace in gateway specification formulation compliance label-workflow keycloak rabbitmq; do
  KUBECONFIG="$kubeconfig_path" kubectl get pods,deployments,statefulsets,services,pvc,networkpolicies -n "$namespace"
done
```

The current deployment identity cannot inspect all namespaces or cluster-scoped resources. Any temporary cluster-wide administrator access would reach every Kubernetes resource, including Secrets. Obtain action-time approval for the exact access change and remove it after verification.

## Gate 1 — complete state-backed plan

1. Reuse the verified backend/workspace and prepare a plan with approved node, add-on, and ingress settings.
2. Review every Terraform action. Node-group changes may roll nodes; any broad node-to-node network permission needs explicit review.
3. Inventory existing platform components before taking ownership. Resolve add-on versions and verify which core components are managed or self-managed.
4. Stop on unknown state, unintended replacement, Secret change, unaccepted ingress change, or unreviewed privilege expansion.
5. Record the plan revision and action summary. Apply only after the mandatory gate passes and the exact plan is approved.

## Gate 2 — cluster foundations

After exact-plan approval:

1. Apply the reviewed node capacity and scheduling configuration without weakening workload isolation or resource requests.
2. Inventory first, then install only missing storage, network-policy, ingress, autoscaling, and metrics components.
3. Verify required namespaces, default-deny and service NetworkPolicies, node placement, allocatable capacity, storage classes, and a disposable test volume before scheduling application pods.

## Gate 3 — database, secrets, and platform services

1. Create isolated least-privilege database users/schemas for each service and the identity provider. Do not use the database administrator credential for applications.
2. Inject runtime Secret values only through the approved protected release workflow. Verify names and ownership without printing values.
3. Install pinned, reviewed platform operators after compatibility and existing state are confirmed.
4. Deploy the messaging cluster and durable topology. Verify persistent volumes, publish/consume, retry, and dead-letter behavior.
5. Deploy the identity provider with its protected database configuration. Verify discovery, signing keys, authorization flow, audience, and gateway token validation.
6. Configure observability only after protected telemetry credentials are available; verify network-policy behavior without printing tokens.

## Gate 4 — services, gateway, and frontend

1. Require commit-linked immutable image digests for every service. Do not use placeholder tags or local image IDs.
2. Deploy each service through a reviewed protected workflow to its chart-defined namespace. Use only the protected runtime Secret references.
3. Deploy the gateway after upstream services and identity are healthy. Configure internal TLS and routing only for the owner-approved access model.
4. Publish the frontend only after its gateway and identity settings are verified. Test sign-in, callback, CORS, authorized API access, and denial for an unauthorized principal.

## Acceptance and rollback

Record image digests, release revisions, node placement, persistent-volume identifiers, redacted test results, and UTC timestamps in the private deployment record. Run live readiness, database, messaging, identity, service-to-service, gateway, network-isolation, and frontend checks. Mark a test PASS only with a real VPC/cluster result.

For a failed rollout, roll back the affected release using its recorded revision and preserve logs and persistent data. Do not delete volumes, database data, Secrets, load balancers, or the cluster without a separate reviewed cleanup plan and approval.
