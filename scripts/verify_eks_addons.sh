#!/usr/bin/env bash
set -euo pipefail

mode="${1:-}"
cluster_name="${STCN_EKS_CLUSTER_NAME:-}"
region="${AWS_REGION:-ap-southeast-1}"
gateway_namespace="${STCN_GATEWAY_NAMESPACE:-gateway}"
gateway_ingress="${STCN_GATEWAY_INGRESS:-gateway}"
certificate_arn="${STCN_ACM_CERTIFICATE_ARN:-}"
if [[ "$region" != "ap-southeast-1" || -z "$cluster_name" ]]; then
  echo "set STCN_EKS_CLUSTER_NAME and use AWS_REGION=ap-southeast-1" >&2
  exit 2
fi
for command in aws jq; do
  command -v "$command" >/dev/null || { echo "missing required command: $command" >&2; exit 127; }
done

case "$mode" in
  deploy)
    command -v kubectl >/dev/null || { echo "missing required command: kubectl" >&2; exit 127; }
    [[ "$certificate_arn" == arn:aws:acm:ap-southeast-1:*:certificate/* ]] || { echo "set STCN_ACM_CERTIFICATE_ARN to the issued staging ACM certificate" >&2; exit 2; }
    for addon in vpc-cni aws-ebs-csi-driver; do
      aws eks describe-addon --region "$region" --cluster-name "$cluster_name" --addon-name "$addon" \
        --query 'addon.status' --output text | grep -qx ACTIVE
    done
    vpc_config="$(aws eks describe-addon --region "$region" --cluster-name "$cluster_name" --addon-name vpc-cni --query 'addon.configurationValues' --output text)"
    jq -e '.enableNetworkPolicy == "true" and .env.NETWORK_POLICY_ENFORCING_MODE == "strict"' <<<"$vpc_config" >/dev/null
    kubectl -n kube-system rollout status deployment/aws-load-balancer-controller --timeout=180s
    kubectl -n kube-system rollout status deployment/cluster-autoscaler --timeout=180s
    kubectl -n kube-system rollout status deployment/metrics-server --timeout=180s
    kubectl -n kube-system rollout status daemonset/aws-node --timeout=180s
    kubectl -n kube-system rollout status daemonset/aws-network-policy-agent --timeout=180s
    kubectl -n kube-system rollout status deployment/ebs-csi-controller --timeout=180s
    kubectl get apiservice v1beta1.metrics.k8s.io -o json | jq -e 'any(.status.conditions[]; .type == "Available" and .status == "True")' >/dev/null
    kubectl get storageclass ebs-gp3 -o json | jq -e '.provisioner == "ebs.csi.aws.com" and .volumeBindingMode == "WaitForFirstConsumer" and .reclaimPolicy == "Delete"' >/dev/null
    kubectl get ingress -A -o json | jq -e --arg cert "$certificate_arn" --arg namespace "$gateway_namespace" --arg name "$gateway_ingress" 'any(.items[]; .metadata.namespace == $namespace and .metadata.name == $name and .metadata.annotations["alb.ingress.kubernetes.io/certificate-arn"] == $cert and (.metadata.annotations["alb.ingress.kubernetes.io/listen-ports"] | fromjson | any(.[]; has("HTTPS"))) and (.status.loadBalancer.ingress | length > 0))' >/dev/null
    kubectl get networkpolicy -A -o json | jq -e 'any(.items[]; .metadata.name == "stcn-kube-system-required-traffic") and any(.items[]; .metadata.name == "stcn-default-deny-gateway")' >/dev/null
    echo "EKS add-on deployments and HTTPS ingress configuration are ready."
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
    echo "usage: $0 deploy|cleanup" >&2
    exit 2
    ;;
esac
