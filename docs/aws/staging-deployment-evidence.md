# AWS Staging Deployment Evidence

**Observed:** 2026-10-14, AWS reads 09:44–09:51 UTC and GitHub refresh 10:05 UTC, `ap-southeast-1`.
**Checkout used for this report:** latest fetched `origin/main`, `355a2f722fd7631bbcaa46b96d9ce22db169c6da`

This index distinguishes live AWS API results, GitHub results, local/offline checks, and unavailable evidence. No secret values were fetched or recorded.

## Live AWS reads

| Evidence | Result |
|---|---|
| STS `FoodLabelFlowStagingLogin` | Confirmed the intended staging AWS account. |
| STS `FoodLabelFlowStagingDeploy` | Confirmed the same staging AWS account. |
| `eks describe-cluster spectrace-staging` | `ACTIVE`, Kubernetes `1.36`, private endpoint enabled, public endpoint disabled. |
| `eks list-access-entries` | Deployment role access entry is present. Kubernetes API authorization still unverified. |
| `eks list-nodegroups` / `describe-nodegroup general` | Only `general`; `t3.medium`; min 1 / desired 1 / max 3; two private AZ subnets; no custom labels/taints. |
| `eks list-addons` | Empty list of EKS managed add-ons. Self-managed pods cannot be inspected from this workstation. |
| `kubectl get namespaces --request-timeout=10s` | Timed out against the private API; namespace/workload/CRD/StorageClass/PVC state remains UNKNOWN. |
| `rds describe-db-instances spectrace-staging-mysql` | `available`, MySQL 8.0.46, `db.t3.small`, Multi-AZ. |
| `ec2 describe-subnets` | RDS and ALB subnets were verified across the expected two AZs. CIDRs are omitted from this public document. |
| `elbv2 describe-load-balancers` | `spectrace-staging-alb` active, scheme `internal`. |
| `elbv2 describe-listeners` / `describe-target-groups` | Both returned empty lists. |
| `secretsmanager list-secrets` | Metadata shows `spectrace-staging/mysql/master`; the value was not read. |
| S3 state object metadata | Historical report's backend object was confirmed with metadata-only `head-object`; it is versioned. Exact bucket/key and object metadata are omitted from this public document; encryption metadata is denied. State contents were not fetched. |

Read calls denied: `acm:ListCertificates`, `route53:ListHostedZones`, `s3:ListAllMyBuckets`, `s3:GetEncryptionConfiguration`, `cloudformation:ListStacks`, `iam:GetRole`, and EKS `DescribeAddonVersions`. These are recorded as UNKNOWN rather than absent.

The 2026-10-11 progress report recorded a no-change Terraform plan and the protected local directory contains saved plans from that date. They are historical, not a current plan for main `355a2f7`. A safe summary attempt could not load provider schemas in the current checkout. No current Terraform state address listing or remote plan was run.

## GitHub evidence

- `staging` Environment exists. Its required reviewer is `hxj04121-lab`; `prevent_self_review=false`; custom branch policies are enabled. Environment variable and secret-name lists are empty.
- Repository self-hosted-runner inventory and Actions-policy API returned 403; runner presence and OIDC trust are UNKNOWN.
- PR #39 current head `976239ba72dae646d3c4787c04a92ad72fa780fc`: current reported CI checks passed; Terraform `plan` was skipped.
- PR #52 refreshed head `cc724a9383b7dda1b4ef85986a8c7050a5bb3e07`: review decision is `CHANGES_REQUESTED`; all listed checks passed at 10:05 UTC. Terraform `plan` was skipped.
- PR #54 current head `5beb98a5b2fe56f1b30d7ada54d145bac9d5ad02`: approved/open. Its successful `publish-gateway` run `38532352262` pushed `ghcr.io/hxj04121-lab/foodlabelflow-microservices/gateway:5beb98a5b2fe56f1b30d7ada54d145bac9d5ad02` with registry digest `sha256:d4190ea6ee31b84e7b67d36ad56e6391612c8bdc4916b8bb14d28b9a17f6ab47`. Pages and deployed E2E were not run.
- PR #55 current head `a842847a7609d988439fd55ae15b9a2925be5042`: Helm/backend/security checks passed. The PR is still Draft and makes no live AWS/Kubernetes change.
- GHCR package API returned 403 because the active GitHub CLI token lacks `read:packages`. The four business-service immutable digests remain unverified; current chart defaults are placeholders.

## Local/offline validation

| Check | Result |
|---|---|
| `terraform fmt -check -recursive` on latest-main snapshot | PASS. |
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

No local result above is an AWS deployment or end-to-end acceptance result.
