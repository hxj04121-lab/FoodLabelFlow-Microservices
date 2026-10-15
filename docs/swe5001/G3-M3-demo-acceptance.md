# M3 demo-path acceptance — 14 October 2026

STCN-102 belongs to M3. The prepared Playwright profile follows the public demo
path using real, separate supplier, manufacturer maker and checker identities.
The deployed profile is **NOT RUN** until the owner inputs below are available.
The Impact inbox's local protocol fixtures are independent UI evidence.

## Owner inputs and ordering

1. M5 integrates the reviewed RabbitMQ bindings and supplies the reachable HTTPS
   Gateway/issuer, private service/JWKS bindings, immutable source/image revisions,
   readiness, access and the agreed demo window. See [G1 inputs](G1-M3-M5-inputs.md).
2. The repository administrator configures Actions Pages. Adopt the M3 delivery
   through normal human review; M3 does not merge PRs. Publish the actual frontend
   build and its `g1-release.json` before the deployed profile.
3. M1 confirms the actual deployed product, current formula, Chocolate Base
   previous specification, provenance and soy ingredient IDs. Local seeded data
   already exists; this requirement concerns the actual deployment.
4. M1/M4 prepare and identify a **current PUBLISHED baseline label** for that
   product/jurisdiction, with declarations that make the soy change meaningful.
   Seeded products/formulas alone are insufficient for this Phase 2 scenario.
5. M4 supplies approved native subjects, organisations, complete roles and test
   credentials through the secret channel. The maker needs LABEL_OFFICER,
   CHANGE_MANAGER and ADMIN_DATA_MAINTENANCE; the distinct checker needs APPROVER
   and PUBLISHER; the supplier needs ADMIN_DATA_MAINTENANCE for specification writes.

## Reproduction

From `frontend`, load the real environment variables according to the existing
[G1 deployed instructions](G1-M3-staging-acceptance.md). Extend them using
`frontend/g3-demo.env.example`, keeping actual credentials in an ignored local
file such as `.env.g3-demo.local` or the agreed secret channel. The config does not
auto-load an env file. Missing inputs fail during config loading, before browser
execution. `M3_DEMO_ALLOW_WRITES=true` enables only the owner-agreed disposable
demo writes; leave it unset until the resources/window are approved.

```powershell
npm run test:demo -- --list  # config/scope check only; no browser or network
npm run test:demo           # real owner-approved demo writes
```

The profile checks published-build/OIDC metadata before credentials are entered,
then verifies each actual caller. It checks the baseline label/current formula,
creates and releases the supplier's soy-containing candidate, polls persisted
POTENTIAL findings through tenant-bound pagination and opens exact inbox evidence.
It explicitly creates/releases the adopted formula with the expected-current
guard, polls CONFIRMED + REVIEW_REQUIRED, and opens that exact finding in the UI.
It then creates a label, records the known required allergen declarations,
validates, submits, switches to the distinct checker, approves, explicitly
confirms publication and verifies the exact current published business version.

Reads are bounded; application mutations are not automatically retried. A failed
or interrupted run may have committed resources. Check its outcome with owners
before rerunning; changing expected-current IDs to suppress a conflict is not a
recovery procedure. No data-volume reset, direct service/DB call, AWS action,
load test or Pod deletion is performed by this browser profile.

## Evidence and remaining acceptance boundary

The live profile disables trace, video and automatic failure screenshots. After
the public flow passes, it records `test-results/demo-path/browser-public-flow.json`
with the actual deployed build SHA, timestamp, exact version IDs/business numbers,
POTENTIAL/CONFIRMED finding IDs and preparation HTTP status/correlation observations;
`published.png` captures the business result after login, without a credential form.
No token/password is deliberately written to evidence.

**Public browser success does not prove exactly-one ReviewTask or LabelPublished
projection application.** There is no public unique ReviewTask query contract.
M4/M5 must attach a dated observer record linking the same finding, organisation,
formula/label versions, event ID/correlation, successful delivery and exactly one
persisted ReviewTask. They also need the exact LabelPublished.v2 business version
and consumer application evidence. These checks remain `OWNER_EVIDENCE_REQUIRED`
in the browser output; STCN-102/G3 cannot be accepted solely from that JSON.

The existing `perf/demo/run_demo.py` is an owner API diagnostic. Its explicitly
blocked observer steps are not replaced with invented public endpoints here.
M4 is the human reviewer; M1 owns full dataset/demo preparation; M5 owns deployment
and runtime topology. M2 owns validation/impact semantics and load/failover work.

STCN-103/104 were marked DESCOPED on 11 October. Partner client and Notification
onboarding are excluded from the required delivery; no measurements are fabricated.
