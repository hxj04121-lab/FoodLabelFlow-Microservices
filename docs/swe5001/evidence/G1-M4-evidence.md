# G1-M4 evidence: realm, starter security, NetworkPolicy

Work order: [G1-M4](../../../.project-control/work-orders/G1/M4-keycloak-security-module.yaml)
(STCN-9, owner M4 Zhu Wenyu, reviewer M3 Xu Feiyang). Recorded 2026-10-12.

| Subtask | Artifact | Test that fails when it regresses | State |
|---|---|---|---|
| G1-M4.1 realm as code | `deploy/keycloak/realm/spectrace-realm.json`, `docker-compose.keycloak.yml`, `claims/sample-access-token-claims.json` | `KeycloakRealmContractTest` (19 tests, backend job) | code complete on `codex/g1-m4.1-realm-as-code`; awaiting review |
| G1-M4.2 starter security module | `platform/starter/.../security/*`, `.../autoconfigure/StarterSecurityAutoConfiguration.java` | `SecurityModuleUnitTest`, `OrganisationSecurityIntegrationTest`, `LegacyHeaderBridgeTest`, `NegativeAuthKitTest` | code complete on `feature/g1-m4.2-starter-security` (merged with main); awaiting review. Exactly four paths stay open: the health probes, the metrics scrape (Alloy carries no token; the NetworkPolicy limits who can reach it) and `/error` |
| G1-M4.3 realm import into the cluster | `deploy/keycloak/operator/keycloak-realm-import.yaml` + generator | `KeycloakOperatorRealmImportContractTest` | needs the staging window: the operator is G1-M5 |
| G1-M4.4 NetworkPolicy allow-list | `docs/swe5001/security/networkpolicy-allowlist.md`, chart template and values | `HelmNetworkPolicyContractTest` | allow-list encoded; enforcement needs the VPC CNI in staging |
| G1-M4.5 Label Workflow skeleton and Flyway root | `services/label-workflow/**`, `V1__label_workflow_schema_root.sql` | `LabelWorkflowApplicationTest` (platform-services job) | code complete; the G2 domain tables follow from V2 |

## Acceptance criteria

| Criterion | Evidence |
|---|---|
| access tokens carry `org_id`, `org_type` and `roles` | the `spectrace-identity-claims` scope carries the attribute mappers, the role mappers and the `subject` (`oidc-sub-mapper`) mapper; `claims/sample-access-token-claims.json` records the shape; `verify-token-claims.py` asserts `sub`, `org_id`, `org_type` and the roles against a running Keycloak |
| legacy `X-Auth-*` headers work only in the local/test profile | `LegacyHeaderBridgeTest`: enabled only with the bridge switch, and the start-up fails unless every active profile is `local` or `test` (a mixed `local,staging` set is refused) |
| the allow-list covers gateway → services and label-workflow → compliance `/internal/**` only | `HelmNetworkPolicyContractTest` asserts the per-service `ingressFromServices` sets and that compliance is the only service another service may call; the `/internal/**` path scope itself is enforced in the service, which the document states |

## Required tests

- **token-claim tests**: `KeycloakRealmContractTest` (static realm/claims contract, 19 tests) and
  `deploy/keycloak/scripts/verify-token-claims.py` (real authorisation code + PKCE login per demo
  user; asserts the pinned `sub`, `org_id`, `org_type` and the business roles).
- **401/403 through the negative-test kit**: `NegativeAuthKitTest` covers the kit itself; the
  Specification, Formulation and Compliance application tests drive their own endpoints with kit
  tokens (anonymous 401, authenticated 404, cross-organisation 403) and Label Workflow asserts the
  authenticated 404 on its skeleton.

## Measured (2026-10-12, development machine)

| Command | Result |
|---|---|
| `mvn -B -ntp -f backend/pom.xml test -Dtest=KeycloakRealmContractTest` | 19/19 PASS |
| `mvn -B -ntp -f backend/pom.xml test -Dtest=HelmNetworkPolicyContractTest` | 3/3 PASS |
| `mvn -B -ntp -f backend/pom.xml test -Dtest=KeycloakOperatorRealmImportContractTest` | 3/3 PASS |
| `python deploy/keycloak/scripts/verify-token-claims.py --self-check` | PASS |
| `python deploy/keycloak/scripts/generate-realm-import.py --check` | PASS |
| `mvn -B -ntp -pl platform/starter -am test` (messaging integration excluded: needs Docker) | 44/44 PASS |
| `mvn -B -ntp test-compile` (root reactor, 7 modules) | BUILD SUCCESS |

## Not measured, and who can close it

| Gap | Why it is open | How to close it |
|---|---|---|
| a real token's `sub`, `org_id`, `org_type`, `roles` | the development sandbox cannot reach Docker, so no Keycloak was started here | `docker compose -f deploy/keycloak/docker-compose.keycloak.yml up -d keycloak`, then `verify-token-claims.py` |
| Specification, Formulation, Compliance and Label Workflow application tests, `MessagingIntegrationTest` | MySQL and RabbitMQ come from Testcontainers | the `platform-services` job of CI |
| the operator realm import result | needs the cluster and M5's operator install | apply `keycloak-realm-import.yaml` in the first window and read the resource condition |
| NetworkPolicy enforcement | needs the VPC CNI | the staging window; the values file is the allow-list |
