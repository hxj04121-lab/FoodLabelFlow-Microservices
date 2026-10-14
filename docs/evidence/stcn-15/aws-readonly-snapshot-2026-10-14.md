# AWS staging read-only snapshot

**Observed:** 2026-10-14 14:07 +08:00  
**Account/region:** `679857346122` / `ap-southeast-1`  
**Identity:** SSO `FoodLabelFlowStagingLogin`, then the preconfigured `FoodLabelFlowStagingDeploy` role. Root credentials were not used.  
**Config:** the active `AWS_CONFIG_FILE` selected the course-directory config; commands used explicit `foodlabelflow-staging` or `foodlabelflow-staging-deploy` profiles. A dead local proxy at `127.0.0.1:7892` initially blocked STS; AWS calls succeeded after unsetting proxy variables for those individual commands. The shell environment was not changed.

## Live resource state

All listed resources carry `Project=FoodLabelFlow`, `Environment=staging`, `Owner=STCN-39`, and/or `ManagedBy=Terraform` tags where the service exposes them. The VPC, EKS cluster, RDS database, NAT gateway and ALB are project staging resources owned/tagged by STCN-39. They are not modified by this check.

| Resource | Observed state |
|---|---|
| VPC `spectrace-staging` | `available`, CIDR `10.40.0.0/16`. |
| EKS `spectrace-staging` | `ACTIVE`, Kubernetes `1.36`, private endpoint enabled, public endpoint disabled. |
| EKS node group `general` | `ACTIVE`, on-demand `t3.medium`, min/desired/max `1/1/3`; Auto Scaling reports one healthy `InService` node. |
| RDS `spectrace-staging-mysql` | `available`, MySQL `8.0.46`, `db.t3.small`, Multi-AZ, private, encrypted, 20 GiB, one-day backup retention. Its security group permits TCP/3306 from the EKS node security group only. |
| NAT gateway | One `available` NAT gateway in the staging VPC, tagged for FoodLabelFlow staging / STCN-39 / Terraform. |
| ALB `spectrace-staging-alb` | `active`, **internal** scheme. AWS returned zero listeners and zero target groups; its attached security group has no ingress rules. There is therefore no active Gateway route through this ALB in this snapshot. |

The Kubernetes API is private-only. A temporary kubeconfig was created under `/tmp` and removed after a read-only `kubectl get pods,deployments,services,ingresses -n staging` attempt timed out against the private API endpoint. This is a workstation network-reachability failure; Kubernetes authorization and workload inventory remain **UNKNOWN**, not “empty.” No public endpoint or network rule was added.

## Budget and billing evidence

The AWS Budget `FoodLabelFlow-Staging-Monthly-USD-120` has a **USD 120/month** limit. At 14:06 +08:00 on 14 Oct, AWS Budgets reported actual spend `USD 0.00` and forecast spend **USD 149.085**, which exceeds the limit by **USD 29.085**. Its returned notifications are actual-spend thresholds at 50%, 80%, and 100%, all currently `OK`; no forecast notification was returned.

Cost Explorer for 1–15 Oct returned an estimated value of approximately `-USD 0.0000000801` for unblended, net unblended, and amortized cost. That estimated result conflicts with the AWS Budget forecast and is not evidence of zero resource cost or available credits. The live AWS credit balance was not obtained. Treat the forecast as a stop condition until the AWS account owner reconciles billing/credits and approves any window.

## Access and safety outcomes

- The SSO login role was confirmed in the correct account but was denied `eks:DescribeCluster`, RDS descriptions, ALB descriptions, `budgets:ViewBudget`, and `ce:GetCostAndUsage`. The configured `FoodLabelFlowStagingDeploy` role was then assumed and the read-only queries above succeeded.
- The local workstation cannot reach the private Kubernetes API. Use an already-authorized VPC-connected CloudShell/bastion/runner for workload inventory and live probes; do not expose the EKS endpoint.
- Terraform state ownership is still unverified (`infra/terraform/backend.hcl` is not present; only the example backend file is committed). Do not initialize against a guessed state backend.
- No Terraform plan/apply, AWS resource mutation, Kubernetes write, GitHub Environment change, or destroy was performed. The forecast currently exceeds the monthly limit, and the resources are tagged to STCN-39; cleanup requires the AWS account owner and STCN-39/M1 coordination.

## GitHub deployment gate (observed 14:12 +08:00)

The repository's `staging` Environment was created during this evidence refresh. GitHub reports a required reviewer `hxj04121-lab`, a custom deployment branch policy allowing only `main`, and `can_admins_bypass=true`. The Environment currently has zero variables and zero secrets. The connected GitHub account reports `admin:false` and `maintain:false`, so it cannot add the required values/secrets or adjust protection. The branch-protection REST endpoint for `main` returned HTTP 404; branch protection is therefore not evidenced. No GitHub settings were changed by this check.

## Read-only recheck (15:27 +08:00)

The configured `FoodLabelFlowStagingDeploy` role still resolves to account `679857346122`. AWS Budgets still reports actual USD 0.00 and forecast USD 149.085 against the USD 120 monthly limit (budget data last updated 13:49 +08:00). EKS remains `ACTIVE`, Kubernetes 1.36, private endpoint enabled/public endpoint disabled. `eks list-addons` returns an empty managed add-on list; because Kubernetes API access is still unavailable, the self-managed VPC CNI and network policy agent configuration remain **UNKNOWN**. RDS remains `available`, MySQL 8.0.46, Multi-AZ and not publicly accessible. The internal ALB is active with zero listeners and zero target groups. No AWS or Kubernetes changes were made.

GitHub recheck found the same `staging` reviewer/branch policy and zero environment variables/secrets. The repository-level Actions secret name list contains `SONAR_TOKEN`; its value was not read. The `main` branch-protection endpoint still returns HTTP 404. No GitHub setting or secret was changed.

## Read-only recheck (15:31 +08:00)

Using the configured `foodlabelflow-staging-deploy` profile, STS again confirmed assumed role `FoodLabelFlowStagingDeploy` in account `679857346122`; no Root credentials were used. AWS Budgets reported actual USD 0.00 and forecast USD 149.085 against the USD 120 monthly limit (budget last updated 13:49 +08:00). EKS is still `ACTIVE`/1.36/private-only; `list-addons` returned no managed add-ons. RDS remains available MySQL 8.0.46, Multi-AZ and private. The internal ALB is active with no listeners or target groups. A fresh `kubectl get pods,deployments,services,ingresses -n staging` attempt timed out against the private API; workloads and self-managed VPC CNI network-policy-agent state remain **UNKNOWN**. No AWS/Kubernetes mutation was made.

## Reproduction

Use the configured non-root SSO profile and role chain, region `ap-southeast-1`; remove the stale proxy only for the AWS CLI process if it still points at `127.0.0.1:7892`. Read-only calls included STS identity, EKS cluster/node-group descriptions, RDS and ALB descriptions, listener/target-group queries, scoped security-group/VPC/NAT queries, AWS Budgets, and Cost Explorer. Do not copy session tokens or generated kubeconfig contents into evidence.

## Read-only recheck (16:27 +08:00)

Using `foodlabelflow-staging-deploy`, STS confirmed `arn:aws:sts::679857346122:assumed-role/FoodLabelFlowStagingDeploy/...`; no Root credentials were used. AWS Budgets still reports actual USD 0.00 and forecast USD 149.085 against the USD 120 monthly limit. EKS `spectrace-staging` remains ACTIVE on Kubernetes 1.36 with authentication mode `API`, `endpointPrivateAccess=true`, and `endpointPublicAccess=false`; RDS remains available, MySQL 8.0.46, Multi-AZ and private. The internal ALB is active with no listeners. One target group named `spectrace-staging-gateway` exists, but its `LoadBalancerArns` list is empty, so it is not attached to the ALB. The current kube context is the expected EKS ARN, but `kubectl get namespaces --request-timeout=8s` timed out waiting for the private API endpoint; Kubernetes authorization and workload state remain UNKNOWN.

The account's IAM OIDC-provider list contains only `oidc.eks.ap-southeast-1.amazonaws.com/...`; no GitHub Actions provider is listed. `iam:GetRole` for the assumed deployment role is denied, so its trust document remains UNKNOWN. GitHub Environment `staging` is restricted to `main`, requires reviewer `hxj04121-lab`, has `prevent_self_review=false`, and has zero variables/secrets. The `main` branch-protection endpoint still returns 404. No AWS, Kubernetes, GitHub Environment, or repository setting was changed.

## Read-only recheck (16:56 +08:00)

Using profile `foodlabelflow-staging-deploy`, STS confirmed the non-root assumed role `FoodLabelFlowStagingDeploy` in account `679857346122`. AWS Budgets returned actual spend USD 0.00 and forecast USD 149.085 against the USD 120 monthly limit. EKS `spectrace-staging` remains ACTIVE on Kubernetes 1.36, authentication mode `API`, private endpoint enabled and public endpoint disabled. `kubectl get namespaces --request-timeout=8s` timed out against the private endpoint; workloads and Kubernetes authorization remain UNKNOWN. RDS `spectrace-staging-mysql` remains available, private MySQL 8.0.46, Multi-AZ.

The internal ALB `spectrace-staging-alb` remains active with zero listeners. The `spectrace-staging-gateway` target group has zero attached load balancers. IAM lists only the EKS OIDC provider; no `token.actions.githubusercontent.com` provider is present. `iam:GetRole` for `FoodLabelFlowStagingDeploy` returned AccessDenied, so its trust policy remains UNKNOWN. GitHub Environment `staging` is restricted to the `main` branch and requires reviewer `hxj04121-lab`; `prevent_self_review=false`, and the Environment has zero variables and secrets. The `main` branch protection endpoint returned HTTP 404. No AWS, Kubernetes, or GitHub settings were changed.
