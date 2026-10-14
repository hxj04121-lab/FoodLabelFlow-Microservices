# AWS Staging Deployment Evidence

**Observed:** 2026-10-14. This public summary omits cloud account, role, cluster, and resource identifiers.

## Live evidence

| Area | Result |
|---|---|
| AWS identity | The intended staging account and deployment identity were verified. |
| Private cluster API | Reachable through an existing VPC-connected path; public access remains disabled. |
| Kubernetes authorization | Namespace-scoped only. Several required namespaces and cluster-scoped resources cannot be inspected or changed. |
| Workload inventory | No application workloads were found in the namespaces visible to the deployment identity. Inaccessible scopes are UNKNOWN. |
| Core components | Core networking and DNS pods are Ready. Storage, policy enforcement, and other platform controllers are unverified. |
| Database and Secrets | Database infrastructure and Secret metadata exist. No Secret value was read; application credentials and isolated database users are unverified. |
| Ingress | The internal load-balancing path has no active listener or registered application targets. No approved domain/certificate/source-range inputs are available. |
| Terraform state and plans | The protected remote state and current workspace were verified. Baseline and foundation plans contain node-group and network-rule changes that require review. No Apply was performed. |
| Budget gate | Public AWS pricing metadata was refreshed and validated. The gate was not run because approved window/experiment/load-test hours and verified available-credit input were not supplied. No estimate or report was generated. |

## Repository and validation evidence

- The protected deployment Environment has a reviewer rule but lacks the runtime configuration required for a release.
- The main branch does not contain a reviewed apply-capable deployment path.
- Only some service image publication is evidenced; immutable digests for all workloads are not verified.
- Existing CI and local checks passed for the reviewed code at their recorded revisions. They do not prove a live deployment or end-to-end acceptance.

No AWS or Kubernetes resource, permission, database, or Secret was changed. No application smoke test passed because no application workloads or route are deployed.
