# Remaining Blockers for Full Staging Deployment

**Current result: BLOCKED.** The deployment requires owner-controlled state/access details and explicit approval before AWS or Kubernetes writes. No AWS resource, IAM policy, Kubernetes object, database, or Secret was changed in this review.

## P0 — required before a state-backed plan or deployment

1. **Private cluster path:** Provide an authorized VPC-connected CloudShell/bastion or confirm the existing `staging-vpc` runner. The local EKS API request timed out; runner inventory is denied to this GitHub token.
2. **Terraform ownership:** The owner-reported versioned state object exists, but its protected backend configuration, current workspace, and resource-address mapping remain unverified in this review; the ignored local `backend.hcl` is absent, and CI `plan` is skipped. The task explicitly requires approval before Terraform state operations; approve a read-only address inventory and current plan, or provide the owner-verified workspace/mapping. Do not Apply from an empty or guessed state.
3. **Ingress decision:** Confirm private-only access through VPN/VPC or supply the approved FQDN, DNS owner, issued ACM certificate, and source CIDRs. ACM/Route 53 listing is denied; the current ALB is internal with no Listener/Target Group.
4. **Protected GitHub deployment configuration:** The `staging` Environment currently has no variable/secret names. Confirm an independent required approver (`prevent_self_review=true` is currently false), approved branch policy, deploy-role OIDC trust, and VPC-connected runner. The role trust and runner inventory could not be verified with current read permissions.
5. **Immutable workload images:** Publish/verify exact GHCR SHA256 digests for Specification, Formulation, Compliance, and Label Workflow. The reusable workflow is defined in an unmerged platform PR and has no current main-branch caller. Only the current PR #54 Gateway digest is proven.
6. **Review existing PRs:** PR #39 remains Draft and its Terraform plan is skipped. PR #52 has `CHANGES_REQUESTED`; all listed checks passed, and its Terraform plan is skipped. PR #54 is approved/open but has no Pages/deployed acceptance. PR #55 remains Draft and its one-node/shared-namespace overlay does not satisfy this deployment's topology requirements.

## P1 — required before workload acceptance

- From the VPC path, verify current VPC CNI/CoreDNS/kube-proxy, NetworkPolicy enforcement, EBS CSI, `ebs-gp3`, controllers/operators, namespaces, PVCs, RBAC, and actual node allocatable capacity. The AWS managed add-on list is empty; it does not establish self-managed add-on state.
- Provision the general pool at a reviewed capacity, enable the dedicated `c6i.large` Compliance group with its label/taint, and verify eligible nodes in both AZs. Current desired general capacity is one `t3.medium`, below declared workload-request floor.
- Create isolated DB credentials and schemas for the four services and a separate least-privilege Keycloak DB account. Inject runtime values via protected workflows; do not use the RDS master credential for workloads.
- Confirm Keycloak/Gateway OIDC values, service API endpoints, RabbitMQ credentials/topology, and observability write credentials are configured in the protected Environment.
- Verify the correct ALB target registration/listener, TLS, DNS, and frontend API/OIDC settings for the selected access model.
- Run live readiness, database, RabbitMQ, Keycloak/OIDC, JWT, Gateway, NetworkPolicy, and frontend E2E checks from the authorized VPC path. Keep their evidence separate from local/CI checks.

## Approval boundary

The next AWS write plan is to reuse the existing state-owned VPC/EKS/RDS, set the general node group to desired 3 (bounded 1–3), enable one dedicated Compliance `c6i.large` group (bounded 1–4), enable only missing reviewed EKS/Helm add-ons, configure the selected ALB route, create isolated runtime database credentials/Secrets, then deploy operators and services in the eight required namespaces. It preserves the private EKS endpoint, service requests, Compliance taint, two-zone spread, and existing data.

That is not yet an Apply-ready action list because the state/workspace, existing in-cluster components, certificate/domain, and exact resource diff remain unknown. Once the P0 inputs are available, produce and review a real state-linked Terraform plan and a VPC-connected Kubernetes change list; then request explicit approval for those exact mutations. Do not run Apply, install CRDs/operators, write databases/Secrets, deploy workloads, or delete PVCs before that approval.

The current deployment workflow also enforces the STCN-37 gate. No budget report was generated and the gate was not bypassed or changed.
