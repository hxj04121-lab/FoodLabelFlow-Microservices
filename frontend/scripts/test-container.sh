#!/usr/bin/env bash
set -euo pipefail
# Only a disposable local Docker container. No AWS, Kubernetes or business writes.
image="${1:?Usage: bash frontend/scripts/test-container.sh IMAGE}"
container=""
evidence="$(mktemp -d)"
cleanup() {
  if [[ -n "$container" ]]; then docker rm -f "$container" >/dev/null; fi
  rm -f "$evidence/index" "$evidence/deep" "$evidence/release" "$evidence/headers"
  rmdir "$evidence"
}
trap cleanup EXIT
container="$(docker run -d -p 127.0.0.1::8080 "$image")"
port="$(docker port "$container" 8080/tcp | awk -F: '{print $NF}')"
url="http://127.0.0.1:$port"
for attempt in $(seq 1 30); do
  if curl -fsS "$url/healthz" >/dev/null; then break; fi
  sleep 1
done
curl -fsS "$url/healthz" | grep -qx 'ok'
curl -fsS -D "$evidence/headers" "$url/" -o "$evidence/index"
grep -qi '^Cache-Control: no-store' "$evidence/headers"
curl -fsS "$url/labels?code=fixture&state=fixture" -o "$evidence/deep"
cmp "$evidence/index" "$evidence/deep"
curl -fsS "$url/g1-release.json" -o "$evidence/release"
grep -q '"gatewayOrigin": "/"' "$evidence/release"
for path in /api /api/specifications/caller-claims /auth /auth/realms/spectrace/account /assets/missing.js; do
  [[ "$(curl -sS -o /dev/null -w '%{http_code}' "$url$path")" == 404 ]]
done
asset="$(sed -n 's/.*src="\([^"]*\.js\)".*/\1/p' "$evidence/index" | head -n 1)"
[[ "$asset" == /assets/* ]]
curl -fsS -D "$evidence/headers" "$url$asset" -o /dev/null
grep -qi '^Cache-Control: public, max-age=31536000, immutable' "$evidence/headers"
[[ "$(docker exec "$container" id -u)" != 0 ]]
echo 'PASS: non-root nginx, root/assets, SPA callback fallback, public metadata, cache rules and API/auth isolation.'
