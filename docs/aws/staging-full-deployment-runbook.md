# Staging Full Deployment Runbook

This runbook is a **prepared plan**, not an authorization to change AWS or Kubernetes. Current live status and blockers are in [readiness](staging-deployment-readiness.md) and [remaining blockers](staging-remaining-blockers.md).

## Target topology

- Keep separate workload namespaces: `gateway`, `specification`, `formulation`, `compliance`, `label-workflow`, `keycloak`, `rabbitmq`, and `observability`. Keep operator namespaces `rabbitmq-system` and `keycloak-system`.
- Keep the Kubernetes API private. Use an authorized VPC-connected CloudShell, bastion, or self-hosted `staging-vpc` runner.
- Use the existing two-AZ VPC, RDS, and ALB reflected in the verified `default` Terraform workspace. Do not Apply until the final plan and staging gate are reviewed. Keep HTTPS ingress internal until the owner confirms a domain, DNS provider, ACM certificate, and approved access CIDRs.
- General pool: `t3.medium`, min 1/max 3; start the approved deployment at desired 3 across the two private AZs. Compliance pool: `c6i.large`, min 1/max 4, desired 1, labels `dedicated=compliance,workload=compliance`, taint `dedicated=compliance:NoSchedule`.
- Retain the Helm chart's two-zone `DoNotSchedule` spread, all Requests/Limits, and Compliance isolation. Do not use PR #55's single-node overlay for final acceptance.
- RabbitMQ: three replicas, three `ebs-gp3` 8Gi claims, durable topology and DLQs. Preserve all existing PVC data.

## Gate 0 — owner inputs and no-write inventory

1. Confirm an approved staging window and pass the existing protected STCN-37 deployment gate. This repository has an enforced gate; do not bypass or edit it. This task generated no cost estimate or budget report.
2. The protected S3 backend has been verified without migration; workspace `default` contains 53 state addresses. Baseline and Compliance-foundation plans are recorded in [state and plan safety](terraform-state-and-plan-safety.md). Reuse that backend/workspace and review every final action; do not print state values.
3. An authorized VPC-connected path already reaches the private API. The deploy role can inspect `staging`, `gateway`, `specification`, `formulation`, `compliance`, `label-workflow`, `keycloak`, `rabbitmq`, and `kube-system` only within its current namespace scopes; the seven readable application/operator namespaces are empty. Cluster-wide inventory, `observability`, CRDs, Nodes, Namespaces, and StorageClasses are forbidden. Obtain the required scoped access before relying on those results; do not treat Forbidden as absence.
4. Confirm the intended access mode: private-only through VPN/VPC, or a specific public FQDN, DNS provider, issued ACM certificate, and reviewed client CIDRs. Do not expose the private cluster API.
5. Confirm independent GitHub Environment reviewers, `staging-vpc` runner, OIDC trust, runtime variables, and secret **names**. Add values only through protected GitHub/AWS secret stores; never paste credentials in commands or logs.

Read-only commands from the VPC path:

```sh
aws sts get-caller-identity
aws eks describe-cluster --region ap-southeast-1 --name spectrace-staging
aws eks list-nodegroups --region ap-southeast-1 --cluster-name spectrace-staging
aws eks list-addons --region ap-southeast-1 --cluster-name spectrace-staging
umask 077
kubeconfig_path="${TMPDIR:-/tmp}/spectrace-kubeconfig.$$.yaml"
aws eks update-kubeconfig --region ap-southeast-1 --name spectrace-staging --kubeconfig "$kubeconfig_path"
for namespace in staging gateway specification formulation compliance label-workflow keycloak rabbitmq kube-system; do
  KUBECONFIG="$kubeconfig_path" kubectl get pods,deployments,statefulsets,services,pvc,networkpolicies -n "$namespace"
done
```

Cluster-wide or cluster-scoped reads are currently forbidden to the deployment role. A temporary cluster-wide `AmazonEKSClusterAdminPolicy` association would grant access to all Kubernetes resources, including Secrets; it requires separate action-time approval and must be removed after deployment verification.

## Gate 1 — state-linked Terraform plan

1. Initialize only against the owner-confirmed backend/workspace and inspect its state list.
2. Prepare values that reuse the existing VPC/EKS/RDS, keep the API private, enable the required add-on module, set the Compliance group on, and set general desired size to 3. The reviewed baseline and Compliance-foundation plans contain no delete/replacement, but do include node-group changes that may roll nodes and a node-to-node rule allowing every protocol and port. Review those exact changes.
3. Obtain live AWS-compatible add-on versions and verify whether core components are managed or self-managed before taking ownership. The add-on code's `most_recent` AWS add-on selection must be resolved and reviewed in the plan.
4. Review every Terraform action. The current Compliance-foundation plan still leaves EKS add-ons off. The add-on module also changes the ALB path and needs owner-provided domain, certificate, and ingress CIDRs. Stop on any unknown state, VPC/EKS/RDS replacement, Secret change, unaccepted ALB route change, or unreviewed IAM expansion.
5. Present the exact plan SHA and action list to the owner and obtain explicit approval for that Apply.

## Gate 2 — approved cluster foundations

After approval of the exact plan only:

1. Scale the general group and create the dedicated Compliance group without reducing resource requests or removing its taint/selector.
2. Inventory first, then install only missing foundations: EBS CSI, `ebs-gp3` StorageClass, NetworkPolicy-capable VPC CNI configuration/agent, AWS Load Balancer Controller, Cluster Autoscaler, and Metrics Server. Preserve healthy VPC CNI/CoreDNS/kube-proxy instead of re-installing them blindly.
3. Create/verify the eight workload namespaces and two operator namespaces. Apply the default-deny and explicit service NetworkPolicies; verify live enforcement from a VPC-connected probe.
4. Verify node labels/taints, allocatable CPU/memory, two eligible AZs, metrics API, gp3 test-PVC binding, and controller readiness before scheduling application pods.

## Gate 3 — database, secrets, and platform services

1. Use the protected workflow/least-privilege administrator to provision the four isolated service schemas/users and the separate least-privilege Keycloak DB account/schema. Confirm migrations and cross-database denials. Do not use the RDS master Secret as an application credential.
2. Inject service and Keycloak runtime Secrets only through the approved staging workflow. Verify names/ownership without printing values.
3. Install pinned, reviewed cert-manager/RabbitMQ Cluster Operator/RabbitMQ Topology Operator/Keycloak Operator versions. The current platform branch declares cert-manager `v1.18.2`, RabbitMQ Cluster Operator `v2.22.3`, Topology Operator `v1.20.2`, and Keycloak `26.8.0`; verify compatibility against the live cluster before applying.
4. Apply RabbitMQ `spectrace` in namespace `rabbitmq`, wait for 3 Ready pods and 3 Bound gp3 PVCs, then apply durable exchanges/queues/bindings/DLQs and run publish/consume/retry checks.
5. Apply Keycloak in namespace `keycloak` with the RDS endpoint and protected DB Secret; import `spectrace` realm/client config without committed or default passwords. Verify readiness, OIDC discovery, JWKS, Authorization Code + PKCE S256, audience, and Gateway JWT validation.
6. Install observability components only after their protected remote-write/log credentials are configured. Verify NetworkPolicy rules and remote telemetry without printing tokens.

## Gate 4 — services, Gateway, and frontend

1. Require commit-tagged published GHCR images and immutable `sha256` digests for Specification, Formulation, Compliance, and Label Workflow. The current PR #54 Gateway digest is recorded in [evidence](staging-deployment-evidence.md). Do not deploy placeholder tags or substitute local Docker IDs.
2. Invoke a reviewed reusable service workflow from a protected main-branch caller. It must run on the authorized VPC runner and use the protected `staging` Environment. Deploy each Helm release into its own required namespace and use the existing runtime Secret names.
3. Deploy Gateway after all four upstream Services and Keycloak are healthy. Configure TLS/ALB only for the owner-confirmed access mode. The current ALB has no listener; target group `spectrace-staging-gateway` is unattached with no registered targets. Its internal DNS name is not an end-user URL.
4. Publish the frontend only after the approved Gateway base URL and OIDC issuer/client configuration are set. Verify login, PKCE callback, CORS, authorized API access, and denial for an unauthorized principal.

## Acceptance and rollback

Record exact image digests, Helm revisions, Deployment revisions, node placements/AZs, PVC IDs, redacted test results, and UTC timestamps. Run readiness, DB connectivity, RabbitMQ publish/consume and DLQ, Keycloak login/JWKS/JWT, service-to-service calls, Gateway routes, NetworkPolicy isolation, and frontend E2E against the live environment. Label a test **PASS** only with its real VPC/EKS result.

For a failed rollout, use the recorded Helm revision/image digest to roll back the affected release and preserve logs. Do not delete PVCs, RDS data, Secrets, ALB, or the cluster during rollback. The existing `scripts/staging.sh down` path deletes RabbitMQ PVCs and destroys the Terraform environment; do not run it without a separate explicit cleanup approval, verified backups, and a reviewed destroy plan.
