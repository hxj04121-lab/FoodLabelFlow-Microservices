# M5 Staging ownership decision

**Decision:** 2026-10-20, M1 / State owner selected independent management (Path B) in [PR #89 comment 6287467427](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/pull/89#issuecomment-6287467427). M1 owns maintenance of the existing controller and its IAM resources through the approved window ending 2026-10-23 00:00 UTC; cluster teardown removes them.

## Approved ownership boundary

| Object | Live identity from reviewed evidence | Terraform behavior in staging | Risk and evidence status |
|---|---|---|---|
| AWS Load Balancer Controller Deployment | `kube-system/aws-load-balancer-controller`, 1/1 Ready | `manage_load_balancer_controller=false`; Helm release count is zero | No chart, values, replica count, IAM policy, or release change is planned through Terraform. Chart version/values are unverified. |
| Helm release metadata | Release `kube-system/aws-load-balancer-controller`; metadata Secret is reported present | Not Terraform-managed | The exact current Secret name is not included in M1's decision comment. The protected Plan inventory requests the server-side table only and records matching release metadata names without retrieving Secret values. |
| Controller IAM role | `spectrace-staging-aws_load_balancer_controller` in account `679857346122` | LBC entry is excluded from `aws_iam_role.addons` when the flag is false | Expected default-path ARN is `arn:aws:iam::679857346122:role/spectrace-staging-aws_load_balancer_controller`; the live ARN must match the read-only protected inventory. No policy permissions are changed. |
| Controller IAM policy | `spectrace-staging-aws-load-balancer-controller` in account `679857346122` | Conditional count is zero | Expected ARN is `arn:aws:iam::679857346122:policy/spectrace-staging-aws-load-balancer-controller`; the live ARN and attachment are rechecked by the protected inventory. |
| Controller IAM attachment | Role `spectrace-staging-aws_load_balancer_controller` attached to policy `spectrace-staging-aws-load-balancer-controller` | Conditional count is zero | Exact role/policy ARN pair is verified read-only before Plan. No IAM grant is added. |
| Namespace `gateway` | Existing `gateway` Namespace | In `existing_namespaces`; no Namespace manifest instance | No create, update, replace, delete, or relabel. Namespaced TGB Role/RoleBinding remain Terraform-managed as net-new objects. |
| Namespace `keycloak` | Existing `keycloak` Namespace | In `existing_namespaces`; no Namespace manifest instance | No create, update, replace, delete, or relabel. TGB permissions are separated from the Keycloak CR deployment Role. |
| Namespace `keycloak-system` | Existing operator Namespace | In `existing_namespaces`; no Namespace manifest instance | No create, update, replace, delete, or relabel. |

The reviewed ownership guard permits only these account, cluster, object names, and the independent mode (`manage_load_balancer_controller=false`). It requires a healthy 1/1 Controller, all three IAM objects, the Helm metadata name family, and all three namespaces. Any additional live add-on, namespace, IAM, or RBAC object outside its State address and allowlist fails closed. A second Plan JSON guard rejects every non-no-op action on the Controller, its IAM resources, or the three existing Namespace addresses.

## Terraform-owned additions

- Staging creates only namespaces absent from the three-name exclusion: RabbitMQ, observability, Specification, Formulation, Compliance, Label Workflow, Frontend, and RabbitMQ operator namespaces.
- TGB Role/RoleBinding pairs in `gateway`, `keycloak`, and `frontend` are new and remain in Terraform at `module.eks_addons[0].kubernetes_manifest.deployer_custom_resource_role["<namespace>"]` and the corresponding `rolebinding` address. Each permits only create/get/patch/watch on `elbv2.k8s.aws/targetgroupbindings`, with reads/patch/watch restricted to the service's exact TGB name, and binds to the existing `spectrace-staging-deploy` group.
- Keycloak CR deployment permissions remain in a separate namespaced Role/RoleBinding scoped to `keycloaks.k8s.keycloak.org` and `spectrace-keycloak`.
- Cluster Autoscaler IAM remains managed at `module.eks[0].aws_iam_role.cluster_autoscaler` and `module.eks[0].aws_iam_role_policy.cluster_autoscaler`. Missing Cluster Autoscaler, metrics-server, `stcn-*` NetworkPolicies, and EBS CSI declarations remain enabled.

## Approval boundary

This decision authorizes a code ownership boundary only. It does not authorize Terraform Plan, Apply, Import, Helm operations, or Kubernetes writes. After code review, M1 must provide the current S3 State VersionId and approve the protected `staging` Plan. The Plan must show zero action against the seven existing objects before a separately approved Apply, budget gate, and deployment window.

The current local environment has no AWS credentials, so live IAM ARNs and the exact Helm metadata Secret name have not been independently re-read here. The protected workflow verifies the live ARN pair and emits only server-returned Helm metadata names; it does not request or write Secret values to summaries. The Plan remains unrun until M1 provides the fresh VersionId and approves the staging Environment.
