#!/usr/bin/env bash
set -euo pipefail
umask 077
release=$(cd -- "$(dirname -- "$0")/../.." && pwd)
# N-1 receivers already extract only verified /releases/<SHA> archives.
USI_EXPECTED_REVISION=${USI_EXPECTED_REVISION:-$(basename -- "$release")}
[[ "$USI_EXPECTED_REVISION" =~ ^[0-9a-f]{40}$ ]] || { echo 'Exact verified release revision required.' >&2; exit 1; }
root=${USI_STAGING_ROOT:-/srv/usi-staging}
state="$root/state"
export COMPOSE_PROJECT_NAME=${COMPOSE_PROJECT_NAME:-usi-staging}
compose=(docker compose --env-file "$state/runtime.env" --env-file "$release/release.env" -f "$release/infra/staging/compose.yaml")
"${compose[@]}" config --quiet
# The receiver already pulled/verified immutable images and serialized deployments.
"${compose[@]}" up -d --wait --wait-timeout 600 postgres rabbitmq minio clamav
table=$("${compose[@]}" exec -T postgres psql -U usi_staging -d usi_staging -Atc "SELECT to_regclass('public.bootstrap_admin_state') IS NOT NULL")
consumed=f
if [ "$table" = t ]; then
  consumed=$("${compose[@]}" exec -T postgres psql -U usi_staging -d usi_staging -Atc 'SELECT consumed FROM bootstrap_admin_state WHERE id=1')
  # Database-only checkpoint is encrypted; runtime/provider secrets never enter it.
  mkdir -p "$state/backups"
  recipient=$(age-keygen -y "$state/recovery/backup.agekey")
  "${compose[@]}" exec -T postgres pg_dump -U usi_staging -d usi_staging -Fc \
    | age -r "$recipient" -o "$state/backups/$(date -u +%Y%m%dT%H%M%SZ).dump.age"
fi
[[ "$consumed" = t || "$consumed" = f ]] || { echo 'Invalid bootstrap checkpoint; deployment refused.' >&2; exit 1; }
export STAGING_BOOTSTRAP_ENABLED=false
if [ "$consumed" = f ]; then export STAGING_BOOTSTRAP_ENABLED=true; fi
"${compose[@]}" up -d --wait --wait-timeout 600
# A restart after consumption must never repeat the one-time bootstrap trigger.
export STAGING_BOOTSTRAP_ENABLED=false
"${compose[@]}" up -d --wait --wait-timeout 180 api
host=$("${compose[@]}" config --format json | python3 -c 'import json,sys,urllib.parse;print(urllib.parse.urlsplit(json.load(sys.stdin)["services"]["api"]["environment"]["USI_PUBLIC_BASE_URL"]).netloc)')
ready=false
for attempt in {1..60}; do
  if curl --fail --silent --output /dev/null "https://$host/login"; then ready=true; break; fi
  sleep 2
done
[ "$ready" = true ] || { echo 'HTTPS readiness failed; prior images retained; no automatic DB rollback.' >&2; exit 1; }
for service in api web; do
  container=$("${compose[@]}" ps -q "$service")
  revision=$(docker inspect --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' "$container")
  [ "$revision" = "$USI_EXPECTED_REVISION" ] || { echo "Running $service revision differs from the verified release." >&2; exit 1; }
done
python3 "$release/infra/staging/smoke.py" --origin "https://$host" --public-only
# Full authenticated smoke uses bootstrap only once. Later releases
# must not depend on retaining/reusing a user's initial bootstrap password.
authenticated_smoke=false
if [ "$consumed" = f ]; then
  email=$("${compose[@]}" config --format json | python3 -c 'import json,sys;print(json.load(sys.stdin)["services"]["api"]["environment"]["USI_BOOTSTRAP_ADMIN_EMAIL"])')
  python3 "$release/infra/staging/smoke.py" --origin "https://$host" --email "$email" --password-file "$state/core/bootstrap-admin-password"
  authenticated_smoke=true
elif [ -f "$state/smoke/email" ] && [ -f "$state/smoke/password" ]; then
  email=$(cat "$state/smoke/email")
  python3 "$release/infra/staging/smoke.py" --origin "https://$host" --email "$email" --password-file "$state/smoke/password"
  authenticated_smoke=true
elif [ -e "$state/smoke/email" ] || [ -e "$state/smoke/password" ]; then
  echo 'Incomplete dedicated smoke credentials; release not recorded.' >&2
  exit 1
else
  echo 'Authenticated release smoke NOT RUN: dedicated staging smoke credentials are not provisioned. Public smoke and exact running revision verified.'
fi
python3 - "$state/release-verification.json" "$USI_EXPECTED_REVISION" "$authenticated_smoke" <<'PY'
import datetime, json, pathlib, sys
pathlib.Path(sys.argv[1]).write_text(json.dumps({"revision": sys.argv[2], "publicSmoke": "PASS", "authenticatedSmoke": "PASS" if sys.argv[3] == "true" else "NOT RUN", "observedAt": datetime.datetime.now(datetime.timezone.utc).isoformat()}) + "\n", encoding="utf-8")
PY
if [ -f "$root/current-release" ]; then cp "$root/current-release" "$root/previous-release"; fi
printf '%s\n' "$release" > "$root/current-release"
echo 'Staging readiness passed. Release recorded; volumes preserved. No webhook was changed.'
