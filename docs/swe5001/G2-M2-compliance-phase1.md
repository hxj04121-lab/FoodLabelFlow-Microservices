# G2-M2 Compliance Phase 1

**State:** implementation draft; CI and human acceptance are pending.

This change implements the Compliance-owned validation and projection slice for STCN-12 / STCN-69–73: seeded allergen and rule data, idempotent validation runs, immutable Specification/Formula/Label projections, duplicate/stale event handling, and the read-only Phase 1 `SpecificationPublished → POTENTIAL` matcher. Unresolved components remain blocking review findings. The matcher does not write Formula state.

## Label business-version contract gap

`LabelPublished.v1` identifies a label with `payload.labelVersionId`, but its frozen payload has no business `versionNumber`. The canonical event envelope defines `aggregateVersion` as the aggregate state version used for event ordering and stale-event rejection. It is not evidence of a Label business version. The example's values happen to align, but that does not define the relationship.

`ComplianceEventProjector` therefore asks `LabelVersionNumberResolver` for the version using the exact `(organisationId, labelVersionId)` pair. The default resolver fails closed until an authoritative CN Label provider adapter is installed. It never substitutes `aggregateVersion`, parses the ID, or queries a legacy FOOD API. A future adapter must be backed by a reviewed CN provider contract and verify the exact ID and tenant.

As a result, Label events cannot currently create a Label projection or Phase 1 finding in this repository. This is an explicit upstream contract/provider blocker. Do not treat the matcher unit golden as end-to-end Label integration evidence.

## Other integration dependencies

- G1-M1 and G1-M2 foundations and G2-M1 producer/staging work (including STCN-115) must be available for end-to-end projections.
- The validation endpoint remains fail-closed until M4 provides the real identity adapter.
- M5 owns runtime queue topology; this change does not create production queues.
- Reviewer acceptance and the required CI evidence remain outstanding.

## Local verification

With the installed JDK 25 runtime (the Maven modules compile with `--release 21`):

```powershell
mvn -B -ntp -pl services/compliance -am -DskipTests compile
mvn -B -ntp -pl services/compliance '-Dtest=ComplianceEventProjectorLabelVersionTest,PhaseOneImpactMatcherTest' test
```

The latest local run passed compilation and all 6 targeted tests. `ComplianceEventProjectorLabelVersionTest` proves an event at `aggregateVersion=9` stores the provider's exact-ID result `versionNumber=4`, and proves the default resolver performs no writes when no verified provider is configured. `PhaseOneImpactMatcherTest` covers the soy-lecithin golden and matcher outcomes.

Container integration tests have not passed locally. Docker Desktop was launched, but WSL returned `E_ACCESSDENIED` and the Docker engine named pipe remained unavailable; no security or virtualization settings were changed. CI/Testcontainers evidence is still required.
