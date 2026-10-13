#!/usr/bin/env bash
# Reconstruct the audit trail of one demo action from Grafana Cloud Loki by correlation ID (G3-M1.4, STCN-92).
#
#   LOKI_ADDR=https://logs-prod-xxx.grafana.net LOKI_USERNAME=<logs user> LOKI_PASSWORD=<api key> \
#     perf/demo/audit_trail.sh <correlationId> [since, default 2h]
#
# Needs Grafana's logcli on PATH. Prints one line per log event across all services, oldest first, as
# "<time> <namespace> <level> <logger> <message>". Credentials come from the environment only.
set -euo pipefail

id="${1:?usage: audit_trail.sh <correlationId> [since]}"
since="${2:-2h}"
[[ "$id" =~ ^[A-Za-z0-9._:-]{1,128}$ ]] || { echo "not a correlation ID: $id" >&2; exit 2; }

query='{namespace=~"gateway|specification|formulation|compliance|label-workflow"} |= "'"$id"'" | json | correlationId="'"$id"'" | line_format "{{.namespace}} {{.log_level}} {{.log_logger}} {{.message}}"'
logcli query --quiet --forward --since="$since" --limit=5000 --output=default "$query"
