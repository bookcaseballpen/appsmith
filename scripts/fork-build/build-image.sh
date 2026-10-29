#!/usr/bin/env bash
# Fork: builds the image from source with Docker only; no Appsmith image or host is used. See FORK.md.
#
# Usage: scripts/fork-build/build-image.sh [image-tag]
#   image-tag  default creative-app-builder:local
# Environment:
#   BASE_IMAGE  tag for the locally built runtime base (default creative-app-builder-base:local)
#   VERSION     version written to info.json (default v0.0.0-local)

set -o errexit
set -o nounset
set -o pipefail

image="${1:-creative-app-builder:local}"
base_image="${BASE_IMAGE:-creative-app-builder-base:local}"
version="${VERSION:-v0.0.0-local}"

root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$root"

commit_sha="$(git rev-parse HEAD 2>/dev/null || echo unknown)"
built_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

echo "== Building runtime base $base_image (deploy/docker/base.dockerfile)"
# The base copies nothing from a context, so the Dockerfile goes in on stdin with no context. Carriage returns are
# stripped: in a checkout made before .gitattributes forced LF, its heredocs would fail on CRLF.
tr -d '\r' < deploy/docker/base.dockerfile | docker build --tag "$base_image" -

echo "== Building $image (deploy/fork/Dockerfile)"
docker build \
  --file deploy/fork/Dockerfile \
  --build-arg "BASE=$base_image" \
  --build-arg "COMMIT_SHA=$commit_sha" \
  --build-arg "VERSION=$version" \
  --build-arg "IMAGE_BUILT_AT=$built_at" \
  --tag "$image" \
  .

echo "== Built $image"
