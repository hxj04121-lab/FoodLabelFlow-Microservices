# G2 M3 independent frontend evidence

**14 October update:** current main includes the current-label lookup and declaration
editing/REQUEST_CHANGES resubmission UI (accepted through #37), and the team has merged
the real Label Workflow provider in #51. The table/49-test figures below record the
original independent checkpoint. Current delivery additionally prepares a disposable
four-service G2 browser CI path in `frontend/real-auth-tests/g2-workflow.spec.ts`;
cite its exact workflow result separately. Real deployed/human acceptance remains open.

**Verified real-component checkpoint (14 October):** exact source
`c60f268fc5b75f2a2416ca59ca5e11c9a1892290` passed both real Keycloak/Gateway jobs with
the current four business services, MySQL and RabbitMQ (five original seed/sign-in
cases plus one actual G2 browser case per job). The cycle validates and submits,
requests changes, saves declarations through the accepted PUT, revalidates/resubmits,
approves as the distinct checker, publishes with the contracted 201, queries the
exact PUBLISHED current label, and rejects a south manufacturer's access with 403.
The owner SeedReplay and consumer acknowledgements are observed only in the
disposable CI broker; they do not certify M5's deployed topology.
[Trusted workflow](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/actions/runs/38524127967),
[PR workflow](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/actions/runs/38524133073),
[sanitized JSON](evidence/G2-M3-real-workflow-20261014.json),
[published-label screenshot](evidence/G2-M3-real-published-20261014.png).
Two actual maker/checker recordings remain in the workflow's real-component
artifact and the locally downloaded evidence; they are CI videos, not an AWS demo.

Record date: 13 October 2026. Source branch: `XFY`. This records the independently
implemented reference UI for the [G2-M3 work order](../../.project-control/work-orders/G2/M3-review-approval-publication-ui.yaml).
Real service integration and human acceptance remain open dependencies. This
record does not mark a repository milestone or Jira issue Done, or claim that
remote CI has run successfully for this change.

## Implemented scope

| Work item | Reference UI behavior | Remaining boundary |
| --- | --- | --- |
| STCN-77 | Authenticated Labels navigation; exact role and manufacturer checks; shared Gateway GET/POST helper; pending, denial, conflict and uncertain-result handling | Gateway and services enforce actual authorization and ownership |
| STCN-74 | Create from product ID and jurisdiction; open an exact label version; validate its binding; display findings; submit only after a matching passing validation | Released formula, rule selection and canonical validation come from providers |
| STCN-75 | Approve, request changes or reject a pending version; optional comments; confirmed decision receipt followed by a fresh GET | Real maker-checker enforcement and provider acceptance remain M4 dependencies |
| STCN-76 | Explicit confirmation before publishing an approved version; validate/display the receipt; current-label lookup now implemented | Real publication/current-label acceptance remains a dependency |

Open **Labels** after signing in with the existing G1 reference configuration.
`MANUFACTURER` callers with `LABEL_OFFICER` see draft actions; `APPROVER` sees
review actions for `PENDING_REVIEW`; `PUBLISHER` sees publication controls for
`APPROVED`. Other callers can attempt an exact-version read, subject to service
permissions. Presentation checks do not establish access rights.

## Contract and recovery behavior

The consumer follows [label-workflow-v1.yaml](../../contracts/openapi/label-workflow-v1.yaml):

| Operation | Public route |
| --- | --- |
| Create | `POST /api/labels/drafts` with `productId` and `jurisdictionCode` |
| Open | `GET /api/labels/{labelVersionId}` |
| Validate / submit | `POST /api/labels/{labelVersionId}/validations` / `/submit` |
| Review | `POST /api/labels/{labelVersionId}/decisions` with decision and optional comments |
| Publish | `POST /api/labels/{labelVersionId}/publish` |

Calls use the configured Gateway origin, Bearer token and correlation ID. The
shared helper rejects foreign, private and ambiguous paths; it does not call
services directly. API 401 ends the local session; 403 preserves the identity and
explains denial. Error output uses controlled messages and a reference ID rather
than raw service internals.

Responses are checked before they can confirm an action. Validation must match
the label/formula/rule-set/jurisdiction binding. Finding severity, pass status and
blocking status are shown separately: a nonblocking error remains visibly an
error. A failed or unreadable revalidation clears the previous passing result.

The public label response has no `creatorSubject`, `allowedActions` or
`draftRevision`. The UI does not invent them. It explains the creator-approval
restriction and relies on the service's 403 for self-approval; it cannot identify
the creator locally. Request-changes and rejection are separate decisions.

After an acknowledged decision, the UI refreshes the exact version with GET
instead of predicting its next lifecycle state. If that refresh fails, the
decision receipt remains visible, the UI explains that the decision was recorded,
and writes stay locked until the user successfully opens the label again.
Publication success requires a matching receipt with `PUBLISHED` and a valid
timestamp. That receipt confirms this version's publication, not that it remains
the product's current label indefinitely.

Pending operations prevent duplicate UI submissions. Conflicts, malformed
mutation responses and ambiguous outcomes require a fresh read before another
write. There is no automatic application POST retry and no undocumented
`Idempotency-Key`. These controls do not guarantee browser/network exactly-once
delivery. If draft creation has an unknown outcome and no label ID was returned,
the UI directs the user to an administrator with the reference ID. Unmount and
identity changes prevent late results from populating another workspace session.

## Visual continuity

This is an extension of the incumbent authenticated workspace. It retains the
shared Button component, existing type stack, dark brand header, pale background,
purple primary controls and focus treatment. Form fields and feedback use the
existing restrained borders and rounded corners. The two-column entry and facts
layout becomes one column at 600px; actions wrap and long identifiers break within
their containers. Review and publication sections use the same detail-section
hierarchy. Labels, fieldsets, status/alert feedback and result-heading focus are
present in the rendered component.

The documenter checked the implementation and CSS for consistency. The finish
review returned **ship** for the severity fix and scoped review/publication
controls based on source and six desktop/mobile screenshots. That review was not
an independent browser run or staging verification. No new visual identity or
global design metadata is needed for this extension.

## Local evidence and reproduction

Local validation passed **49 Playwright tests**, an independent TypeScript check,
the frontend build, **4 Pages checks** and **10 baseline label/English-UI checks**.
A fresh instrumented auth/label run reported **94.86% lines**;
the label feature reached **100% lines / 93.72% branches**. These figures describe
this local run, not remote CI or real provider acceptance.

The suite performs actual browser Code + PKCE login and interactions with rendered
React components against HTTP protocol/API fixtures. It covers permission
visibility, exact binding checks, severity display, validation/submission guards,
denials, stale state, malformed responses, concurrent clicks, uncertain writes,
self-approval denial, review decisions, failed post-decision refresh and confirmed
publication. These are component interactions within Playwright, not standalone
React unit tests. They do not prove real Keycloak/Label Workflow integration.

From `frontend`:

```powershell
npx tsc -b
npm run test:signin
```

The sign-in configuration starts Vite on 5191 and the suite's HTTP fixture uses
8191. Screenshot evidence lives in `frontend/test-results/signin/`:

- `labels-desktop.png` / `labels-mobile.png`: finding severity and blocking text.
- `labels-review-desktop.png` / `labels-review-mobile.png`: review controls.
- `labels-published-desktop.png` / `labels-published-mobile.png`: publication receipt.
- `labels-flow-desktop.png` / `labels-flow-mobile.png`: draft workflow.

Record the targeted draft/validate/submit happy path with:

```powershell
$env:G2_RECORD_VIDEO = 'true'
npx playwright test --config playwright.signin.config.ts --grep 'STCN-74 creates' --output test-results/label-demo
Remove-Item Env:G2_RECORD_VIDEO
```

Video recording is opt-in in
[playwright.signin.config.ts](../../frontend/playwright.signin.config.ts).
The [M3 workflow](../../.github/workflows/swe5001-m3.yml) now includes this targeted
recording step and collects `frontend/test-results/**` in `m3-signin-evidence`.
That workflow configuration is not evidence of a completed remote run. Pages and
deployed-profile checks remain separate from these label fixtures.

The local targeted recording was also executed successfully and produced WebM
video under `frontend/test-results/label-demo/`. It records the fixture-backed
reference flow and is not a deployed-service demonstration.

## Owner dependencies and acceptance

| Owner | Required follow-through |
| --- | --- |
| M4 | Real Label Workflow provider now on main; cooperate in integrated runtime, maker-checker and workflow acceptance |
| M2 | Canonical validation and rule sources, public contract approval and changes |
| M1 | Product and released-formula data for meaningful integrated flows |
| M5 | Deployment environment and deployed acceptance inputs |

The work order still requires human acceptance, Playwright evidence and a screen
recording. The independent UI and fixture evidence support that review; provider
integration, current-label behavior and deployed acceptance need their own results.

Checked sources: [LabelWorkspace.tsx](../../frontend/src/features/labels/LabelWorkspace.tsx),
[labelWorkflow.ts](../../frontend/src/features/labels/labelWorkflow.ts),
[session.ts](../../frontend/src/auth/session.ts),
[SignInWorkspace.tsx](../../frontend/src/auth/SignInWorkspace.tsx),
[styles.css](../../frontend/src/styles.css),
[signin.spec.ts](../../frontend/auth-tests/signin.spec.ts), the Playwright config,
M3 workflow, public contract and work order linked above.
