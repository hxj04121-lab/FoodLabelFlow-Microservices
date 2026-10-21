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
if [[ "$GITHUB_ACTIONS" != true || "$GITHUB_REF" != refs/heads/main || "$GITHUB_WORKFLOW" != "STCN-15 staging runtime Secrets" ]]; then
  echo "Refusing runtime Secret operation outside the protected main staging workflow" >&2
  exit 2
fi
if [[ "$action" == apply && "${STAGING_WINDOW_VALIDATED:-}" != true ]]; then
  echo "Refusing Secret apply without the protected workflow's validated budget window" >&2
  exit 2
fi

: "${AWS_REGION:?Set AWS_REGION}"
: "${AWS_ACCOUNT_ID:?Set AWS_ACCOUNT_ID}"
: "${EKS_CLUSTER_NAME:?Set EKS_CLUSTER_NAME}"
[[ "$AWS_REGION" == ap-southeast-1 ]] || { echo "AWS_REGION must be ap-southeast-1" >&2; exit 2; }
[[ "$AWS_ACCOUNT_ID" =~ ^[0-9]{12}$ ]] || { echo "AWS_ACCOUNT_ID must be 12 digits" >&2; exit 2; }
[[ "$EKS_CLUSTER_NAME" =~ ^[A-Za-z0-9_-]+$ ]] || { echo "Invalid EKS cluster name" >&2; exit 2; }
actual_account_id="$(aws sts get-caller-identity --query Account --output text)"
[[ "$actual_account_id" == "$AWS_ACCOUNT_ID" ]] || {
  echo "Refusing runtime Secret operation: STS account does not match AWS_ACCOUNT_ID" >&2
  exit 2
}

expected_context="arn:aws:eks:${AWS_REGION}:${AWS_ACCOUNT_ID}:cluster/${EKS_CLUSTER_NAME}"
if [[ "$(kubectl config current-context)" != "$expected_context" ]]; then
  echo "Refusing runtime Secret operation: current Kubernetes context is not the configured staging cluster" >&2
  exit 2
fi

namespaces=(specification formulation compliance label-workflow)
schemas=(specification formulation compliance label_workflow)
variables=(SPECIFICATION_DB_PASSWORD FORMULATION_DB_PASSWORD COMPLIANCE_DB_PASSWORD LABEL_WORKFLOW_DB_PASSWORD)
for namespace in "${namespaces[@]}" keycloak; do
  kubectl get serviceaccount default --namespace "$namespace" >/dev/null
  test "$(kubectl auth can-i create secrets --namespace "$namespace")" = yes || {
    echo "Refusing runtime Secret operation: deploy role cannot create Secrets in $namespace" >&2
    exit 1
  }
  test "$(kubectl auth can-i patch secrets --namespace "$namespace")" = yes || {
    echo "Refusing runtime Secret operation: deploy role cannot update Secrets in $namespace" >&2
    exit 1
  }
done

if [[ "$action" == apply ]]; then
  : "${MYSQL_HOST:?Set MYSQL_HOST to the private RDS endpoint}"
  : "${GATEWAY_ISSUER:?Set GATEWAY_ISSUER to the approved staging realm issuer}"
  : "${SPECTRACE_SECURITY_JWKSETURI:?Set the internal Keycloak JWKS URI}"
  [[ "$MYSQL_HOST" =~ ^[A-Za-z0-9.-]+\.ap-southeast-1\.rds\.amazonaws\.com$ ]] || { echo "MYSQL_HOST must be the actual ap-southeast-1 RDS endpoint" >&2; exit 2; }
  [[ "$GATEWAY_ISSUER" =~ ^https://[A-Za-z0-9.-]+(:[0-9]{1,5})?/auth/realms/spectrace$ ]] || { echo "GATEWAY_ISSUER must be the HTTPS /auth/realms/spectrace URL" >&2; exit 2; }
  [[ "$SPECTRACE_SECURITY_JWKSETURI" =~ ^https?://[A-Za-z0-9.-]+(:[0-9]{1,5})?/auth/realms/spectrace/protocol/openid-connect/certs$ ]] || { echo "SPECTRACE_SECURITY_JWKSETURI must be the Keycloak realm JWKS URL" >&2; exit 2; }
  [[ "${KEYCLOAK_DB_PASSWORD:-}" =~ ^[A-Za-z0-9_-]{32,128}$ ]] || { echo "KEYCLOAK_DB_PASSWORD must be a 32-128 character generated secret" >&2; exit 2; }
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

  if [[ "$action" == delete ]]; then
    kubectl --namespace "$namespace" delete secret "$secret_name" --ignore-not-found=true >/dev/null
  else
    variable="${variables[$i]}"
    password="${!variable}"
    kubectl --namespace "$namespace" create secret generic "$secret_name" \
      --from-env-file=<(printf '%s\n' \
        "SPRING_DATASOURCE_URL=jdbc:mysql://${MYSQL_HOST}:3306/${schema}?sslMode=REQUIRED&serverTimezone=UTC" \
        "SPRING_DATASOURCE_USERNAME=${schema}" \
        "SPRING_DATASOURCE_PASSWORD=${password}" \
        "SPECTRACE_SECURITY_ISSUERURI=${GATEWAY_ISSUER}" \
        "SPECTRACE_SECURITY_JWKSETURI=${SPECTRACE_SECURITY_JWKSETURI}" \
        "SPECTRACE_SECURITY_AUDIENCE=spectrace-api") \
      --dry-run=client --output=yaml | kubectl apply --server-side \
        --field-manager=stcn15-runtime-secrets -f - >/dev/null
  fi
  printf '%s runtime Secret %s/%s\n' "$action" "$namespace" "$secret_name"
done

if [[ "$action" == delete ]]; then
  kubectl --namespace keycloak delete secret keycloak-db --ignore-not-found=true >/dev/null
else
  before=$(kubectl --namespace keycloak get secret keycloak-db --ignore-not-found -o jsonpath='{.metadata.resourceVersion}')
  # The bootstrap above has just set the keycloak DB account to KEYCLOAK_DB_PASSWORD, so this Secret must
  # carry the same value even if another manager (kubectl create) wrote it first.
  kubectl --namespace keycloak create secret generic keycloak-db \
    --from-env-file=<(printf '%s\n' \
      'username=keycloak' \
      "password=${KEYCLOAK_DB_PASSWORD}") \
    --dry-run=client --output=yaml | kubectl apply --server-side --force-conflicts \
      --field-manager=stcn15-runtime-secrets -f - >/dev/null
  after=$(kubectl --namespace keycloak get secret keycloak-db -o jsonpath='{.metadata.resourceVersion}')
  if [[ -n "$before" && "$before" != "$after" ]] \
    && kubectl --namespace keycloak get statefulset spectrace-keycloak -o name >/dev/null 2>&1; then
    # Running Keycloak pods still hold the previous password; restart them onto the new one.
    echo 'keycloak-db changed; restarting Keycloak so it reconnects with the new database password'
    kubectl --namespace keycloak rollout restart statefulset/spectrace-keycloak >/dev/null
    kubectl --namespace keycloak rollout status statefulset/spectrace-keycloak --timeout=10m
  fi
fi
printf '%s runtime Secret keycloak/keycloak-db\n' "$action"
