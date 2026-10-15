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
    kubectl --namespace kube-system get networkpolicies -o json | jq -e 'any(.items[]; .metadata.name == "stcn-kube-system-required-traffic")' >/dev/null
    kubectl --namespace "$gateway_namespace" get networkpolicies -o json | jq -e 'any(.items[]; .metadata.name == "stcn-default-deny-gateway")' >/dev/null
    if [[ "$mode" == "deploy" ]]; then
      [[ "$certificate_arn" == arn:aws:acm:ap-southeast-1:*:certificate/* && -n "$target_group_arn" && -n "$keycloak_target_group_arn" && -n "$load_balancer_arn" && "$vpc_id" == vpc-* ]] || { echo "set issued certificate, existing ALB, both target-group ARNs and the staging VPC ID" >&2; exit 2; }
      kubectl -n "$gateway_namespace" get targetgroupbinding "$gateway_binding" -o json | jq -e --arg arn "$target_group_arn" '.spec.targetGroupARN == $arn and .spec.targetType == "ip" and .spec.serviceRef.name == "gateway-gateway" and .spec.serviceRef.port == 8080' >/dev/null
      kubectl -n "$keycloak_namespace" get targetgroupbinding "$keycloak_binding" -o json | jq -e --arg arn "$keycloak_target_group_arn" '.spec.targetGroupARN == $arn and .spec.targetType == "ip" and .spec.serviceRef.name == "spectrace-keycloak-service" and .spec.serviceRef.port == 8080' >/dev/null
      listener_arn="$(aws elbv2 describe-listeners --region "$region" --load-balancer-arn "$load_balancer_arn" --output json | jq -er --arg cert "$certificate_arn" '[.Listeners[] | select(.Protocol == "HTTPS" and .Port == 443 and any(.Certificates[]; .CertificateArn == $cert))] | if length == 1 then .[0].ListenerArn else error("expected one HTTPS listener with the supplied certificate") end')"
      aws elbv2 describe-rules --region "$region" --listener-arn "$listener_arn" --output json | jq -e --arg keycloak "$keycloak_target_group_arn" --arg gateway "$target_group_arn" '
        any(.Rules[]; any(.Conditions[]?; .Field == "path-pattern" and any(.Values[]?; . == "/auth/*")) and any(.Actions[]?; .Type == "forward" and .TargetGroupArn == $keycloak)) and
        any(.Rules[]; any(.Conditions[]?; .Field == "path-pattern" and any(.Values[]?; . == "/api/*")) and any(.Actions[]?; .Type == "forward" and .TargetGroupArn == $gateway))
      ' >/dev/null
      network_dir="$(mktemp -d)"
      trap 'rm -rf "$network_dir"' EXIT
      aws elbv2 describe-target-groups --region "$region" --target-group-arns "$target_group_arn" "$keycloak_target_group_arn" --output json > "$network_dir/target-groups.json"
      jq -e --arg vpc "$vpc_id" --arg gateway "$target_group_arn" --arg keycloak "$keycloak_target_group_arn" '
        (.TargetGroups | length == 2) and
        all(.TargetGroups[]; .TargetType == "ip" and .VpcId == $vpc and (.TargetGroupArn == $gateway or .TargetGroupArn == $keycloak)) and
        any(.TargetGroups[]; .TargetGroupArn == $gateway) and any(.TargetGroups[]; .TargetGroupArn == $keycloak)
      ' "$network_dir/target-groups.json" >/dev/null
      aws elbv2 describe-load-balancers --region "$region" --load-balancer-arns "$load_balancer_arn" --output json > "$network_dir/load-balancers.json"
      alb_subnet_ids=()
      while IFS= read -r subnet_id; do
        [[ -z "$subnet_id" ]] || alb_subnet_ids+=("$subnet_id")
      done < <(jq -r '.LoadBalancers[0].AvailabilityZones[].SubnetId' "$network_dir/load-balancers.json")
      [[ "${#alb_subnet_ids[@]}" -ge 2 ]] || { echo "public ALB must use at least two subnets" >&2; exit 1; }
      aws ec2 describe-subnets --region "$region" --subnet-ids "${alb_subnet_ids[@]}" --output json > "$network_dir/subnets.json"
      aws elbv2 describe-target-health --region "$region" --target-group-arn "$target_group_arn" --output json > "$network_dir/gateway-health.json"
      aws elbv2 describe-target-health --region "$region" --target-group-arn "$keycloak_target_group_arn" --output json > "$network_dir/keycloak-health.json"
      for group_arn in "$target_group_arn" "$keycloak_target_group_arn"; do
        health_file="$network_dir/gateway-health.json"
        [[ "$group_arn" != "$keycloak_target_group_arn" ]] || health_file="$network_dir/keycloak-health.json"
        jq -e '.TargetHealthDescriptions | length > 0 and all(.[]; .TargetHealth.State == "healthy")' "$health_file" >/dev/null || {
          echo "target group has missing or unhealthy targets: $group_arn" >&2
          exit 1
        }
      done
      jq -n --arg gateway "$target_group_arn" --arg keycloak "$keycloak_target_group_arn" \
        --slurpfile gateway_health "$network_dir/gateway-health.json" \
        --slurpfile keycloak_health "$network_dir/keycloak-health.json" \
        '{($gateway): $gateway_health[0], ($keycloak): $keycloak_health[0]}' > "$network_dir/target-health.json"
      target_ips=()
      while IFS= read -r target_ip; do
        [[ -z "$target_ip" ]] || target_ips+=("$target_ip")
      done < <(jq -r '.TargetHealthDescriptions[].Target.Id' "$network_dir/gateway-health.json" "$network_dir/keycloak-health.json" | sort -u)
      [[ "${#target_ips[@]}" -gt 0 ]] || { echo "target groups returned no target IPs" >&2; exit 1; }
      target_ip_values=$(IFS=,; printf '%s' "${target_ips[*]}")
      aws ec2 describe-network-interfaces --region "$region" --filters "Name=addresses.private-ip-address,Values=$target_ip_values" --output json > "$network_dir/network-interfaces.json"
      alb_security_group_ids=()
      while IFS= read -r security_group_id; do
        [[ -z "$security_group_id" ]] || alb_security_group_ids+=("$security_group_id")
      done < <(jq -r '.LoadBalancers[0].SecurityGroups[]?' "$network_dir/load-balancers.json")
      target_security_group_ids=()
      while IFS= read -r security_group_id; do
        [[ -z "$security_group_id" ]] || target_security_group_ids+=("$security_group_id")
      done < <(jq -r '.NetworkInterfaces[].Groups[].GroupId' "$network_dir/network-interfaces.json" | sort -u)
      security_group_ids=()
      while IFS= read -r security_group_id; do
        [[ -z "$security_group_id" ]] || security_group_ids+=("$security_group_id")
      done < <(printf '%s\n' "${alb_security_group_ids[@]}" "${target_security_group_ids[@]}" | sort -u)
      [[ "${#security_group_ids[@]}" -gt 0 ]] || { echo "ALB or target ENI has no security groups" >&2; exit 1; }
      aws ec2 describe-security-groups --region "$region" --group-ids "${security_group_ids[@]}" --output json > "$network_dir/security-groups.json"
      kubectl --namespace "$gateway_namespace" get networkpolicies -o json > "$network_dir/network-policies-gateway.json"
      kubectl --namespace "$keycloak_namespace" get networkpolicies -o json > "$network_dir/network-policies-keycloak.json"
      jq -s '{items: map(.items[]) }' "$network_dir/network-policies-gateway.json" "$network_dir/network-policies-keycloak.json" > "$network_dir/network-policies.json"
      python3 "$repo_root/scripts/verify_staging_target_network.py" \
        --directory "$network_dir" --gateway-target-group-arn "$target_group_arn" \
        --keycloak-target-group-arn "$keycloak_target_group_arn" --vpc-id "$vpc_id" \
        --load-balancer-arn "$load_balancer_arn"
    fi
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
