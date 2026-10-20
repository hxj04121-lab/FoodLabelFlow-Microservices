# M5 staging read-only inventory

Observed from the active AWS CloudShell session on 2026-10-20 at approximately 03:39 UTC; add-on IAM metadata was rechecked around 03:51 UTC. These are point-in-time observations, not a deployment acceptance run. No cluster, Terraform State, database, RabbitMQ, or IAM writes were made.

## AWS and EKS

- AWS account: `679857346122`; CloudShell caller role: `FoodLabelFlowStagingDeploy`.
- Kubernetes context: `spectrace-staging`.
- Two nodes were `Ready`; both were general-purpose nodes running Kubernetes `v1.36.4-eks-3b4a6ca`.
- The only managed node group was `general`.
- EKS add-ons list was empty. The EBS CSI driver was absent. The only StorageClass was `gp2`, with reclaim policy `Delete`; `ebs-gp3` was absent.
- The AWS Load Balancer Controller ServiceAccount annotation pointed to `spectrace-staging-aws_load_balancer_controller`. That IAM role and the `spectrace-staging-aws-load-balancer-controller` managed policy both existed; the policy was attached to the role. Terraform State ownership was not checked.
- The `spectrace-staging-ebs_csi` IAM role was absent at this observation time.

## Workloads and routing

- The `gateway` Deployment had one ready Pod with zero restarts. Its Service had endpoint `10.40.70.188:8080`.
- Gateway configuration pointed to `http://formulation-formulation.formulation.svc.cluster.local:8080` and `http://compliance-compliance.compliance.svc.cluster.local:8080`.
- Keycloak had one ready StatefulSet Pod and a Service endpoint.
- The `specification`, `formulation`, `compliance`, `label-workflow`, `rabbitmq`, and `frontend` namespaces were absent. No corresponding service workload/endpoints were available in those namespaces.
- The AWS Load Balancer Controller Deployment was ready `1/1`, with Helm management labels for release `aws-load-balancer-controller` in `kube-system`. A Helm release Secret name was visible as metadata only; its contents were not read. Cluster Autoscaler and metrics-server Deployments were absent.
- Only `gateway/gateway-gateway` was present in the NetworkPolicy inventory; platform `stcn-*` policies were absent.
- Existing TargetGroupBindings were `gateway/gateway-gateway` (Helm labels for release `gateway`) and `keycloak/spectrace-keycloak` (no Helm release labels).

## Authorization and state freshness

- The inspected `foodlabelflow-deployment-planners` ClusterRole did not contain TargetGroupBinding permissions. The exact `spectrace-staging-deployer-custom-resources` Role and RoleBinding were absent from `gateway`, `keycloak`, and `frontend`. Although `kubectl auth can-i` reported permission for those resources, the authorization source was not resolved and remains UNKNOWN.
- S3 `head-object` for `spectrace/staging/terraform.tfstate` returned VersionId `tIRsj1wl1CWHRWeAa2C1G.1QujMl.0dk`, last modified `2026-10-11T15:36:03Z`, size `121021` bytes. The object was nine days old at observation time; this does not prove its contents or AWS drift.
- Terraform State addresses, current budget approval, protected Environment approval, and a fresh Plan were not verified in this read-only session.

## Acceptance status

The two authenticated API requests `GET /api/compliance/impact-findings?limit=20` and `GET /api/formulations/products?limit=20` were not run. Their backing service namespaces and workloads were absent in this inventory, so live API acceptance remains BLOCKED. This inventory does not prove that any service has been deployed.
