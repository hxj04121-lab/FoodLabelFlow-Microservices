# ADR-G2-M2-002: exact Label business-version evidence

Status: **PROPOSED — requires M4 provider approval and M2 contract acceptance** (11 October 2026).

The frozen `LabelPublished.v1` carries `labelVersionId` but omits business `versionNumber`.
Its envelope `aggregateVersion` orders events and cannot substitute for this value. The existing
Label Workflow `GET /api/labels/{labelVersionId}` response includes `versionNumber`, but the
consumer's service identity and tenant-scoped read permission have not been agreed or implemented.
M4's starter security module is now available in PR #36; the Label Workflow business provider and
real-realm audience are still pending.

Minimal request to M4: provide an authoritative adapter for
`LabelVersionNumberResolver.resolve(organisationId, labelVersionId)`, resolving the exact immutable
published Label ID, verifying the owning organisation and publication state, and returning its positive
business version. Confirm the service identity, issuer/audience/roles, endpoint and error/retry semantics.
An absent record, tenant mismatch, non-published state, timeout or invalid response must throw and
roll back the consumer transaction; retries may enter the configured DLQ. Do not use a legacy FOOD
endpoint or another service's database. A local bootstrap must preserve the same authoritative values.

## Preferred decision for M4 review

M2 proposes a new versioned publication event, `LabelPublished.v2`, carrying the exact positive Label
business `payload.versionNumber` alongside the existing immutable `labelVersionId` and owning
`organisationId`. This preserves the architecture's asynchronous propagation and avoids a new network
dependency inside the Compliance projection transaction. M4 should prepare this authoritative value
from the same committed Label publication record used for its outbox. The number must never come from
the event sequence or from parsing an ID.

The next concrete step is M4 confirmation of that producer field and migration sequence, followed by
M2/M4 acceptance of this ADR and a separately reviewed v2 schema/example/consumer change. Required
consumer checks are a positive integral version, matching envelope/payload organisation, exact Label
identity, duplicates/stale ordering, and rollback on malformed or unavailable evidence. A transition
must preserve the frozen v1 route and define how existing v1 events acquire authoritative versions
before they can leave the DLQ; it must not silently discard them or synthesize a version.

`GET /api/labels/{labelVersionId}` remains part of M4's existing public API scope. Its implementation
does not imply approval to call it synchronously from this consumer. An HTTP resolver would require
an explicit architecture exception, service identity, tenant-scoped permission, timeout/retry policy
and NetworkPolicy edge. It is therefore a fallback requiring a revised accepted decision.

This is a concrete proposed direction for the producer owner, **not an accepted/deployed v2 contract**.
This PR changes **no frozen v1 public contract**. No v2 schema is installed and no network lookup is
enabled before joint approval.

Until approval/provider delivery, the default port fails closed. MySQL integration tests must prove no
processed-event cursor, Label projection or Formula mutation survives that failure. A provider fixture
returning business version 4 for aggregateVersion 9 proves the two concepts remain distinct; this is
consumer-contract evidence, not evidence of a deployed M4 provider.

Related: STCN-71, STCN-73, STCN-95, STCN-117; M4 STCN-34/14/19.
