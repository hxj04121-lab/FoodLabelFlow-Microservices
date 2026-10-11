# G1 M2 shared service chart — STCN-48

Owner M2; reviewer M4. Prepared from merged AWS architecture/work orders at main `56931b4`. This draft supplies one chart and values for Compliance, Specification, Formulation, Label Workflow and Gateway; no service implementation is copied.

Run `npm ci` then `npm test` in this directory with Helm 4.3.0 on PATH (`HELM_BIN` can specify a portable executable). Tests run strict Helm lint and local template rendering, including negative resource/placement overrides. They never contact a Kubernetes API. Outputs are saved in `.evidence/`; the CI workflow uploads them.

Compliance values require node group `compliance`, `c6i.large`, taint `dedicated=compliance:NoSchedule`, and exactly 1 CPU / 2 GiB requests and limits. The selector restricts placement; the toleration permits this tainted pool. Zone and hostname spread use DoNotSchedule, maxSkew 1 and two minimum zones. M5 must provision matching labels/taints across two eligible AZs; unavailable domains or capacity can leave replicas Pending. Rendered selectors do not prove actual placement.

HPA is disabled for the fixed 1-vs-3 Must comparison. Setting `autoscaling.enabled=true` renders the optional Should CPU HPA (60%, 1–4). Service images are explicit `example.invalid` placeholders until their owners provide actual commit-tagged GHCR images. Starter integration must verify non-root UID 10001, writable /tmp, anonymous health probes, graceful shutdown and secret/env bindings before any deployment.

NetworkPolicy isolates ingress/egress for each release. Compliance allows same-namespace Gateway and Label Workflow callers; defaults allow DNS only. M3/M4/M5 supply explicit Gateway/Keycloak/broker/RDS/metrics egress and ALB ingress rules through values at integration, using real selectors/CIDRs. No permissive fabricated CIDR is inserted. The CNI must enforce policies. Static checks do not prove policy enforcement or authentication.

See [node-pool handoff](../../docs/swe5001/G1-M2-node-pool-interface.md). G0 merge/acceptance, M1 starter and M5 Terraform/runtime evidence remain dependencies. No apply, resource creation or deployment is performed by these checks.

References: [Helm lint](https://helm.sh/docs/helm/helm_lint/), [Kubernetes topology spread](https://kubernetes.io/docs/concepts/scheduling-eviction/topology-spread-constraints/), [NetworkPolicy](https://kubernetes.io/docs/concepts/services-networking/network-policies/).
