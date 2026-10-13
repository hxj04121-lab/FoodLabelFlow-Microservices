# NetworkPolicy allow-list — G1-M4.4

Authority: architecture source of truth v3 §11.2 (threat: *gateway bypass* —
"ClusterIP services; NetworkPolicy allows only gateway (and label-workflow →
compliance)") and §12.2 (the Helm chart ships the NetworkPolicy stanza). The
policy is enforced by the EKS VPC CNI; nothing here depends on a service mesh.

The Helm values **are** the allow-list: the chart default
(`deploy/helm/spectrace-service/values.yaml`) allows the gateway, a service that
needs more overrides it in `deploy/helm/values/<service>.yaml`, and
`deploy/helm/spectrace-service/templates/networkpolicy.yaml` renders one
`NetworkPolicy` per release with `policyTypes: [Ingress, Egress]` and a pod
selector for that service. Everything not listed is denied. Today only
`compliance` overrides the default (it adds `label-workflow`); the other three
services inherit it.

## Ingress (who may reach a service pod)

| Target | Allowed peers | Port | Why |
|---|---|---|---|
| `specification` | `gateway` | `http` (8080) | public API only through the gateway |
| `formulation` | `gateway` | `http` | public API only through the gateway |
| `label-workflow` | `gateway` | `http` | public API only through the gateway |
| `compliance` | `gateway`, `label-workflow` | `http` | public API plus the one synchronous validation call label-workflow makes to `/internal/**` |
| `gateway` | *(ALB — see open items)* | `http` | north-south edge; the AWS Load Balancer Controller targets the pods |

Kubelet probes are not affected: in the VPC CNI they originate from the node
itself, and the network policy engine does not apply to that traffic.

## Egress (what a pod may reach)

| Source | Target | Port | Why |
|---|---|---|---|
| every pod | `kube-dns` (kube-system) | 53 UDP/TCP | name resolution; `networkPolicy.allowDNS` |
| every service | its MySQL database (RDS) | 3306 | own database and own credentials only |
| `specification`, `formulation`, `label-workflow`, `compliance` | RabbitMQ | 5672 | outbox relay and idempotent consumers |
| every service, `gateway` | Keycloak | `http` | JWKS for local JWT validation, `/auth` at the edge |
| `gateway` | `specification`, `formulation`, `label-workflow`, `compliance` | `http` | routing |
| `keycloak` | its database | 3306 | realm and user store |

Egress rules are deployment values, not chart defaults: the RDS endpoint and the
broker/Keycloak services exist only in a live cluster, so each deployment passes
`networkPolicy.egressRules` (or a namespace-wide default) for its environment.
Until it does, a service can resolve names but cannot reach its database, which
fails closed and loudly.

## What NetworkPolicy cannot express

- **Paths.** The requirement is "label-workflow → compliance `/internal/**`
  only", but a NetworkPolicy peer cannot be a path. This allow-list pins the
  peer and port; the path scope is enforced inside the Compliance service (only
  `/internal/**` accepts the label-workflow caller; public `/api/**` still
  requires an end-user token). Both halves are needed — the policy stops every
  other pod, the service stops every other path.
- **Identity beyond labels.** Peers are selected by pod labels
  (`app.kubernetes.io/name`), so a workload that can set those labels inside the
  namespace can forge a peer. Namespace hygiene and RBAC are the control.

## Verification

`backend/src/test/java/com/spectrace/deploy/HelmNetworkPolicyContractTest.java`
reads the five values files and fails when a service stops allowing the gateway,
when `compliance` stops allowing `label-workflow`, when any other service gains a
service-to-service peer, or when the chart stops rendering the allow-list. It
runs in the backend job of CI (`mvn -B -ntp -f backend/pom.xml verify`), next to
the realm contract test. Locally:

```bash
mvn -B -ntp -f backend/pom.xml test -Dtest=HelmNetworkPolicyContractTest
```

## Open items

1. **ALB → gateway ingress.** The gateway is reached by the AWS Load Balancer
   Controller, not by an in-cluster pod, so it cannot be expressed with
   `ingressFromServices`. Until M3 supplies the real selector, `gateway.yaml`
   keeps the list empty — which is default-deny and therefore safe, but the demo
   window must add the ALB peers (or allow the ingress-controller namespace).
2. **Keycloak and RabbitMQ placement.** The rules above assume they run in the
   same cluster with stable labels (the operators from G1-M5). If Keycloak's
   database becomes RDS, its egress rule moves to the same ipBlock/CIDR form as
   the services'.
3. **kube-dns labels.** `allowDNS` assumes the standard `k8s-app: kube-dns`
   label in `kube-system`; verify it on the staging cluster before the window.
