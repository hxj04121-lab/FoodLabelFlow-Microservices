# AWS Staging Deployment Progress

Updated: 2026-10-11 (Asia/Shanghai). This report records observed repository, GitHub, and AWS state. Billing data can lag behind provisioning.

## Current status

**Staging infrastructure is deployed. Application services are not deployed or verified.** Account `679857346122`, region `ap-southeast-1`; Terraform backend is the persistent state bucket and the final refresh plan reports no changes.

## Completed

- Used the non-root `FoodLabelFlowStagingDeploy` role through IAM Identity Center for Terraform. Root was used only to update that role's narrowly scoped inline policy after AWS returned specific missing-action diagnostics: the current EKS AMI `RunInstances` permission is tied to its launch template and region, the EKS nodegroup service role can be read, nodegroup service-linked role creation is service-name restricted, and Auto Scaling tag reads are region restricted.
- Created the persistent S3 state bucket `foodlabelflow-staging-tfstate-679857346122-ap-southeast-1`; versioning is enabled, and the bucket is separate from the staging stack.
- Deployed VPC, two public and two private subnets, one NAT Gateway, private EKS cluster `spectrace-staging` (Kubernetes 1.36), one `t3.medium` managed node group (`general`, desired/min 1, max 3), encrypted private Multi-AZ MySQL `db.t3.small`, CloudWatch RDS log groups, Secrets Manager credential secret, internal ALB, and gateway target group. The node group is currently one healthy instance; the max of 3 is only a ceiling and the Cluster Autoscaler add-on is disabled.
- Live reads: EKS cluster `ACTIVE`, node group `ACTIVE` with no reported health issues, and its single `t3.medium` instance `InService` / `Healthy`; RDS `available` / Multi-AZ / encrypted / not publicly accessible; ALB `active` / internal with no listeners. The EKS endpoint is private-only; `kubectl get nodes` from this workstation timed out, so Kubernetes node readiness was not verified. `eks:ListAddons` was not granted to the deployment role, so the add-on inventory was not checked.
- The DB secret has an `AWSCURRENT` version. Its value was not read or printed.
- Terraform fixes in this branch: removed a TLS certificate thumbprint lookup that returned multiple CA certificates (the AWS provider supports an omitted EKS OIDC thumbprint); removed the now-unused TLS provider; added a `moved` block for the existing CNI policy attachment so Terraform does not detach it during the resource-address change; ignored inline ingress drift on the node security group because its control-plane ingress is managed by a separate rule and the provider attempted to revoke it. The latter means Terraform will not detect other ingress drift on that security group.
- `terraform fmt`, `terraform validate`, and a fresh `terraform plan -detailed-exitcode` passed. Final plan: **No changes**. Saved plan and kubeconfig are outside the repository under `/Users/shj/.codex/aws-state/foodlabelflow-staging/` with restrictive local permissions.

## AWS budget and cost

- `FoodLabelFlow-Staging-Monthly-USD-120` remains configured at USD 120/month. The latest AWS Budget read still reports actual USD 0.00 and forecast USD 0.042; Cost Explorer returned `-0` through 2026-10-11 even though resources have now been provisioned. These figures have not caught up and are not a spending cap.
- The repository's prior pricing snapshot estimated USD 5.87 for an 8-hour baseline window and USD 372.81 for 30 days continuously. Those are planning estimates, not a live quote; no fresh pricing calculation was made during apply.
- No expiry or automatic destroy is configured. The environment continues to incur costs until the staging Terraform stack is destroyed. The separate state bucket must be retained.

## Application readiness

- `deploy/helm/spectrace-service/values.yaml` still uses `example.invalid/spectrace/service:replace-with-commit-sha`. The chart's service-specific values configure network policy but do not supply publishable image references.
- The ALB is internal and has no public HTTPS listener because domain, ACM certificate, and ingress CIDRs are not configured. The GitHub workflow is plan-only; no staging Apply or application deployment workflow exists.
- No application release, health check, database migration, integration test, or acceptance test was run. The EKS add-on module available on the PR branch defaults to disabled until domain, certificate, and ingress CIDRs are supplied. Deploying services requires real image references and a reachable deployment path (private workstation access such as VPN/bastion, or a reviewed GitHub deployment runner in the VPC), plus finalized application-to-RDS settings.

## Pull requests

Live GitHub CLI reads on 2026-10-11:

| PR | State | Checks / review | Merge status |
| --- | --- | --- | --- |
| [#10](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/pull/10) | Merged | Required checks passed; review decision field still reports `CHANGES_REQUESTED` on the merged PR. | Merged. |
| [#18](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/pull/18) | Merged | Checks passed; review decision `APPROVED`. | Merged. |
| [#27](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/pull/27), head `6fb4060` | Open | Terraform validate, Helm, budget, backend/frontend/platform CI, security, container and Sonar checks passed; review required. | Behind base branch; not merged. |

PR #27 needs the required reviewer and base-branch synchronization. Do not self-approve or merge it.

## Remaining work and risks

1. **Application deployment:** publish immutable service images, replace placeholder chart values, configure RDS secret references and migrations, establish private connectivity, and add a reviewed release workflow.
2. **Service verification:** run `kubectl get nodes` and application health/integration/acceptance checks from a network that can reach the private EKS endpoint.
3. **PR #27:** commit/push the local Terraform fixes and report update, synchronize the PR with its base safely, then obtain the required review. It remains open and behind.
4. **Cost control:** refresh Budget and Cost Explorer after their reporting delay, calculate the actual run window, and arrange an explicit destroy time. There is no automatic expiry. Destroy only the staging Terraform stack; retain the state bucket and Identity Center setup.

Milestones: **Terraform infrastructure deployed**; **Services healthy** not verified; **Acceptance tests passed** not started; **Resources destroyed** not started.
