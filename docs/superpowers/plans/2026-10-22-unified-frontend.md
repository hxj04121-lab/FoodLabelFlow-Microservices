# Unified authenticated frontend implementation plan

> Execute inline with executing-plans; use one fresh whole-branch review before handoff.

**Goal:** Make the Dashboard shell the authenticated production entry, with working Impact,
review/label, and native Gateway Catalog routes.

**Architecture:** Retain the existing PKCE session and public API helper as the security
boundary. The auth gate mounts the existing Shell only after sign-in. Production Catalog
uses the current Specification/Formulation DTOs and component-local state; the legacy
local preview remains isolated from the production routes.

**Tech stack:** Existing React/TypeScript, React Router, shared UI components and Playwright.
No new dependencies. Work in XFY. Never commit handoff.md or credential files; no PR merge
or protected deployment is part of implementation.

**Spec:** User request of 22 October; G1/G2/G3 M3 work orders; contracts/openapi/
specification.v1.yaml, formulation.v1.yaml and label-workflow-v1.yaml.

## Scope and interfaces

- Preserve root/Pages PKCE callbacks, memory-only tokens, 401 session clearing, role checks,
  maker/checker restrictions and write outcome uncertainty handling.
- Shell routes: Overview, Products, Formula versions, Suppliers, Materials/specifications,
  Labels, Change impact, Review workspace and service identity/status.
- Reuse ImpactInbox and LabelWorkspace; Impact's label link passes an exact label ID to
  the review route. No invented review-task list endpoint.
- Catalog reads only /api/specifications/** and /api/formulations/** through publicApiGet.
  Cursor paging, exact resource IDs, product/formula ownership and version bindings are
  checked before display. No /api/catalog, dev identity headers, private routes or seed
  fallback in the authenticated flow.
- Formula writes use ADMIN_DATA_MAINTENANCE and MANUFACTURER presentation guards,
  explicit provenance, released specification references and expectedCurrentFormulaVersionId.
  Services remain the authorization authority. No retry of uncertain writes.
- Sidebar identity comes from the session; cross-account state is discarded. Counts describe
  only returned data. Unavailable services show errors rather than fabricated health.

## Tasks

- [x] Add failing authenticated browser cases for unified navigation and native Catalog.
- [x] Mount the authenticated Shell; connect Impact, Labels and Review routes, identity and
      logout; preserve intended public route through PKCE.
- [x] Implement validated native Catalog reads and explicit formula create/release/history.
- [x] Update existing browser navigation selectors and run auth, root/Pages, Catalog,
      type/build and relevant configuration checks. Inspect desktop/mobile results.
- [x] Fresh review, resolve actionable findings, update staging handoff, commit/push XFY
      and create a reviewable PR. Record CI/image publication separately from deployment.

## Review focus

No unauthenticated legacy fallback on a remote production host; no cross-organisation or
late-session data; no cursor loops/false totals; no role-ineligible writes or optimistic
success after malformed responses; root and Pages deep links remain within the application.

## Validation boundary

Local browser fixtures and CI prove implementation behaviour, not live staging acceptance.
The previously supplied staging credential candidates were rejected; this work does not
reuse them or execute the one-shot demo against live business data.
