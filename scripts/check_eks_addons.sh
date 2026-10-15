#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
helm_bin="${HELM_BIN:-helm}"
terraform_root="$repo_root/infra/terraform"
for command in terraform ruby "$helm_bin"; do
  command -v "$command" >/dev/null || { echo "missing required command: $command" >&2; exit 127; }
done

terraform -chdir="$terraform_root" fmt -check -recursive
terraform -chdir="$terraform_root" init -backend=false -input=false
terraform -chdir="$terraform_root" validate

tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT
"$helm_bin" repo add --force-update eks https://aws.github.io/eks-charts >/dev/null
"$helm_bin" repo add --force-update autoscaler https://kubernetes.github.io/autoscaler >/dev/null
"$helm_bin" repo add --force-update metrics-server https://kubernetes-sigs.github.io/metrics-server/ >/dev/null
"$helm_bin" repo update >/dev/null
"$helm_bin" lint --strict "$repo_root/deploy/helm/platform-network-policies"
cat > "$tmp_dir/load-balancer-values.yaml" <<'YAML'
clusterName: stcn-validation
region: ap-southeast-1
vpcId: vpc-0123456789abcdef0
replicaCount: 2
serviceAccount:
  create: true
  name: aws-load-balancer-controller
  annotations:
    eks.amazonaws.com/role-arn: arn:aws:iam::000000000000:role/stcn-validation-lbc
YAML
cat > "$tmp_dir/autoscaler-values.yaml" <<'YAML'
fullnameOverride: cluster-autoscaler
cloudProvider: aws
awsRegion: ap-southeast-1
image:
  tag: v1.36.0
autoscalingGroups:
  - {name: stcn-validation-general, minSize: 1, maxSize: 3}
  - {name: stcn-validation-compliance, minSize: 1, maxSize: 4}
rbac:
  create: true
  serviceAccount:
    create: true
    name: cluster-autoscaler
    annotations:
      eks.amazonaws.com/role-arn: arn:aws:iam::000000000000:role/stcn-validation-ca
YAML
cat > "$tmp_dir/metrics-values.yaml" <<'YAML'
replicas: 2
podDisruptionBudget:
  enabled: true
  minAvailable: 1
YAML
for chart in eks/aws-load-balancer-controller:3.6.0 autoscaler/cluster-autoscaler:9.59.0 metrics-server/metrics-server:3.14.0; do
  repo_chart="${chart%:*}"
  version="${chart##*:}"
  chart_name="${repo_chart##*/}"
  "$helm_bin" pull "$repo_chart" --version "$version" --untar --untardir "$tmp_dir"
  case "$chart_name" in
    aws-load-balancer-controller) values_file="$tmp_dir/load-balancer-values.yaml" ;;
    cluster-autoscaler) values_file="$tmp_dir/autoscaler-values.yaml" ;;
    metrics-server) values_file="$tmp_dir/metrics-values.yaml" ;;
  esac
  "$helm_bin" lint --strict -f "$values_file" "$tmp_dir/$chart_name"
done

"$helm_bin" template stcn-network-policies "$repo_root/deploy/helm/platform-network-policies" > "$tmp_dir/network-policies.yaml"

"$helm_bin" template gateway "$repo_root/deploy/helm/spectrace-service" -f "$repo_root/deploy/helm/values/gateway.yaml" \
  --set-string 'targetGroupArn=arn:aws:elasticloadbalancing:ap-southeast-1:000000000000:targetgroup/test-gateway/0123456789abcdef' > "$tmp_dir/gateway-routing.yaml"
sed 's|${cluster_name}|stcn-validation|' "$repo_root/deploy/kubernetes/staging/ebs-gp3-storageclass.yaml.tmpl" > "$tmp_dir/ebs-gp3-storageclass.yaml"
ruby -ryaml -e '
  ARGV.each do |path|
    docs = YAML.load_stream(File.read(path))
    abort "empty or malformed YAML: #{path}" if docs.empty? || docs.any?(&:nil?)
    docs.each do |doc|
      abort "missing Kubernetes object fields: #{path}" unless doc["apiVersion"] && doc["kind"] && doc.dig("metadata", "name")
      if doc["kind"] == "NetworkPolicy"
        spec = doc.fetch("spec")
        abort "NetworkPolicy missing selectors or policy types: #{path}" unless spec.key?("podSelector") && spec.key?("policyTypes")
      end
    end
  end
' "$tmp_dir/network-policies.yaml" "$tmp_dir/gateway-routing.yaml" "$tmp_dir/ebs-gp3-storageclass.yaml"
if [[ -n "${KUBECONFORM_BIN:-}" ]]; then
  "$KUBECONFORM_BIN" -strict -summary -kubernetes-version "${KUBERNETES_VERSION:-1.36.0}" "$tmp_dir/network-policies.yaml" "$tmp_dir/ebs-gp3-storageclass.yaml"
fi
echo "Terraform validate, four Helm chart lints, rendered Kubernetes YAML checks passed; kubeconform ran when configured."
