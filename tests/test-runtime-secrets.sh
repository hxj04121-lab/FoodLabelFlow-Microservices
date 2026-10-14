#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT
mkdir -p "$tmp_dir/bin" "$tmp_dir/captures"

cat > "$tmp_dir/bin/kubectl" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "$TEST_CAPTURE_DIR/args"
case "$1 ${2:-}" in
  "config current-context")
    printf '%s\n' "$FAKE_CONTEXT"
    ;;
  "get namespace")
    printf 'namespace/%s\n' "$3"
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
export STAGING_WINDOW_OPEN=true
export STAGING_AWS_ACCOUNT_ID=679857346122
export STAGING_EKS_CLUSTER_NAME=spectrace-staging
export MYSQL_HOST=spectrace-staging-mysql.example.rds.amazonaws.com
export FAKE_CONTEXT="arn:aws:eks:${AWS_REGION}:${STAGING_AWS_ACCOUNT_ID}:cluster/${STAGING_EKS_CLUSTER_NAME}"
export SPECIFICATION_DB_PASSWORD=specification-password-012345678901
export FORMULATION_DB_PASSWORD=formulation-password-012345678901
export COMPLIANCE_DB_PASSWORD=compliance-password-012345678901
export LABEL_WORKFLOW_DB_PASSWORD=label-workflow-password-0123456789

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
  grep -Fq "apply runtime Secret ${namespace}/${namespace}-runtime" "$tmp_dir/output"
  if grep -Fq "$password" "$tmp_dir/output"; then
    echo "FAIL database password appeared in command output" >&2
    exit 1
  fi
done
for variable in SPECIFICATION_DB_PASSWORD FORMULATION_DB_PASSWORD COMPLIANCE_DB_PASSWORD LABEL_WORKFLOW_DB_PASSWORD; do
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

if STAGING_WINDOW_OPEN=false bash "$repo_root/scripts/staging/apply-runtime-secrets.sh" > "$tmp_dir/closed-window" 2>&1; then
  echo "FAIL Secret apply was accepted outside an approved window" >&2
  exit 1
fi
! grep -q 'create secret' "$tmp_dir/closed-window"

if STAGING_AWS_ACCOUNT_ID=123456789012 bash "$repo_root/scripts/staging/apply-runtime-secrets.sh" > "$tmp_dir/wrong-account" 2>&1; then
  echo "FAIL wrong AWS account was accepted" >&2
  exit 1
fi

if SPECIFICATION_DB_PASSWORD=too-short bash "$repo_root/scripts/staging/apply-runtime-secrets.sh" > "$tmp_dir/invalid-password" 2>&1; then
  echo "FAIL invalid database password was accepted" >&2
  exit 1
fi
! grep -Fq too-short "$tmp_dir/invalid-password"

unset MYSQL_HOST SPECIFICATION_DB_PASSWORD FORMULATION_DB_PASSWORD COMPLIANCE_DB_PASSWORD LABEL_WORKFLOW_DB_PASSWORD
export STAGING_WINDOW_OPEN=false
bash "$repo_root/scripts/staging/apply-runtime-secrets.sh" delete > "$tmp_dir/deleted-output"
[[ $(wc -l < "$tmp_dir/captures/deleted") -eq 4 ]]
echo "PASS runtime Secret injection and cleanup: exact staging context, four isolated namespaces, no secret in output"
