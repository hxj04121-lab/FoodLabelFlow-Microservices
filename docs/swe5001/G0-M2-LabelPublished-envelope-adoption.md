# LabelPublished canonical Envelope adoption candidate

M2 stewardship correction for the reproduced producer/schema incompatibility in
[M4 PR16](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/pull/16#issuecomment-6039649122).
This separate branch is based on M4 head
`f02a6f5238c1211c0a1cce8272a955717215b942`; the author's branch is untouched.

The event now composes the actual M1 canonical Envelope with the existing M4
payload and declaration definitions. Its producer is pinned to
`label-workflow-service`; the example uses that value. The former standalone
envelope accepted the old producer and weaker timestamp/identifier cases that
canonical rejected. Existing payload fields and declaration semantics are retained.

## Provider provenance and validation

`contracts/label-envelope-sources.v1.json` records the exact unmodified inputs:

- M1 PR8 `a788fa5fc815c34cb23cf219d5194d92a51937bb`,
  `contracts/events/event-envelope.v1.schema.json`, Git blob
  `909f043f68257c8c767a94bb396491ad9d2aa2b3`.
- M2 PR2 `64e0ee8223134669d0687d58306614838d7c324c`,
  `contracts/openapi/compliance-types.v1.schema.json`, Git blob
  `03ed2ac738456a969f8d057ca7af6080a9deb0b3`.

The local runner verifies both source Git blob identities (normalizing Git text
checkouts from CRLF to LF on Windows), resolves the files at
their real URI bases, validates the corrected example against canonical and
the LabelPublished profile, and rejects missing envelope/payload/declaration
fields, old/wrong producers, invalid UUID/version/UTC/IDs and undeclared fields.
It preserves valid empty declarations and nullable display text. The existing
OpenAPI lint remains part of `npm run validate`.

Ajv 8.20.0 is now a direct pinned dependency for this runner; it was already
present at that version in M4's lock. No dependency version is upgraded.
The new contract workflow runs local lint/schema checks with contents:read.
The existing project CI adds routing for this M4 review base; its jobs, scan
conditions, credentials and gate thresholds are unchanged.

## Integration and acceptance

The target is the M4 candidate branch. M4/M1 must review provider adoption and
integrate the common files through the agreed upstream order. Shared package
and lock integration with M1/M2 must retain all owners' checks; copying these
exact source files into a review branch does not merge or freeze the providers.

The initial event-only candidate left HTTP compatibility for follow-through.
The October 8 repair below now fixes correlation headers, architectural 403 and
validation invariants, and documents the private snapshot adapter. Public
revision/creator exposure, M3 UI interfaces, role binding and runtime realization
remain at their actual owner gates; existing public DTO field sets are preserved. No target Java transactions, realm
export, deployed authentication, measured capacity/failover or cloud action is
included. M3 remains the designated reviewer of M4's G0 deliverables; M4 remains
the designated reviewer of M2's stewardship work. Technical validation and a
draft repair PR do not establish either acceptance or Jira Done.


## October 8 G0 HTTP compatibility follow-through

This isolated author repair also aligns the supplied Label Workflow HTTP contract with existing AWS architecture BR-05/BR-11 and the M2 contract. Every operation receives and returns the gateway correlation ID. Cross-organisation private resources return 403, with 404 reserved for absence. PASSED cannot contain blocking findings; FAILED preserves at least one blocker; a blocking finding is ERROR and not passed. No role, organisation visibility rule or service boundary is introduced. Public DTO field sets are preserved.

The internal snapshot adapter is server-owned: JWT org_id -> organisationId; private aggregate draftRevision is distinct from immutable versionNumber; public jurisdictionCode maps unchanged to internal jurisdiction; declarations project to allergenId/declarationType. The architecture's created_by_subject stays private for maker-checker, so its absence from a public DTO is not a missing M2 snapshot field. The actor JWT is forwarded; absent revision/exact inputs fails closed. M4 confirms that implementation binding; this schema/document change does not claim deployed authorization or runtime equivalence.

The realm design uses baseline role codes, while M1 descriptions name four service capabilities as roles. Owners must choose explicit aliases to existing permissions or introduce separately reviewed realm roles before the security/starter integration. This repair selects neither policy. Undeclared UI read/edit/current-publication APIs remain a later interface scope; no public operation is invented merely to close G0-M2. Native OpenAPI/event checks and focused HTTP positive/negative checks execute together in the existing contract workflow.
