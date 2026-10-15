# NetworkPolicy namespace-peer hardening (2026-10-15)

Removed the chart's pod-only `ingressFromServices` path. Service ingress now
uses only explicit namespace and pod selectors from `ingressFromPeers`; this
prevents same-namespace pods from matching a trusted service name without also
being in the expected namespace. The approved Gateway → service and Label
Workflow → Compliance paths, Alloy scrape peers, DNS, broker, Keycloak, and
database egress rules remain represented in the rendered chart.

## Local validation

- `cd deploy/helm && npm test`: 23 checks passed, including exact per-service
  namespace/pod peer assertions, empty-ingress default deny, and the Label
  Workflow → Compliance TCP/8080 path. Output: `networkpolicy-helm-test-2026-10-15.txt`.
- `mvn -B -ntp -f backend/pom.xml test -Dtest=HelmNetworkPolicyContractTest`:
  3 tests passed, 0 failures/errors/skips. Output:
  `networkpolicy-java-contract-test-2026-10-15.txt`.
- `git diff --check`: passed before commit.

These local tests ran against the working tree based on source commit
`ddd21529ca93662ac31406954a606f0967a8b054`. They do not prove that GitHub
Actions passed for the eventual commit or that the private staging cluster
enforces the policy. Live allowed/denied pod probes remain pending an approved
VPC-connected runner and G2 staging window.
