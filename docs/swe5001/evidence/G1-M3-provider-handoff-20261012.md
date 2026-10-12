# G1-M3 provider handoff follow-up — 12 October 2026

Owner M3 Xu Feiyang. Source branch XFY, PR37. This follows the earlier pinned
image/test-service evidence; that earlier evidence remains historical and unchanged.

## Delivered source and local checks

- Accepted main8316071 (#34 realm and #36 starter) is synchronized through an
  ordinary merge; accepted #46 regression/stacked CI is preserved.
- Frontend configuration and browser suites use `/api/specifications/caller-claims`.
  Gateway already routes the Specification prefix unchanged. The path and DTO
  are recorded in `gateway-routes.md`; no new Gateway route or rewrite is needed.
- Login and logout callbacks are exactly
  `https://hxj04121-lab.github.io/FoodLabelFlow-Microservices/`.
  A Pages browser test confirms that a hash-route login redirects to the project
  root without a hash. The same callback value configures both OIDC operations.
- Local base Compose and the Gateway override use `/auth/realms/spectrace`;
  all four services bind external issuer/private JWKS/spectrace-api through
  both Spring resource-server and starter settings. Base direct login uses8180;
  Gateway-fronted G1 uses8080. Switching the entry requires new tokens.
  The Gateway override automatically imports the accepted tracked realm.
- G1 remains JWT-only. The baseline database-backed legacy seam stays in backend;
  the starter's disabled local/test explicit-header fixture is a separate interface.
  No invented organisation/roles or header rename bridges these trust models.
- Specification's own CallerResolver now rejects missing, empty and whitespace
  native sub even when preferred_username is present. Two unit regressions pass;
  this supplies a concrete repair for M2's still-open PR47 subject finding.
- Local16 sign-in and4 Pages checks pass. Focused accepted-starter checks pass37;
  PR47 realm/operator/NetworkPolicy contracts pass25. Compose models verify both
  Gateway ports8080/9080 and the accepted default realm, plus direct-mode bindings.

## Real-component CI and remaining acceptance

The real-keycloak job now builds the actual Specification service from pinned
PR47 source78855e7, starts real MySQL/RabbitMQ/Keycloak and exercises its caller
path through Gateway. The old test-only whoami service is removed. Its eventual
run result, claims and release digest must be recorded from actual CI artifacts;
editing this workflow is not by itself a passed real-service run.

PR47's direct-service blank-sub repair is still pending adoption by M4; the
valid-token CI candidate does not resolve that negative-case review. XFY's guard
and regression can be adopted without changing the four-field response contract.
The NetworkPolicy/import artifacts are deployment candidates: actual namespace/
Keycloak CR, egress, ALB ingress and service enforcement need M4/M5 verification.

No public HTTPS Gateway domain, certificate or authorized staging window has
been supplied. Pages stays gated until those real public OIDC inputs exist.
No AWS LB result, live Pages URL or final Jira/M4 acceptance is asserted here.
