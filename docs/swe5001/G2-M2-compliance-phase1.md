# G2-M2 Compliance Phase 1

**State:** implementation draft; CI and human acceptance are pending.

This change implements the Compliance-owned validation and projection slice for STCN-12 / STCN-69–73: seeded allergen and rule data, idempotent validation runs, immutable Specification/Formula/Label projections, duplicate/stale event handling, and the read-only Phase 1 `SpecificationPublished → POTENTIAL` matcher. Unresolved components remain blocking review findings. The matcher does not write Formula state.

## Unresolved-result semantics

The frozen Compliance OpenAPI distinguishes a completed validation run from an impact classification: a completed validation with any blocking ERROR (including an unresolved formula component) is persisted as `FAILED` and returned as the normal idempotent result. Repeating the same tenant-scoped `Idempotency-Key` and snapshot returns that stored `FAILED` run; it is not a transport error. Separately, BR-03/BR-04 classify an ImpactFinding with unresolved evidence as `REVIEW_REQUIRED` (Phase 1 findings remain `POTENTIAL`). These two contracts both fail closed and are covered separately. The unresolved validation integration test asserts the stored-result replay; it requires MySQL/Testcontainers and was not run locally because Docker is unavailable.

## Label business-version contract gap

`LabelPublished.v1` identifies a label with `payload.labelVersionId`, but its frozen payload has no business `versionNumber`. The canonical event envelope defines `aggregateVersion` as the aggregate state version used for event ordering and stale-event rejection. It is not evidence of a Label business version. The example's values happen to align, but that does not define the relationship.

`LabelVersionNumberResolver` is currently only a port; the repository has no concrete provider implementation or agreed consumer authentication/configuration for an exact CN lookup. `ComplianceEventProjector` asks the port for the exact `(organisationId, labelVersionId)` pair. The default resolver fails closed before the first projection write; a provider that returns a non-positive number is also treated as an integration failure, not a malformed public request. The frozen LabelPublished event is valid v1, but it lacks data the downstream ImpactFinding contract requires. Through the shared `IdempotentConsumer`, either exception rolls back event bookkeeping and projection writes. Broker retries and dead-letter behavior depend on M5's actual queue topology and have not been verified here. The consumer never substitutes `aggregateVersion`, parses the ID, or queries a legacy FOOD API. A future adapter needs an agreed CN provider contract and exact tenant/ID verification.

As a result, Label events cannot currently create a Label projection or Phase 1 finding in this repository. This is an explicit upstream contract/provider blocker. Do not treat the matcher unit golden as end-to-end Label integration evidence.

## Other integration dependencies

- G1-M1 and G1-M2 foundations and G2-M1 producer/staging work (including STCN-115) must be available for end-to-end projections.
- The validation endpoint remains fail-closed until M4 provides the real identity adapter.
- M5 owns runtime queue topology; this change does not create production queues.
- Reviewer acceptance and the required CI evidence remain outstanding.

## Branch base and upstream evidence

This implementation branch was prepared from `feature/stcn49-compliance-starter` at PR24 head `ea27756b0c2426bba96712ff462d24bd07003320`; that is the intended base for a future G2-M2 draft PR. The local merge also carries PR21 head `861c52365c014331c1993b5de892d70fc41b0b73` as an explicit second parent because the frozen G0 contracts it supplies are still unmerged. Until PR21 is merged, a comparison against the G1 base necessarily includes that contract dependency as well as G2-M2.

PR21 has M1 and M4 approvals and successful exact-head CI, G0-contract and LabelPublished-contract workflows (runs `37801108474`, `37801108066`, `37801108246`). A single ordinary merge attempt using the expected head returned GitHub 403 `Resource not accessible by integration`; it remains open and unmerged. PR23 is open at head `be6f53c2bbf58a370fe41da7ac32ee419869f61f`; the connected GitHub reads exposed no status checks or PR workflow runs for that exact head. PR24 remains draft at head `ea27756b0c2426bba96712ff462d24bd07003320`, with no submitted reviews; its exact-head CI run `37904164865` succeeded. These upstream states are blockers to end-to-end adoption and do not replace this branch's own CI or reviewer acceptance.

## Local verification

With the installed JDK 25 runtime (the Maven modules compile with `--release 21`):

```powershell
mvn -B -ntp -pl services/compliance -am -DskipTests compile
mvn -B -ntp -pl services/compliance '-Dtest=ComplianceEventProjectorLabelVersionTest,PhaseOneImpactMatcherTest' test
```

The latest local run passed compilation and all 7 targeted tests. `ComplianceEventProjectorLabelVersionTest` proves an event at `aggregateVersion=9` stores the provider's exact-ID result `versionNumber=4`, and proves the default resolver and a non-positive provider response perform no writes. `PhaseOneImpactMatcherTest` covers the soy-lecithin golden, verifies serialized unresolved findings remain `POTENTIAL` + `REVIEW_REQUIRED`, and keeps explicit unresolved evidence. The Node contract scripts were not run in this checkout because `contracts/node_modules` is absent; PR21's successful exact-head workflows are upstream evidence, not G2-branch CI.

Container integration tests have not passed locally. Docker Desktop was launched, but WSL returned `E_ACCESSDENIED` and the Docker engine named pipe remained unavailable; no security or virtualization settings were changed. CI/Testcontainers evidence is still required.
