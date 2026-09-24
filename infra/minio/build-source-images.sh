#!/usr/bin/env bash
set -euo pipefail

usi_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
mode="${1:-all}"

MINIO_TAG="RELEASE.2025-09-07T16-13-09Z"
MINIO_COMMIT="07c3a429bfed433e49018cb0f78a52145d4bedeb"
MC_TAG="RELEASE.2025-08-13T08-35-41Z"
MC_COMMIT="7394ce0dd2a80935aded936b09fa12cbb3cb8096"

build_server() {
  docker build \
    --file "${usi_dir}/Dockerfile.server" \
    --build-arg "MINIO_TAG=${MINIO_TAG}" \
    --build-arg "MINIO_COMMIT=${MINIO_COMMIT}" \
    --tag "usi/minio:${MINIO_TAG}" \
    "${usi_dir}"
}

build_mc() {
  docker build \
    --file "${usi_dir}/Dockerfile.mc" \
    --build-arg "MC_TAG=${MC_TAG}" \
    --build-arg "MC_COMMIT=${MC_COMMIT}" \
    --tag "usi/minio-mc:${MC_TAG}" \
    "${usi_dir}"
}

case "${mode}" in
  all)
    build_server
    build_mc
    ;;
  server)
    build_server
    ;;
  mc)
    build_mc
    ;;
  *)
    echo "Usage: $0 [all|server|mc]" >&2
    exit 2
    ;;
esac
