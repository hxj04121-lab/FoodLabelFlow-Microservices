#!/usr/bin/env bash
set -euo pipefail

container="stcn15-mysql-test-$$"
root_password=$(openssl rand -hex 24)
docker run --detach --name "$container" --env "MYSQL_ROOT_PASSWORD=$root_password" mysql:8.4.11 >/dev/null
cleanup() { docker rm --force "$container" >/dev/null 2>&1 || true; }
trap cleanup EXIT

for _ in {1..60}; do
  if docker exec "$container" mysqladmin --user=root --password="$root_password" ping --silent >/dev/null 2>&1; then break; fi
  sleep 2
done
docker exec "$container" mysqladmin --user=root --password="$root_password" ping --silent >/dev/null
docker cp deploy/mysql/bootstrap-service-databases.sh "$container:/tmp/bootstrap-service-databases.sh"

services=(specification formulation compliance label_workflow keycloak)
variables=(SPECIFICATION_DB_PASSWORD FORMULATION_DB_PASSWORD COMPLIANCE_DB_PASSWORD LABEL_WORKFLOW_DB_PASSWORD KEYCLOAK_DB_PASSWORD)
bootstrap_env=()
passwords=()
for i in "${!services[@]}"; do
  service="${services[$i]}"
  password=$(openssl rand -hex 24)
  passwords+=("$password")
  variable="${variables[$i]}"
  bootstrap_env+=(--env "$variable=$password")
done
bootstrap_env+=(--env "MYSQL_ADMIN_PASSWORD=$root_password" --env MYSQL_ADMIN_USER=root --env MYSQL_HOST=127.0.0.1)

docker exec "${bootstrap_env[@]}" "$container" bash /tmp/bootstrap-service-databases.sh
docker exec "${bootstrap_env[@]}" "$container" bash /tmp/bootstrap-service-databases.sh

for i in "${!services[@]}"; do
  service="${services[$i]}"
  password="${passwords[$i]}"
  for j in "${!services[@]}"; do
    target="${services[$j]}"
    if [[ "$i" == "$j" ]]; then
      docker exec "$container" mysql --user="$service" --password="$password" "$target" --execute='SELECT 1' >/dev/null
      printf 'PASS %s can access %s\n' "$service" "$target"
    elif docker exec "$container" mysql --user="$service" --password="$password" "$target" --execute='SELECT 1' >/dev/null 2>&1; then
      printf 'FAIL %s accessed %s\n' "$service" "$target" >&2
      exit 1
    else
      printf 'PASS %s denied access to %s\n' "$service" "$target"
    fi
  done
  grants=$(docker exec "$container" mysql --user="$service" --password="$password" --execute='SHOW GRANTS')
  expected_usage="GRANT USAGE ON *.* TO \`$service\`@\`%\`"
  expected_database="GRANT ALL PRIVILEGES ON \`$service\`.* TO \`$service\`@\`%\`"
  if [[ $(grep -c '^GRANT ' <<<"$grants") -ne 2 ]] || ! grep -Fqx "$expected_usage" <<<"$grants" || ! grep -Fqx "$expected_database" <<<"$grants"; then
    printf 'FAIL unexpected grants for %s\n' "$service" >&2
    exit 1
  fi
  printf 'GRANTS %s:\n%s\n' "$service" "$grants"
  printf 'PASS %s grant list contains only its own database and no grant option\n' "$service"
done
