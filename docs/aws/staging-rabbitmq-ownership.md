# RabbitMQ staging ownership boundary

## Independently managed cluster operators

M1, as AWS account owner, independently installs and maintains cert-manager v1.21.2, RabbitMQ Cluster Operator v2.22.3, and Messaging Topology Operator v1.20.2 from the reviewed bytes in [`operator-manifests.lock`](../../deploy/operators/rabbitmq/operator-manifests.lock). The CloudShell procedure is in [`install-rabbitmq-operators-cloudshell.md`](runbooks/install-rabbitmq-operators-cloudshell.md).

These cluster-scoped namespaces, CRDs, ClusterRoles, ClusterRoleBindings, webhook configurations, operator Services, Deployments, and certificates are outside the RabbitMQ GitHub Workflow's ownership. The RabbitMQ deploy workflow cannot install, upgrade, patch, or delete them; only the separate protected Operator bootstrap workflow creates them once, using `AWS_DEPLOY_ROLE_ARN` under M1's 21 Oct decision. The workflow fails its read-only preflight if the expected CRDs and operator Deployments are not already available. An absent read-only permission is a blocker for M1 to review separately; it is not a reason to grant cluster-admin.

## M5 RabbitMQ deployment ownership

After PR #89 has been reviewed, merged, and applied under its protected process, the RabbitMQ Workflow uses only the existing `AWS_DEPLOY_ROLE_ARN` and the namespace-scoped `spectrace-staging-deploy` RoleBindings declared by that infrastructure change. It creates only the `rabbitmq` namespace RabbitmqCluster and PodDisruptionBudget, the reviewed service and smoke User/Permission objects, and the exchanges, queues, bindings, and policies rendered from the single RabbitMQ topology Helm chart. Its safe-apply helper is create-only, adds the `staging.spectrace.cn/managed-by: rabbitmq-protected-workflow` marker, and refuses unknown ownership or drift.

The Workflow never manages operator resources, Terraform resources, IAM, cluster-scoped Kubernetes RBAC, namespaces, or pre-existing RabbitMQ data. If an expected RabbitmqCluster is absent while StatefulSets, Pods, or PVCs already exist in `rabbitmq`, it stops for owner investigation. It never deletes or replaces a StatefulSet or PVC.

## Generated credential Secret label behavior

Messaging Topology Operator v1.20.2 automatically labels its generated `<user>-user-credentials` Secrets with `rabbitmq.com/topology-operator: "true"`. Its upstream [`api/v1beta1/labels.go`](https://github.com/rabbitmq/messaging-topology-operator/blob/v1.20.2/api/v1beta1/labels.go) documents that user-provided Secret references instead must carry this label so the restricted Secret informer can see them and admission can accept them. The staging `User` resources omit `importCredentialsSecret`, so the workflow waits for and reads the operator-generated credential Secrets; it does not create, patch, label, or print Secret contents. Business Users have no management tag. Only the isolated `staging-smoke` User has the management tag required for the Management HTTP API publish/consume smoke.
