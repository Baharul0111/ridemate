#!/usr/bin/env bash
# Prints the current digest of a Docker Hub image (default python:3.12-slim) from the registry API, without docker,
# for the monthly base-image refresh (architecture §4.18 C4): paste the printed reference into BASE_IMAGE in
# backend/modal_app.py, run the tests, redeploy.
#
#   backend/scripts/base_image_digest.sh [TAG]       # → python:3.12-slim@sha256:…
#
# It uses a HEAD request for the manifest, which Docker Hub does not count against the anonymous pull limit.
set -euo pipefail

REPO="library/python"
TAG="${1:-3.12-slim}"
[[ "$TAG" =~ ^[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}$ ]] || { echo "error: invalid tag" >&2; exit 64; }

token="$(curl -fsS "https://auth.docker.io/token?service=registry.docker.io&scope=repository:${REPO}:pull" |
  python3 -c 'import json, sys; print(json.load(sys.stdin)["token"])')"

digest="$(curl -fsSI \
  -H "Authorization: Bearer ${token}" \
  -H "Accept: application/vnd.oci.image.index.v1+json" \
  -H "Accept: application/vnd.docker.distribution.manifest.list.v2+json" \
  "https://registry-1.docker.io/v2/${REPO}/manifests/${TAG}" |
  awk -F': ' 'tolower($1) == "docker-content-digest" { gsub(/\r/, "", $2); print $2 }')"

[[ "$digest" =~ ^sha256:[0-9a-f]{64}$ ]] || { echo "error: no digest in the registry's answer" >&2; exit 1; }
echo "python:${TAG}@${digest}"
