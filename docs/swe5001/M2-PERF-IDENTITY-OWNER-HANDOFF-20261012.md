# M2 performance identities and owner import — pending M1/M4 approval

Status: **OWNER DECISION REQUIRED / GENERATED_NOT_LOADED**. This is a proposed integration input, not a deployed realm overlay or an authorisation to create identities or run AWS load.

The frozen capacity plan requires5,000 released Formula versions across20 manufacturers, 250 per manufacturer. M1's12October #32 comment confirms that the current four-organisation demo realm/seed does not cover this dataset. Keep the predeclared20 manufacturers; the two demo manufacturers cannot substitute for them.

| Generated scope | Exact identifiers | Required owner decision |
|---|---|---|
|20 manufacturers | `perf_manufacturer_01` through `perf_manufacturer_20`, inclusive | M4 approve these org_id values and MANUFACTURER claims in an isolated test-only realm overlay; real preview callers need CHANGE_MANAGER / IMPACT.RUN |
| Specification supplier | `supplier_01`, inherited from the frozen Specification example; supplierId is separately `sup_001` | M1/M4 confirm an isolated test supplier with this org_id, or approve a consistent regenerated owner mapping before import; it is not one of the current demo organisation IDs |
|20 real JWTs | One org-scoped caller for every generated manufacturer | M4 provide approved issuer/audience/JWKS and token refresh/lifetime coverage for a full rate run; private tokenFile stays outside source/evidence |
|5,000 current published Labels | Exact generated product/formula/Label IDs and authoritative business version numbers | M4 own publication/import, exact-version provider and expected finding acceptance; synthetic Label plans are not published events |

M1 offered a perf-seed profile that loads generated envelopes through each source service's own domain/outbox and replays them like SeedReplay once organisation IDs are settled. Please confirm the supplier/manufacturer mapping with M4, then provide source-owned released snapshot/count/hash and outbox replay evidence. M2 will consume released events into its own projections; source databases remain source-owned.

Approval should identify the overlay/profile commit, all21 organisation scopes above (or an explicitly agreed regenerated supplier mapping), exact released versions, requests/manifest hashes and actual load/replay counts. Generated schema-valid files and freshness-only token checks do not prove identity, load or end-to-end acceptance. M2's version resolver continues to reject LabelPublished.v1 without an authoritative exact business number.

No demo realm change, account/role creation, bulk import, token generation or cloud execution has been performed by this handoff. The existing approved budget/window/access and frozen SLO gates also apply when these owner inputs become available.
