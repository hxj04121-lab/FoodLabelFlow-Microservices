#!/usr/bin/env python3
"""Print `helm list --all -o json`-style rows from Helm's storage Secret labels (works with Helm 3 and 4).

Reads `kubectl get secrets -l owner=helm -o json` on stdin; only labels are used, never Secret data.
"""

import json
import sys


def releases(secret_list):
    latest = {}
    for item in secret_list.get("items", []):
        labels = item.get("metadata", {}).get("labels", {})
        name, status, version = labels.get("name"), labels.get("status"), labels.get("version", "0")
        if labels.get("owner") != "helm" or not name or not status or not str(version).isdigit():
            continue
        if name not in latest or int(version) > latest[name][0]:
            latest[name] = (int(version), status)
    return [{"name": name, "status": status, "revision": str(version)} for name, (version, status) in sorted(latest.items())]


if __name__ == "__main__":
    print(json.dumps(releases(json.load(sys.stdin))))
