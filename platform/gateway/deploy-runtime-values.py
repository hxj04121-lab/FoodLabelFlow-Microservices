#!/usr/bin/env python3
"""Render verified GitHub Environment Gateway URIs as Helm values JSON."""
import json
import os
import sys
from urllib.parse import urlsplit


URLS = {
    "GATEWAY_ISSUER": "/auth/realms/spectrace",
    "GATEWAY_JWK_SET_URI": "/auth/realms/spectrace/protocol/openid-connect/certs",
    "GATEWAY_KEYCLOAK": "",
    "GATEWAY_SPECIFICATION": "",
    "GATEWAY_FORMULATION": "",
    "GATEWAY_COMPLIANCE": "",
    "GATEWAY_LABEL_WORKFLOW": "",
}
SERVICE_NAMESPACES = {
    "GATEWAY_KEYCLOAK": "keycloak",
    "GATEWAY_SPECIFICATION": "specification",
    "GATEWAY_FORMULATION": "formulation",
    "GATEWAY_COMPLIANCE": "compliance",
    "GATEWAY_LABEL_WORKFLOW": "label-workflow",
}


def validate_url(name, value, required_path):
    parsed = urlsplit(value)
    if (not value or parsed.scheme not in ("http", "https") or not parsed.hostname
            or parsed.username or parsed.password or parsed.query or parsed.fragment
            or parsed.path != required_path):
        raise ValueError(f"{name} must be an absolute HTTP(S) URI with path {required_path!r} and no credentials/query/fragment")
    if name == "GATEWAY_ISSUER" and parsed.scheme != "https":
        raise ValueError("GATEWAY_ISSUER must use HTTPS")
    if name in SERVICE_NAMESPACES or name == "GATEWAY_JWK_SET_URI":
        namespace = "keycloak" if name == "GATEWAY_JWK_SET_URI" else SERVICE_NAMESPACES[name]
        labels = (parsed.hostname or "").split(".")
        expected_suffix = [namespace, "svc", "cluster", "local"]
        if parsed.scheme != "http" or len(labels) != 5 or labels[1:] != expected_suffix or parsed.port != 8080:
            raise ValueError(f"{name} must use the existing in-cluster Service in {namespace} on TCP/8080")
    elif parsed.scheme == "http" and not parsed.hostname.endswith(".svc.cluster.local"):
        raise ValueError(f"{name} may use HTTP only for an in-cluster .svc.cluster.local target")


def build_values(env=os.environ):
    rows = []
    issuer = env.get("GATEWAY_ISSUER", "")
    for name, path in URLS.items():
        value = env.get(name, "").strip()
        if not value:
            raise ValueError(f"missing GitHub staging Environment variable: {name}")
        validate_url(name, value, path)
        rows.append({"name": name, "value": value})
    issuer_path = urlsplit(issuer).path
    jwks_path = urlsplit(env["GATEWAY_JWK_SET_URI"]).path
    if jwks_path != issuer_path + "/protocol/openid-connect/certs":
        raise ValueError("GATEWAY_JWK_SET_URI path must match the configured Spectrace realm issuer")
    return {"env": rows}


if __name__ == "__main__":
    try:
        json.dump(build_values(), sys.stdout, separators=(",", ":"))
        sys.stdout.write("\n")
    except (KeyError, ValueError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        raise SystemExit(2)
