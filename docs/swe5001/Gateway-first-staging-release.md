# First Gateway staging release

The protected workflow `.github/workflows/deploy-gateway.yml` is manual-only and accepts dispatches from `main`. It installs the real Gateway chart into the already-existing `gateway` namespace, pinned to the reviewed GHCR digest. It does not create namespaces or AWS infrastructure. GitHub's required-reviewer approval on the `staging` Environment is the release-time write approval.

## GitHub configuration required before dispatch

In **Repository → Settings → Environments → staging**, retain the required reviewer and configure these **Environment variables**:

| Variable | Required value |
| --- | --- |
| `AWS_DEPLOY_ROLE_ARN` | ARN of the existing `FoodLabelFlowStagingDeploy` role in account `679857346122` |
| `GATEWAY_ISSUER` | Actual externally reachable HTTPS origin plus `/auth/realms/spectrace`, matching the Keycloak `KC_HOSTNAME` configuration |

The runtime renderer derives the JWKS path from this issuer and uses the existing internal Keycloak Operator contract (`spectrace-keycloak` CR → `spectrace-keycloak-service`, namespace `keycloak`, HTTP/8080). It derives the four business Service DNS names from the shared Helm chart's `<release>-<serviceName>` helper and the reviewed release names. These are routing configuration, not evidence that those Services are deployed. Initial rollout checks only the Gateway process and its HTTP readiness; it does not query Keycloak discovery, fetch JWKS, or require business Services. Authenticated login and upstream business acceptance remain a separate E2E milestone after those workloads are installed.

The exact CORS origin, realm, audience, and route roles are set in the chart values from the reviewed repository configuration. The Gateway JWT validation remains enabled. A request without a valid token to protected API paths must still receive 401.

In the same protected Environment, configure at least one **Environment secret**:

- `AVAILABLE_CREDIT_USD`: actual verified AWS credit balance, if the account has available credit; or
- `APPROVED_STAGING_SPEND_USD`: finite USD ceiling explicitly approved by the AWS account owner for this staging window.

The existing fail-closed pricing/funding gate accepts the sum of those sources; it does not claim that approved spend is a credit balance or limit actual AWS billing. No value is committed to Git. The staging window in this workflow is the requested 24 hours, with 4 experiment hours and 2 k6 hours.

## AWS and runner prerequisites

- The `staging-vpc` self-hosted GitHub runner must be online and connected to the private EKS API. Backend Services are not a prerequisite for process readiness.
- The existing deploy role's OIDC trust must accept only this repository's `staging` Environment subject (`repo:hxj04121-lab/FoodLabelFlow-Microservices:environment:staging`) and `token.actions.githubusercontent.com` audience. The workflow receives no long-lived AWS credentials.
- The role must already authorize the existing cluster, with namespaced create for Deployments, Services, ServiceAccounts, NetworkPolicies, and Helm release Secrets; get/list/watch for rollout and evidence reads; and `create pods/portforward` for the private HTTP smoke check. This workflow does not modify IAM or Kubernetes RBAC.
- The Gateway GHCR package must be pullable by the EKS nodes without an unapproved Kubernetes pull secret. The exact immutable image is rendered and checked before installation.

If any prerequisite is missing, the protected workflow fails closed before `helm install`. A successful release reports process readiness only; JWT login and authenticated upstream business calls still require the Keycloak and business-service acceptance flow.
