#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TF_DIR="$ROOT/infra/terraform"
cd "$ROOT"
umask 077
REGION="${AWS_REGION:-ap-southeast-1}"
export AWS_REGION="$REGION" AWS_DEFAULT_REGION="$REGION"

die() { echo "BLOCKED: $*" >&2; exit 2; }
need() { command -v "$1" >/dev/null || die "missing command: $1"; }

identity() {
  need aws
  [[ "$REGION" == ap-southeast-1 ]] || die "AWS_REGION must be ap-southeast-1"
  local caller
  caller="$(aws sts get-caller-identity --output json)" || die "AWS identity is unavailable"
  python3 -c 'import json,sys; x=json.load(sys.stdin); print(x["Account"]+" "+x["Arn"])' <<<"$caller"
}

check_identity() {
  local account arn
  read -r account arn <<<"$(identity)"
  [[ "$arn" != *:root ]] || die "Root identities cannot deploy staging"
  [[ -n "${STAGING_AWS_ACCOUNT_ID:-}" && "$account" == "$STAGING_AWS_ACCOUNT_ID" ]] || die "set STAGING_AWS_ACCOUNT_ID to the explicitly approved target account"
  echo "AWS identity verified for the configured target account (${arn##*/})."
}

check_budget() {
  [[ -f "$ROOT/scripts/aws_staging_budget.py" && -f "$ROOT/infra/aws-staging-budget.json" ]] || die "the reviewed STCN-37 fail-closed budget gate is not present on this branch"
  : "${STAGING_WINDOW_HOURS:?set the approved deployment-window duration}"
  : "${STAGING_EXPERIMENT_HOURS:?set the approved compliance experiment duration}"
  : "${STAGING_K6_HOURS:?set the approved k6 test duration}"
  : "${AVAILABLE_CREDIT_USD:?set the verified available AWS credit balance}"
  local args=(--hours "$STAGING_WINDOW_HOURS" --experiment-hours "$STAGING_EXPERIMENT_HOURS" --k6-hours "$STAGING_K6_HOURS")
  python3 "$ROOT/scripts/aws_staging_budget.py" "${args[@]}"
  python3 "$ROOT/scripts/aws_staging_budget.py" --require-gate "${args[@]}"
  python3 "$ROOT/scripts/aws_staging_budget.py" quota
}

init_state() {
  need terraform
  [[ -f "$TF_DIR/backend.hcl" ]] || die "create infra/terraform/backend.hcl for the separate persistent state bucket"
  ! rg -q 'REPLACE_WITH|example\.com' "$TF_DIR/backend.hcl" || die "replace the backend example values"
  terraform -chdir="$TF_DIR" init -input=false -backend-config=backend.hcl
}

confirm() {
  local expected="$1" reply
  [[ -t 0 ]] || die "interactive confirmation is required (type $expected)"
  read -r -p "Type $expected to continue: " reply
  [[ "$reply" == "$expected" ]] || die "confirmation did not match"
}

plan_apply() {
  local action="$1" plan_dir plan_file
  mkdir -p "$TF_DIR/.plans"
  chmod 700 "$TF_DIR/.plans"
  plan_dir="$(mktemp -d "$TF_DIR/.plans/${action}.XXXXXX")"
  chmod 700 "$plan_dir"
  plan_file="$plan_dir/${action}.tfplan"
  if [[ "$action" == destroy ]]; then
    terraform -chdir="$TF_DIR" plan -destroy -input=false -out="$plan_file"
  else
    terraform -chdir="$TF_DIR" plan -input=false -out="$plan_file"
  fi
  terraform -chdir="$TF_DIR" show -no-color "$plan_file"
  confirm "$action"
  terraform -chdir="$TF_DIR" apply -input=false "$plan_file"
}

configure_cluster() {
  need kubectl
  local cluster
  cluster="$(terraform -chdir="$TF_DIR" output -raw eks_cluster_name)"
  aws eks update-kubeconfig --region "$REGION" --name "$cluster" --kubeconfig "$TF_DIR/.kubeconfig"
  export KUBECONFIG="$TF_DIR/.kubeconfig"
}

install_operators() {
  need kubectl
  need helm
  kubectl apply -f https://github.com/cert-manager/cert-manager/releases/download/v1.18.2/cert-manager.yaml
  kubectl -n cert-manager rollout status deployment/cert-manager --timeout=180s
  kubectl -n cert-manager rollout status deployment/cert-manager-webhook --timeout=180s
  kubectl -n cert-manager rollout status deployment/cert-manager-cainjector --timeout=180s
  kubectl create namespace rabbitmq-system --dry-run=client -o yaml | kubectl apply -f -
  kubectl create namespace keycloak --dry-run=client -o yaml | kubectl apply -f -
  kubectl apply -f https://github.com/rabbitmq/cluster-operator/releases/download/v2.22.3/cluster-operator.yml
  kubectl apply -f https://github.com/rabbitmq/messaging-topology-operator/releases/download/v1.20.2/messaging-topology-operator-with-certmanager.yaml
  kubectl apply -k "$ROOT/deploy/operators"
  kubectl -n rabbitmq-system rollout status deployment/rabbitmq-cluster-operator --timeout=180s
  kubectl -n rabbitmq-system rollout status deployment/messaging-topology-operator --timeout=180s
  kubectl -n keycloak-system rollout status deployment/keycloak-operator --timeout=180s
  kubectl apply -k "$ROOT/deploy/operators/rabbitmq"
  kubectl -n rabbitmq wait --for=condition=Ready rabbitmqcluster/spectrace --timeout=600s
  helm upgrade --install spectrace-topology "$ROOT/deploy/helm/rabbitmq-topology" -n rabbitmq

  : "${GRAFANA_CLOUD_API_KEY:?set the protected Grafana Cloud token}"
  : "${GRAFANA_CLOUD_METRICS_USER:?set the protected metrics instance ID}"
  : "${PROMETHEUS_URL:?set the protected metrics remote-write URL}"
  : "${GRAFANA_CLOUD_LOGS_USER:?set the protected logs instance ID}"
  : "${LOKI_URL:?set the protected logs push URL}"
  python3 - "$ROOT/deploy/observability/alloy" <<'PY' | kubectl apply -f -
import base64, os, sys
from pathlib import Path
keys = ("PROMETHEUS_URL", "GRAFANA_CLOUD_METRICS_USER", "GRAFANA_CLOUD_API_KEY", "LOKI_URL", "GRAFANA_CLOUD_LOGS_USER")
lines = ["apiVersion: v1", "kind: Secret", "metadata:", "  name: grafana-cloud", "  namespace: observability", "type: Opaque", "data:"]
for key in keys:
    lines.append(f"  {key}: {base64.b64encode(os.environ[key].encode()).decode()}")
print("\n".join(lines))
PY
  kubectl apply -k "$ROOT/deploy/observability/alloy"
  kubectl -n observability rollout status daemonset/grafana-alloy --timeout=300s
  kubectl -n observability rollout status deployment/kube-state-metrics --timeout=180s

  : "${KEYCLOAK_DB_USERNAME:?set the runtime Keycloak database username}"
  : "${KEYCLOAK_DB_PASSWORD:?set the runtime Keycloak database password}"
  local endpoint
  endpoint="$(terraform -chdir="$TF_DIR" output -raw rds_endpoint)"
  RDS_ENDPOINT="$endpoint" python3 - <<'PY' | kubectl apply -f -
import base64, sys
from pathlib import Path
import os
endpoint = os.environ["RDS_ENDPOINT"]
username = os.environ["KEYCLOAK_DB_USERNAME"]
password = os.environ["KEYCLOAK_DB_PASSWORD"]
template = Path("deploy/operators/keycloak/keycloak.yaml.tmpl").read_text().replace("${RDS_ENDPOINT}", endpoint)
assert "${" not in template, "unexpanded template variable"
print("apiVersion: v1\nkind: Secret\nmetadata:\n  name: keycloak-db\n  namespace: keycloak\ntype: Opaque\ndata:\n  username: " + base64.b64encode(username.encode()).decode() + "\n  password: " + base64.b64encode(password.encode()).decode() + "\n---\n" + template, end="")
PY
  kubectl -n keycloak wait --for=condition=Ready keycloak/spectrace --timeout=600s
}

case "${1:-}" in
  status)
    if identity >/dev/null 2>&1; then
      check_identity
    else
      echo "AWS identity: UNVERIFIED (no usable credentials)"
    fi
    if [[ -f "$TF_DIR/backend.hcl" ]]; then terraform -chdir="$TF_DIR" workspace show 2>/dev/null || true; else echo "Terraform state: not initialized"; fi
    ;;
  verify)
    need curl
    check_identity
    configure_cluster
    kubectl get nodes
    kubectl get pods -A
    : "${STAGING_BASE_URL:?set STAGING_BASE_URL to the HTTPS Gateway URL}"
    curl --fail --silent --show-error "$STAGING_BASE_URL/actuator/health"
    ;;
  up)
    [[ "${STAGING_WINDOW_OPEN:-false}" == true ]] || die "STAGING_WINDOW_OPEN must be true"
    [[ "${STAGING_ENVIRONMENT_APPROVED:-false}" == true ]] || die "set STAGING_ENVIRONMENT_APPROVED only after the protected staging Environment is approved"
    check_identity
    check_budget
    init_state
    plan_apply up
    configure_cluster
    STCN_EKS_CLUSTER_NAME="$(terraform -chdir="$TF_DIR" output -raw eks_cluster_name)" \
      "$ROOT/scripts/verify_eks_addons.sh" deploy
    install_operators
    ;;
  down)
    [[ "${STAGING_WINDOW_OPEN:-false}" == true ]] || die "STAGING_WINDOW_OPEN must be true"
    check_identity
    init_state
    cluster="$(terraform -chdir="$TF_DIR" output -raw eks_cluster_name 2>/dev/null || true)"
    if [[ -n "$cluster" ]] && aws eks describe-cluster --region "$REGION" --name "$cluster" >/dev/null 2>&1; then
      configure_cluster
      if helm status spectrace-topology -n rabbitmq >/dev/null 2>&1; then helm uninstall spectrace-topology -n rabbitmq; fi
      if kubectl get namespace rabbitmq >/dev/null 2>&1; then
        kubectl -n rabbitmq delete rabbitmqcluster/spectrace --wait=true --ignore-not-found
        kubectl -n rabbitmq delete pvc --all --wait=true
      fi
    fi
    plan_apply destroy
    STCN_EKS_CLUSTER_NAME="$cluster" AWS_REGION="$REGION" "$ROOT/scripts/verify_eks_addons.sh" cleanup
    ;;
  *) echo "usage: $0 status|verify|up|down" >&2; exit 2 ;;
esac
