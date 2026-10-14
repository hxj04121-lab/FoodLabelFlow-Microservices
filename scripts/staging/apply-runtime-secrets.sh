#!/usr/bin/env bash
set -euo pipefail
set +x

action="${1:-apply}"
if [[ "$action" != apply && "$action" != delete ]] || [[ $# -gt 1 ]]; then
  echo "usage: $0 [apply|delete]" >&2
  exit 2
fi

: "${GITHUB_ACTIONS:?Runtime Secrets may only be managed by the protected GitHub workflow}"
: "${GITHUB_REF:?Set GITHUB_REF}"
: "${GITHUB_WORKFLOW:?Set GITHUB_WORKFLOW}"
: "${STAGING_WINDOW_OPEN:?Set STAGING_WINDOW_OPEN}"
if [[ "$GITHUB_ACTIONS" != true || "$GITHUB_REF" != refs/heads/main || "$GITHUB_WORKFLOW" != "STCN-15 staging runtime Secrets" ]]; then
  echo "Refusing runtime Secret operation outside the protected main staging workflow" >&2
  exit 2
fi
if [[ "$action" == apply && "$STAGING_WINDOW_OPEN" != true ]]; then
  echo "Refusing Secret apply without an approved open staging window" >&2
  exit 2
fi

: "${AWS_REGION:?Set AWS_REGION}"
: "${STAGING_AWS_ACCOUNT_ID:?Set STAGING_AWS_ACCOUNT_ID}"
: "${STAGING_EKS_CLUSTER_NAME:?Set STAGING_EKS_CLUSTER_NAME}"
[[ "$AWS_REGION" == ap-southeast-1 ]] || { echo "AWS_REGION must be ap-southeast-1" >&2; exit 2; }
[[ "$STAGING_AWS_ACCOUNT_ID" =~ ^[0-9]{12}$ ]] || { echo "STAGING_AWS_ACCOUNT_ID must be 12 digits" >&2; exit 2; }
[[ "$STAGING_EKS_CLUSTER_NAME" =~ ^[A-Za-z0-9_-]+$ ]] || { echo "Invalid staging cluster name" >&2; exit 2; }
[[ "$STAGING_AWS_ACCOUNT_ID" == 679857346122 && "$STAGING_EKS_CLUSTER_NAME" == spectrace-staging ]] || {
  echo "Refusing runtime Secret operation outside the approved staging account and cluster" >&2
  exit 2
}

expected_context="arn:aws:eks:${AWS_REGION}:${STAGING_AWS_ACCOUNT_ID}:cluster/${STAGING_EKS_CLUSTER_NAME}"
if [[ "$(kubectl config current-context)" != "$expected_context" ]]; then
  echo "Refusing runtime Secret operation: current Kubernetes context is not the configured staging cluster" >&2
  exit 2
fi

namespaces=(specification formulation compliance label-workflow)
schemas=(specification formulation compliance label_workflow)
variables=(SPECIFICATION_DB_PASSWORD FORMULATION_DB_PASSWORD COMPLIANCE_DB_PASSWORD LABEL_WORKFLOW_DB_PASSWORD)

if [[ "$action" == apply ]]; then
  : "${MYSQL_HOST:?Set MYSQL_HOST to the private RDS endpoint}"
  [[ "$MYSQL_HOST" =~ ^[A-Za-z0-9][A-Za-z0-9.-]*[A-Za-z0-9]$ && "$MYSQL_HOST" != *..* ]] || { echo "MYSQL_HOST must be a DNS hostname" >&2; exit 2; }
  for variable in "${variables[@]}"; do
    value="${!variable:-}"
    if [[ ! "$value" =~ ^[A-Za-z0-9_-]{32,128}$ ]]; then
      echo "$variable must be a 32-128 character generated alphanumeric, underscore or hyphen secret" >&2
      exit 2
    fi
  done
fi

for i in "${!namespaces[@]}"; do
  namespace="${namespaces[$i]}"
  schema="${schemas[$i]}"
  secret_name="${namespace}-runtime"
  kubectl get namespace "$namespace" >/dev/null

  if [[ "$action" == delete ]]; then
    kubectl --namespace "$namespace" delete secret "$secret_name" --ignore-not-found=true >/dev/null
  else
    variable="${variables[$i]}"
    password="${!variable}"
    kubectl --namespace "$namespace" create secret generic "$secret_name" \
      --from-env-file=<(printf '%s\n' \
        "SPRING_DATASOURCE_URL=jdbc:mysql://${MYSQL_HOST}:3306/${schema}?sslMode=REQUIRED&serverTimezone=UTC" \
        "SPRING_DATASOURCE_USERNAME=${schema}" \
        "SPRING_DATASOURCE_PASSWORD=${password}") \
      --dry-run=client --output=yaml | kubectl apply --server-side \
        --field-manager=stcn15-runtime-secrets -f - >/dev/null
  fi
  printf '%s runtime Secret %s/%s\n' "$action" "$namespace" "$secret_name"
done
