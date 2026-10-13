# EKS add-ons (STCN-59)

The staging Terraform root includes this child module when `enable_eks_addons=true`. It consumes the existing EKS cluster, OIDC provider, Cluster Autoscaler role, node groups, VPC and ACM certificate; it creates no second VPC, cluster, NAT gateway or static ALB. Enabling the module replaces the G0 static ALB with the LBC-managed Gateway ALB. Keep it disabled until an issued `ap-southeast-1` ACM certificate, domain, reviewed ALB source CIDRs, staging window and budget gate are ready.

The module installs the AWS Load Balancer Controller, EBS CSI managed add-on, Cluster Autoscaler, metrics-server, and VPC CNI NetworkPolicy enforcement. It creates exact-subject IRSA roles for LBC, EBS CSI and `aws-node`; Cluster Autoscaler reuses the existing EKS module role and ASG discovery tags. Add-on versions are selected for the configured EKS version; Helm charts are pinned. AWS provider credentials remain the existing profile/SSO or OIDC chain, and Helm/Kubernetes providers request short-lived tokens through `aws eks get-token`.

The EKS module exposes autoscaler ranges of general 1–3 and optional compliance 1–4. The root keeps the existing node-role CNI policy until this child module is ready, then removes it; the managed VPC CNI add-on uses the `aws-node` IRSA role. Node groups and their project tags remain owned by G0 Terraform.

## Storage and HTTPS

`ebs-gp3` is encrypted, waits for first consumer, and deletes volumes when staging claims are deleted. RabbitMQ's 8 GiB claims remain owned by STCN-60. Delete the claims and wait for their EBS volumes to disappear before `staging-down`.

The Gateway Ingress uses only HTTPS 443, the existing ACM certificate, the configured domain, the existing two public subnet tags, and explicit ALB inbound CIDRs. Terraform's graph deletes the Ingress before removing LBC, then removes the add-ons before EKS. The project-tagged ALB is checked by the cleanup verifier.

## Network policies

The Helm chart creates default-deny ingress and egress policies for eight workload namespaces and the RabbitMQ/Keycloak operator namespaces. Explicit policies permit ALB-to-Gateway, Gateway-to-services/Keycloak, app-to-RabbitMQ/Keycloak/MySQL/OTLP, Keycloak-to-MySQL, RabbitMQ peer/app/metrics, operator webhooks, DNS, metrics-server-to-kubelets, and required kube-system/API traffic. Database egress is restricted to the two private subnet CIDRs; Gateway ALB ingress is restricted to the supplied reviewed CIDRs.

The policies are installed before Terraform enables strict VPC CNI NetworkPolicy mode. Service labels and operator ports must be compared with the owning M2/M5.2 chart values before the first staging window; those service releases are not part of STCN-59. Strict mode affects all non-hostNetwork pods, including kube-system.

## Validation and lifecycle

Run `scripts/check_eks_addons.sh` for Terraform validate, Helm lint and rendered YAML validation; set `KUBECONFORM_BIN` for strict Kubernetes schema validation. Run `scripts/verify_eks_addons.sh deploy` only against an authorized staging context and supply `STCN_ACM_CERTIFICATE_ARN`. After the normal staging teardown, run `scripts/verify_eks_addons.sh cleanup` to confirm the cluster and tagged staging resources are gone. The separate Terraform state bucket is excluded.

This branch integrates the module into Terraform apply/destroy dependency ordering. The G0 branch has no staging apply/destroy script or apply workflow; STCN-37's fail-closed budget gate, G0 account readiness and an approved staging window remain prerequisites. Static validation does not prove that AWS accepts the selected add-on schema, IRSA succeeds, the ALB target is healthy, RabbitMQ mounts EBS, policies permit live traffic, or cleanup leaves no AWS resources.
