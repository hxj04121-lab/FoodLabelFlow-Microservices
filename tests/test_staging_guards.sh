#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
for action in up down; do
  output="$(STAGING_WINDOW_OPEN=false "$root/scripts/staging.sh" "$action" 2>&1 || true)"
  [[ "$output" == *"STAGING_WINDOW_OPEN must be true"* ]] || {
    echo "$action did not fail closed while the staging window was closed" >&2
    exit 1
  }
done
echo "Staging closed-window guard: PASS"
