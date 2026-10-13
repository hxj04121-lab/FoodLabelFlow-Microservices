# ADR-G2-M2-002: exact Label business-version evidence

Status: **M2 ACCEPTED DESIGN — M4 producer implementation and joint transition evidence pending** (12 October 2026).

The frozen `LabelPublished.v1` carries `labelVersionId` but omits business `versionNumber`.
Its envelope `aggregateVersion` orders events and cannot substitute for this value. The existing
Label Workflow `GET /api/labels/{labelVersionId}` response includes `versionNumber`, but the
consumer's service identity and tenant-scoped read permission have not been agreed or implemented.
M4's starter security module is now available in PR #36; the Label Workflow business provider and
real-realm audience are still pending.

M4 proposed and M2 accepted the event-based resolution path on 12 October: keep v1 unchanged and add
`LabelPublished.v2` with a positive integer `payload.versionNumber`, taken from the same authoritative
Label publication record/current-version uniqueness guard as the committed outbox event. Retain
`labelVersionId` as the exact immutable identity; `aggregateVersion` remains event ordering metadata.
M2 implements a separate v2 schema/example and consumer path in #41. These are candidate shared
contract artifacts pending M4's producer/schema review; they do not claim that M4 has shipped v2.

## Preferred decision for M4 review

M2 proposes a new versioned publication event, `LabelPublished.v2`, carrying the exact positive Label
business `payload.versionNumber` alongside the existing immutable `labelVersionId` and owning
`organisationId`. This preserves the architecture's asynchronous propagation and avoids a new network
dependency inside the Compliance projection transaction. M4 should prepare this authoritative value
from the same committed Label publication record used for its outbox. The number must never come from
the event sequence or from parsing an ID.

M4 must next wire `versionNumber` from the committed publication record into its outbox atomically,
validate the actual Label uniqueness/current-version guard, review/own the v2 producer contract, and
provide publication/replay evidence. A producer must not emit a guessed or event-sequence-derived
version. M2 validates a positive integral version and exact `aggregateId`/`labelVersionId` identity;
the shared envelope's `organisationId` remains the tenant boundary. The existing consumer identity is
kept so this in-place consumer upgrade does not replay the entire queue under a new deduplication key.

The migration keeps the frozen v1 schema and consumer behavior: v1 uses the exact authoritative
resolver and fails closed if that provider is unavailable. Existing uncommitted v1 messages may be
replayed only after the authoritative resolver is available, or M4 may republish the exact committed
Label state as v2 with a fresh event ID and the proper aggregate sequence. Never discard a v1 DLQ item,
invent a version, or assume that a CI fixture is a production provider. Events that already committed
remain protected by the existing processed-event/aggregate-version idempotency checks.

`GET /api/labels/{labelVersionId}` remains part of M4's existing public API scope. Its implementation
does not imply approval to call it synchronously from this consumer. An HTTP resolver would require
an explicit architecture exception, service identity, tenant-scoped permission, timeout/retry policy
and NetworkPolicy edge. It is therefore a fallback requiring a revised accepted decision.

This records M2's acceptance of the design direction, **not an accepted/deployed shared producer
contract**. #41 leaves the frozen v1 schema/example unchanged and adds separately versioned v2
candidate files; M4 producer implementation, independent review, staging publication, and replay
acceptance remain outstanding. No network lookup is enabled for v2.

Until approval/provider delivery, the default port fails closed. MySQL integration tests must prove no
processed-event cursor, Label projection or Formula mutation survives that failure. A provider fixture
returning business version 4 for aggregateVersion 9 proves the two concepts remain distinct; this is
consumer-contract evidence, not evidence of a deployed M4 provider.

## Active rule-set lookup for draft creation

M2 now publishes the Compliance-owned internal lookup in PR #41:
`GET /internal/rule-sets/effective?jurisdiction={code}`. Label Workflow calls it with the actor JWT
that authorized draft creation, and pins the returned exact `ruleSetVersionId` to the new draft.
The database model owns rule sets by jurisdiction (not by product or organisation), so the endpoint
does not accept either as an override. It accepts only a `LABEL_OFFICER` with `LABEL.CREATE` and
returns the single version named by Compliance's explicit per-jurisdiction effective-rule-set
pointer, only while that version remains `ACTIVE` and effective on the current UTC date. A missing,
inactive, or out-of-date pointer returns 422 without falling back to another row. The pointer avoids
ambiguous current rules where compatibility seeds intentionally keep older identifiers `ACTIVE` for
already pinned snapshots. The endpoint is read-only and fails closed; Label Workflow must not read
Compliance's database or replace the pinned version during later validation.

This gives M4 a concrete provider contract to implement in `RuleSetVersionResolver`. M4's HTTP client
must forward the same verified actor Bearer token and correlation ID. The owner-side adapter and the
cross-service draft-create test still need to be added in PR #51; this M2 provider candidate does not
claim that M4 has integrated or accepted it.

Related: STCN-71, STCN-73, STCN-95, STCN-117; M4 STCN-34/14/19.
