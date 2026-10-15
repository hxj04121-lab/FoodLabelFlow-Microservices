# G1 M2 shared service chart — STCN-48

Owner M2; reviewer M4. Prepared from merged AWS architecture/work orders at main `56931b4`. This draft supplies one chart and values for Compliance, Specification, Formulation, Label Workflow and Gateway; no service implementation is copied.

Run `npm ci` then `npm test` in this directory with Helm 4.3.0 on PATH (`HELM_BIN` can specify a portable executable). Tests run strict Helm lint and local template rendering, including negative resource/placement overrides. They never contact a Kubernetes API. Outputs are saved in `.evidence/`; the CI workflow uploads them.

Compliance values require node group `compliance`, `c6i.large`, taint `dedicated=compliance:NoSchedule`, and exactly 1 CPU / 2 GiB requests and limits. The selector restricts placement; the toleration permits this tainted pool. Zone and hostname spread use DoNotSchedule, maxSkew 1 and two minimum zones. M5 must provision matching labels/taints across two eligible AZs; unavailable domains or capacity can leave replicas Pending. Rendered selectors do not prove actual placement.

Set `image.repository` to the delivered service image and `image.digest` to its immutable `sha256:<64 hex>` digest for every load or failover experiment. Record that digest in the experiment evidence. The empty digest default supports ordinary tag-based development deployments; it is insufficient for experiment acceptance. When a digest is supplied the chart renders `repository@sha256:...` and ignores the tag.

HPA is disabled for the fixed 1-vs-3 Must comparison. Setting `autoscaling.enabled=true` renders the optional Should CPU HPA (60%, 1–4). Service images are explicit `example.invalid` placeholders until their owners provide actual commit-tagged GHCR images. Starter integration must verify non-root UID 10001, writable /tmp, anonymous health probes, graceful shutdown and secret/env bindings before any deployment.

NetworkPolicy isolates ingress/egress for each release. Compliance allows Gateway and Label Workflow peers selected by namespace and pod labels; defaults allow DNS only. M3/M4/M5 supply explicit Gateway/Keycloak/broker/RDS/metrics egress and ALB ingress rules through values at integration, using real selectors/CIDRs. No permissive fabricated CIDR is inserted. The CNI must enforce policies. Static checks do not prove policy enforcement or authentication.

For staging, keep each release in its service namespace (`gateway`, `specification`, `formulation`, `compliance`, or `label-workflow`), matching the deploy role and NetworkPolicy selectors. Label Workflow's `label-workflow` namespace egress explicitly reaches Compliance in `compliance` on TCP/8080. Use the single-node overlay for the general-pool business services; Compliance keeps its dedicated `c6i.large` pool and two-zone contract:

```sh
for service in specification formulation label-workflow; do
  helm upgrade --install "$service" deploy/helm/spectrace-service \
    --namespace "$service" --create-namespace \
    --values "deploy/helm/values/$service.yaml" \
    --values deploy/helm/values/staging-single-node.yaml \
    --values deploy/helm/values/staging-rds.yaml
done

helm upgrade --install compliance deploy/helm/spectrace-service \
  --namespace compliance --create-namespace \
  --values deploy/helm/values/compliance.yaml \
  --values deploy/helm/values/staging-rds.yaml
```

The database overlay allows TCP 3306 only to the two observed private RDS subnets. The optional Gateway overlay limits inbound traffic to the internal ALB subnets, preserves Alloy metrics access, and routes to backend pods in their service namespaces. The ALB still needs a working controller, listener and permitted inbound security-group rule before it can serve requests. The first Gateway staging release uses the protected `workflow_dispatch` workflow in `.github/workflows/deploy-gateway.yml`.

For an existing deployment that places Label Workflow and Compliance together in `staging`, apply `staging-shared-label-workflow.yaml` and `staging-shared-compliance.yaml` to those two releases. These opt-in overlays route Label validation to `compliance.staging` and allow only the Compliance pods on TCP/8080; the default values continue to target the separate `compliance` namespace. The deployment role must be allowed to manage the `staging` namespace.

The protected GitHub `staging` Environment supplies `STAGING_AWS_ACCOUNT_ID`, `STAGING_AWS_REGION`, and `STAGING_EKS_CLUSTER_NAME` variables, shared `STAGING_RABBITMQ_HOST`, `STAGING_RABBITMQ_PORT`, `STAGING_SECURITY_ISSUERURI`, `STAGING_SECURITY_JWKSETURI`, and `STAGING_SECURITY_AUDIENCE` secrets, plus service-scoped `STAGING_<service>_SPRING_DATASOURCE_URL`, `STAGING_<service>_SPRING_DATASOURCE_USERNAME`, `STAGING_<service>_SPRING_DATASOURCE_PASSWORD`, `STAGING_<service>_RABBITMQ_USERNAME`, and `STAGING_<service>_RABBITMQ_PASSWORD` secrets. The reusable workflow maps them to each `<service>-runtime` Secret without logging their values.

See [node-pool handoff](../../docs/swe5001/G1-M2-node-pool-interface.md). G0 merge/acceptance, M1 starter and M5 Terraform/runtime evidence remain dependencies. No apply, resource creation or deployment is performed by these checks.

References: [Helm lint](https://helm.sh/docs/helm/helm_lint/), [Kubernetes topology spread](https://kubernetes.io/docs/concepts/scheduling-eviction/topology-spread-constraints/), [NetworkPolicy](https://kubernetes.io/docs/concepts/services-networking/network-policies/).
