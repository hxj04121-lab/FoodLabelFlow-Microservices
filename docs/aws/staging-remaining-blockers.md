# Remaining Blockers for Full Staging Deployment

**Current result: BLOCKED.** The private cluster API is reachable, but the application is not deployed and no AWS or Kubernetes write has been made.

## P0 — before deployment writes

1. **Mandatory budget gate:** Supply approved deployment-window, experiment, and load-test durations plus a verified available-credit input. Public pricing metadata is refreshed and validated; the gate was not run, bypassed, or modified. No cost estimate/report was generated.
2. **Kubernetes bootstrap access:** Current access is namespace-scoped and cannot inspect all required namespaces or create cluster-scoped components. Any temporary cluster-wide administrator access would reach all Kubernetes resources, including Secrets, and requires action-time approval plus a removal plan.
3. **Final Terraform review:** Current state-backed plans are foundations, not a full deployment plan. They include node-group changes that may roll nodes and a broad node-to-node network rule. Add-on settings and their ingress effects still need review; do not Apply an incomplete plan.
4. **Supported release path:** Main lacks a reviewed apply-capable deployment workflow. Protected runtime configuration and a VPC-connected release runner are not verified.
5. **Images and runtime values:** Immutable digests for every service and protected runtime Secret wiring are not verified. Do not deploy placeholder tags or use database administrator credentials for applications.
6. **Application route:** Internal ingress has no listener or registered targets. The owner has not supplied the approved domain, certificate, or source ranges needed for the selected route.

## P1 — workload acceptance

- No application workloads were found in readable namespaces; other scopes remain UNKNOWN.
- Core networking and DNS are Ready, while storage integration, network-policy enforcement, operators, and observability remain unverified.
- Deploy only after confirming node capacity, workload placement, persistent-volume behavior, and required isolation policies from the cluster.
- Create isolated database identities for each service and identity provider. Inject values only through the protected release path.
- Run live database, messaging, identity, service-to-service, gateway, network-isolation, and frontend checks. CI/local validation is not staging acceptance.

## Next write sequence

After the budget gate passes and access is approved, prepare a complete state-backed plan, inventory the cluster, install only missing foundations, configure protected runtime values, deploy pinned images, and run the acceptance checks. Preserve the private API and existing database/storage data. Do not enable external ingress without approved routing inputs.

No Terraform Apply/Destroy/Import, permission change, cluster installation, database/Secret write, workload deployment, or cleanup has been executed.
