# G3 M3 — independent impact inbox (STCN-101)

> **15 October status update:** the inbox and later all-frontend refinement were
> adopted through PR54 at implementation checkpoint `5beb98a`. The current shared
> styling is recorded in [DESIGN.md](../../DESIGN.md); the following initial
> 69-test/capture/design-extension discussion is historical. The later 72-test
> authenticated suite and real G2 CI are recorded in
> [the M3 report](report/M3-technical-sections.md). Staging acceptance remains
> NOT RUN; no historical screenshot is relabelled as deployed evidence.

The authenticated reference frontend now includes a read-only **Impact inbox** for a manufacturer change manager. It presents persisted finding evidence and exact bound version references before the user continues to the existing label workspace. This is a scoped extension of `SignInWorkspace`, with the incumbent implementation as its visual authority.

## Access and usage

After signing in, choose **Impact inbox** in the workspace navigation. The presentation guard requires `organisationType === 'MANUFACTURER'` and the `CHANGE_MANAGER` role. Supplier and roleless accounts see the requirement message and receive no inbox read controls. This local guard is presentation only: Compliance remains responsible for `IMPACT.RUN` authorization and verified manufacturer ownership.

Choose a finding kind (`POTENTIAL`, `CONFIRMED`, or all kinds) and outcome (`NO_ACTION`, `REVIEW_REQUIRED`, or all outcomes), then select **Refresh inbox**. There is no automatic initial inbox fetch. Changing either filter clears the page, finding evidence, verified-version result, errors and cursor history; the next refresh starts from the first page.

The table shows kind/outcome, formula version/jurisdiction, missing and unresolved counts, and **View evidence**. That control performs an exact finding GET before displaying the inline detail section and focusing its heading. **Next page** appears only when a cursor is returned. It replaces the current page and clears previous details rather than accumulating findings. The opaque cursor is encoded with `URLSearchParams`, never decoded or interpreted as an organisation selector. A repeated current or previously used cursor is rejected and the user is directed to restart with refresh.

The inbox has no adoption, impact-run, review-task creation or label mutation action. Navigating to Labels opens the established label workflow, whose separate permissions and actions still apply.

## Public reads and exact response checks

| Control | Public route | Checked before displaying success |
| --- | --- | --- |
| Refresh / Next page | `GET /api/compliance/impact-findings?limit=20` with optional `kind`, `outcome`, `cursor` | Page organisation matches the signed-in organisation; at most 20 valid findings; unique finding IDs; each finding matches the requested filters; cursor is null or a nonblank string. |
| View evidence | `GET /api/compliance/impact-findings/{findingId}` | Finding organisation and exact requested finding ID match; the finding passes the evidence checks below. |
| Previous / Candidate specification | `GET /api/specifications/versions/{specificationVersionId}` | Returned specification version ID and version number match the persisted reference; lifecycle is `DRAFT`, `RELEASED` or `RETIRED`. |
| Formula | `GET /api/formulations/formula-versions/{formulaVersionId}` | Returned formula version ID and version number match the persisted reference; organisation matches the signed-in organisation; lifecycle is `DRAFT`, `RELEASED` or `RETIRED`. |

Every version ID is encoded as a path segment. The specification detail check does not add a client-side organisation comparison; its access remains subject to the authenticated service. Specification and formula controls display a verified ID/version/lifecycle status, rather than opening an editor or claiming to have loaded every version field. The rule-set ID and version are displayed as persisted evidence; there is no rule-set detail control.

The [shared API helper](../../frontend/src/auth/session.ts) sends the Bearer token and a fresh `X-Correlation-ID` to the configured Gateway origin. It rejects foreign origins, private routes and ambiguous paths; the inbox does not call internal services directly or supply an organisation query parameter. Responses from a changed identity are discarded. Only one inbox request is pending at a time, controls are disabled during the read, and leaving the inbox aborts its pending request.

The [finding reader](../../frontend/src/features/impact/impactFindings.ts) performs these runtime checks:

- Finding IDs and reference IDs are nonwhitespace strings of 1–128 characters. Version numbers are positive safe integers. Previous and candidate specifications, formula, label and rule-set references must all be present.
- Kind is `POTENTIAL` or `CONFIRMED`; outcome is `NO_ACTION` or `REVIEW_REQUIRED`. Jurisdiction is a nonblank string of at most 32 characters.
- Required, declared and missing allergen arrays contain valid, unique IDs. An unresolved item carries valid formula-item, specification-version and specification-component IDs, a nullable/string ingredient ID, a nonblank raw phrase, and an `UNMAPPED` or `AMBIGUOUS` reason.
- `REVIEW_REQUIRED` is accepted exactly when missing or unresolved evidence is present. A `NO_ACTION` response containing either is rejected, as is `REVIEW_REQUIRED` with neither.
- Each list item belongs to the signed-in organisation. An invalid item rejects the entire page; a detail response for a different finding is not displayed.

These are the implemented consumer checks, not a claim that the frontend fully validates every field or recomputes Compliance's allergen evaluation.

## Allergen evidence and finding meaning

The detail panel keeps **Required allergens**, **Declared allergens** and **Missing allergens** distinct. An empty array is shown as **None recorded**. Unresolved evidence retains its raw phrase and reason together with the formula-item ID, specification-version ID and component ID. The nullable ingredient ID is shown as **Not identified** when absent. The frontend does not turn an unresolved phrase into an invented canonical allergen.

`POTENTIAL` explicitly means potential impact only: this finding does not adopt the formula or create a review task. `CONFIRMED / REVIEW_REQUIRED` describes confirmed impact requiring review, while explicitly stating that ReviewTask delivery must be verified separately. The finding is not a task-creation receipt, and the inbox contains no task-delivery status or ReviewTask lookup. A confirmed `NO_ACTION` describes no missing or unresolved allergens in that finding. An empty filtered page does not establish a `NO_ACTION` conclusion.

## Label ID handoff and recovery

**Open label: {id} · v{number}** passes the persisted `labelVersion.id` to `SignInWorkspace`, switches to Labels, and prefills **Label version ID** through `initialLabelId`. The user then selects the existing **Open label** control, which reads `GET /api/labels/{labelVersionId}` and its declaration facts. The inbox handoff itself makes no label GET and does not independently verify the label's returned version number, organisation or lifecycle. It preserves the exact ID instead of substituting a current-label lookup. The label workspace's own response checks and service permissions apply.

Initial guidance, loading, empty, denied and error states remain distinct. A 401 ends the session and removes the authenticated surface; 403 explains denial while retaining the identity. The inbox gives specific guidance for 404 (finding/version missing), 422 (required projections not ready), and 503 (impact service unavailable). Rate-limit and other failures use the shared helper's controlled messages. A reference ID may be shown; raw service error text is not rendered. There is no automatic request retry. Refresh, page changes and new finding selection clear obsolete detail; aborted or late responses do not repopulate another view or identity.

## Visual continuity and finish disposition

The checked [component](../../frontend/src/features/impact/ImpactInbox.tsx), [workspace](../../frontend/src/auth/SignInWorkspace.tsx) and [stylesheet](../../frontend/src/styles.css) retain the existing Segoe UI/Microsoft YaHei stack, dark SpecTrace header (`#303353`), pale ground (`#fafaff`), purple shared Button controls and visible focus treatment. The compact ruled table and inline fact panel extend the authenticated operational surface. No replacement visual identity, modal, entrance animation, generated/shipping raster, global token repair, `PRODUCT.md` or `DESIGN.md` was introduced for this ordinary extension.

The desktop and mobile captures were inspected at `frontend/test-results/signin/impact-desktop.png` and `impact-mobile.png`. They show the document top, loaded confirmed evidence, bound-version controls and verified formula status. At the mobile breakpoint (600px), filters stack, evidence becomes one column and version controls fill the available width; horizontal overflow is confined to the focusable table region. The final 69-test run regenerated both captures with the corrected singular caption **1 finding**; this copy-only delta preserves the reviewed composition.

Native labelled selects, named navigation, table caption and scoped headers, keyboard-focusable table scrolling, status/alert semantics and result-heading focus are present in source. Loading, denied, error, POTENTIAL and unresolved states were assessed in source; those rendered states are not included in this capture pair. This is not a live assistive-technology assessment.

The finish reviewer disposition is **ship** at the supplied screenshot/source scope. The captures are fixture evidence, not proof of persisted staging data. The documentation and finish review do not claim an independent browser run, staging inspection, AWS acceptance or real provider/service integration.

## Local evidence and reproduction

The final local validation passed **69 auth/label/inbox Playwright tests**, including **18 STCN-101 cases**. The final two cover POTENTIAL/NO_ACTION semantics and rejection of findings outside the selected filters. Production build, test-source TypeScript, seven Pages checks and three deployed/demo config test groups are verified separately; none represents a deployed run.

The [fixture suite](../../frontend/auth-tests/signin.spec.ts) covers exact finding/version reads and label ID handoff, opaque pagination, unresolved provenance, filter resets, foreign or unreadable evidence, contradictory outcomes, repeated cursors, controlled errors, role guards, session expiry and leaving during a read. The configured browser flow exercises rendered React components against HTTP identity/API fixtures. It does not establish real Keycloak, Gateway, Compliance, Specifications, Formulations or Label Workflow integration.

From `frontend`, run the targeted inbox cases with:

```powershell
npm run test:signin -- --grep STCN-101
```

The exact-confirmed-evidence case writes the full-page desktop/mobile captures to `test-results/signin/impact-desktop.png` and `test-results/signin/impact-mobile.png`. Run `npm run test:signin` for the complete local auth/label/inbox suite. The [sign-in Playwright configuration](../../frontend/playwright.signin.config.ts) defines the local fixture profile.

Real G2 CI execution and preparation of the G3 deployed test script are separate work. Configuration or script preparation is not a completed remote run or deployed acceptance result; those require their own recorded evidence and owner review.
