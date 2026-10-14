# Remaining Blockers for Full Staging Deployment

**Current result: BLOCKED.** The private EKS API is reachable from the existing VPC CloudShell, but no AWS or Kubernetes resources were changed.

## P0 — resolve before deployment writes

1. **Mandatory staging gate:** The workflow requires explicit deployment-window, Compliance experiment, and k6 hours plus a verified available-credit input. None was supplied. Public Price List metadata is now refreshed and validated; the gate still cannot run without those owner inputs. It was not bypassed or modified, and no cost report was generated.
2. **Kubernetes bootstrap authorization:** The existing `FoodLabelFlowStagingDeploy` access entry grants `AmazonEKSEditPolicy` only in `staging`, `gateway`, `specification`, `formulation`, `compliance`, and `label-workflow`; its `AmazonEKSViewPolicy` covers `keycloak`, `rabbitmq`, and `kube-system`. It cannot write the Keycloak/RabbitMQ/operator namespaces, access `observability`, or create cluster-scoped CRDs/StorageClasses. A temporary cluster-wide `AmazonEKSClusterAdminPolicy` association to this role would permit full Kubernetes API access, including reading/modifying/deleting Secrets and cluster-scoped resources. It must be explicitly approved at action time and disassociated after deployment verification; no such change has been made.
3. **Terraform plan:** The current remote-state baseline plan has 1 create, 30 updates, 19 no-ops, and 0 deletes/replacements. It scales general desired capacity 1 → 3, updates the launch-template version (possible node roll), and adds all-protocol/all-port node self-ingress. It omits the dedicated Compliance group and all EKS add-ons because their default flags are off. Do not Apply this incomplete plan.
4. **Supported deployment path:** Main has no apply-capable workflow. The interactive `scripts/staging.sh` exists only in Draft PR #39, which is based on a feature branch; its Apply path is not a merged deployment route. The GitHub `staging` Environment has a required reviewer but currently has no variables or secrets. Self-hosted runner inventory is HTTP 403, so VPC runner presence is UNKNOWN.
5. **Runtime inputs and images:** Main has no runtime-secret workflow/caller; the service chart defaults `envFrom` to empty. PR #52 is still `CHANGES_REQUESTED` and its checks were partly running at the last refresh. Exact GHCR digests for Specification, Formulation, Compliance, and Label Workflow are not verified. PR #54's Gateway digest is the only current service image digest evidenced.
6. **Application route:** The ALB is internal and has no listener. ACM listing is denied, and no FQDN, certificate ARN, or approved ingress source CIDRs were supplied. The app has no usable URL; keep the EKS API private and do not present the internal ALB DNS name as public.
7. **PR review:** PR #39 is Draft/open and Terraform plan is skipped; PR #52 has changes requested; PR #54 is approved/open but Pages and deployed E2E were skipped; PR #55 is Draft and its single-node/shared-namespace overlay is not acceptable. PR #56 is the Draft readiness/test-cleanup PR.

## P1 — required for workload acceptance

- The seven readable application/operator namespaces currently have zero Deployments, StatefulSets, Pods, Services, PVCs, and NetworkPolicies. `observability` and cluster-scoped StorageClasses/CRDs remain inaccessible.
- `aws-node`, both CoreDNS Pods, and kube-proxy are Ready. EKS managed add-ons are empty; EBS CSI, NetworkPolicy enforcement, and other self-managed platform components remain unverified.
- The current general node group is one `t3.medium`; account Standard On-Demand quota is 16 vCPU. The dedicated `c6i.large` Compliance group is absent. Preserve service requests, the Compliance taint, and two-zone spread.
- Create isolated schemas/users for each service and a separate least-privilege Keycloak database identity. Inject runtime values only through a protected deployment path; do not use the RDS master credential for workloads.
- Install the RabbitMQ/Keycloak operators, EBS storage, NetworkPolicy enforcement, and observability only after their exact existing state and access are verified. Do not overwrite PVCs or existing data.
- Run real VPC/EKS probes for DB connectivity, RabbitMQ publish/consume, Keycloak discovery/JWKS/JWT, service-to-service calls, Gateway routing, NetworkPolicy isolation, and frontend E2E. CI/local checks are not staging acceptance.

## Next write plan

After the gate inputs are complete and the exact access change is approved, prepare a final state-linked plan that reuses the existing VPC/EKS/RDS/ALB, scales general capacity, adds the dedicated Compliance group, and enables only missing add-ons. Review node rotation and node-to-node security-group access. Keep the cluster API private. Do not enable the ALB-backed HTTPS ingress until the owner supplies an approved domain, issued certificate, and source CIDRs.

No Apply, IAM/EKS access-policy change, CRD/operator installation, database/Secret write, Kubernetes workload deployment, or cleanup has been executed.
