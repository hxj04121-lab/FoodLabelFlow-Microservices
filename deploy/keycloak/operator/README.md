# Keycloak Operator staging bootstrap

This bundle installs Keycloak Operator `26.0.7` as a single-workload-namespace controller. The Operator runs in the existing `keycloak-system` namespace and watches only `keycloak`; its Role and RoleBinding grant access only inside `keycloak`. The CRDs are the only cluster-scoped resources. No ClusterRole or ClusterRoleBinding is installed.

## M5: one-time CRD bootstrap and Operator installation

First run the reviewed Terraform Plan and approved infrastructure change so the existing `keycloak` and `keycloak-system` namespaces, EBS CSI, NetworkPolicies, and AWS Load Balancer Controller are ready. The Plan is expected to reuse the existing cluster and node group; inspect the actual state-backed Plan before any Apply.

CRDs are cluster-scoped. The **Protected one-time Keycloak Operator bootstrap** workflow uses a distinct `AWS_OPERATOR_INSTALL_ROLE_ARN`, only from `main`, on the VPC runner, with staging approval and the existing budget gate. Its EKS access must be temporarily cluster-scoped (typically `AmazonEKSClusterAdminPolicy` or equivalent Kubernetes admin) because CRD creation and the Operator Role's `escalate`/`bind` checks cannot be delegated through the existing namespace deploy role. This is a broad one-time grant; use OIDC trust restricted to this repository, `main`, and the protected `staging` Environment, then remove the EKS access association immediately after rollout. The workflow applies only the two pinned CRDs and namespace-scoped Operator files. Never grant this access to `AWS_DEPLOY_ROLE_ARN` or use it for routine deployments.

The Operator's namespaced Role includes Secret access in `keycloak`, because the controller must read database and realm-import Secrets and create Keycloak-generated Secrets. Treat the ability to create Keycloak CRs and the temporary CRD installer association as privileged staging actions.

## M5: database and runtime Secret

The approved database bootstrap adds the isolated `keycloak` schema and account using the protected `KEYCLOAK_DB_PASSWORD` secret. The protected **STCN-15 staging runtime Secrets** workflow writes only `keycloak/keycloak-db` with `username` and `password` keys; no credential is committed or placed in kubectl arguments. Its apply path requires the existing funding gate and `staging` approval. The helper distributes the same protected `GATEWAY_ISSUER` value to the business services. The protected credit input is the existing Environment variable `STAGING_AVAILABLE_CREDIT_USD`.

## M4: deploy the Keycloak server and bind the existing target group

Set protected staging Environment variables `GATEWAY_ISSUER=https://staging.52zz.top/auth/realms/spectrace` and `MYSQL_HOST=<private RDS hostname>`. After the Operator is Ready and `keycloak/keycloak-db` exists, dispatch **Protected Keycloak staging release** from `main`. It passes the funding gate, checks the existing account/context, verifies the namespaced custom-resource permissions, renders the CR without database credentials, applies the `Keycloak` CR, waits for readiness, then applies `deploy/kubernetes/staging/keycloak-targetgroupbinding.yaml`.

The CR creates `spectrace-keycloak-service` in namespace `keycloak`, uses MySQL, accepts `xforwarded` headers, publishes `/auth`, disables Operator-managed ingress creation, and uses the staging realm issuer's host as its Keycloak hostname. It leaves Keycloak's default memory request (1700 Mi) and 2 Gi limit in place. No realm or bootstrap password is embedded in the CR. Terraform maps the protected deploy role to a dedicated Kubernetes group with only Keycloak/TGB custom-resource access in `keycloak`; the standard deploy workflow still cannot create namespaces or install the Operator.

The existing `KeycloakRealmImport` remains generated from `deploy/keycloak/realm/spectrace-realm.json`. Apply it only when the protected `spectrace-demo-credentials` Secret exists; this PR does not create those demo-user credentials. Check its `Done` condition before attempting authenticated login.

## Capacity and network checks

Declared request estimates before Kubernetes/system overhead and EBS CSI are: AWS Load Balancer Controller 2 × (100m CPU, 128 Mi); Cluster Autoscaler 100m/256 Mi; metrics-server 2 × (100m/200 Mi); Keycloak Operator 300m/450 Mi; one Keycloak instance 1700 Mi memory; Gateway 100m/256 Mi. The known subtotal is about 900m CPU plus Keycloak's runtime CPU demand, and 3318 Mi memory, before EBS CSI, CNI, kube-proxy, CoreDNS, and node reservations. The actual node allocatable and EBS CSI requests are not available from this workstation. One `t3.medium` is not a safe capacity assumption; after the budget gate opens, use the existing general node group's 1–3 bound and let the approved Cluster Autoscaler add a second node if measured requests require it.

Once both Pods are registered, run `scripts/verify_eks_addons.sh deploy` from the authorized VPC path. It checks the existing HTTPS listener routes, target-group VPC/IP type/HTTP health checks, target health, ENI/backend and ALB security-group paths on TCP/8080, and Gateway/Keycloak NetworkPolicies against actual ALB subnets. The check is read-only; the LBC policy has no EC2 security-group mutation actions. Live AWS/Kubernetes results must be recorded separately from this static bundle and its tests.

## Realm import compatibility

`keycloak-realm-import.yaml` is a `KeycloakRealmImport` (`k8s.keycloak.org/v2alpha1`) generated from `../realm/spectrace-realm.json`. Run `python3 deploy/keycloak/scripts/generate-realm-import.py --check` to verify the committed copy. The importer creates the realm, clients, client scopes, mappers, and demo users on CR `spectrace-keycloak`; it reports `Done` or `HasErrors` and runs the `spectrace-realm` Job.
