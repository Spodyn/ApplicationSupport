#!/usr/bin/env bash
set -euo pipefail

usi_test_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
usi_infra_dir="$(cd "${usi_test_dir}/.." && pwd)"
usi_compose_file="${usi_infra_dir}/compose.yaml"
usi_env_file="${usi_infra_dir}/.env.example"
usi_project_name="usi-42-lifecycle-$$"

if ! command -v docker >/dev/null 2>&1; then
  echo "Docker CLI is required for the Compose lifecycle test." >&2
  exit 127
fi
if ! docker compose version >/dev/null 2>&1; then
  echo "Docker Compose v2 is required for the lifecycle test." >&2
  exit 127
fi

# Select currently available loopback ports. The test interacts through
# docker compose exec; these bindings only prove the host-side security shape.
read -r \
  POSTGRES_PORT \
  RABBITMQ_AMQP_PORT \
  RABBITMQ_MANAGEMENT_PORT \
  MINIO_API_PORT \
  MINIO_CONSOLE_PORT < <(
  python3 - <<'PY'
import socket

sockets = []
for _ in range(5):
    listener = socket.socket()
    listener.bind(("127.0.0.1", 0))
    sockets.append(listener)
print(*(listener.getsockname()[1] for listener in sockets))
PY
)

export POSTGRES_DB=application_support_dev
export POSTGRES_USER=application_support_dev
export POSTGRES_PASSWORD=postgres_dev_only_change_me
export POSTGRES_PORT
export RABBITMQ_DEFAULT_USER=application_support_dev
export RABBITMQ_DEFAULT_PASS=rabbitmq_dev_only_change_me
export RABBITMQ_DEFAULT_VHOST=application_support_dev
export RABBITMQ_AMQP_PORT
export RABBITMQ_MANAGEMENT_PORT
export MINIO_ROOT_USER=usi-dev-root
export MINIO_ROOT_PASSWORD=minio_dev_only_change_me
export MINIO_ATTACHMENTS_BUCKET=usi-attachments-dev
export MINIO_EXPORTS_BUCKET=usi-exports-dev
export MINIO_API_PORT
export MINIO_CONSOLE_PORT

usi_compose=(
  docker compose
  --project-name "${usi_project_name}"
  --env-file "${usi_env_file}"
  --file "${usi_compose_file}"
)

usi_cleanup() {
  "${usi_compose[@]}" down --volumes --remove-orphans >/dev/null 2>&1 || true
}

usi_start() {
  "${usi_compose[@]}" up \
    --detach \
    --wait \
    --wait-timeout 180 \
    postgres rabbitmq minio
  "${usi_compose[@]}" run --rm --no-deps -T minio-init
}

usi_postgres_system_id() {
  "${usi_compose[@]}" exec -T postgres sh -ec \
    'psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --tuples-only --no-align --command "SELECT system_identifier FROM pg_control_system();"'
}

usi_rabbitmq_cookie_hash() {
  "${usi_compose[@]}" exec -T rabbitmq sh -ec \
    'sha256sum /var/lib/rabbitmq/.erlang.cookie | cut -d " " -f 1'
}

usi_create_probe_object() {
  printf '%s\n' "volume persistence probe" | \
    "${usi_compose[@]}" run --rm --no-deps -T minio-init \
      '/usr/bin/mc alias set local http://minio:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null && /usr/bin/mc pipe "local/$MINIO_ATTACHMENTS_BUCKET/usi-volume-persistence-probe" >/dev/null'
}

usi_probe_object_exists() {
  "${usi_compose[@]}" run --rm --no-deps -T minio-init \
    '/usr/bin/mc alias set local http://minio:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null && /usr/bin/mc stat "local/$MINIO_ATTACHMENTS_BUCKET/usi-volume-persistence-probe" >/dev/null'
}

trap usi_cleanup EXIT INT TERM
usi_cleanup

"${usi_compose[@]}" config --quiet

usi_start

for usi_port_check in \
  "postgres 5432" \
  "rabbitmq 5672" \
  "rabbitmq 15672" \
  "minio 9000" \
  "minio 9001"; do
  read -r usi_service usi_container_port <<<"${usi_port_check}"
  usi_binding="$(
    "${usi_compose[@]}" port "${usi_service}" "${usi_container_port}"
  )"
  [[ "${usi_binding}" == 127.0.0.1:* ]]
done

usi_private_network_id="$(
  docker network ls \
    --quiet \
    --filter "label=com.docker.compose.project=${usi_project_name}" \
    --filter "label=com.docker.compose.network=private"
)"
[[ -n "${usi_private_network_id}" ]]
[[ "$(
  docker network inspect --format '{{.Driver}}' "${usi_private_network_id}"
)" == "bridge" ]]

usi_postgres_version="$(
  "${usi_compose[@]}" exec -T postgres \
    psql --username application_support_dev \
      --dbname application_support_dev \
      --tuples-only --no-align \
      --command 'SHOW server_version;'
)"
[[ "${usi_postgres_version}" == 18.* ]]

usi_rabbitmq_version="$(
  "${usi_compose[@]}" exec -T rabbitmq rabbitmqctl version
)"
[[ "${usi_rabbitmq_version}" == 4.3.* ]]

usi_rabbitmq_queues="$(
  "${usi_compose[@]}" exec -T rabbitmq \
    rabbitmqctl --silent list_queues -p application_support_dev name
)"
[[ -z "${usi_rabbitmq_queues}" ]]

usi_minio_version="$(
  "${usi_compose[@]}" exec -T minio minio --version
)"
[[ "${usi_minio_version}" == *"RELEASE.2025-09-07T16-13-09Z"* ]]

usi_user_table_count="$(
  "${usi_compose[@]}" exec -T postgres \
    psql --username application_support_dev \
      --dbname application_support_dev \
      --tuples-only --no-align \
      --command "SELECT count(*) FROM pg_catalog.pg_tables WHERE schemaname NOT IN ('pg_catalog', 'information_schema');"
)"
[[ "${usi_user_table_count}" == "0" ]]

usi_postgres_id_before="$(usi_postgres_system_id)"
usi_rabbitmq_cookie_before="$(usi_rabbitmq_cookie_hash)"
usi_create_probe_object
usi_probe_object_exists

# Recreate every container while deliberately preserving named volumes.
"${usi_compose[@]}" down --remove-orphans
usi_start

[[ "$(usi_postgres_system_id)" == "${usi_postgres_id_before}" ]]
[[ "$(usi_rabbitmq_cookie_hash)" == "${usi_rabbitmq_cookie_before}" ]]
usi_probe_object_exists

# The explicit reset must remove every project volume and all persisted state.
"${usi_compose[@]}" down --volumes --remove-orphans
if docker volume ls \
  --quiet \
  --filter "label=com.docker.compose.project=${usi_project_name}" | grep -q .; then
  echo "Compose reset left a project volume behind" >&2
  exit 1
fi

usi_start

[[ "$(usi_postgres_system_id)" != "${usi_postgres_id_before}" ]]
[[ "$(usi_rabbitmq_cookie_hash)" != "${usi_rabbitmq_cookie_before}" ]]
if usi_probe_object_exists >/dev/null 2>&1; then
  echo "MinIO probe object survived docker compose down --volumes" >&2
  exit 1
fi

echo "Compose lifecycle, health, persistence, and reset checks passed."
