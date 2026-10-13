# M2 shared-security and producer integration evidence — 11 October evening

Verified 2026-10-11T21:02:01.903573+08:00. Source PR #30 HEAD `859e740d0f477eced10b62c7aa853131497231fe`; [actual PR CI](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/actions/runs/38140944709), artifact `platform-test-reports`. Java 21, real MySQL/Rabbit Testcontainers and real Keycloak 26.0.7. Compliance: **49 tests; 0 failures/errors/skips**. Complete shared reactor: **140 tests; 0 failures/errors/skips**. These are CI results; the present local full run was blocked by Docker Desktop's inference-socket startup failure. Local compilation and two configuration tests passed separately.

| Suite | Tests | Failures | Errors | Skipped |
|---|---|---|---|---|
| com.spectrace.compliance.authorization.FailClosedAuthorizationConfigurationTest | 2 | 0 | 0 | 0 |
| com.spectrace.compliance.ComplianceApplicationTest | 20 | 0 | 0 | 0 |
| com.spectrace.compliance.ComplianceImpactHttpTest | 9 | 0 | 0 | 0 |
| com.spectrace.compliance.ComplianceKeycloakHttpTest | 3 | 0 | 0 | 0 |
| com.spectrace.compliance.impact.PhaseOneImpactMatcherTest | 7 | 0 | 0 | 0 |
| com.spectrace.compliance.impact.PhaseTwoImpactMatcherTest | 3 | 0 | 0 | 0 |
| com.spectrace.compliance.impact.ReleasedVersionCacheTest | 2 | 0 | 0 | 0 |
| com.spectrace.compliance.projection.ComplianceEventProjectorLabelVersionTest | 3 | 0 | 0 | 0 |

The real IdP test imports the committed production realm, uses authorization code + PKCE and the configured external issuer, then calls Compliance with the actual signed access token. No direct grant, test-only user/role/mapper, raw token, credential, cookie or authorization code is added to evidence. The fixture rewrites only the test HTTP transport to the randomly exposed container port.

Failures retained as evidence: run 38139282792 exposed numeric-sub coercion; run 38140413367 verified signatures/issuer/audience but legitimate callers received 401; the unmodified realm scope lacked the native subject mapper. A follow-up subject-presence assertion and final production `oidc-sub-mapper` correction retain strict identity validation. The mapper uses the immutable Keycloak user ID and is proposed for M4 review, not deployed to staging. [Pinned mapper implementation](https://github.com/keycloak/keycloak/blob/26.0.7/services/src/main/java/org/keycloak/protocol/oidc/mappers/SubMapper.java).

M1 #26 at 34f7661 is merged normally into #28/#30: actual Specification/Formulation producer/outbox/seed-replay tests coexist with Compliance tests. The two overlapping service smoke tests preserve M1's protected API anonymous-401 check; the CI trigger conflict retains both members' branch patterns. This does not claim one live producer-to-Label-to-ReviewTask chain or a loaded 5000/20 experiment dataset.

All frozen v1 contracts, performance/availability targets, migration checks and quality/security thresholds remain enforced. AWS load, pod deletion and measured bottleneck results are **BLOCKED / NOT RUN**.
