# M1 CloudShell runbook: install RabbitMQ staging operators

M5 executes this CloudShell runbook against the staging account under M1's approval. As of 2026-10-21, M5 holds hands-on access to that account; M1 remains the approval owner. Do not run any command until M1 records the separately approved operator-install window and M5 verifies the target account, cluster, and context. The normal RabbitMQ workflow must not install or upgrade these operators.

The manifests have been downloaded and checked from their versioned GitHub Release URLs. The lock file records the exact URLs, SHA-256 digests, and byte sizes. This procedure downloads them again and verifies the bytes before any cluster write.

## 1. Verify target and prepare private evidence

In CloudShell, set these values from the approved M1 change record; do not guess an account ID:

```bash
export AWS_REGION=ap-southeast-1
export EKS_CLUSTER_NAME=spectrace-staging
export EXPECTED_STAGING_ACCOUNT_ID='<approved 12-digit AWS account ID>'
export EVIDENCE_DIR="$HOME/m5-rabbitmq-operator-evidence-$(date -u +%Y%m%dT%H%M%SZ)"
umask 077
mkdir -p "$EVIDENCE_DIR"
set -euo pipefail
test "$AWS_REGION" = ap-southeast-1
test "$EKS_CLUSTER_NAME" = spectrace-staging
actual_account_id=$(aws sts get-caller-identity --query Account --output text)
test "$actual_account_id" = "$EXPECTED_STAGING_ACCOUNT_ID"
aws eks describe-cluster --name "$EKS_CLUSTER_NAME" --region "$AWS_REGION" > "$EVIDENCE_DIR/cluster-before.json"
test "$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["cluster"]["status"])' "$EVIDENCE_DIR/cluster-before.json")" = ACTIVE
aws eks update-kubeconfig --name "$EKS_CLUSTER_NAME" --region "$AWS_REGION" --alias m5-rabbitmq-operator-review
kubectl config current-context > "$EVIDENCE_DIR/context-before.txt"
aws sts get-caller-identity > "$EVIDENCE_DIR/aws-identity-before.json"
```

Do not put kubeconfig, tokens, or Secret data in the evidence directory. Protect the directory according to M1's evidence-retention process. Before installation, capture only resource metadata and status; do not dump Secret resources.

## 2. Inspect existing installation state; stop on any pre-existing component

This check is fail-closed. `--ignore-not-found` turns a missing named object into an empty result; it does not hide authorization, API, or network errors. Any non-zero `kubectl` result stops the runbook. The evidence contains only identity metadata and readiness fields, never Secret objects or values.

```bash
set -euo pipefail
operator_state_dir="$EVIDENCE_DIR/operator-state-before"
mkdir -m 700 "$operator_state_dir"
tmp_dir=$(mktemp -d)
trap 'rm -rf "$tmp_dir"' EXIT

capture_named_state() {
  local label="$1" resource="$2" name="$3" namespace="$4"
  local raw="$tmp_dir/${label}.json" destination="$operator_state_dir/${label}.json"
  local command=(kubectl get "$resource" "$name" --ignore-not-found -o json)
  if [[ -n "$namespace" ]]; then command+=(--namespace "$namespace"); fi
  if ! "${command[@]}" > "$raw"; then
    echo "Failed to read ${resource}/${name}${namespace:+ in namespace ${namespace}}. This is not evidence of absence; stop and resolve access or connectivity."
    return 1
  fi
  python3 - "$raw" "$destination" <<'PY'
import json, pathlib, sys
raw_path, destination = map(pathlib.Path, sys.argv[1:])
raw = raw_path.read_text()
if not raw.strip():
    result = {"exists": False}
else:
    obj = json.loads(raw)
    metadata = obj.get("metadata", {})
    status = obj.get("status", {})
    result = {
        "exists": True,
        "apiVersion": obj.get("apiVersion"),
        "kind": obj.get("kind"),
        "metadata": {
            key: metadata[key]
            for key in ("name", "namespace", "uid", "resourceVersion", "creationTimestamp", "labels")
            if key in metadata
        },
        "status": {
            key: status[key]
            for key in ("conditions", "availableReplicas", "readyReplicas", "replicas", "observedGeneration", "acceptedNames", "storedVersions")
            if key in status
        },
    }
destination.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n")
PY
}

for namespace in cert-manager rabbitmq-system; do
  capture_named_state "namespace-${namespace}" namespaces "$namespace" ""
done

for target in \
  'cert-manager cert-manager' \
  'cert-manager cert-manager-webhook' \
  'cert-manager cert-manager-cainjector' \
  'rabbitmq-system rabbitmq-cluster-operator' \
  'rabbitmq-system messaging-topology-operator'; do
  read -r namespace deployment <<< "$target"
  capture_named_state "deployment-${namespace}-${deployment}" deployments "$deployment" "$namespace"
done

for crd in \
  certificates.cert-manager.io certificaterequests.cert-manager.io issuers.cert-manager.io \
  clusterissuers.cert-manager.io orders.acme.cert-manager.io challenges.acme.cert-manager.io \
  rabbitmqclusters.rabbitmq.com users.rabbitmq.com permissions.rabbitmq.com \
  exchanges.rabbitmq.com queues.rabbitmq.com bindings.rabbitmq.com policies.rabbitmq.com; do
  capture_named_state "crd-${crd//./-}" customresourcedefinitions.apiextensions.k8s.io "$crd" ""
done

for target in \
  'mutatingwebhookconfigurations.admissionregistration.k8s.io cert-manager-webhook' \
  'validatingwebhookconfigurations.admissionregistration.k8s.io cert-manager-webhook' \
  'mutatingwebhookconfigurations.admissionregistration.k8s.io cluster-operator-mutating-webhook-configuration' \
  'validatingwebhookconfigurations.admissionregistration.k8s.io cluster-operator-validating-webhook-configuration' \
  'mutatingwebhookconfigurations.admissionregistration.k8s.io messaging-topology-mutating-webhook-configuration' \
  'validatingwebhookconfigurations.admissionregistration.k8s.io messaging-topology-validating-webhook-configuration'; do
  read -r resource name <<< "$target"
  capture_named_state "webhook-${resource//./-}-${name}" "$resource" "$name" ""
done

if grep -l '"exists": true' "$operator_state_dir"/*.json > "$operator_state_dir/preexisting-components.txt"; then
  echo "Pre-existing target operator namespaces, Deployments, CRDs, or webhook configurations found; stop and ask M1 to confirm ownership and health. See $operator_state_dir."
  exit 1
fi
echo 'No target operator namespace, Deployment, or CRD was found. Review the captured evidence before requesting M1 approval.'
rm -rf "$tmp_dir"
trap - EXIT
```

Review every JSON file. `exists: false` means the named object was successfully queried and was not found; command errors stop before this point. If either namespace, any target CRD, operator Deployment, or webhook configuration exists, stop and ask M1 to determine its owner and health. Do not reapply release manifests to an existing installation. Record the review decision before proceeding.

## 3. Download and verify exact reviewed bytes

Run from a clean checkout of the reviewed main commit containing `deploy/operators/rabbitmq/operator-manifests.lock` and `scripts/staging/verify_operator_manifest_lock.py`:

```bash
set -euo pipefail
tmp_dir=$(mktemp -d)
trap 'rm -rf "$tmp_dir"' EXIT
curl --fail --location --silent --show-error 'https://github.com/cert-manager/cert-manager/releases/download/v1.21.2/cert-manager.yaml' --output "$tmp_dir/cert-manager.yaml"
curl --fail --location --silent --show-error 'https://github.com/rabbitmq/cluster-operator/releases/download/v2.22.3/cluster-operator.yml' --output "$tmp_dir/rabbitmq-cluster-operator.yaml"
curl --fail --location --silent --show-error 'https://github.com/rabbitmq/messaging-topology-operator/releases/download/v1.20.2/messaging-topology-operator-with-certmanager.yaml' --output "$tmp_dir/messaging-topology-operator.yaml"
python3 scripts/staging/verify_operator_manifest_lock.py --manifest-dir "$tmp_dir"
```

If any download or verification fails, stop. Do not edit the lock to match an unexpected response; ask M1 to review the release source and bytes.

## 4. Confirm the separate install window before writes

Before `kubectl create`, M1 records the approved window, expected account/cluster/context, the before-state evidence, and approval reference. Re-run `aws sts get-caller-identity`, `aws eks describe-cluster`, and `kubectl config current-context`; compare them with step 1. If anything differs, stop.

Use `kubectl create -f` so an unexpected pre-existing object causes a failure. Do not use `apply`, `replace`, `patch`, `delete`, or force. If a partial create fails, stop and record exactly which objects exist; do not clean up or retry over them without M1's review.

```bash
set -euo pipefail
kubectl create -f "$tmp_dir/cert-manager.yaml"
for deployment in cert-manager cert-manager-webhook cert-manager-cainjector; do
  kubectl rollout status "deployment/$deployment" --namespace cert-manager --timeout=5m
done

kubectl create -f "$tmp_dir/rabbitmq-cluster-operator.yaml"
kubectl rollout status deployment/rabbitmq-cluster-operator --namespace rabbitmq-system --timeout=5m

kubectl create -f "$tmp_dir/messaging-topology-operator.yaml"
kubectl rollout status deployment/messaging-topology-operator --namespace rabbitmq-system --timeout=5m
```

The release manifests contain cluster-scoped CRDs, roles, and webhook configuration. Those writes require M1's explicit approval and are performed by M5 using the approved CloudShell identity; the M5 GitHub workflow has no permission to perform them.

## 5. Verify availability and capture after-state

```bash
set -euo pipefail
for crd in certificates.cert-manager.io certificaterequests.cert-manager.io issuers.cert-manager.io clusterissuers.cert-manager.io orders.acme.cert-manager.io challenges.acme.cert-manager.io rabbitmqclusters.rabbitmq.com users.rabbitmq.com permissions.rabbitmq.com exchanges.rabbitmq.com queues.rabbitmq.com bindings.rabbitmq.com policies.rabbitmq.com; do
  kubectl wait --for=condition=Established "crd/$crd" --timeout=2m
done
for deployment in cert-manager cert-manager-webhook cert-manager-cainjector; do kubectl rollout status "deployment/$deployment" --namespace cert-manager --timeout=5m; done
kubectl rollout status deployment/rabbitmq-cluster-operator --namespace rabbitmq-system --timeout=5m
kubectl rollout status deployment/messaging-topology-operator --namespace rabbitmq-system --timeout=5m
kubectl get deployments -n cert-manager -o wide > "$EVIDENCE_DIR/cert-manager-deployments-after.txt"
kubectl get deployments -n rabbitmq-system -o wide > "$EVIDENCE_DIR/rabbitmq-operator-deployments-after.txt"
kubectl get crd certificates.cert-manager.io certificaterequests.cert-manager.io issuers.cert-manager.io clusterissuers.cert-manager.io orders.acme.cert-manager.io challenges.acme.cert-manager.io rabbitmqclusters.rabbitmq.com users.rabbitmq.com permissions.rabbitmq.com exchanges.rabbitmq.com queues.rabbitmq.com bindings.rabbitmq.com policies.rabbitmq.com -o wide > "$EVIDENCE_DIR/operator-crds-after.txt"
aws eks describe-cluster --name "$EKS_CLUSTER_NAME" --region "$AWS_REGION" > "$EVIDENCE_DIR/cluster-after.json"

for target in \
  'mutatingwebhookconfigurations.admissionregistration.k8s.io cert-manager-webhook' \
  'validatingwebhookconfigurations.admissionregistration.k8s.io cert-manager-webhook' \
  'mutatingwebhookconfigurations.admissionregistration.k8s.io cluster-operator-mutating-webhook-configuration' \
  'validatingwebhookconfigurations.admissionregistration.k8s.io cluster-operator-validating-webhook-configuration' \
  'mutatingwebhookconfigurations.admissionregistration.k8s.io messaging-topology-mutating-webhook-configuration' \
  'validatingwebhookconfigurations.admissionregistration.k8s.io messaging-topology-validating-webhook-configuration'; do
  read -r resource name <<< "$target"
  kubectl get "$resource" "$name" -o json | python3 -c '
import json,sys
obj=json.load(sys.stdin)
rows=obj.get("webhooks",[])
assert rows and all(w.get("clientConfig",{}).get("caBundle") and w.get("clientConfig",{}).get("service",{}).get("name") and w.get("clientConfig",{}).get("service",{}).get("namespace") for w in rows), "Webhook CA injection or Service reference is not ready"
print(json.dumps({"name":obj["metadata"]["name"],"webhooks":[{"name":w.get("name"),"caBundlePresent":True,"service":w["clientConfig"]["service"]} for w in rows]},indent=2,sort_keys=True))
' > "$EVIDENCE_DIR/webhook-${resource//./-}-${name}.json"
done

for target in \
  'cert-manager cert-manager-webhook' \
  'rabbitmq-system cluster-operator-webhook-service' \
  'rabbitmq-system messaging-topology-webhook-service'; do
  read -r namespace service <<< "$target"
  kubectl get endpoints "$service" --namespace "$namespace" -o json | python3 -c '
import json,sys
obj=json.load(sys.stdin)
count=sum(len(subset.get("addresses",[])) for subset in obj.get("subsets",[]))
assert count > 0, "Webhook Service has no ready Endpoint addresses"
print(json.dumps({"namespace":obj["metadata"]["namespace"],"service":obj["metadata"]["name"],"readyAddressCount":count},indent=2,sort_keys=True))
' > "$EVIDENCE_DIR/webhook-endpoints-${namespace}-${service}.json"
done
```

Record the lock file, its checksum, the manifest digests verified by the script, the AWS identity ARN/account, cluster ARN/status, context, CRD Established conditions, Deployment Available status, timestamps, approval reference, and any deviation. Never include Secret values or bearer tokens. Keep the evidence for the protected RabbitMQ deployment review.

The protected GitHub workflow does not execute sections 4–5. M5 performs those steps manually in CloudShell only after M1 has recorded the approval and window; this document alone does not authorize an installation.
