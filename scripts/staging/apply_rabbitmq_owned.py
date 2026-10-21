#!/usr/bin/env python3
"""Create RabbitMQ staging objects and resume only objects this workflow owns."""

import argparse
import copy
import json
import os
import subprocess
import sys
import time
from pathlib import Path

import yaml

OWNER_ANNOTATION = "staging.spectrace.cn/managed-by"
OWNER = "rabbitmq-protected-workflow"
FIELD_MANAGER = "foodlabelflow-m5-rabbitmq"
WORKFLOW = "Protected RabbitMQ staging deployment and smoke test"
CREATE_ATTEMPTS = 3
RABBITMQ_CLUSTER_KEY = ("rabbitmq.com/v1beta1", "RabbitmqCluster", "rabbitmq", "spectrace")
RECOVERY_GUIDANCE = " Contact the RabbitMQ owner and use the protected recovery workflow."
ALLOWED_NAMESPACES = {"rabbitmq", "specification", "formulation", "compliance", "label-workflow"}
TOPOLOGY_QUEUES = {
    "compliance": {
        "compliance-specification-published": 1,
        "compliance-formula-published": 1,
        "compliance-label-published": 2,
    },
    "formulation": {"formulation-specification-published": 1},
    "label-workflow": {"label-workflow-formula-published": 1, "label-workflow-impact-finding": 1},
    "rabbitmq": {"staging-smoke-smoke": 1},
}


class SafeApplyError(RuntimeError):
    pass


def protected_context(env):
    expected = {
        "GITHUB_ACTIONS": "true",
        "GITHUB_REF": "refs/heads/main",
        "GITHUB_WORKFLOW": WORKFLOW,
        "STAGING_WINDOW_VALIDATED": "true",
    }
    if any(env.get(key) != value for key, value in expected.items()):
        raise SafeApplyError("Refusing resource changes outside the protected main staging window.")


def flatten(document):
    if document is None:
        return []
    if not isinstance(document, dict):
        raise SafeApplyError("Manifest contains a non-object YAML document.")
    if document.get("kind") == "List":
        objects = []
        for item in document.get("items", []):
            objects.extend(flatten(item))
        return objects
    metadata = document.get("metadata")
    if not document.get("apiVersion") or not document.get("kind") or not isinstance(metadata, dict) or not metadata.get("name"):
        raise SafeApplyError("Every manifest object must have apiVersion, kind, and a stable metadata.name.")
    annotations = metadata.setdefault("annotations", {})
    if not isinstance(annotations, dict):
        raise SafeApplyError("Manifest metadata.annotations must be a mapping.")
    owner = annotations.get(OWNER_ANNOTATION)
    if owner not in (None, OWNER):
        raise SafeApplyError("Manifest carries a different ownership marker.")
    annotations[OWNER_ANNOTATION] = OWNER
    if not is_allowed_resource(document):
        raise SafeApplyError("Manifest contains a resource outside the reviewed RabbitMQ namespace and name allowlist.")
    return [document]


def object_key(resource):
    metadata = resource["metadata"]
    return (
        resource.get("apiVersion", ""),
        resource.get("kind", ""),
        metadata.get("namespace", ""),
        metadata["name"],
    )


def is_allowed_resource(resource):
    """Permit only the reviewed, namespace-scoped RabbitMQ deployment object set."""
    api_version, kind, namespace, name = object_key(resource)
    if namespace not in ALLOWED_NAMESPACES:
        return False
    if kind == "RabbitmqCluster":
        return api_version == "rabbitmq.com/v1beta1" and (namespace, name) == ("rabbitmq", "spectrace")
    if kind == "PodDisruptionBudget":
        return api_version == "policy/v1" and (namespace, name) == ("rabbitmq", "spectrace-rabbitmq-quorum")
    if kind in {"User", "Permission"}:
        expected = {
            ("rabbitmq", "staging-smoke"),
            ("specification", "specification-rabbitmq"),
            ("formulation", "formulation-rabbitmq"),
            ("compliance", "compliance-rabbitmq"),
            ("label-workflow", "label-workflow-rabbitmq"),
        }
        return api_version == "rabbitmq.com/v1beta1" and (namespace, name) in expected
    if api_version != "rabbitmq.com/v1beta1":
        return False
    if kind == "Exchange":
        return namespace == "rabbitmq" and name in {"spectrace-events", "spectrace-dlx"}
    if kind not in {"Queue", "Binding", "Policy"}:
        return False
    for service_namespace, queues in TOPOLOGY_QUEUES.items():
        if namespace != service_namespace:
            continue
        expected_names = set()
        for queue_name, event_count in queues.items():
            if kind == "Queue":
                expected_names.update({queue_name, f"{queue_name}-dlq"})
            elif kind == "Binding":
                expected_names.add(f"{queue_name}-dead-letter")
                expected_names.update(f"{queue_name}-event-{index}" for index in range(event_count))
            else:
                expected_names.add(f"{queue_name}-delivery-policy")
        return name in expected_names
    return False


def response_objects(output):
    if not output.strip():
        return []
    parsed = json.loads(output)
    if isinstance(parsed, list):
        return parsed
    if parsed.get("kind") == "List":
        return parsed.get("items", [])
    return [parsed]


def checked_response_objects(output, failure_message):
    try:
        rows = response_objects(output)
        if not isinstance(rows, list) or any(
            not isinstance(row, dict)
            or not isinstance(row.get("metadata"), dict)
            or not row.get("metadata", {}).get("name")
            for row in rows
        ):
            raise ValueError("unexpected Kubernetes response shape")
    except (ValueError, TypeError, AttributeError) as exc:
        raise SafeApplyError(failure_message) from exc
    return rows


def call_kubectl(runner, args, payload=""):
    try:
        return runner(
            ["kubectl", *args],
            input=payload,
            text=True,
            capture_output=True,
            timeout=45,
        )
    except subprocess.TimeoutExpired as exc:
        return subprocess.CompletedProcess(args, 124, exc.stdout or "", exc.stderr or "kubectl timed out")
    except OSError as exc:
        raise SafeApplyError("Could not run kubectl; no resource was intentionally changed.") from exc


def verify_owned_resource(runner, item):
    fetched = call_kubectl(
        runner,
        ["get", "--ignore-not-found=true", "-f", "-", "-o", "json"],
        yaml.safe_dump(item, sort_keys=False),
    )
    if fetched.returncode:
        raise SafeApplyError(
            "Could not safely inspect a RabbitMQ resource after a create retry; refusing changes."
            + RECOVERY_GUIDANCE
        )
    rows = checked_response_objects(
        fetched.stdout,
        "Could not safely decode a RabbitMQ resource during create recovery; refusing changes."
        + RECOVERY_GUIDANCE,
    )
    if not rows:
        return False
    if len(rows) != 1 or object_key(rows[0]) != object_key(item):
        raise SafeApplyError("Kubernetes returned an unexpected resource while recovering a RabbitMQ create.")
    owner = rows[0].get("metadata", {}).get("annotations", {}).get(OWNER_ANNOTATION)
    if owner != OWNER:
        raise SafeApplyError(
            "Refusing to adopt a RabbitMQ resource with unknown ownership."
            + RECOVERY_GUIDANCE
        )
    diff = call_kubectl(
        runner,
        ["diff", "--server-side", "--field-manager", FIELD_MANAGER, "-f", "-"],
        yaml.safe_dump(item, sort_keys=False),
    )
    if diff.returncode:
        raise SafeApplyError(
            "RabbitMQ resource changed during create recovery; refusing to update it."
            + RECOVERY_GUIDANCE
        )
    return True


def apply_owned_manifest(path, runner=subprocess.run, env=None):
    protected_context(os.environ if env is None else env)
    documents = []
    try:
        for document in yaml.safe_load_all(Path(path).read_text()):
            documents.extend(flatten(copy.deepcopy(document)))
    except (OSError, yaml.YAMLError) as exc:
        raise SafeApplyError("Could not read the RabbitMQ manifest.") from exc
    if not documents:
        raise SafeApplyError("RabbitMQ manifest contains no resources.")

    rendered = [yaml.safe_dump(item, sort_keys=False) for item in documents]
    payload = "---\n".join(rendered)
    fetched = call_kubectl(runner, ["get", "--ignore-not-found=true", "-f", "-", "-o", "json"], payload)
    if fetched.returncode:
        raise SafeApplyError(
            "Could not safely inspect existing RabbitMQ resources; refusing changes."
            + RECOVERY_GUIDANCE
        )

    desired = {}
    for item in documents:
        key = object_key(item)
        if key in desired:
            raise SafeApplyError("Manifest contains a duplicate Kubernetes resource identity.")
        desired[key] = item
    existing = {}
    rows = checked_response_objects(
        fetched.stdout,
        "Could not safely decode existing RabbitMQ resources; refusing changes."
        + RECOVERY_GUIDANCE,
    )
    for item in rows:
        try:
            key = object_key(item)
        except (KeyError, TypeError, AttributeError) as exc:
            raise SafeApplyError(
                "Could not safely identify an existing RabbitMQ resource; refusing changes."
                + RECOVERY_GUIDANCE
            ) from exc
        if key not in desired:
            raise SafeApplyError("Kubernetes returned an unexpected resource for this manifest.")
        owner = item.get("metadata", {}).get("annotations", {}).get(OWNER_ANNOTATION)
        if owner not in (None, OWNER):
            guidance = RECOVERY_GUIDANCE if key == RABBITMQ_CLUSTER_KEY else ""
            raise SafeApplyError(
                f"Refusing to take over existing {key[1]} {key[2]}/{key[3]} with unknown ownership."
                + guidance
            )
        existing[key] = item

    if RABBITMQ_CLUSTER_KEY in desired and RABBITMQ_CLUSTER_KEY not in existing:
        workload = call_kubectl(
            runner,
            [
                "get",
                "statefulsets,pods,persistentvolumeclaims",
                "--namespace",
                "rabbitmq",
                "-o",
                "name",
            ],
        )
        if workload.returncode:
            raise SafeApplyError(
                "Could not verify RabbitMQ StatefulSet, Pod, and PVC state; refusing to create the Cluster."
                + RECOVERY_GUIDANCE
            )
        if workload.stdout.strip():
            raise SafeApplyError(
                "RabbitMQ StatefulSet, Pod, or PVC objects exist without the expected Cluster; "
                "refusing adoption or automatic recovery."
                + RECOVERY_GUIDANCE
            )

    for key, item in existing.items():
        owner = item.get("metadata", {}).get("annotations", {}).get(OWNER_ANNOTATION)
        if owner != OWNER:
            guidance = RECOVERY_GUIDANCE if key == RABBITMQ_CLUSTER_KEY else ""
            raise SafeApplyError(
                f"Refusing to adopt existing {key[1]} {key[2]}/{key[3]} with unknown ownership."
                + guidance
            )
        diff = call_kubectl(runner, ["diff", "--server-side", "--field-manager", FIELD_MANAGER, "-f", "-"], yaml.safe_dump(desired[key], sort_keys=False))
        if diff.returncode:
            guidance = RECOVERY_GUIDANCE if key == RABBITMQ_CLUSTER_KEY else ""
            raise SafeApplyError(
                f"Existing owned {key[1]} {key[2]}/{key[3]} differs from the reviewed manifest; refusing to update it."
                + guidance
            )

    created = 0
    for key, resource in desired.items():
        if key in existing:
            continue
        for attempt in range(CREATE_ATTEMPTS):
            result = call_kubectl(runner, ["create", "--field-manager", FIELD_MANAGER, "-f", "-"], yaml.safe_dump(resource, sort_keys=False))
            if not result.returncode:
                created += 1
                break
            if verify_owned_resource(runner, resource):
                created += 1
                break
            if attempt + 1 == CREATE_ATTEMPTS:
                raise SafeApplyError(f"Could not create {key[1]} {key[2]}/{key[3]}; no existing resource was overwritten.")
            time.sleep(2**attempt)
    unchanged = len(existing)
    print(f"Created {created} RabbitMQ resource(s); verified {unchanged} existing resource(s) unchanged.")
    return created, unchanged


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path)
    args = parser.parse_args()
    try:
        apply_owned_manifest(args.manifest)
    except SafeApplyError as exc:
        print(str(exc), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
