# AWS Staging Deployment Evidence

**Observed:** AWS and Kubernetes reads 2026-10-14 10:25–10:36 UTC; GitHub refreshed 10:35 UTC; state-backed Terraform plans reviewed through 10:42 UTC, `ap-southeast-1`.
**Repository:** `hxj04121-lab/FoodLabelFlow-Microservices`. **Main snapshot:** `355a2f722fd7631bbcaa46b96d9ce22db169c6da`. **Deployment checkout:** `codex/aws-eks-full-deployment-readiness` at `f6dc165c246a8758a6b70797e2af22e2bbbe8b0e` before this evidence refresh.

This report separates AWS identity, private API reachability, Kubernetes authorization/workloads, ingress, and application acceptance. No secret values were fetched or recorded. No AWS or Kubernetes resources were changed.

## Live AWS and Kubernetes reads

| Evidence | Result |
|---|---|
| STS `FoodLabelFlowStagingLogin` / `FoodLabelFlowStagingDeploy` | Both resolve to the intended staging account `679857346122`; deployment uses the least-privilege deploy role, not Root. |
| EKS `spectrace-staging` | `ACTIVE`, Kubernetes `1.36`, `ap-southeast-1`; private endpoint enabled and public endpoint disabled. |
| EKS access entry | `FoodLabelFlowStagingDeploy` is present. Its access policies are namespace-scoped: `AmazonEKSEditPolicy` for `staging`, `gateway`, `specification`, `formulation`, `compliance`, and `label-workflow`; `AmazonEKSViewPolicy` for `keycloak`, `rabbitmq`, and `kube-system`. |
| Private API path | Existing VPC-connected CloudShell `staging-login-vpc-a` reaches the private API after assuming the deploy role. |
| Workload inventory | The seven readable application/operator namespaces each have 0 Deployments, StatefulSets, Pods, Services, PVCs, and NetworkPolicies. `observability` is inaccessible, so its state is UNKNOWN. |
| Cluster-scoped inventory | Listing Nodes, Namespaces, StorageClasses, CRDs, and all-namespace NetworkPolicies is Forbidden under the current scope. Cluster-scoped bootstrap writes are unavailable. |
| Core Kubernetes pods | In `kube-system`, `aws-node` is 2/2 Ready; both CoreDNS Pods are 1/1 Ready; kube-proxy is 1/1 Ready. |
| EKS add-ons | `eks list-addons` is empty. Self-managed component state is not implied by this result; EBS CSI and NetworkPolicy enforcement remain UNKNOWN. |
| Managed node groups / quota | Only `general` exists: `t3.medium`, min 1 / desired 1 / max 3, private subnets in two AZs, no custom labels/taints. The Compliance node group is absent. Standard On-Demand vCPU quota reads 16. |
| RDS `spectrace-staging-mysql` | `available`, MySQL 8.0.46, `db.t3.small`, Multi-AZ, private, GP3, 20 GiB. |
| Secrets Manager | Metadata confirms `spectrace-staging/mysql/master`; its value was not read. |
| ALB `spectrace-staging-alb` | Active and `internal`. No listener exists. Target group `spectrace-staging-gateway` exists for port 8080/IP targets, but has no associated load balancer and no registered targets. |
| ACM / DNS / runner inventory | ACM certificate listing and GitHub self-hosted runner inventory are denied. No team FQDN, certificate ARN, or approved ingress source CIDRs were supplied. Runner presence is UNKNOWN. |

The deploy role can list Pods in the seven namespaces above but cannot access `observability` or cluster-scoped resources. Existing workload and operator namespaces are empty where readable. No application route or public URL is available; do not present the internal ALB DNS name as public.

## Terraform state and plans

Terraform initialized against the protected S3 backend without migration. The workspace is `default`; state inventory returned 53 managed addresses. The remote state lock was acquired and released for planning. State values and Secret values were not printed. No Apply, Destroy, Import, state migration, or repair ran.

Two state-backed plans were reviewed from the deployment checkout:

| Plan | Inputs | Create | Update | No-op | Delete / replace |
|---|---|---:|---:|---:|---:|
| Baseline | Terraform defaults | 1 | 30 | 19 | 0 |
| Compliance foundation | `general_desired_size=3`, `enable_compliance_node_group=true`, `enable_eks_addons=false` | 4 | 31 | 18 | 0 |

The baseline includes general-node desired capacity 1 → 3, launch-template changes that may roll nodes, and a node security-group self-ingress rule allowing all protocols and ports between nodes. It leaves the Compliance node group and EKS add-ons disabled, so it is not a deployment plan.

The Compliance foundation plan adds the dedicated Compliance managed node group, its two cluster-autoscaler ASG tags, and the same all-protocol node self-ingress rule. Its 31 updates include the general node group's launch-template/scaling configuration and in-place tag updates to existing network, EKS, RDS, and ALB resources. The node-group changes may roll nodes. It still leaves EKS add-ons disabled and does not deploy operators, services, database users, runtime Secrets, or ingress. Neither plan is approved for Apply; review the exact changes and pass the staging gate first.

The mandatory STCN-37 staging gate was not run: approved deployment, Compliance experiment, and k6 hours plus verified available-credit input were not provided, and the checked-in public pricing metadata is incomplete. No cost estimate/report was generated and the gate was not bypassed.

## GitHub deployment controls and image evidence

- The `staging` Environment exists with required reviewer `hxj04121-lab`; `prevent_self_review=false`, custom branch policy, and 0 variables / 0 secrets.
- Main has no apply-capable deployment workflow. `scripts/staging.sh` exists only in unmerged Draft PR #39, based on a feature branch.
- PR #39 is Draft/open at `976239ba`; its Terraform `plan` is skipped.
- PR #52 is open at `f5b2d70b`, review `CHANGES_REQUESTED`; several checks were still running at 10:35 UTC and Terraform `plan` was skipped.
- PR #54 is approved/open at `5beb98a5`. A successful current-head publish run produced the Gateway digest `ghcr.io/hxj04121-lab/foodlabelflow-microservices/gateway@sha256:d4190ea6ee31b84e7b67d36ad56e6391612c8bdc4916b8bb14d28b9a17f6ab47`. Pages and deployed E2E were skipped.
- PR #55 is Draft/open at `a842847a`; its single-node/shared-namespace overlay does not meet the required topology.
- PR #56 is Draft/open. Its seven checks passed on the pre-refresh head at 10:35 UTC. The deployment evidence refresh is not an AWS release.
- The GHCR package API returned 403 due to missing `read:packages`; immutable digests for Specification, Formulation, Compliance, and Label Workflow remain unverified. Main chart defaults still have placeholder image tags and empty `envFrom`.

## Local and CI validation

| Check | Result |
|---|---|
| `terraform fmt -check -recursive` on the latest-main snapshot | PASS. |
| `terraform init -backend=false -input=false` | PASS; used the lockfile and shared provider cache; no remote backend was configured. |
| `terraform validate` | PASS with backend disabled; not a real plan. |
| `helm lint --strict deploy/helm/spectrace-service` | PASS, 1 chart. |
| `helm lint --strict deploy/helm/platform-network-policies deploy/helm/rabbitmq-topology` on PR #39 worktree | PASS, 2 charts. |
| `bash -n ... && bash tests/test_staging_guards.sh` on PR #39 worktree | PASS; closed-window guard rejected `up` and `down`. |
| `kubectl kustomize deploy/operators` and `deploy/operators/rabbitmq` | PASS as local rendering only. |
| `npm test` under `deploy/helm` | PASS; 19 Helm/template assertions passed after `npm ci` installed the locked dependency. |
| `python scripts/check_m5_platform.py` on PR #39 worktree | PASS with CI-pinned PyYAML 6.0.3 installed under `/tmp`. |
| `scripts/check_eks_addons.sh` on PR #39 worktree | PASS; Terraform validate, four Helm lints, and rendered Kubernetes YAML checks passed. Optional `kubeconform` was not configured. |
| `PYTHON_BIN=<bundled Python 3.12> mvn -B -ntp verify` | PASS after fixing `LabelPublicationTransactionTest` cleanup to delete FK child rows before label versions; all reactor modules and Testcontainers tests passed. |

These local and CI results are not AWS deployment or end-to-end acceptance evidence. No IAM/EKS access-policy change, Terraform Apply, add-on/CRD installation, database/Secret write, Kubernetes deployment, or cleanup was executed.
