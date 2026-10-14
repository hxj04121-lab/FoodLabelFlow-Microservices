# AWS Staging Deployment Readiness

**Evidence time:** AWS/Kubernetes reads 2026-10-14 10:25–10:36 UTC; GitHub refreshed 10:35 UTC. **Repository:** `hxj04121-lab/FoodLabelFlow-Microservices`. **Main snapshot:** `355a2f722fd7631bbcaa46b96d9ce22db169c6da`.
**Target:** EKS `spectrace-staging` in `ap-southeast-1`

## Decision

**BLOCKED — the application is not deployed.** An existing VPC-connected CloudShell reaches the private API, but the deployment role has only namespace-scoped access and cannot inspect or write cluster-scoped resources, install CRDs/operators, or deploy into all required namespaces. No AWS or Kubernetes resource was changed.

Status terms in this report:

- **PASS** means the named check was verified during this review.
- **BLOCKED** means a required condition is known not to be met.
- **UNKNOWN** means access or evidence was unavailable; it does not mean absent.
- **CODE ONLY** means configuration exists in a branch or manifest but has not been applied.
- **NOT RUN** means the test was not executed.

## AWS identity and cluster

| Check | Status | Current evidence |
|---|---|---|
| Login identity | PASS | `FoodLabelFlowStagingLogin` assumed role in the intended staging account. |
| Deployment identity | PASS | `FoodLabelFlowStagingDeploy` assumed role in the same account. |
| EKS cluster | PASS | `spectrace-staging` is `ACTIVE`, Kubernetes `1.36`. |
| API exposure | PASS | `endpointPrivateAccess=true`; `endpointPublicAccess=false`; no public CIDRs. |
| Deployment-role EKS access entry | PASS | The `FoodLabelFlowStagingDeploy` role is listed. |
| VPC-connected path | PASS | Existing CloudShell VPC environment `staging-login-vpc-a`; assumed the deployment role and reached the private EKS API. |
| Kubernetes authorization | PARTIAL | `AmazonEKSEditPolicy` covers `staging`, `gateway`, `specification`, `formulation`, `compliance`, `label-workflow`; `AmazonEKSViewPolicy` covers `keycloak`, `rabbitmq`, `kube-system`. Cluster-scoped reads/writes and `observability` are denied. |
| Application workload inventory | BLOCKED | Seven readable namespaces each have 0 Deployments, StatefulSets, Pods, Services, PVCs, and NetworkPolicies. `observability` is inaccessible, so its existence/state is UNKNOWN. |
| Core Kubernetes pods | PASS | In `kube-system`, `aws-node` is 2/2 Ready, both CoreDNS Pods are 1/1 Ready, and `kube-proxy` is 1/1 Ready. |
| Cluster-scoped components | UNKNOWN | Listing Nodes, Namespaces, StorageClasses, CRDs, or all-namespace NetworkPolicies is forbidden by the current access scope. |
| EKS managed add-ons | BLOCKED | `eks list-addons` returned an empty list. This does not reveal self-managed in-cluster components. |
| Add-on compatible versions | UNKNOWN | `eks:DescribeAddonVersions` is denied to the deployment role. |

No NetworkPolicies exist in the seven readable namespaces. VPC CNI, CoreDNS, and kube-proxy are self-managed because the EKS managed add-on list is empty. NetworkPolicy enforcement, EBS CSI, StorageClasses, CRDs, operators, and Compliance-node labels/taints remain UNKNOWN.

## Compute and storage capacity

The only managed node group is `general`:

| Property | Current value |
|---|---|
| Status / instance | `ACTIVE` / `t3.medium` |
| Scaling | min 1, desired 1, max 3 |
| Subnets | private subnets in `ap-southeast-1a` and `ap-southeast-1b` |
| Custom labels / taints | none |
| Compliance node group | not listed |
| Standard On-Demand vCPU quota | 16 vCPU (live Service Quotas read) |

The declared requests in the current service chart and the deployment branch manifests give this **floor**, before Kubernetes system pods, operators, observability agents, sidecars, or any extra replicas:

| Workloads on the general pool | Replicas × request | Subtotal |
|---|---:|---:|
| Specification, Formulation, Label Workflow, Gateway | 4 × (1 × 100m CPU / 256Mi) | 400m CPU / 1Gi |
| Keycloak (operator CR) | 1 × 250m CPU / 512Mi | 250m CPU / 512Mi |
| RabbitMQ | 3 × 500m CPU / 1Gi | 1.5 CPU / 3Gi |
| **General-pool floor** |  | **2.15 CPU / 4.5Gi** |

One `t3.medium` has only 2 vCPU / 4Gi raw capacity, less than this floor before node overhead. It cannot host the complete declared general workload. The separate Compliance chart requests 1 CPU / 2Gi and selects a `c6i.large` node group with a `dedicated=compliance:NoSchedule` taint; Terraform currently defaults that node group off. The RabbitMQ design declares three 8Gi `ebs-gp3` claims (24Gi total). All seven readable workload/operator namespaces currently have 0 PVCs; cluster-scoped StorageClass state is inaccessible.

Terraform's normal general desired size is three, with a 1–3 bound. Three general nodes across the two configured AZs plus one dedicated Compliance node is the current manifest-compatible starting configuration, not a capacity acceptance result; pod allocatable capacity and scheduling must be measured from the VPC-connected cluster. Keep the chart's two-zone `DoNotSchedule` spread and all declared requests. The `PR #55` single-node overlay sets `minimumZones: 1` and its description places backend releases in one `staging` namespace; that overlay does not meet this task's multi-AZ, multi-namespace requirements.

## RDS, secrets, and network edge

| Resource | Status | Current evidence |
|---|---|---|
| RDS MySQL | PASS | `spectrace-staging-mysql` is `available`, MySQL `8.0.46`, `db.t3.small`, Multi-AZ, port 3306. |
| RDS network | PASS | Its private subnets span `ap-southeast-1a` and `ap-southeast-1b`. |
| Database/application connectivity | UNKNOWN | No in-cluster probe or authenticated database test was possible. |
| Secrets Manager metadata | PASS | Secret name `spectrace-staging/mysql/master` exists. Its value was not read. |
| Service/Keycloak runtime secrets | BLOCKED | The protected GitHub `staging` Environment has 0 variables and 0 secrets. Main-chart `envFrom` defaults to empty; the runtime-secrets workflow is in PR #52, not merged. No secret values were read. |
| ALB | PASS | `spectrace-staging-alb` is active and `internal`; its DNS name is internal-only. |
| ALB listeners / target groups | BLOCKED | No listener exists. Target group `spectrace-staging-gateway` exists on port 8080/IP, but is unattached and has no registered targets. |
| ACM / DNS | BLOCKED | `acm:ListCertificates` is denied; no team FQDN, certificate ARN, or approved source CIDR was supplied. |
| Public or private application URL | BLOCKED | There is no listener or route. Do not use the internal ALB DNS name as a public URL. |

## GitHub deployment controls and image evidence

The `staging` Environment exists and has a required-reviewer rule for `hxj04121-lab`; `prevent_self_review=false`, and its branch policy is custom rather than protected-branch-only. The Environment has no variables or secrets. Self-hosted runner inventory returns HTTP 403, so a `staging-vpc` runner is **UNKNOWN**, not absent. The main branch has no apply-capable workflow; `scripts/staging.sh` exists only in unmerged Draft PR #39.

The current open PRs are separate code/CI evidence, not an AWS release:

| PR | Current state | Deployment relevance |
|---|---|---|
| [#29](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/pull/29) | Merged into feature branch `feat/stcn-39-aws-terraform`, not `main` | EKS add-on module. |
| [#39](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/pull/39) | Draft/open, head `976239ba`, based on feature branch; Terraform `plan` skipped | Operators, platform workflow and staging controls; not a deployed revision. |
| [#52](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/pull/52) | Open, head `f5b2d70b`, review decision `CHANGES_REQUESTED`; several checks still running at 10:35 UTC and Terraform `plan` skipped | Database isolation, NetworkPolicies, runtime Secrets and observability. |
| [#54](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/pull/54) | Open/approved, head `5beb98a5` | Gateway/frontend and disposable E2E preparation. A successful current-head workflow published Gateway digest `ghcr.io/hxj04121-lab/foodlabelflow-microservices/gateway@sha256:d4190ea6ee31b84e7b67d36ad56e6391612c8bdc4916b8bb14d28b9a17f6ab47`. Pages deployment was skipped; deployed E2E profile was not run. |
| [#55](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/pull/55) | Draft/open, head `a842847a`; listed CI checks passed | Staging overlays only; its single-node/shared-namespace mode is unsuitable for the requested final topology. |
| [#56](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/pull/56) | Draft/open; all seven checks passed on the pre-refresh head at 10:35 UTC | Readiness/runbook and test-cleanup update; not a deployed revision. |

The current main chart still has `example.invalid`/replacement-tag defaults. No current immutable GHCR digests were verified for Specification, Formulation, Compliance, or Label Workflow. The repository package API denied access because the local GitHub token lacks `read:packages`; the Gateway digest above is taken from the successful GitHub Actions push log at the exact current PR #54 head.

## Readiness summary

- **AWS identity:** PASS.
- **EKS private network reachability:** PASS through the existing VPC CloudShell.
- **Kubernetes authorization:** PARTIAL; namespace scope blocks cluster bootstrap and observability access.
- **Live workloads:** BLOCKED; all seven readable namespaces are empty.
- **Terraform state and baseline plan:** verified; the default-variable plan has no deletions/replacements but is not a full deployment plan. See [state and plan safety](terraform-state-and-plan-safety.md).
- **Add-ons, node sizing, RabbitMQ, Keycloak, and application deployment:** CODE ONLY / UNKNOWN; not installed or accepted live.
- **Ingress, DNS, HTTPS, frontend, and end-to-end access:** BLOCKED.

No Terraform Apply/Destroy/Import, IAM change, add-on/CRD installation, database/Secret change, Kubernetes deployment, or cleanup was executed. The mandatory staging gate was not run: no approved deployment/experiment/k6 window or verified credit input was supplied, and the checked-in gate pricing metadata is incomplete. No cost estimate/report was generated or gate bypassed.
