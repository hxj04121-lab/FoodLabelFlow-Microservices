#!/usr/bin/env bash
set -euo pipefail

mode="${1:-}"
cluster_name="${STCN_EKS_CLUSTER_NAME:-}"
region="${AWS_REGION:-ap-southeast-1}"
gateway_namespace="${STCN_GATEWAY_NAMESPACE:-gateway}"
gateway_binding="${STCN_GATEWAY_BINDING:-gateway-gateway}"
target_group_arn="${STCN_ALB_TARGET_GROUP_ARN:-}"
load_balancer_arn="${STCN_ALB_ARN:-}"
certificate_arn="${STCN_ACM_CERTIFICATE_ARN:-}"
keycloak_namespace="${STCN_KEYCLOAK_NAMESPACE:-keycloak}"
keycloak_binding="${STCN_KEYCLOAK_BINDING:-spectrace-keycloak}"
keycloak_target_group_arn="${STCN_KEYCLOAK_TARGET_GROUP_ARN:-}"
vpc_id="${STCN_VPC_ID:-}"

if [[ "$region" != "ap-southeast-1" || -z "$cluster_name" ]]; then
  echo "set STCN_EKS_CLUSTER_NAME and use AWS_REGION=ap-southeast-1" >&2
  exit 2
fi
for command in aws jq; do
  command -v "$command" >/dev/null || { echo "missing required command: $command" >&2; exit 127; }
done

case "$mode" in
  foundations|deploy)
    command -v kubectl >/dev/null || { echo "missing required command: kubectl" >&2; exit 127; }
    aws eks describe-addon --region "$region" --cluster-name "$cluster_name" --addon-name aws-ebs-csi-driver \
      --query 'addon.status' --output text | grep -qx ACTIVE
    kubectl -n kube-system rollout status deployment/aws-load-balancer-controller --timeout=180s
    kubectl -n kube-system rollout status deployment/cluster-autoscaler --timeout=180s
    kubectl -n kube-system rollout status deployment/metrics-server --timeout=180s
    kubectl -n kube-system rollout status daemonset/aws-node --timeout=180s
    kubectl -n kube-system get daemonset aws-node -o json | jq -e '
      any(.spec.template.spec.containers[]; .name == "aws-eks-nodeagent" and any(.args[]?; . == "--enable-network-policy=true")) and
      any(.spec.template.spec.containers[]; .name == "aws-node" and any(.env[]?; .name == "NETWORK_POLICY_ENFORCING_MODE" and .value == "strict"))
    ' >/dev/null
    kubectl -n kube-system rollout status deployment/ebs-csi-controller --timeout=180s
    kubectl get apiservice v1beta1.metrics.k8s.io -o json | jq -e 'any(.status.conditions[]; .type == "Available" and .status == "True")' >/dev/null
    kubectl get storageclass ebs-gp3 -o json | jq -e '.provisioner == "ebs.csi.aws.com" and .volumeBindingMode == "WaitForFirstConsumer" and .reclaimPolicy == "Delete" and .parameters.type == "gp3" and .parameters.encrypted == "true" and .allowVolumeExpansion == true' >/dev/null
    if [[ "$mode" == "deploy" ]]; then
      [[ "$certificate_arn" == arn:aws:acm:ap-southeast-1:*:certificate/* && -n "$target_group_arn" && -n "$keycloak_target_group_arn" && -n "$load_balancer_arn" && "$vpc_id" == vpc-* ]] || { echo "set issued certificate, existing ALB, both target-group ARNs and the staging VPC ID" >&2; exit 2; }
      kubectl -n "$gateway_namespace" get targetgroupbinding "$gateway_binding" -o json | jq -e --arg arn "$target_group_arn" '.spec.targetGroupARN == $arn and .spec.targetType == "ip" and .spec.serviceRef.name == "gateway-gateway" and .spec.serviceRef.port == 8080' >/dev/null
      kubectl -n "$keycloak_namespace" get targetgroupbinding "$keycloak_binding" -o json | jq -e --arg arn "$keycloak_target_group_arn" '.spec.targetGroupARN == $arn and .spec.targetType == "ip" and .spec.serviceRef.name == "spectrace-keycloak-service" and .spec.serviceRef.port == 8080' >/dev/null
      listener_arn="$(aws elbv2 describe-listeners --region "$region" --load-balancer-arn "$load_balancer_arn" --output json | jq -er --arg cert "$certificate_arn" '[.Listeners[] | select(.Protocol == "HTTPS" and .Port == 443 and any(.Certificates[]; .CertificateArn == $cert))] | if length == 1 then .[0].ListenerArn else error("expected one HTTPS listener with the supplied certificate") end')"
      aws elbv2 describe-rules --region "$region" --listener-arn "$listener_arn" --output json | jq -e --arg keycloak "$keycloak_target_group_arn" --arg gateway "$target_group_arn" '
        any(.Rules[]; any(.Conditions[]?; .Field == "path-pattern" and any(.Values[]?; . == "/auth/*")) and any(.Actions[]?; .Type == "forward" and .TargetGroupArn == $keycloak)) and
        any(.Rules[]; any(.Conditions[]?; .Field == "path-pattern" and any(.Values[]?; . == "/api/*")) and any(.Actions[]?; .Type == "forward" and .TargetGroupArn == $gateway))
      ' >/dev/null
      aws elbv2 describe-target-groups --region "$region" --target-group-arns "$target_group_arn" "$keycloak_target_group_arn" --output json | jq -e --arg vpc "$vpc_id" --arg gateway "$target_group_arn" --arg keycloak "$keycloak_target_group_arn" '
        (.TargetGroups | length == 2) and
        all(.TargetGroups[]; .TargetType == "ip" and .VpcId == $vpc and (.TargetGroupArn == $gateway or .TargetGroupArn == $keycloak)) and
        any(.TargetGroups[]; .TargetGroupArn == $gateway) and any(.TargetGroups[]; .TargetGroupArn == $keycloak)
      ' >/dev/null
      for group_arn in "$target_group_arn" "$keycloak_target_group_arn"; do
        aws elbv2 describe-target-health --region "$region" --target-group-arn "$group_arn" --output json | jq -e '.TargetHealthDescriptions | length > 0 and all(.[]; .TargetHealth.State == "healthy")' >/dev/null || {
          echo "target group has missing or unhealthy targets: $group_arn" >&2
          exit 1
        }
      done
    fi
    kubectl get networkpolicy -A -o json | jq -e 'any(.items[]; .metadata.name == "stcn-kube-system-required-traffic") and any(.items[]; .metadata.name == "stcn-default-deny-gateway")' >/dev/null
    echo "EKS foundations are ready; both existing-ALB path routes and target groups were checked in deploy mode."
    ;;
  cleanup)
    error_file="$(mktemp)"
    trap 'rm -f "$error_file"' EXIT
    if aws eks describe-cluster --region "$region" --name "$cluster_name" >/dev/null 2>"$error_file"; then
      echo "EKS cluster still exists: $cluster_name" >&2
      exit 1
    elif ! grep -q 'ResourceNotFoundException' "$error_file"; then
      cat "$error_file" >&2
      echo "could not prove the EKS cluster is absent" >&2
      exit 1
    fi
    resources="$(aws resourcegroupstaggingapi get-resources --region "$region" \
      --tag-filters Key=Project,Values=SpecTrace-CN Key=Environment,Values=staging \
      --query 'ResourceTagMappingList[].ResourceARN' --output json)"
    jq -e 'map(select(contains(":s3:::" ) | not)) | length == 0' <<<"$resources" >/dev/null || {
      echo "tagged staging resources remain (the Terraform state bucket is excluded)" >&2
      jq -r 'map(select(contains(":s3:::" ) | not))[]' <<<"$resources" >&2
      exit 1
    }
    echo "Staging cluster and project-tagged resources are absent; the Terraform state bucket may remain."
    ;;
  *)
    echo "usage: $0 foundations|deploy|cleanup" >&2
    exit 2
    ;;
esac
