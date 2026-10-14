#!/usr/bin/env python3
"""Record a successful main-branch staging deployment after rollout and smoke checks."""

import argparse
import json
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path
from urllib.parse import urlsplit


REPOSITORY = "hxj04121-lab/FoodLabelFlow-Microservices"
SERVICES = {"gateway", "specification", "formulation", "compliance", "label-workflow"}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def validate_identity(env):
    require(env.get("GITHUB_ACTIONS") == "true", "must run inside GitHub Actions")
    require(env.get("GITHUB_REPOSITORY", "").lower() == REPOSITORY.lower(), "unexpected GitHub repository")
    require(env.get("GITHUB_REF") == "refs/heads/main", "deployment evidence is restricted to main")
    require(env.get("GITHUB_EVENT_NAME") == "push", "deployment evidence requires a main push workflow")
    commit = env.get("GITHUB_SHA", "")
    require(re.fullmatch(r"[a-f0-9]{40}", commit) is not None, "GITHUB_SHA must be a full commit SHA")
    run_id = env.get("GITHUB_RUN_ID", "")
    require(run_id.isdigit() and int(run_id) > 0, "GITHUB_RUN_ID must be a positive integer")
    server = urlsplit(env.get("GITHUB_SERVER_URL", ""))
    require(server.scheme == "https" and server.netloc and not server.query and not server.fragment,
            "GITHUB_SERVER_URL must be an HTTPS origin")
    return commit, int(run_id), env["GITHUB_SERVER_URL"].rstrip("/")


def validate_deployment(deployment, service, image):
    # The G1 reusable workflow names each Helm release after its service.
    deployment_name = f"{service}-{service}"
    metadata = deployment.get("metadata", {})
    require(metadata.get("name") == deployment_name, "Deployment name does not match the service Helm release")
    require(metadata.get("namespace") == service, "Deployment namespace does not match service")
    labels = metadata.get("labels", {})
    require(labels.get("app.kubernetes.io/name") == service, "Deployment service label does not match")

    revision_text = metadata.get("annotations", {}).get("deployment.kubernetes.io/revision", "")
    require(revision_text.isdigit() and int(revision_text) > 0, "Deployment revision is missing or invalid")

    spec = deployment.get("spec", {})
    desired = spec.get("replicas", 1)
    require(isinstance(desired, int) and desired > 0, "Deployment must request at least one replica")
    containers = spec.get("template", {}).get("spec", {}).get("containers", [])
    require(sum(container.get("image") == image for container in containers) == 1,
            "Deployment does not contain exactly one requested digest-pinned image")

    status = deployment.get("status", {})
    generation = metadata.get("generation", 0)
    require(status.get("observedGeneration", 0) >= generation, "Deployment controller has not observed the current generation")
    for field in ("updatedReplicas", "readyReplicas", "availableReplicas"):
        require(status.get(field, 0) == desired, f"Deployment {field} does not match desired replicas")
    return int(revision_text)


def kubectl(args):
    result = subprocess.run(["kubectl", *args], capture_output=True, text=True, check=False)
    if result.returncode != 0:
        raise RuntimeError(f"kubectl {args[0]} failed with exit code {result.returncode}")
    return result.stdout


def write_record(output_path, runner_temp, record):
    temp_root = Path(runner_temp).resolve(strict=True)
    output = Path(output_path).resolve()
    require(output.is_relative_to(temp_root), "record output must stay inside RUNNER_TEMP")
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=output.parent, delete=False) as stream:
        json.dump(record, stream, indent=2, sort_keys=True)
        stream.write("\n")
        temporary = Path(stream.name)
    temporary.replace(output)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--service", required=True, choices=sorted(SERVICES))
    parser.add_argument("--image", required=True, help="Published GHCR repository@sha256 digest from the publish job")
    parser.add_argument("--output", required=True, help="JSON record path under RUNNER_TEMP")
    args = parser.parse_args(argv)

    try:
        env = os.environ
        commit, run_id, server_url = validate_identity(env)
        expected_prefix = f"ghcr.io/{REPOSITORY.lower()}/{args.service}@sha256:"
        require(args.image.startswith(expected_prefix) and
                re.fullmatch(r"[a-f0-9]{64}", args.image.removeprefix(expected_prefix)) is not None,
                "image must be this service's GHCR image pinned by a SHA-256 digest")

        deployment_name = f"{args.service}-{args.service}"
        kubectl(["rollout", "status", f"deployment/{deployment_name}", "--namespace", args.service, "--timeout=180s"])
        raw = kubectl(["get", "deployment", deployment_name, "--namespace", args.service, "--output=json"])
        deployment = json.loads(raw)
        revision = validate_deployment(deployment, args.service, args.image)

        record = {
            "service": args.service,
            "namespace": args.service,
            "commit_sha": commit,
            "workflow_run_id": run_id,
            "workflow_run_url": f"{server_url}/{REPOSITORY}/actions/runs/{run_id}",
            "conclusion": "success",
            "image": args.image,
            "deployment_revision": revision,
        }
        write_record(args.output, env.get("RUNNER_TEMP", ""), record)
        print(f"PASS: recorded {args.service} revision {revision} at {args.image}")
        return 0
    except (OSError, RuntimeError, ValueError, json.JSONDecodeError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
