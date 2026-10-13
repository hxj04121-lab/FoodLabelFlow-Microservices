#!/usr/bin/env bash
set -euo pipefail

: "${TARGET_SERVICE_FQDN:?Set TARGET_SERVICE_FQDN to the target ClusterIP service DNS name}"
: "${ALLOWED_NAMESPACE:?Set ALLOWED_NAMESPACE to the approved caller namespace}"
: "${ALLOWED_APP_NAME:?Set ALLOWED_APP_NAME to the caller pod app.kubernetes.io/name label}"
[[ "$TARGET_SERVICE_FQDN" =~ ^[a-z0-9]([-a-z0-9]*[a-z0-9])?\.[a-z0-9]([-a-z0-9]*[a-z0-9])?\.svc\.cluster\.local$ ]] || { echo 'Invalid target service DNS name' >&2; exit 2; }
[[ "$ALLOWED_NAMESPACE" =~ ^[a-z0-9]([-a-z0-9]*[a-z0-9])?$ ]] || { echo 'Invalid caller namespace' >&2; exit 2; }
[[ "$ALLOWED_APP_NAME" =~ ^[a-z0-9]([-a-z0-9]*[a-z0-9])?$ ]] || { echo 'Invalid caller app name' >&2; exit 2; }
probe_image="${PROBE_IMAGE:-busybox:1.37.0}"
allowed="stcn-15-allow-$RANDOM"
denied="stcn-15-deny-$RANDOM"
url="http://${TARGET_SERVICE_FQDN}:8080/actuator/health/readiness"
cleanup() {
  kubectl delete pod -n "$ALLOWED_NAMESPACE" "$allowed" --ignore-not-found --wait=false >/dev/null 2>&1 || true
  kubectl delete pod -n default "$denied" --ignore-not-found --wait=false >/dev/null 2>&1 || true
}
trap cleanup EXIT

kubectl run "$allowed" -n "$ALLOWED_NAMESPACE" --image="$probe_image" --restart=Never \
  --labels="app.kubernetes.io/name=$ALLOWED_APP_NAME" --command -- sh -c 'sleep 600' >/dev/null
kubectl run "$denied" -n default --image="$probe_image" --restart=Never \
  --labels=app.kubernetes.io/name=stcn-15-unauthorized --command -- sh -c 'sleep 600' >/dev/null
kubectl wait -n "$ALLOWED_NAMESPACE" --for=condition=Ready "pod/$allowed" --timeout=120s
kubectl wait -n default --for=condition=Ready "pod/$denied" --timeout=120s

if ! kubectl exec -n "$ALLOWED_NAMESPACE" "$allowed" -- sh -c "wget -T 5 -qO- '$url' | grep -q 'UP'"; then
  echo 'FAIL approved peer could not reach the service readiness endpoint' >&2
  exit 1
fi
echo 'PASS approved peer reached the service readiness endpoint'

if kubectl exec -n default "$denied" -- sh -c "wget -T 5 -qO- '$url'" >/dev/null 2>&1; then
  echo 'FAIL unapproved peer reached the service' >&2
  exit 1
fi
echo 'PASS unapproved peer was denied by NetworkPolicy'
