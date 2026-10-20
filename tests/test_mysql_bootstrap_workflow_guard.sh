#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT
mkdir -p "$tmp_dir/bin"
cat > "$tmp_dir/bin/mysql" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail
cat > "$MYSQL_CAPTURE"
MOCK
chmod +x "$tmp_dir/bin/mysql"
export PATH="$tmp_dir/bin:$PATH"
export MYSQL_CAPTURE="$tmp_dir/mysql.sql"
export MYSQL_ADMIN_USER=staging_admin
export MYSQL_ADMIN_PASSWORD=never-written-to-sql
export SPECIFICATION_DB_PASSWORD=specification_password_123456789012
export FORMULATION_DB_PASSWORD=formulation_password_123456789012
export COMPLIANCE_DB_PASSWORD=compliance_password_123456789012
export LABEL_WORKFLOW_DB_PASSWORD=label_workflow_password_123456789
export KEYCLOAK_DB_PASSWORD=keycloak_password_12345678901234
export MYSQL_HOST=spectrace.abc.ap-southeast-1.rds.amazonaws.com

if env -u GITHUB_ACTIONS bash "$repo_root/deploy/mysql/bootstrap-service-databases.sh" > "$tmp_dir/unprotected.log" 2>&1; then
  echo "FAIL RDS database bootstrap ran outside GitHub Actions" >&2
  exit 1
fi
test ! -e "$MYSQL_CAPTURE"

export GITHUB_ACTIONS=true
export GITHUB_REF=refs/heads/main
export GITHUB_WORKFLOW='STCN-15 staging runtime Secrets'
export STAGING_WINDOW_VALIDATED=true
bash "$repo_root/deploy/mysql/bootstrap-service-databases.sh" > "$tmp_dir/protected.log"
for service in specification formulation compliance label_workflow keycloak; do
  grep -Fqx "CREATE DATABASE IF NOT EXISTS ${service};" "$MYSQL_CAPTURE"
  grep -Fq "GRANT ALL PRIVILEGES ON ${service}.* TO '${service}'@'%';" "$MYSQL_CAPTURE"
done
! grep -Fq "$MYSQL_ADMIN_PASSWORD" "$MYSQL_CAPTURE"
for password in "$SPECIFICATION_DB_PASSWORD" "$FORMULATION_DB_PASSWORD" "$COMPLIANCE_DB_PASSWORD" "$LABEL_WORKFLOW_DB_PASSWORD" "$KEYCLOAK_DB_PASSWORD"; do
  ! grep -Fq "$password" "$tmp_dir/protected.log"
done
echo "PASS RDS database bootstrap is protected and each application account is restricted to its own schema"
