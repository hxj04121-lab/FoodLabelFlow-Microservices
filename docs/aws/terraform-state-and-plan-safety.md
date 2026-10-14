# Terraform State and Plan Safety

**As of:** 2026-10-14 09:51 UTC
**Decision:** **BLOCKED — no account-backed Terraform plan is safe to run yet.**

## What is known

- The tracked backend is `terraform { backend "s3" {} }`.
- The 2026-10-11 staging progress report records the remote backend. On 2026-10-14, metadata-only `s3api head-object` confirmed the reported versioned object exists, and `get-bucket-versioning` returned `Enabled`. The exact bucket/key and object metadata are omitted from this public document.
- `infra/terraform/backend.hcl.example` still contains a placeholder bucket; no real `infra/terraform/backend.hcl` exists in this checkout. The actual file is gitignored. The current workspace and resource-address mapping have not been revalidated.
- AWS `s3:ListAllMyBuckets` and `s3:GetEncryptionConfiguration` are denied to `FoodLabelFlowStagingDeploy`; CloudFormation `ListStacks` and IAM `GetRole` are also denied. The exact object was readable by metadata only; its contents were not fetched.
- The protected local directory contains saved plan files from 2026-10-11. The prior progress report says the final plan at that time had no changes. That plan is historical and is not bound to current main `355a2f7` or today's PR heads. An attempt to summarize it with the current checkout failed to load provider schemas; it is not used as current plan evidence.
- The EKS, RDS, VPC, and ALB resources are present in the intended staging account and carry Terraform/project tags. Those tags do not prove that this Terraform root and workspace own the current resources.
- GitHub Actions Terraform validation passed in PR #39 and PR #52, but their real `plan` jobs were **SKIPPED**. No plan JSON or saved plan exists for this review.

## Why an Apply could duplicate or replace live resources

`infra/terraform/main.tf` declares a network, EKS cluster/node groups, RDS, and edge resources. The root defaults `enable_eks=true`, `enable_rds=true`, `enable_alb=true`, and `enable_eks_addons=false`. With a missing/empty/wrong backend or workspace, those resources may appear new even though AWS already contains them.

Enabling the add-on module also changes the edge path: the static `module.edge` is omitted when `enable_eks_addons=true`, while the AWS Load Balancer Controller and Gateway Ingress are introduced. A correct plan may therefore replace/remove the current static ALB and create a controller-managed ALB. The existing ALB currently has no listener or target group, but replacement still needs review against the real state and intended DNS path.

The add-on module plans managed VPC CNI and EBS CSI add-ons and configures VPC CNI NetworkPolicy enforcement. `eks list-addons` is empty, but the private Kubernetes API is unreachable here, so self-managed VPC CNI/CoreDNS/kube-proxy versions and workloads cannot be checked. The deployment role also lacks `eks:DescribeAddonVersions`. Do not let a Terraform Apply overwrite core components before a VPC-connected inventory confirms what is already installed.

## Required evidence before a real plan

1. An owner/admin confirms the protected backend configuration and `default` or named workspace are the authoritative staging state. The object exists, but this public report omits the exact bucket/key; the current workspace and exact resource mapping are not verified. Do not put backend configuration in Git or chat.
2. Obtain explicit approval for the read-only Terraform state inventory required by the task's state-operation boundary. Then, with the least-privilege plan identity, confirm the resource address list without printing Secret values and verify the protected backend/workspace.
3. From the VPC-connected path, inventory existing cluster/node groups, add-ons, namespaces, controllers, StorageClasses/PVCs, Ingress/ALB, and access entries. Reconcile those objects to Terraform addresses before changing configuration.
4. Run a plan with the confirmed backend/workspace and reviewed variable file. Inspect **every** create/update/replace/destroy, especially VPC, EKS, node groups, RDS, Secrets Manager, and ALB. A replacement or deletion of shared state is a stop condition.
5. Save the plan with restrictive file permissions because Terraform plan/state can contain sensitive values. Review its exact SHA and full text before seeking Apply approval.

Example sequence after items 1–3 are satisfied (the variable file and backend values must be provided by the state owner):

```sh
terraform -chdir=infra/terraform init -input=false -backend-config=backend.hcl
terraform -chdir=infra/terraform workspace show
terraform -chdir=infra/terraform state list
umask 077
terraform -chdir=infra/terraform plan -input=false -var-file=/secure/path/staging.tfvars -out=/secure/path/staging.tfplan
terraform -chdir=infra/terraform show -no-color /secure/path/staging.tfplan
```

The last command is a **plan only**. Do not run `apply`, `destroy`, `import`, state migration, or state repair until the ownership and exact action list have been reviewed and the user has approved that specific change set. The state inventory and a current remote plan have not been run in this review; the user request requires explicit approval before Terraform state operations.

## Offline validation

`terraform fmt -check -recursive`, `terraform init -backend=false -input=false`, and `terraform validate` passed for the latest-main snapshot. Initialization used the lockfile and shared provider cache. These checks validate syntax and provider schemas only; they do not establish state ownership or Apply safety.
