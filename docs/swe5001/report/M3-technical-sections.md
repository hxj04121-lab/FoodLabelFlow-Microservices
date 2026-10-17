# M3 Technical Report Contribution — SpecTrace-CN

Submission draft for Huang Xiangjia (M1), prepared **15 October 2026**
(Asia/Singapore). Author: **Xu Feiyang (M3)**. Designated reviewer: **M4 Zhu Wenyu**.
STCN-118 requires the draft by **15 October 23:59**, with team integration on
16 October. This contribution is ready for review; report acceptance and course
submission are separate actions.

M3 implementation, automated integration and deployed acceptance are recorded
separately. The implementation checkpoint is
`5beb98a5b2fe56f1b30d7ada54d145bac9d5ad02`, adopted into main through
[PR #54](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/pull/54)
at `ec595de37cf0fd17ffc31e2890dd4416b8d0e9bb`. The report baseline is
`afc90e0838d57eba09c7922fae44e3e78ae14ade`; Gateway/frontend source is unchanged
between these checkpoints. **M3 live G1/G2/G3 acceptance is NOT RUN.**

The [evidence index](M3-evidence-index-20261015.json) records exact sources,
run/job links, outcomes, artifact hashes and limitations. The
[submission checklist](M3-submission-checklist-20261015.md) separates independent
delivery from remaining owner inputs. Section ownership follows
[the team breakdown](../TEAM_WORK_BREAKDOWN.md):

| Team report placement | M3 material |
| --- | --- |
| §3.1.2 Tiers & Layers | Browser, public Gateway and domain-service boundaries |
| §4.4 Extensibility & Maintainability | Shared client, version guards, reusable UI and verification profiles |
| §6 Reuse evidence | Existing contracts/starter/components reused; descoped experiments excluded |

M3 supplies supporting test/CI evidence; M5 owns the team's CI/CD chapter.

## Gateway and Service Boundaries

Spring Cloud Gateway is the public API entry point for the React reference
consumer. React handles interaction and response-contract checks; Gateway owns
public ingress; domain services own business decisions and persistence. The
browser receives no private upstream URLs, database credentials or internal
validation/task endpoint. It does not query service databases.

Gateway validates JWT issuer, JWKS and audience, routes public `/auth`, rejects
private/admin paths and enforces exact CORS origins, body limits and correlation
propagation. Bucket4j limits protect each process; they are not cluster-wide
quotas. Services independently verify tokens, roles and organisation ownership.
These layers deliberately provide different controls rather than relying on the
browser or edge as the sole authorisation authority.

Real G2 integration exposed the previous GET/POST-only declaration-editing gap.
PUT is now admitted only on the exact public label-declarations path with
LABEL_OFFICER, including its CORS preflight. Other PUT routes remain denied;
body/rate, path, origin and service-owner checks remain. Signed-token regressions
cover forwarding, role/origin denial, unsupported paths and oversized payloads.
[Implementation](../../../platform/gateway/README.md),
[route contract](../gateway-routes.md).

## Authentication and Organisation Identity

The consumer uses Keycloak authorisation code with PKCE S256. Bearer tokens stay
in memory; callback state is cleared; expiry or 401 ends the local session; 403
preserves the identity and explains denial. Logout returns to the stable root
callback. The formal caller response must match native subject, organisation,
organisation type and the complete role set; a display username is not an
identity substitute. Public requests use a fresh correlation ID.

CI verifies real Keycloak/Gateway and owned/foreign seeded resources. Protocol
fixtures separately cover invalid state/nonce/audience, unreadable identities,
expiry, permission denial and callback recovery. Neither profile establishes
public HTTPS reachability, load-balancer behavior or staging acceptance.
[G1 live acceptance procedure](../G1-M3-staging-acceptance.md).

## Review, Approval and Publication Consumer

STCN-74–77 implement draft creation, declaration editing, validation/submission,
approval, request changes, rejection, explicit publication confirmation and the
current-label query. All reads/writes use the shared authenticated public
GET/POST/PUT helper. Exact returned bindings and receipts are checked before
displaying success. Editing invalidates a previous validation pass; an uncertain
write requires a fresh read. The UI does not invent a private draft revision,
silently retry mutations or predict a successful decision.

Role-aware controls aid presentation; Label Workflow remains authoritative for
tenant ownership and maker-checker restrictions. The team adopted that provider
through [PR #51](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/pull/51)
at `356f9b8c`, enabling real mainline integration.

The disposable CI profile starts all four services, Keycloak, Gateway, MySQL and
RabbitMQ. Owner SeedReplay emits baseline events; the CI operator waits for
specification consumer acknowledgements before formulation replay and for Label's
baseline/new-formula acknowledgements before draft creation. Preparation uses
owner public APIs and real event projection, not direct database injection or a
weakened exact-version assertion.

Separate maker/checker browser sessions exercise validation, request changes,
editing/revalidation/resubmission, approval, publication and current-label lookup,
plus a south-manufacturer denial. At `5beb98a`, both XFY and PR real-component
jobs passed five sign-in/seed cases and one G2 workflow case. The downloaded
business evidence records the newly released exact formula, current PUBLISHED
label, publication HTTP **201** and foreign access **403**. Its disposable CI IDs
must not be reused as staging resource IDs.
[Trusted run](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/actions/runs/38532352262),
[PR run](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/actions/runs/38532363285),
[safe workflow JSON](../evidence/G2-M3-real-workflow-5beb98a.json),
[actual publication screenshot](../evidence/G2-M3-real-published-5beb98a.png).

## Impact Inbox and Two-Phase Evidence

STCN-101 adds a manufacturer CHANGE_MANAGER inbox using public list/detail APIs,
kind/outcome filters and opaque cursor pagination. Every page/finding must match
the signed-in organisation. Detail shows required, declared, missing and
unresolved allergens with raw phrase, reason and exact component/item references.
Bound specification/formula controls read exact public versions. The label control
passes the exact ID to the established workspace for an explicit open action;
it does not silently replace that ID with a current-label lookup.

POTENTIAL describes a hypothetical specification change, not formula adoption.
CONFIRMED + REVIEW_REQUIRED establishes task eligibility, not delivery. An empty
inbox is not a NO_ACTION finding. Rule-set references are displayed without
inventing a rules endpoint or timestamp absent from the contract. Identity/filter
changes abort reads and clear obsolete evidence; late responses do not restore
another view's data. [Inbox implementation evidence](../G3-M3-inbox-independent.md).

The final refinement unifies Dashboard, Catalog, materials/suppliers, reference
labels/reviews/status and authenticated sign-in/Labels/Inbox in the incumbent
purple/navy identity. Semantic colors, support text, controls, focus and contained
tables share [documented design rules](../../../DESIGN.md). Product names retain
full accessible text. Reference catalog and authenticated modes preserve their
separate routing/data boundaries; visual polish does not connect unavailable
prototype workflows. Forty-six settled local route/viewport/state screenshots
received a separate AI-assisted screenshot/source finish pass. That supports
presentation verification; it is not M4 human acceptance, a live
assistive-technology assessment or AWS acceptance.

## Automated Verification and Evidence Handling

Counts below were rechecked against GitHub run/job metadata and decoded test
summaries on 15 October. They are per-suite outcomes, not additive coverage totals;
recording reruns and overlapping profiles are not counted as new test cases.

| Verification at exact implementation checkpoint `5beb98a` | Observed result | Evidence |
| --- | --- | --- |
| Gateway policy and signed-token HTTP tests | **41 passed**, zero failures/errors/skips: 3 policy + 38 HTTP | [Gateway job](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/actions/runs/38532352262/job/115746957694) |
| Frontend reference regression profile | **67 passed** | [Full CI analysis job](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/actions/runs/38532363300/job/115748704727) |
| Authenticated sign-in/Labels/Inbox protocol-fixture profile | **72 passed**, including 18 inbox and 3 unified-surface viewport cases | [Frontend job](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/actions/runs/38532352262/job/115746958012) |
| Pages asset/base/callback/config checks | **7 passed**; Pages publication **SKIPPED** | [Frontend job](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/actions/runs/38532352262/job/115746958012) |
| Deployed/demo configuration guard groups | **3 passed**; configuration checks only | [Frontend job](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/actions/runs/38532352262/job/115746958012) |
| Real Keycloak/Gateway/four-service integration | **5 sign-in/seed + 1 G2 case passed** in each XFY/PR job | [Real-component job](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/actions/runs/38532352262/job/115747917276) |
| Production frontend build and independent test-source TypeScript | **PASSED** | [Frontend job](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/actions/runs/38532352262/job/115746958012) |

All applicable runs at `5beb98a` completed successfully: trusted M3
[38532352262](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/actions/runs/38532352262),
PR M3 [38532363285](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/actions/runs/38532363285),
and full CI [38532363300](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/actions/runs/38532363300).
Conditional Pages/PR image publication was skipped. Trusted publication produced
`ghcr.io/hxj04121-lab/foodlabelflow-microservices/gateway:5beb98a5b2fe56f1b30d7ada54d145bac9d5ad02`
at digest
`sha256:d4190ea6ee31b84e7b67d36ad56e6391612c8bdc4916b8bb14d28b9a17f6ab47`.
Downloaded release metadata and an anonymous registry manifest check agree. The
new G2 JSON/screenshot are byte-for-byte artifact copies with hashes in the index.
Maker/checker recordings remain in that run's `m3-real-keycloak-evidence` artifact.

The fixture suite drives rendered React controls with HTTP identity/API fixtures;
it is not real-provider acceptance. Real-component CI and deployed tests are
separate profiles. CI login traces are redacted; live profiles disable traces,
video and automatic failure screenshots. Successful public evidence records
business/source/status references rather than passwords or bearer tokens.
Historical `9b7595b` and `c60f268` evidence retains its original source and digest;
the [earlier G2 JSON](../evidence/G2-M3-real-workflow-20261014.json) has not been
relabelled. A report-only commit is not a new tested image or deployed result.

**Current-main limitation:** baseline `afc90e0` was inspected separately. Its
[containers job](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/actions/runs/38694436953/job/116288657561)
failed during backend Docker build dependency retrieval: Maven Central returned
HTTP 502 for `org.apache.maven.doxia:doxia-module-markdown:1.11.1`. The timestamp
and other observed job states are in the index. This report does not call that
main workflow green or assert a successful retry. The immutable M3 checkpoint is
a separate claim.

## Full Demo Path and Runtime Acceptance

STCN-102's prepared deployed Playwright profile follows supplier specification
release → POTENTIAL inbox → explicit formula adoption → CONFIRMED inbox → label
draft/declarations/validate/submit → distinct checker approval → publication/current
label. It requires actual published-build metadata, native identities, an
owner-prepared baseline PUBLISHED label and explicit disposable-write enablement.
The script and rejection/config guards are delivered; the staging run is
**NOT RUN**. Test preparation is not a completed remote execution.

| Remaining acceptance | Status | Required input/evidence |
| --- | --- | --- |
| G1 public HTTPS sign-in, routing, ownership and denial | **NOT RUN** | M5 endpoint/issuer/build/readiness/access window; M4 approved identities |
| G2 deployed label lifecycle and maker-checker flow | **NOT RUN** | Healthy dependent services; M4 baseline US label ID/version; M1 deployed version verification |
| G3 soy-change full browser demo | **NOT RUN** | Actual spec/formula/baseline bindings, agreed disposable resources and write window |
| Exactly-one ReviewTask for the same confirmed finding | **OWNER_EVIDENCE_REQUIRED** | M4/M5 correlated finding/org/formula/label/event record and persisted task observation |
| LabelPublished.v2 application by consumers | **OWNER_EVIDENCE_REQUIRED** | M4/M5 exact business version, event/correlation and consumer-application evidence |
| M4 human acceptance and formal task closure | **PENDING** | Executed evidence and designated review; no tracker transition inferred from CI |

M1's local seeds do not establish a staging-loaded dataset or baseline label.
M4 must publish the staging baseline; M1 will record its label ID/business version
alongside the actual specification/formula tuple. M2's cloud load/failover results
remain separate owner work and are not fabricated here. The demo mutates versions
and is not idempotent; owners must reconcile actual resources before a rerun.
[Demo procedure and inputs](../G3-M3-demo-acceptance.md).

## Deployment and Reproduction

Pages uses a stable repository base/root callback and public Gateway/OIDC values
at build time. `g1-release.json` lets deployed checks compare the actual published
source/configuration with their target. Missing inputs or incompatible endpoints
fail before credentials are entered. M5 supplies HTTPS routing, exact issuer,
private JWKS/service bindings, deployment revisions, readiness and access/window;
the repository administrator configures Actions Pages.
[M5 input matrix](../G1-M3-M5-inputs.md).

M5 has reported infrastructure/private-connectivity progress. M3 has no live
execution evidence for this report. Runner installation or a Gateway Running/
readiness result alone cannot establish authenticated business integration.
No cloud percentage, completion promise or speculative performance measurement is
used as an acceptance result.

After the initial report snapshot, the team merged PR58 at main a0fc86b. M3
verified its complete runtime environment with seven parameter/target tests and
32 shared Helm checks. The protected release workflow is now available, but the
observed staging issuer and Pages endpoint inputs remain absent. The separate
post-merge M3 CI video-step timeout and one bounded retry are tracked in the
[dated post58 readiness note](../G1-M3-post58-readiness.md). This progress does not
change the earlier immutable test/image evidence or the live NOT RUN entries.

The [17 October public preflight](../evidence/G1-M3-staging-preflight-20261017.md)
records the later runtime state separately: TLS and discovery pass, and anonymous
API access returns canonical 401, but the staging root/build metadata remain 404,
the root login callback is rejected and the new-origin preflight returns 403.
Approved identities and a test/shutdown window have not been delivered to M3.
Real browser sign-in, authenticated service identity/API and sign-out remain
NOT RUN; no business workload readiness or E2E result is inferred from these
public checks. M3's delivered image remains available for the owner-controlled
frontend rollout.

From `frontend`, use `npm run build`, `npm run test:types`, `npm run test:signin`,
`npm run test:pages` and `npm run test:deployed-config` for independent validation.
`npm run test:deployed` is the read-only live G1 smoke; `npm run test:demo` is the
separately gated live business demo. Both require actual environment inputs;
neither is launched by fixture tests. The local environment must use a supported
Node version and an installed Playwright browser. The submission checklist records
what can be reproduced without a deployed system.

## Contribution, Limits and Closure

M3 delivered Gateway/React integration, permission-aware label screens, impact
inbox, consistent frontend presentation, browser automation and this report
contribution. The G1 foundation was adopted through
[PR #37](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/pull/37);
the later acceptance preparation/inbox/real-G2/UI delivery through PR #54.
Both were merged by other team members; M3 did not perform those merges.

For §4.4/§6, reuse is evidenced by one public authenticated client shared by Labels
and Inbox, shared UI controls/tokens, existing public DTO contracts and the
existing starter/event mechanisms in the real-component test setup. Version and
identity guards remain at the consumer boundary; extensions do not acquire direct
database access. Domain implementations and the shared starter belong to their
respective M1/M2/M4 owners, not M3. This is source/integration reuse evidence,
not a measured new-service onboarding experiment.

STCN-103/104 partner/Notification onboarding is **DESCOPED**. No onboarding duration,
effort log, load result or failover time is invented. G0's historical delivery and
G1/G2/G3 independent implementation are distinct from live acceptance and formal
Jira closure. Existing work-order status/window fields are planning history;
this report does not update Jira or reinterpret them as executed acceptance.

The eight-section contribution, dated task checklist, immutable CI/artifact index
and safe business screenshot/JSON are prepared for today's draft handoff. M4's
report review and M1's final integration remain pending. Later executed results
must be appended with exact build/image/version/date references, or retain the
explicit NOT RUN entries above. The private local handoff file and actual
credentials are excluded from this public delivery.
