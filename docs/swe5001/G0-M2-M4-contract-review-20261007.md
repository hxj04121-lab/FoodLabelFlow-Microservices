# M2 review of the October 7 M4 contract candidate

Steward: RunChen Cai (M2). This supplements the historical October 5
[review](G0-M2-contract-review.md). M4's contracts, realm design and oracle
inventory are now supplied in [PR #16](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/pull/16)
at `f02a6f5238c1211c0a1cce8272a955717215b942`; their former absence is superseded.
They are candidates, not merged or reviewer-accepted v1 contracts.

## Pinned inputs and executed checks

- M4: the PR16 head above; eight changed files, including Label Workflow
  OpenAPI, LabelPublished schema/example, native validation scripts, realm
  design and oracle inventory.
- M1: PR8 `a788fa5fc815c34cb23cf219d5194d92a51937bb`; actual canonical
  Envelope blob `909f043f68257c8c767a94bb396491ad9d2aa2b3`.
- M2: PR3 `e89233a35da419977a98e9d8e3cb963f0ce3074e`; Compliance OpenAPI
  blob `a72ce5002e1f473d1d4f744c3dad0bf53fcea123` and shared wire primitives
  blob `03ed2ac738456a969f8d057ca7af6080a9deb0b3`.
- Frozen regression oracle: `a3520e1f1d450796a694b6930d7792dda0f2b512`.
- Thirteen downloaded source blobs were verified against Git blob hashes.
  M4 native `npm run validate` passes (Redocly OpenAPI lint and Ajv
  Draft 2020 event example validation), with its existing baseline ApiError
  dependency present. Six normal PR16 checks pass in
  [run 37626775802](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/actions/runs/37626775802).
- Twenty-four focused source/schema observations confirm the incompatibilities
  below. The review script completing successfully means the observations
  reproduced, not that cross-service compatibility passed. All fourteen named
  oracle methods exist at the frozen baseline; their presence is not a rerun
  of database tests or proof of equivalence to future Java transactions.

## Findings requiring owner action

1. **Canonical Envelope incompatibility.** The M4 LabelPublished example uses
   producer `label-workflow`, which passes its own schema but fails M1's enum.
   Changing only this value to `label-workflow-service` makes the example pass
   the actual canonical schema. M4's standalone envelope also accepts unknown
   producers, offset timestamps, whitespace organisation IDs and correlation
   IDs longer than 128 characters that canonical rejects. M4 must compose the
   actual M1 Envelope with its strict payload/producer profile and exercise
   cross-canonical positive and negative cases.
2. **Revision and validation mapping.** Closed public LabelDraft and
   LabelValidationRecord schemas omit `draftRevision`; LabelDraft also omits
   `creatorSubject`. M2's internal snapshot/run requires the revision and uses
   `jurisdiction`, while M4 exposes `jurisdictionCode`. A constructed projection
   of two internal runs differing only in revision is identical in the declared
   public fields. M2/M4 must agree the exact adapter, revision/creator reads and
   submit binding. This is a contract ambiguity, not a demonstrated runtime
   bypass. M4's public record currently accepts PASSED with a blocking ERROR
   and FAILED without a blocking finding; M2 rejects both. Encode the documented
   fail-closed invariants and declare request/response X-Correlation-ID.
3. **M3 consumer reads and edits.** POST validation and GET decision history are
   now supplied. ReviewTask reads/finding-to-task lookup, current publication and
   history by product/jurisdiction, stored-validation GET and draft/declaration
   editing remain undeclared. M3/M4 must agree provider endpoints or explicit
   scope before freeze; this is not a demand for G2 implementation in G0.
4. **Shared owner decisions.** M4 LabelNotFound selects 404 for invisible tenant
   resources; merged architecture/M2 currently specifies 403. M1/M2/M4 must
   record the agreed policy. M4's baseline realm-role mapping also needs an
   agreed mapping for M1's SPEC_AUTHOR/SPEC_RELEASER and
   FORMULA_AUTHOR/FORMULA_RELEASER literals. Organisation types SUPPLIER and
   MANUFACTURER are not realm roles; LABEL.VALIDATE is a permission. M2 corrects
   its description terminology without changing wire shapes or visibility policy.
5. **Shared package integration.** PR16 and M2 PR2 independently add
   contracts/package.json and its lock. Integration must retain M1/M2/M4 checks
   together, including cross-canonical validation. This is future branch
   integration work, not a claim that PR16 conflicts with current main.

[Published technical feedback](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/pull/16#issuecomment-6039649122)
pins the source and reproduction. Realm export, deployed authorization and
target Java transaction implementation remain later-gate work. No cloud or
deployment action was performed.

## Acceptance and review handoff

M4 owns correction of its candidate; M3 is its designated reviewer. M4 must
also review M2's OpenAPI/schema, snapshot/idempotency and fail-closed semantics,
test-capacity/failover plan and ADR-G0-M2-001. Technical validation does not
substitute for those human acceptances, merged evidence or a G0 freeze.

At inspection PR16 visibly requests rcncai, hxj04121-lab and codingbychatgpt.
M2's seven draft PRs name the intended reviewer in their descriptions/work orders
but have no GitHub review requests. No formal approval exists on any open PR.
Do not infer a sent reviewer request from prose or treat a draft as approved.
STCN-2/26..29 remain In Progress; protected-main and upstream dependency gates
continue to apply.
