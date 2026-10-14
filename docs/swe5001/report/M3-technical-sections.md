# M3 Technical Report Contribution — SpecTrace-CN

Draft for Huang Xiangjia, 14 October 2026 (Asia/Singapore). STCN-118 requires the
draft by **15 October 23:59**; M4 is the reviewer. This contribution records
implementation, local/CI evidence and deployed acceptance separately. Live G1/G2/G3
acceptance remains pending. The team integration/submission date is 16 October.

## Gateway and Service Boundaries

The Spring Cloud Gateway is the single public API entry point for the React
reference consumer. It forwards specification, formulation, compliance and label
requests without giving the browser private service URLs or database access.
JWT issuer, JWKS and audience checks, public `/auth` routing, private/admin route
denial, CORS, request/body guards and correlation propagation are covered by the
Gateway tests. Bucket4j limits protect each replica; they are not global quotas.
Services independently verify tokens and enforce organisation ownership.
The accepted declaration-editing PUT is restricted to its exact label path and
LABEL_OFFICER, including CORS preflight, while retaining body/rate and owner checks.
Real G2 integration exposed the previous GET/POST-only edge gap; signed-token
regressions now cover forwarding, denial, unsupported paths and oversized PUT.
[Implementation](../../../platform/gateway/README.md),
[route review](../gateway-routes.md).

## Authentication and Organisation Identity

The consumer uses authorisation code with PKCE S256 through Keycloak. Bearer
tokens stay in memory; callback state is cleared, expiry/401 ends the local session,
403 preserves the identity and explains denial, and logout returns to the root
callback. The formal caller endpoint is compared against the native `sub`,
organisation/type and complete role set rather than trusting a display username.
CI has verified real Keycloak/Gateway and owned/foreign seeded resources. This
proves local real-component identity boundaries, not an AWS deployment.
[G1 runbook](../G1-M3-staging-acceptance.md).

## Review, Approval and Publication Consumer

STCN-74–77 implement draft creation, declaration editing, validation/submission,
approval, request changes, rejection, confirmed publication and the current-label
query. All requests use the shared authenticated public GET/POST/PUT helper. The
UI checks returned version bindings and response receipts, invalidates a stale
passing validation after edits, and requires a fresh read after uncertain writes.
It neither invents the service's private draft revision nor predicts success.
Role-based controls aid presentation; the service is authoritative for tenant
ownership and maker-checker restrictions. The Label Workflow candidate was merged
by the team as [PR #51](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/pull/51)
at `356f9b8c`, permitting actual mainline integration checks.

The added disposable CI path uses the actual four services, Keycloak and Gateway.
Preparation publishes a specification and formula through their owner APIs so
consumer projections are created by real events rather than direct database
injection. Existing M1 SeedReplay first emits the immutable baseline events;
the disposable CI waits for acknowledged specification replay before starting
formula replay, preserving exact predecessor checks.
The CI operator also waits for the Label queue to acknowledge the 60 seed formulas
and new release before creating the draft, preventing a timing-dependent old-formula
binding without weakening the exact-version assertion. Separate maker/checker
browser sessions exercise validation, request
changes, editing/revalidation/resubmission, approval, publication and current-label
lookup, plus a south manufacturer denial. Its actual run result must be cited from
the exact source workflow before describing it as passed; it is not deployed acceptance.
[G2 evidence](../G2-M3-frontend-independent.md).

## Impact Inbox and Two-Phase Evidence

STCN-101 adds a manufacturer CHANGE_MANAGER inbox using public list/detail APIs,
kind/outcome filters and opaque cursor pagination. Both the page and each finding
must match the signed-in organisation. The selected finding displays required,
declared, missing and unresolved allergens with raw phrase, reason and exact
component/item references. Specification/formula controls read exact bound public
versions; the label control hands the exact ID to the existing label workspace.
Rule-set references are displayed without inventing a public rules endpoint.

POTENTIAL is evidence of a hypothetical specification change, not formula adoption
or a task. CONFIRMED + REVIEW_REQUIRED is task eligibility, not proof of delivery.
An empty inbox is not a NO_ACTION finding. The interface preserves these distinctions
and adds no timestamp field absent from the HTTP contract. Desktop/mobile captures
and an independent screenshot/source review support UI acceptance only.
[Inbox evidence](../G3-M3-inbox-independent.md).

## Automated Verification and Evidence Handling

The local browser suite uses rendered React controls and real Code+PKCE interactions
with a protocol/API fixture. It verifies happy paths, permissions, denial/error
states, malformed and mismatched bindings, pagination, interrupted reads, uncertain
writes and workflow recovery. It does not impersonate real business providers.
Production build and separate test-source TypeScript checks are required alongside
these runs. The M3 workflow retains reports/screenshots and the fixture recording.

Real-component CI and deployed checks are separate profiles. Local CI login traces
are redacted before upload. Deployed profiles disable traces/video and automatic
failure screenshots; successful evidence stores source/version/status references,
not bearer tokens or passwords. Historical verified G1 source `9b7595b` passed all
applicable checks and published the Gateway digest
`sha256:4440dd8408c674a707b945c9d4b55698c7364fbd0aab3ba5b3af8aba3f6233a2`.
That immutable checkpoint must not be relabelled as the later inbox/G2 source.
[Checkpoint CI](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/actions/runs/38426000062).

## Full Demo Path and Runtime Acceptance

STCN-102's prepared deployed Playwright profile follows supplier specification
release → POTENTIAL inbox → explicit formula adoption → CONFIRMED inbox → label
draft/declarations/validate/submit → distinct checker approval → publication/current
label. It requires actual build metadata, native identities, an owner-prepared
baseline published label and explicit disposable-write enablement. It records only
the public/browser scope it observes. The deployed run is **NOT RUN** while the
HTTPS environment and owner inputs are absent.

M4/M5 must provide a correlated observer record proving exactly one ReviewTask
and LabelPublished.v2 consumer application; public publication success alone is
insufficient. M1's local seeds and import/replay evidence do not establish a
staging-loaded dataset or baseline published labels. M2's load/failover results
are separate owner work and are not fabricated in this contribution.
[Demo acceptance](../G3-M3-demo-acceptance.md).

## Deployment and Reproduction

Pages uses a stable repository base/root callback and loads public Gateway/OIDC
values at build time. The delivery adds `g1-release.json` so deployed verification
compares actual build inputs and source to the test target. Missing inputs and
incompatible endpoints fail before credentials are entered. M5 supplies public
HTTPS routing, exact issuer/private JWKS, service bindings, deployment revisions,
readiness and the access/window; a repository administrator configures Actions Pages.
[M5 input matrix](../G1-M3-M5-inputs.md).

From `frontend`, build with `npm run build`, run fixture coverage with
`npm run test:signin`, and run Pages/config checks with `npm run test:pages` and
`npm run test:deployed-config`. `npm run test:deployed` is the read-only live G1 smoke;
`npm run test:demo` is the separately gated live business demo. Both require actual
environment variables and are not launched by the fixture tests.

## Contribution, Limits and Closure

M3 owns Gateway/React integration, inbox, browser demo and the report contribution.
G0 is formally complete. G1/G2 code is delivered, but formal Jira closure requires
actual integration and M4 acceptance. G3 still requires the deployed full-path
evidence and owner observations. STCN-103/104 are DESCOPED; partner/Notification
onboarding and invented time measurements are excluded.

Before final report integration, replace pending runtime entries with dated exact
source/image evidence or retain explicit NOT RUN limitations. Attach the Playwright
report, safe screenshots/recording and owner observer references, obtain M4 review,
then reconcile Jira to accepted work. No PR was merged by M3 and the private local
`handoff.md` is not part of this report or the repository delivery.
