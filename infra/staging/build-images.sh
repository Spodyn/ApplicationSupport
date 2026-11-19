#!/usr/bin/env bash
set -euo pipefail
: "${USI_RELEASE_SHA:?}" "${GITHUB_ACTOR:?}" "${GITHUB_TOKEN:?}"
[[ "$USI_RELEASE_SHA" =~ ^[0-9a-f]{40}$ ]]
printf '%s' "$GITHUB_TOKEN" | docker login ghcr.io -u "$GITHUB_ACTOR" --password-stdin
release=$(mktemp)
trap 'rm -f "$release"' EXIT
printf '{"sha":"%s","images":{' "$USI_RELEASE_SHA" > "$release"
separator=
for name in api web minio mc; do
  context=.
  case "$name" in
    api) file=apps/api/Dockerfile ;;
    web) file=apps/web/Dockerfile ;;
    minio) file=infra/minio/Dockerfile.server; context=infra/minio ;;
    mc) file=infra/minio/Dockerfile.mc; context=infra/minio ;;
  esac
  image="ghcr.io/spodyn/applicationsupport-$name:$USI_RELEASE_SHA"
  docker build --label "org.opencontainers.image.revision=$USI_RELEASE_SHA" \
    --label org.opencontainers.image.source=https://github.com/Spodyn/ApplicationSupport \
    -f "$file" -t "$image" "$context"
  docker push "$image"
  digest=$(docker inspect --format '{{index .RepoDigests 0}}' "$image")
  printf '%s"%s":"%s"' "$separator" "$name" "$digest" >> "$release"
  separator=,
done
printf '}}\n' >> "$release"
python3 - "$release" <<'PY'
import json,sys
sys.path.insert(0,'infra/staging')
from deployment_policy import validate_images
with open(sys.argv[1]) as source: release=json.load(source)
validate_images(release['sha'],release)
PY
cp "$release" release.json
