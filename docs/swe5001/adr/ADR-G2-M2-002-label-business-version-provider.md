# ADR-G2-M2-002: exact Label business-version evidence

Status: **PROPOSED — requires M4 provider approval and M2 contract acceptance** (11 October 2026).

The frozen `LabelPublished.v1` carries `labelVersionId` but omits business `versionNumber`.
Its envelope `aggregateVersion` orders events and cannot substitute for this value. The existing
Label Workflow `GET /api/labels/{labelVersionId}` response includes `versionNumber`, but the
consumer's service identity and tenant-scoped read permission have not been agreed or implemented.
No Label Workflow business implementation or security module is present in the current remote branches.

Minimal request to M4: provide an authoritative adapter for
`LabelVersionNumberResolver.resolve(organisationId, labelVersionId)`, resolving the exact immutable
published Label ID, verifying the owning organisation and publication state, and returning its positive
business version. Confirm the service identity, issuer/audience/roles, endpoint and error/retry semantics.
An absent record, tenant mismatch, non-published state, timeout or invalid response must throw and
roll back the consumer transaction; retries may enter the configured DLQ. Do not use a legacy FOOD
endpoint or another service's database. A local bootstrap must preserve the same authoritative values.

The existing GET contract is one compatible candidate; approval must also resolve its added synchronous
event-consumption lookup against the architecture's no-business-fan-out rule. An alternative is a new
versioned publication event containing the business version, introduced only with approved ADR and
schemas. This PR changes **no frozen v1 public contract** and selects neither unapproved option.

Until approval/provider delivery, the default port fails closed. MySQL integration tests must prove no
processed-event cursor, Label projection or Formula mutation survives that failure. A provider fixture
returning business version 4 for aggregateVersion 9 proves the two concepts remain distinct; this is
consumer-contract evidence, not evidence of a deployed M4 provider.

Related: STCN-71, STCN-73, STCN-95, STCN-117; M4 STCN-34/14/19.
