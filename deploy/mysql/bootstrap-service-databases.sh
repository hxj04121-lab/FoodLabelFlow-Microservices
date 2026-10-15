#!/usr/bin/env bash
set -euo pipefail

: "${MYSQL_HOST:?Set MYSQL_HOST to the private RDS endpoint}"
: "${MYSQL_ADMIN_USER:?Set MYSQL_ADMIN_USER to the bootstrap administrator}"
: "${MYSQL_ADMIN_PASSWORD:?Set MYSQL_ADMIN_PASSWORD from a protected secret}"

services=(specification formulation compliance label_workflow)
variables=(SPECIFICATION_DB_PASSWORD FORMULATION_DB_PASSWORD COMPLIANCE_DB_PASSWORD LABEL_WORKFLOW_DB_PASSWORD)
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

MYSQL_PWD="$MYSQL_ADMIN_PASSWORD" mysql \
  --host="$MYSQL_HOST" --user="$MYSQL_ADMIN_USER" --protocol=TCP \
  --ssl-mode=REQUIRED < "$sql_file"
echo "Created the four service databases and restricted each account to its own database."
