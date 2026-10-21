#!/usr/bin/env bash
set -euo pipefail

: "${MYSQL_HOST:?Set MYSQL_HOST to the private RDS endpoint}"
: "${MYSQL_ADMIN_USER:?Set MYSQL_ADMIN_USER to the bootstrap administrator}"
: "${MYSQL_ADMIN_PASSWORD:?Set MYSQL_ADMIN_PASSWORD from a protected secret}"
if [[ "$MYSQL_HOST" != 127.0.0.1 ]]; then
  : "${GITHUB_ACTIONS:?RDS bootstrap is restricted to the protected GitHub workflow}"
  : "${GITHUB_REF:?Set GITHUB_REF}"
  : "${GITHUB_WORKFLOW:?Set GITHUB_WORKFLOW}"
  : "${STAGING_WINDOW_VALIDATED:?Set the validated staging budget marker}"
  [[ "$GITHUB_ACTIONS" == true && "$GITHUB_REF" == refs/heads/main && \
    "$GITHUB_WORKFLOW" == "STCN-15 staging runtime Secrets" && "$STAGING_WINDOW_VALIDATED" == true && \
    "$MYSQL_HOST" =~ ^[A-Za-z0-9.-]+\.ap-southeast-1\.rds\.amazonaws\.com$ ]] || {
    echo "Refusing database bootstrap outside the protected staging window or against a non-RDS host" >&2
    exit 2
  }
fi
[[ "$MYSQL_ADMIN_USER" =~ ^[A-Za-z0-9_]{1,32}$ ]] || {
  echo "MYSQL_ADMIN_USER has invalid characters or length" >&2
  exit 2
}

services=(specification formulation compliance label_workflow keycloak)
variables=(SPECIFICATION_DB_PASSWORD FORMULATION_DB_PASSWORD COMPLIANCE_DB_PASSWORD LABEL_WORKFLOW_DB_PASSWORD KEYCLOAK_DB_PASSWORD)
for variable in "${variables[@]}"; do
  value="${!variable:-}"
  if [[ ! "$value" =~ ^[A-Za-z0-9_-]{32,128}$ ]]; then
    echo "$variable must be a 32-128 character generated alphanumeric, underscore or hyphen secret" >&2
    exit 2
  fi
done

sql_file=$(mktemp)
trap 'rm -f "$sql_file"' EXIT
for i in "${!services[@]}"; do
  service="${services[$i]}"
  variable="${variables[$i]}"
  printf "CREATE DATABASE IF NOT EXISTS %s;\n" "$service" >> "$sql_file"
  printf "CREATE USER IF NOT EXISTS '%s'@'%%' IDENTIFIED BY '%s';\n" "$service" "${!variable}" >> "$sql_file"
  printf "ALTER USER '%s'@'%%' IDENTIFIED BY '%s';\n" "$service" "${!variable}" >> "$sql_file"
  printf "REVOKE ALL PRIVILEGES, GRANT OPTION FROM '%s'@'%%';\n" "$service" >> "$sql_file"
  printf "GRANT ALL PRIVILEGES ON %s.* TO '%s'@'%%';\n" "$service" "$service" >> "$sql_file"
done
printf 'FLUSH PRIVILEGES;\n' >> "$sql_file"

if [[ -n "${BOOTSTRAP_KUBECTL_NAMESPACE:-}" ]]; then
  # RDS accepts MySQL only from the EKS node security group, so run the client in a short-lived cluster Pod.
  # The admin password is the first stdin line, never part of the Pod spec; the SQL follows it.
  [[ "$BOOTSTRAP_KUBECTL_NAMESPACE" =~ ^[a-z0-9-]{1,63}$ ]] || { echo "Invalid BOOTSTRAP_KUBECTL_NAMESPACE" >&2; exit 2; }
  { printf '%s\n' "$MYSQL_ADMIN_PASSWORD"; cat "$sql_file"; } | kubectl run "mysql-bootstrap-$RANDOM" \
    --namespace "$BOOTSTRAP_KUBECTL_NAMESPACE" --image=mysql:8.4.3 --restart=Never --rm -i --quiet \
    --pod-running-timeout=5m --labels=app.kubernetes.io/name=mysql-bootstrap --command -- \
    sh -c 'IFS= read -r pw; MYSQL_PWD="$pw" exec mysql --host="$1" --user="$2" --protocol=TCP --ssl-mode=REQUIRED' \
    sh "$MYSQL_HOST" "$MYSQL_ADMIN_USER"
elif command -v mysql >/dev/null 2>&1; then
  MYSQL_PWD="$MYSQL_ADMIN_PASSWORD" mysql \
    --host="$MYSQL_HOST" --user="$MYSQL_ADMIN_USER" --protocol=TCP \
    --ssl-mode=REQUIRED < "$sql_file"
else
  # The self-hosted staging runner has no mysql CLI; run the same statements over TLS with PyMySQL.
  python3 "$(dirname "${BASH_SOURCE[0]}")/execute_sql.py" "$sql_file"
fi
echo "Created the service and Keycloak databases and restricted each account to its own database."
