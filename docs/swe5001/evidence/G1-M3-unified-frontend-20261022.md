# M3 unified frontend delivery — 22 October 2026

The production PKCE entry now opens the Dashboard shell. Products, Formula versions,
Suppliers, Materials & specs, Labels, Change impact and Review workspace share the
same signed-in identity and public Gateway. Impact and review reuse the existing
business components; they are no longer placeholder routes in the production shell.

## Delivered behaviour

- Root and Pages login preserve the intended protected route. The shell displays
  the actual account/organisation and supports sign-out; a 401 removes protected data.
- Catalog uses native Specification/Formulation public contracts, bearer tokens and
  correlation IDs. Product/formula ownership, version references and cursor pages
  are checked before display. Legacy development headers/API are localhost-only.
- Manufacturer data maintainers can create formula drafts from released specification
  projections and explicitly release them with `expectedCurrentFormulaVersionId`.
  Malformed/unknown write results stay unconfirmed and locked. A 409 requires a fresh
  read and another explicit confirmation. Unsaved input has a navigation guard.
- Impact links an exact label version to Review workspace. Existing role checks,
  maker/checker rules, validation, decisions and publication are retained. No task-list
  endpoint, backend health aggregate or preview data is invented.

## Published image and M5 handoff

Source: `cc990e22f46e8c083b8764a5ca0e8f913d9ebe16`.

```text
ghcr.io/hxj04121-lab/foodlabelflow-microservices/frontend@sha256:0cdc79235d96e0992d8aacbf048d08c9ce3d73ee68b4f5eba9179da35ae7aa29
```

The SHA tag is the full source above. The [image receipt](G1-M3-frontend-release-20261022.json)
records the source, actual registry digest, build metadata and verified platform.
The [container build and actual nginx smoke](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/actions/runs/39733036986/job/119672224907)
and [publisher](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/actions/runs/39733036986/job/119672416248)
both passed. Anonymous registry lookup confirms the SHA tag and digest resolve to
the same manifest and the registry config matches the tested image archive.

Runtime contract is unchanged: Linux/amd64, port 8080, static `GET /healthz` returns
200 `ok`, root SPA fallback, public issuer, same-origin `/api` requests, existing
`spectrace-web` PKCE S256 client. The image declares user 101; the existing Kubernetes
security context remains unchanged. `/api/*` and `/auth/*` retain higher ALB priority.
Helm values and all four protected-release digest checks pin this reviewed image.

M5 deploys only after PR review/merge and M1 staging approval, then returns the Pod
image ID, root/release metadata and test window. These delivery jobs did not deploy
EKS or modify the running realm. The source image SHA is intentionally distinct from
the later configuration/evidence commit; no runtime source changes follow this image.

## Validation and limits

- Local authenticated browser coverage: 85 cases exercised; 83 passed in the final
  full run, and 2 affected cases passed on targeted reruns (a development-server
  reload interruption and the collapsed mobile-nav assertion). The latter selector
  now checks the active DOM link while the sidebar is hidden.
- Existing Catalog fixtures: 67 passed across runs, 3 opt-in live cases skipped.
  The separate 2 human live-evidence cases cannot run without the local backend/Docker;
  they are excluded from offline acceptance with CI's `--grep-invert 'captures.*live'`.
- Root browser 3, Pages 7, deployed-config 4, application/test TypeScript and production
  builds passed. Local root/Pages build startup allowance was 180s; assertions were
  unchanged. Desktop and 390px Catalog/form/Impact/Labels screenshots inspected.
- All 60 Helm checks passed after updating the receipt and exact digest assertions;
  deployment YAML and all 11 Bash blocks parse. Deployment approval/budget/RBAC gates
  are retained. Windows checks select the bundled Python interpreter for `python3`.
- Fresh read-only review identified subject-length validation, contradictory release
  success and an unrealistic conflict fixture; all 3 were fixed, with failing-before /
  passing-after regressions, and independently rechecked without remaining findings.

Exact source/head CI remains independently visible on
[PR145](https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/pull/145).
The successful image jobs above are not a claim that every workflow or live acceptance
has completed. Historical 18 October evidence remains unchanged.

## Remaining live M3 acceptance

After deployment, verify browser login, native identity, Catalog/API organisation
isolation and logout with valid staging credentials. Then run maker/checker label
validation → submit → decision → publish and inspect real Impact findings against
M1's specification/formula versions. Full M3 acceptance needs Spec/Form as well as
Label Workflow, Compliance and their dependencies. M4/M5 must supply actual baseline
label/event/ReviewTask evidence; a finding alone does not prove task delivery.

The previously supplied staging credential candidates were rejected. No repeated
credential guesses or one-shot business demonstration were attempted in this change.
No password/token or local handoff is included. Jira closure, AWS deployment and
live business E2E remain separate from this implementation delivery.
