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

## Standalone Terraform Plan role bootstrap

On 2026-10-21, M1 explicitly approved a standalone Plan-role bootstrap. Sun executed it with the authorized AWS IAM Identity Center administrator profile in account `679857346122`. This role is deliberately outside Terraform State and must not be imported, managed, or deleted by Terraform.

| Item | Verified configuration |
|---|---|
| IAM role | `arn:aws:iam::679857346122:role/spectrace-staging-github-plan` |
| Trust | Existing GitHub OIDC provider `arn:aws:iam::679857346122:oidc-provider/token.actions.githubusercontent.com`; `StringEquals` audience `sts.amazonaws.com` and immutable subject `repo@245486830/FoodLabelFlow-Microservices@1388701923:environment`; no ref condition |
| Managed policy | AWS managed `ReadOnlyAccess`. Its read scope is broad across the account, including S3 reads beyond the State bucket; the inline policy below does not narrow those managed-policy grants. |
| Inline policy | Policy `FoodLabelFlowStagingTerraformPlan` lists only `foodlabelflow-staging-tfstate-679857346122-ap-southeast-1` under `spectrace/`; grants `GetObject` for `spectrace/staging/terraform.tfstate`; grants `PutObject`/`DeleteObject` only for its `.tflock`; and grants `DescribeSecret`/`GetSecretValue` only for `spectrace-staging/mysql/master-*` in `ap-southeast-1`. |
| S3 encryption | At verification, the State object used SSE-S3 (`AES256`). The bucket default uses the AWS-managed `alias/aws/s3` KMS key with Bucket Keys disabled. The role's KMS `Decrypt`/`GenerateDataKey` grant is restricted to S3 and the exact staging `.tflock` encryption context needed for a future lock object. |
| EKS Access Entry | `arn:aws:eks:ap-southeast-1:679857346122:access-entry/spectrace-staging/role/679857346122/spectrace-staging-github-plan/e0d0aeb8-8b5e-fb6c-699b-d713dc5dd404`, group `spectrace-staging-github-plan-readonly` |
| EKS managed access policy | `AmazonEKSViewPolicy`, cluster scope |
| Custom Kubernetes RBAC | The reviewed, read-only manifest is [`infra/terraform/manual/plan-readonly-rbac.yaml`](../../infra/terraform/manual/plan-readonly-rbac.yaml). It grants only `get`, `list`, and `watch` for the named discovery/RBAC resources; Secret reads are a separate Role/RoleBinding in `kube-system` only. It has not yet been applied because the private EKS API is unreachable from the Mac. |

The prepared local `staging.tfvars.json` is outside Git with mode `0600`, contains all 30 current root variables, and leaves `github_repository` empty. The Terraform root module has `count = 0` for `module.github_oidc` when that variable is empty. The staging Environment does not yet contain `AWS_PLAN_ROLE_ARN` or `TF_PLAN_VARS_JSON`; M1 must configure them separately. The State inventory checked during bootstrap had 53 resource instances and no Plan-role or EKS Access Entry address; the State object VersionId remained unchanged. Keep the role outside State even if that module is enabled later; any future ownership transfer requires a separate review and approval.

The protected `staging-vpc` deployment workflow reads `vars.AWS_DEPLOY_ROLE_ARN`; the current value was verified as `arn:aws:iam::679857346122:role/FoodLabelFlowStagingDeploy`. Its existing EKS Access Entry has a cluster-scoped `AmazonEKSClusterAdminPolicy`. That pre-existing executor is not granted to the Plan role. No RBAC-only protected workflow currently exists, so do not dispatch the business-service deployment workflow to apply this manifest. The RBAC write remains pending until an approved VPC-runner/manual execution path is provided under the existing role; then run only this manifest and read-only `kubectl auth can-i` checks. No Kubernetes RBAC write was performed during this bootstrap.

This bootstrap does not authorize a Terraform Plan or Apply. M1 must configure `AWS_PLAN_ROLE_ARN` with the role ARN above and provide the protected `TF_PLAN_VARS_JSON` GitHub Environment secret before the separately approved Plan workflow can run. A Plan still needs a fresh State VersionId and normal Environment, budget, and window approvals.
