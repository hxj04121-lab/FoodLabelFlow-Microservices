#!/usr/bin/env bash
# Golden Path: create services/<name> from platform/starter/template/service.
# Usage: platform/starter/new-service.sh <service-name>   e.g. notification, label-workflow
set -euo pipefail

name="${1:-}"
if [[ ! "$name" =~ ^[a-z][a-z0-9]*(-[a-z0-9]+)*$ ]]; then
  echo "usage: $0 <service-name in kebab-case>" >&2
  exit 1
fi

root="$(cd "$(dirname "$0")/../.." && pwd)"
template="$root/platform/starter/template/service"
target="$root/services/$name"
if [[ -e "$target" ]]; then
  echo "services/$name already exists" >&2
  exit 1
fi

package="${name//-/}"                                   # label-workflow -> labelworkflow
database="${name//-/_}"                                 # label-workflow -> label_workflow
class="$(echo "$name" | awk -F- '{for (i = 1; i <= NF; i++) printf "%s", toupper(substr($i, 1, 1)) substr($i, 2)}')"
title="$(echo "$name" | awk -F- '{for (i = 1; i <= NF; i++) printf "%s%s", (i > 1 ? " " : ""), toupper(substr($i, 1, 1)) substr($i, 2)}')"

(cd "$template" && find . -type f) | while read -r file; do
  relative="${file#./}"
  relative="${relative//__PACKAGE_PATH__/com/spectrace/$package}"
  relative="${relative//__CLASS__/$class}"
  mkdir -p "$target/$(dirname "$relative")"
  sed -e "s/__SERVICE__/$name/g" -e "s/__PACKAGE__/$package/g" -e "s/__CLASS__/$class/g" \
      -e "s/__DATABASE__/$database/g" -e "s/__TITLE__/$title/g" "$template/$file" > "$target/$relative"
done

echo "Created services/$name (package com.spectrace.$package, database $database)."
echo "Next: add the module to the root pom.xml, a service block to deploy/local/compose.yaml and deploy/helm/values/$name.yaml."
