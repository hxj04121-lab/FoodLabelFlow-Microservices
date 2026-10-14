# Terraform State and Plan Safety

**As of:** 2026-10-14 10:42 UTC
**Decision:** **BLOCKED from Apply.** The protected state and a current baseline plan were verified, but the baseline is not a full deployment plan and the mandatory staging gate has not passed.

## State inventory

- The tracked backend is S3. Metadata checks confirmed the reported state object exists and the bucket is versioned. The bucket/key and object metadata remain omitted from this public report.
- Terraform initialized against that remote backend without state migration. The current workspace is `default`; `terraform state list` returned 53 managed addresses. No state values or Secret values were printed.
- The remote state lock was acquired and released by the plan. No Apply, Destroy, Import, state migration, or state repair ran.
- CI `plan` jobs in PRs #39 and #52 are skipped. The local plan below is the only current state-backed plan reviewed here; it was saved outside the repository with restrictive permissions.

## Baseline plan

Plan generated 2026-10-14 around 10:26 UTC from PR #56 head `f6dc165`, using default Terraform variables and the verified `default` workspace:

| Action | Count |
|---|---:|
| Create | 1 |
| Update | 30 |
| No-op | 19 |
| Delete or replace | 0 |

The planned create is a node security-group self-ingress rule allowing all protocols and ports between instances in the existing node security group. The in-place updates include general-node desired capacity 1 → 3 (bounded 1–3), launch-template tags/version changes, and mostly resource tags. The managed node group moves from launch-template version 1 to the new latest version, which can roll existing nodes. The plan does not create or replace the VPC, EKS cluster, RDS, or ALB.

A second plan set `general_desired_size=3`, `enable_compliance_node_group=true`, and left `enable_eks_addons=false`:

| Action | Count |
|---|---:|
| Create | 4 |
| Update | 31 |
| No-op | 18 |
| Delete or replace | 0 |

It creates the dedicated Compliance node group, two cluster-autoscaler ASG tags, and the same broad node self-ingress rule. The other updates are general-node launch-template/scaling changes and in-place tags on existing network, EKS, RDS, and ALB resources. The node-group updates may roll nodes. This remains a foundation plan, not a full deployment plan: EKS add-ons, operators, applications, runtime Secrets, and ingress are not included.

This is **not** the full deployment plan. Defaults leave `enable_compliance_node_group=false` and `enable_eks_addons=false`; no domain, certificate, or ALB source CIDRs are configured. It therefore does not plan the Compliance pool, EKS add-ons, operators, application workloads, database users, or runtime Secrets.

## Apply blockers

1. The exact plan has not been approved after review. In particular, review the all-protocol node self-ingress rule and potential managed-node-group rollout.
2. The required STCN-37 gate was not run: no approved deployment/experiment/k6 window or verified available-credit input was supplied, and the checked-in pricing metadata is incomplete. No cost estimate/report was generated and the gate was not bypassed.
3. The main branch has no apply-capable workflow. The staging script is only in Draft PR #39 on a feature-branch base; do not deploy from that unmerged branch.
4. The current EKS deployment role has namespace-scoped Kubernetes access. It cannot install cluster-scoped CRDs/StorageClasses or access the `observability` namespace. See [staging blockers](staging-remaining-blockers.md).
5. Neither reviewed plan includes EKS add-ons. A full plan still needs reviewed add-on settings; the add-on module also requires an owner-provided domain, certificate, and ingress CIDRs and changes the ALB path.

Do not Apply this baseline. Before a deployment Apply, prepare a state-linked plan with the final approved variables and access path, inspect every action, and preserve the private EKS endpoint and existing RDS/ALB data. A plan with no delete/replace actions does not prove the cluster can accept the complete application.

## Offline validation

`terraform fmt -check -recursive`, `terraform init -backend=false -input=false`, and `terraform validate` passed on the latest-main snapshot. These validate syntax and schemas only; they do not authorize or prove a live Apply.
