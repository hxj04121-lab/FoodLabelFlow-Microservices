#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
workflow="$repo_root/.github/workflows/stcn-15-runtime-secrets.yml"
grep -Fq '      staging_window_hours:' "$workflow"
grep -Fq '      experiment_hours:' "$workflow"
grep -Fq '      k6_hours:' "$workflow"
grep -Fq '      staging_window_end_sgt:' "$workflow"
grep -Fq '      STAGING_WINDOW_HOURS: ${{ inputs.staging_window_hours }}' "$workflow"
grep -Fq '      STAGING_EXPERIMENT_HOURS: ${{ inputs.experiment_hours }}' "$workflow"
grep -Fq '      STAGING_K6_HOURS: ${{ inputs.k6_hours }}' "$workflow"
grep -Fq '      STAGING_WINDOW_END_SGT: ${{ inputs.staging_window_end_sgt }}' "$workflow"
grep -Fq 'platform/staging/validate_window.py "$STAGING_WINDOW_HOURS" "$STAGING_WINDOW_END_SGT"' "$workflow"
grep -Fqx '      AWS_REGION: ${{ vars.AWS_REGION }}' "$workflow"
grep -Fq '      AWS_ACCOUNT_ID: ${{ vars.AWS_ACCOUNT_ID }}' "$workflow"
grep -Fq '      EKS_CLUSTER_NAME: ${{ vars.EKS_CLUSTER_NAME }}' "$workflow"
grep -Fq 'role/FoodLabelFlowStagingDeploy' "$workflow"
! grep -q 'vars.AWS_PLAN_ROLE_ARN' "$workflow"
grep -Fq '          APPROVED_STAGING_SPEND_USD: ${{ secrets.APPROVED_STAGING_SPEND_USD }}' "$workflow"
grep -Fq -- '--approved-spend-usd "$APPROVED_STAGING_SPEND_USD"' "$workflow"
grep -Fq "cap > Decimal('150')" "$workflow"
grep -Fq 'scripts/staging/resolve_rds_values.py' "$workflow"
grep -Fq 'spectrace-staging/mysql/master' "$workflow"
grep -Fq 'bash deploy/mysql/bootstrap-service-databases.sh' "$workflow"
grep -Fq "echo 'STAGING_WINDOW_VALIDATED=true'" "$workflow"
! grep -q 'STAGING_WINDOW_OPEN\|vars.MYSQL_HOST\|vars.RABBITMQ_HOST\|STAGING_AVAILABLE_CREDIT_USD' "$workflow"
! grep -q 'STAGING_AVAILABLE_CREDIT_USD\|--available-credit-usd' "$workflow"
! grep -Eq 'STAGING_AWS_ACCOUNT_ID|STAGING_EKS_CLUSTER_NAME' "$workflow"
for secret in SPECIFICATION_DB_PASSWORD FORMULATION_DB_PASSWORD COMPLIANCE_DB_PASSWORD LABEL_WORKFLOW_DB_PASSWORD KEYCLOAK_DB_PASSWORD; do
  grep -Fq "\${{ secrets.$secret }}" "$workflow"
done
tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT
mkdir -p "$tmp_dir/bin" "$tmp_dir/captures"

cat > "$tmp_dir/bin/aws" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail
[[ "$*" == 'sts get-caller-identity --query Account --output text' ]]
printf '%s\n' "$FAKE_ACCOUNT_ID"
MOCK
chmod +x "$tmp_dir/bin/aws"

cat > "$tmp_dir/bin/kubectl" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "$TEST_CAPTURE_DIR/args"
case "$1 ${2:-}" in
  "auth can-i")
    printf 'yes\n'
    ;;
  "config current-context")
    printf '%s\n' "$FAKE_CONTEXT"
    ;;
  "get serviceaccount")
    printf 'serviceaccount/%s\n' "$3"
    ;;
  "--namespace "*)
    namespace="$2"
    case "$3 ${4:-}" in
      "create secret")
        shift 5
        secret_name="$1"
        from_env_file=""
        while (($#)); do
          case "$1" in
            --from-env-file=*) from_env_file="${1#*=}" ;;
          esac
          shift
        done
        cat "$from_env_file" > "$TEST_CAPTURE_DIR/${namespace}.env"
        printf 'kind: Secret\nmetadata:\n  name: %s\n' "$secret_name"
        ;;
      "delete secret")
        printf '%s/%s\n' "$namespace" "$5" >> "$TEST_CAPTURE_DIR/deleted"
        ;;
      "get secret")
        count=$(( $(cat "$TEST_CAPTURE_DIR/secret-reads" 2>/dev/null || echo 0) + 1 ))
        printf '%s' "$count" > "$TEST_CAPTURE_DIR/secret-reads"
        printf '%s' "$count"
        ;;
      "get statefulset")
        printf 'statefulset.apps/%s\n' "$5"
        ;;
      "rollout restart"|"rollout status")
        printf '%s\n' "$*" >> "$TEST_CAPTURE_DIR/rollouts"
        ;;
      *) exit 2 ;;
    esac
    ;;
  "apply --server-side")
    cat >/dev/null
    ;;
  *) exit 2 ;;
esac
MOCK
chmod +x "$tmp_dir/bin/kubectl"

export PATH="$tmp_dir/bin:$PATH"
export TEST_CAPTURE_DIR="$tmp_dir/captures"
export AWS_REGION=ap-southeast-1
export GITHUB_ACTIONS=true
export GITHUB_REF=refs/heads/main
export GITHUB_WORKFLOW='STCN-15 staging runtime Secrets'
export STAGING_WINDOW_VALIDATED=true
export AWS_ACCOUNT_ID=000000000000
export FAKE_ACCOUNT_ID=000000000000
export EKS_CLUSTER_NAME=staging-cluster
export MYSQL_HOST=spectrace.abc.ap-southeast-1.rds.amazonaws.com
export GATEWAY_ISSUER=https://gateway.example.invalid/auth/realms/spectrace
export SPECTRACE_SECURITY_JWKSETURI=http://keycloak.keycloak.svc.cluster.local:8080/auth/realms/spectrace/protocol/openid-connect/certs
export FAKE_CONTEXT="arn:aws:eks:${AWS_REGION}:${AWS_ACCOUNT_ID}:cluster/${EKS_CLUSTER_NAME}"
export SPECIFICATION_DB_PASSWORD=specification-password-012345678901
export FORMULATION_DB_PASSWORD=formulation-password-012345678901
export COMPLIANCE_DB_PASSWORD=compliance-password-012345678901
export LABEL_WORKFLOW_DB_PASSWORD=label-workflow-password-0123456789
export KEYCLOAK_DB_PASSWORD=keycloak-password-012345678901234

bash "$repo_root/scripts/staging/apply-runtime-secrets.sh" > "$tmp_dir/output"
for spec in \
  'specification|specification|SPECIFICATION_DB_PASSWORD' \
  'formulation|formulation|FORMULATION_DB_PASSWORD' \
  'compliance|compliance|COMPLIANCE_DB_PASSWORD' \
  'label-workflow|label_workflow|LABEL_WORKFLOW_DB_PASSWORD'; do
  IFS='|' read -r namespace schema variable <<< "$spec"
  password="${!variable}"
  grep -Fqx "SPRING_DATASOURCE_URL=jdbc:mysql://${MYSQL_HOST}:3306/${schema}?sslMode=REQUIRED&serverTimezone=UTC" "$tmp_dir/captures/${namespace}.env"
  grep -Fqx "SPRING_DATASOURCE_USERNAME=${schema}" "$tmp_dir/captures/${namespace}.env"
  grep -Fqx "SPRING_DATASOURCE_PASSWORD=${password}" "$tmp_dir/captures/${namespace}.env"
  ! grep -q '^RABBITMQ_' "$tmp_dir/captures/${namespace}.env"
  grep -Fqx "SPECTRACE_SECURITY_ISSUERURI=${GATEWAY_ISSUER}" "$tmp_dir/captures/${namespace}.env"
  grep -Fqx "SPECTRACE_SECURITY_JWKSETURI=${SPECTRACE_SECURITY_JWKSETURI}" "$tmp_dir/captures/${namespace}.env"
  grep -Fqx 'SPECTRACE_SECURITY_AUDIENCE=spectrace-api' "$tmp_dir/captures/${namespace}.env"
  grep -Fq "apply runtime Secret ${namespace}/${namespace}-runtime" "$tmp_dir/output"
  for secret in "$password" "$KEYCLOAK_DB_PASSWORD"; do
    if grep -Fq "$secret" "$tmp_dir/output"; then
      echo "FAIL runtime credential appeared in command output" >&2
      exit 1
    fi
  done
done
grep -Fqx 'username=keycloak' "$tmp_dir/captures/keycloak.env"
grep -Fqx "password=${KEYCLOAK_DB_PASSWORD}" "$tmp_dir/captures/keycloak.env"
grep -Fq "apply runtime Secret keycloak/keycloak-db" "$tmp_dir/output"
for variable in SPECIFICATION_DB_PASSWORD FORMULATION_DB_PASSWORD COMPLIANCE_DB_PASSWORD LABEL_WORKFLOW_DB_PASSWORD KEYCLOAK_DB_PASSWORD; do
  if grep -Fq "${!variable}" "$tmp_dir/captures/args"; then
    echo "FAIL database password appeared in kubectl arguments" >&2
    exit 1
  fi
done

if FAKE_CONTEXT=wrong-cluster bash "$repo_root/scripts/staging/apply-runtime-secrets.sh" > "$tmp_dir/wrong-context" 2>&1; then
  echo "FAIL wrong Kubernetes context was accepted" >&2
  exit 1
fi
! grep -q 'create secret' "$tmp_dir/wrong-context"

if env -u GITHUB_ACTIONS bash "$repo_root/scripts/staging/apply-runtime-secrets.sh" > "$tmp_dir/unprotected" 2>&1; then
  echo "FAIL an unprotected local invocation was accepted" >&2
  exit 1
fi

if STAGING_WINDOW_VALIDATED=false bash "$repo_root/scripts/staging/apply-runtime-secrets.sh" > "$tmp_dir/closed-window" 2>&1; then
  echo "FAIL Secret apply was accepted outside an approved window" >&2
  exit 1
fi
! grep -q 'create secret' "$tmp_dir/closed-window"

if AWS_ACCOUNT_ID=123456789012 bash "$repo_root/scripts/staging/apply-runtime-secrets.sh" > "$tmp_dir/wrong-account" 2>&1; then
  echo "FAIL wrong AWS account was accepted" >&2
  exit 1
fi
if FAKE_ACCOUNT_ID=123456789012 bash "$repo_root/scripts/staging/apply-runtime-secrets.sh" > "$tmp_dir/wrong-sts-account" 2>&1; then
  echo "FAIL mismatched STS identity was accepted" >&2
  exit 1
fi

if SPECIFICATION_DB_PASSWORD=too-short bash "$repo_root/scripts/staging/apply-runtime-secrets.sh" > "$tmp_dir/invalid-password" 2>&1; then
  echo "FAIL invalid database password was accepted" >&2
  exit 1
fi
! grep -Fq too-short "$tmp_dir/invalid-password"

if GATEWAY_ISSUER=http://gateway.example.invalid/auth/realms/spectrace bash "$repo_root/scripts/staging/apply-runtime-secrets.sh" > "$tmp_dir/invalid-issuer" 2>&1; then
  echo "FAIL non-HTTPS OIDC issuer origin was accepted" >&2
  exit 1
fi
! grep -q 'create secret' "$tmp_dir/invalid-issuer"

if SPECTRACE_SECURITY_JWKSETURI=http://keycloak.example.invalid/jwks bash "$repo_root/scripts/staging/apply-runtime-secrets.sh" > "$tmp_dir/invalid-jwks" 2>&1; then
  echo "FAIL non-realm JWKS URL was accepted" >&2
  exit 1
fi
! grep -q 'create secret' "$tmp_dir/invalid-jwks"

if MYSQL_HOST=staging-mysql.example.invalid bash "$repo_root/scripts/staging/apply-runtime-secrets.sh" > "$tmp_dir/invalid-mysql-host" 2>&1; then
  echo "FAIL non-RDS MySQL hostname was accepted" >&2
  exit 1
fi
! grep -q 'create secret' "$tmp_dir/invalid-mysql-host"

unset MYSQL_HOST GATEWAY_ISSUER SPECTRACE_SECURITY_JWKSETURI SPECIFICATION_DB_PASSWORD FORMULATION_DB_PASSWORD COMPLIANCE_DB_PASSWORD LABEL_WORKFLOW_DB_PASSWORD KEYCLOAK_DB_PASSWORD
unset STAGING_WINDOW_VALIDATED
bash "$repo_root/scripts/staging/apply-runtime-secrets.sh" delete > "$tmp_dir/deleted-output"
[[ $(wc -l < "$tmp_dir/captures/deleted") -eq 5 ]]
echo "PASS runtime Secret injection and cleanup: isolated DBs, OIDC configuration, protected window, exact staging context and redaction"
grep -Fq 'BOOTSTRAP_KUBECTL_NAMESPACE=specification' "$workflow"
grep -Fq -- '--force-conflicts' "$repo_root/scripts/staging/apply-runtime-secrets.sh"
grep -Fq 'rollout restart statefulset/spectrace-keycloak' "$tmp_dir/captures/rollouts" 2>/dev/null
