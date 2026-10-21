# M5 Staging ownership decision

**Decision:** 2026-10-20, M1 / State owner selected independent management (Path B) in [PR #89 comment 6287467427](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/pull/89#issuecomment-6287467427). M1 owns maintenance of the existing controller and its IAM resources through the approved window ending 2026-10-23 00:00 UTC; cluster teardown removes them.

## Approved ownership boundary

| Object | Live identity from reviewed evidence | Terraform behavior in staging | Risk and evidence status |
|---|---|---|---|
| AWS Load Balancer Controller Deployment | `kube-system/aws-load-balancer-controller`, 1/1 Ready | `manage_load_balancer_controller=false`; Helm release count is zero | No chart, values, replica count, IAM policy, or release change is planned through Terraform. Chart version/values are unverified. |
| Helm release identity | Release `kube-system/aws-load-balancer-controller`; Plan checks the Deployment's Helm ownership labels and annotations | Not Terraform-managed | The Plan reads only the Deployment metadata (`managed-by=Helm`, release name, and namespace). The Plan Role has no Kubernetes Secret access. |
| Controller IAM role | `spectrace-staging-aws_load_balancer_controller` in account `679857346122` | LBC entry is excluded from `aws_iam_role.addons` when the flag is false | Expected default-path ARN is `arn:aws:iam::679857346122:role/spectrace-staging-aws_load_balancer_controller`; the live ARN must match the read-only protected inventory. No policy permissions are changed. |
| Controller IAM policy | `spectrace-staging-aws-load-balancer-controller` in account `679857346122` | Conditional count is zero | Expected ARN is `arn:aws:iam::679857346122:policy/spectrace-staging-aws-load-balancer-controller`; the live ARN and attachment are rechecked by the protected inventory. |
| Controller IAM attachment | Role `spectrace-staging-aws_load_balancer_controller` attached to policy `spectrace-staging-aws-load-balancer-controller` | Conditional count is zero | Exact role/policy ARN pair is verified read-only before Plan. No IAM grant is added. |
| Namespace `gateway` | Existing `gateway` Namespace | In `existing_namespaces`; no Namespace manifest instance | No create, update, replace, delete, or relabel. Namespaced TGB Role/RoleBinding remain Terraform-managed as net-new objects. |
| Namespace `keycloak` | Existing `keycloak` Namespace | In `existing_namespaces`; no Namespace manifest instance | No create, update, replace, delete, or relabel. TGB permissions are separated from the Keycloak CR deployment Role. |
| Namespace `keycloak-system` | Existing operator Namespace | In `existing_namespaces`; no Namespace manifest instance | No create, update, replace, delete, or relabel. |

The reviewed ownership guard permits only these account, cluster, object names, and the independent mode (`manage_load_balancer_controller=false`). It requires a healthy 1/1 Controller, exact Helm ownership labels/annotations, all three IAM objects, and all three namespaces. It does not list or read Kubernetes Secrets. Any additional live add-on, namespace, IAM, or RBAC object outside its State address and allowlist fails closed. A second Plan JSON guard rejects every non-no-op action on the Controller, its IAM resources, or the three existing Namespace addresses.

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
| Trust | Existing GitHub OIDC provider `arn:aws:iam::679857346122:oidc-provider/token.actions.githubusercontent.com`; live policy verified with `StringEquals` audience `sts.amazonaws.com` and immutable subject `repo:hxj04121-lab@245486830/FoodLabelFlow-Microservices@1388701923:environment:staging`; no ref condition. Updated on 2026-10-21 to match M1's exact review instruction. |
| Managed policy | AWS managed `ReadOnlyAccess`. Its read scope is broad across the account, including S3 reads beyond the State bucket; the inline policy below does not narrow those managed-policy grants. |
| Inline policy | Policy `FoodLabelFlowStagingTerraformPlan` lists only `foodlabelflow-staging-tfstate-679857346122-ap-southeast-1` under `spectrace/`; grants `GetObject` for `spectrace/staging/terraform.tfstate`; grants `PutObject`/`DeleteObject` only for its `.tflock`; and grants `DescribeSecret`/`GetSecretValue` only for `spectrace-staging/mysql/master-*` in `ap-southeast-1`. |
| S3 encryption | At verification, the State object used SSE-S3 (`AES256`). The bucket default uses the AWS-managed `alias/aws/s3` KMS key with Bucket Keys disabled. The role's KMS `Decrypt`/`GenerateDataKey` grant is restricted to S3 and the exact staging `.tflock` encryption context needed for a future lock object. |
| EKS Access Entry | `arn:aws:eks:ap-southeast-1:679857346122:access-entry/spectrace-staging/role/679857346122/spectrace-staging-github-plan/e0d0aeb8-8b5e-fb6c-699b-d713dc5dd404`, group `spectrace-staging-github-plan-readonly` |
| EKS managed access policy | `AmazonEKSViewPolicy`, cluster scope |
| Custom Kubernetes RBAC | The reviewed manifest is [`infra/terraform/manual/plan-readonly-rbac.yaml`](../../infra/terraform/manual/plan-readonly-rbac.yaml). The Plan group gets only `get`, `list`, and `watch` for the named discovery/RBAC resources plus `get` on the single named LBC Deployment in `kube-system`; it receives no Secret access. The manifest has not been applied. |

The prepared local `staging.tfvars.json` is outside Git with mode `0600`, contains all 30 current root variables, and leaves `github_repository` empty. The Terraform root module has `count = 0` for `module.github_oidc` when that variable is empty. The `staging` Environment currently has the names `AWS_ACCOUNT_ID`, `AWS_REGION`, `EKS_CLUSTER_NAME`, `AWS_DEPLOY_ROLE_ARN`, and `AWS_PLAN_ROLE_ARN`; the `TF_PLAN_VARS_JSON` secret is absent. M1 must add that secret through the approved secure channel. The last State inventory recorded 53 resource instances and no Plan-role or EKS Access Entry address; the current read-only S3 `HeadObject` still reports VersionId `tIRsj1wl1CWHRWeAa2C1G.1QujMl.0dk` (last modified 2026-10-11). This turn did not read or modify the State body. Keep the role outside State even if that module is enabled later; any future ownership transfer requires a separate review and approval.

The protected Plan and RBAC workflows are intended to use the `staging-vpc` self-hosted runner. PR #102 adds a main-only, manual, `staging`-Environment RBAC workflow; after the manifest change it needs an updated checksum, exact-head CI, and renewed M1/M2 review before merge. It uses the existing `FoodLabelFlowStagingDeploy` role, whose live Access Entry currently has cluster-scoped `AmazonEKSClusterAdminPolicy`. M1 accepted that risk for this staging window only. No RBAC workflow has been dispatched and no Kubernetes RBAC write was performed. GitHub's runner-list API returned 403 to the current token, so runner online status is unverified.

## Private EKS execution and teardown

- **Plan RBAC bootstrap:** use only the protected main-only workflow on the `staging-vpc` runner, with the `staging` Environment approval. It may create only the four fixed, SHA-pinned RBAC objects and then verify Plan-role access. Do not run it until M1 explicitly approves the RBAC write and GitHub grants the Environment approval.
- **RabbitMQ/Keycloak Operators:** use a separate protected main-only workflow on `staging-vpc`, with a dedicated least-privilege Operator installation role. Pin and verify the versioned manifests and hashes; review CRD, webhook, and cluster-scope RBAC changes separately. No Operator role or workflow is provisioned by this document, so the existing CloudShell runbook remains preparatory and is not an execution authorization.
- **Terraform Apply:** use a separate protected main-only workflow on `staging-vpc` and a dedicated Apply Role; never reuse the Plan Role. Bind execution to M1's reviewed Plan artifact/hash and State VersionId, stop on drift, and reject unapproved delete/replace or IAM changes. No Apply Role is created and no Apply is authorized here.
- **Teardown:** after M1 confirms the current window no longer depends on the temporary executor permission, remove only the cluster-scope `AmazonEKSClusterAdminPolicy` association from `FoodLabelFlowStagingDeploy`, then re-read `list-associated-access-policies` to prove it is absent. Preserve any separately approved namespace-scoped associations.

This bootstrap does not authorize a Terraform Plan or Apply. Although `AWS_PLAN_ROLE_ARN` is now present in the protected `staging` Environment, `TF_PLAN_VARS_JSON` is still absent. Do not trigger Plan until M1 securely configures it and confirms the internal ALB versus public ALB Terraform ownership boundary. A Plan also needs the current State VersionId and normal Environment, budget, and window approvals.
