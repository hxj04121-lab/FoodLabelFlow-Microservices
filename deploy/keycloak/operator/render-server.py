#!/usr/bin/env python3
"""Render the staging Keycloak CR without embedding database credentials."""
import json
import os
import re
import sys
from urllib.parse import urlsplit, urlunsplit


def render(env=os.environ):
    mysql_host = env.get("MYSQL_HOST", "").strip()
    issuer = env.get("GATEWAY_ISSUER", "").strip()
    if not re.fullmatch(r"[A-Za-z0-9.-]+\.ap-southeast-1\.rds\.amazonaws\.com", mysql_host):
        raise ValueError("MYSQL_HOST must be the actual ap-southeast-1 RDS endpoint")
    parsed = urlsplit(issuer)
    if (parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password
            or parsed.path != "/auth/realms/spectrace" or parsed.query or parsed.fragment):
        raise ValueError("GATEWAY_ISSUER must be the approved HTTPS /auth/realms/spectrace URL")
    hostname = urlunsplit(("https", parsed.netloc, "/auth", "", ""))
    return {
        "apiVersion": "k8s.keycloak.org/v2alpha1",
        "kind": "Keycloak",
        "metadata": {
            "name": "spectrace-keycloak",
            "namespace": "keycloak",
            "labels": {"app": "keycloak"},
        },
        "spec": {
            "instances": 1,
            "db": {
                "vendor": "mysql",
                "host": mysql_host,
                "database": "keycloak",
                "usernameSecret": {"name": "keycloak-db", "key": "username"},
                "passwordSecret": {"name": "keycloak-db", "key": "password"},
            },
            "http": {"httpEnabled": True, "httpPort": 8080},
            "hostname": {"hostname": hostname},
            "proxy": {"headers": "xforwarded"},
            "ingress": {"enabled": False},
            "additionalOptions": [
                {"name": "http-relative-path", "value": "/auth"},
            ],
        },
    }


if __name__ == "__main__":
    try:
        json.dump(render(), sys.stdout, separators=(",", ":"))
        sys.stdout.write("\n")
    except ValueError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        raise SystemExit(2)
