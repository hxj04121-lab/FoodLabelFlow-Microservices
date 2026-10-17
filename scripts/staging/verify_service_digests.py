#!/usr/bin/env python3
"""Verify the four committed GHCR digests without registry credentials."""

import json
import re
import urllib.parse
import urllib.request
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SERVICES = ("specification", "formulation", "compliance", "label-workflow")
REGISTRY = "ghcr.io/hxj04121-lab/foodlabelflow-microservices"


def pinned_images():
    images = {}
    for service in SERVICES:
        source = (ROOT / "deploy/helm/values" / f"{service}.yaml").read_text()
        repository = re.search(r"^  repository: (\S+)$", source, re.MULTILINE)
        digest = re.search(r"^  digest: (sha256:[a-f0-9]{64})$", source, re.MULTILINE)
        if not repository or repository.group(1) != f"{REGISTRY}/{service}" or not digest:
            raise ValueError(f"{service} must have its exact GHCR repository and immutable SHA-256 digest")
        images[service] = {"repository": repository.group(1), "digest": digest.group(1)}
    return images


def verify_image(repository, digest):
    package = repository.removeprefix("ghcr.io/")
    query = urllib.parse.urlencode({"scope": f"repository:{package}:pull", "service": "ghcr.io"})
    with urllib.request.urlopen(f"https://ghcr.io/token?{query}", timeout=20) as response:
        token = json.load(response)["token"]
    headers = {
        "Authorization": f"Bearer {token}",
        "Accept": ", ".join((
            "application/vnd.oci.image.manifest.v1+json",
            "application/vnd.docker.distribution.manifest.v2+json",
        )),
    }
    request = urllib.request.Request(f"https://ghcr.io/v2/{package}/manifests/{digest}", headers=headers)
    with urllib.request.urlopen(request, timeout=20) as response:
        manifest = json.load(response)
        if response.headers.get("Docker-Content-Digest") != digest:
            raise ValueError(f"registry digest mismatch for {repository}")
        if manifest.get("mediaType") not in {
            "application/vnd.oci.image.manifest.v1+json",
            "application/vnd.docker.distribution.manifest.v2+json",
        }:
            raise ValueError(f"{repository} does not resolve to a single-platform manifest")
    blob = urllib.request.Request(
        f"https://ghcr.io/v2/{package}/blobs/{manifest['config']['digest']}",
        headers={"Authorization": f"Bearer {token}"},
    )
    with urllib.request.urlopen(blob, timeout=20) as response:
        config = json.load(response)
    if (config.get("os"), config.get("architecture")) != ("linux", "amd64"):
        raise ValueError(f"{repository} is not a linux/amd64 image")


def main():
    images = pinned_images()
    for item in images.values():
        verify_image(item["repository"], item["digest"])
    print(json.dumps(images, sort_keys=True))


if __name__ == "__main__":
    main()
