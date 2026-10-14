# First Gateway staging release

The protected workflow `.github/workflows/deploy-gateway.yml` is manual-only and accepts dispatches from `main`. It installs the real Gateway chart into the already-existing `gateway` namespace, pinned to the reviewed GHCR digest. It does not create namespaces or AWS infrastructure. GitHub's required-reviewer approval on the `staging` Environment is the release-time write approval.

## GitHub configuration required before dispatch

In **Repository → Settings → Environments → staging**, retain the required reviewer and configure these **Environment variables** from the actual staging deployment:

| Variable | Required value |
| --- | --- |
| `AWS_DEPLOY_ROLE_ARN` | ARN of the existing `FoodLabelFlowStagingDeploy` role in account `679857346122` |
| `GATEWAY_ISSUER` | Actual external HTTPS issuer ending `/auth/realms/spectrace` |
| `GATEWAY_JWK_SET_URI` | Actual in-cluster Keycloak Service URI ending `/auth/realms/spectrace/protocol/openid-connect/certs` |
| `GATEWAY_KEYCLOAK` | Actual Keycloak Service DNS name, in `keycloak` namespace, TCP/8080 |
| `GATEWAY_SPECIFICATION` | Actual Specification Service DNS name, in `specification` namespace, TCP/8080 |
| `GATEWAY_FORMULATION` | Actual Formulation Service DNS name, in `formulation` namespace, TCP/8080 |
| `GATEWAY_COMPLIANCE` | Actual Compliance Service DNS name, in `compliance` namespace, TCP/8080 |
| `GATEWAY_LABEL_WORKFLOW` | Actual Label Workflow Service DNS name, in `label-workflow` namespace, TCP/8080 |

Service values use `http://<actual-service>.<namespace>.svc.cluster.local:8080`. Do not enter a guessed hostname: the workflow checks that every named Kubernetes Service exists before installing Gateway. Issuer discovery and the configured JWKS endpoint are fetched and verified before the install. The exact CORS origin, realm, audience, and route roles are set in the chart values from the reviewed repository configuration.

In the same protected Environment, configure at least one **Environment secret**:

- `AVAILABLE_CREDIT_USD`: actual verified AWS credit balance, if the account has available credit; or
- `APPROVED_STAGING_SPEND_USD`: finite USD ceiling explicitly approved by the AWS account owner for this staging window.

The existing fail-closed pricing/funding gate accepts the sum of those sources; it does not claim that approved spend is a credit balance or limit actual AWS billing. No value is committed to Git. The staging window in this workflow is the requested 24 hours, with 4 experiment hours and 2 k6 hours.

## AWS and runner prerequisites

- The `staging-vpc` self-hosted GitHub runner must be online and connected to the private EKS API and the required staging services.
- The existing deploy role's OIDC trust must accept only this repository's `staging` Environment subject (`repo:hxj04121-lab/FoodLabelFlow-Microservices:environment:staging`) and `token.actions.githubusercontent.com` audience. The workflow receives no long-lived AWS credentials.
- The role must already authorize the existing cluster, with namespaced create for Deployments, Services, ServiceAccounts, NetworkPolicies, and Helm release Secrets; get/list/watch for rollout and evidence reads; and `create pods/portforward` for the private HTTP smoke check. This workflow does not modify IAM or Kubernetes RBAC.
- The Gateway GHCR package must be pullable by the EKS nodes without an unapproved Kubernetes pull secret. The exact immutable image is rendered and checked before installation.

If any prerequisite is missing, the protected workflow fails closed before `helm install`. A successful release reports process readiness only; JWT login and authenticated upstream business calls still require the Keycloak and business-service acceptance flow.
