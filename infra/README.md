# USI local infrastructure

This directory contains the development-only Docker Compose stack for USI. It
starts PostgreSQL 18, RabbitMQ 4.3 with the management plugin, and MinIO
S3-compatible storage. It does not start the frontend or backend, run Flyway,
or create application tables, queues, or exchanges.

The stack is deliberately local-only:

- every host port is bound to `127.0.0.1`;
- all containers share one dedicated project-scoped Docker bridge;
- credentials in `.env.example` are obvious disposable development values;
- real local overrides live in the ignored `infra/.env` file;
- named volumes preserve local data until an explicit volume reset.

The pinned `minio/minio` image is the last upstream-published community
container and is suitable only for this loopback development stack. It must not
be promoted to staging or production. Production image selection and runtime
secret injection are outside USI-42.

## Prerequisites

- Docker Engine or Docker Desktop
- Docker Compose v2 with `docker compose up --wait` support

## First start

From the repository root:

```bash
cp infra/.env.example infra/.env
docker compose --env-file infra/.env -f infra/compose.yaml config --quiet
docker compose --env-file infra/.env -f infra/compose.yaml up --detach --wait --wait-timeout 180 postgres rabbitmq minio
docker compose --env-file infra/.env -f infra/compose.yaml run --rm minio-init
docker compose --env-file infra/.env -f infra/compose.yaml ps --all
```

The three long-running services should report `healthy`; `minio-init` exits
successfully after idempotently ensuring both development buckets exist.

## Local endpoints

| Service | Host endpoint | Container endpoint | Default identity |
| --- | --- | --- | --- |
| PostgreSQL | `127.0.0.1:5432` | `postgres:5432` | DB/user `application_support_dev` |
| RabbitMQ AMQP | `127.0.0.1:5672` | `rabbitmq:5672` | user/vhost `application_support_dev` |
| RabbitMQ management | <http://127.0.0.1:15672> | `rabbitmq:15672` | same RabbitMQ user |
| MinIO S3 API | <http://127.0.0.1:9000> | `minio:9000` | root user from `infra/.env` |
| MinIO console | <http://127.0.0.1:9001> | `minio:9001` | root user from `infra/.env` |

Default private MinIO buckets are `usi-attachments-dev` and
`usi-exports-dev`. Override ports, credentials, or bucket names only in
`infra/.env`; do not commit that file.

## Day-to-day commands

The examples below assume commands are run from the repository root and use the
ignored `infra/.env` created above.

```bash
# Start existing services and wait for their healthchecks.
docker compose --env-file infra/.env -f infra/compose.yaml up --detach --wait --wait-timeout 180 postgres rabbitmq minio

# Re-run the idempotent bucket initialization after a clean start.
docker compose --env-file infra/.env -f infra/compose.yaml run --rm minio-init

# Inspect health and logs.
docker compose --env-file infra/.env -f infra/compose.yaml ps --all
docker compose --env-file infra/.env -f infra/compose.yaml logs --follow

# Restart containers without deleting named volumes.
docker compose --env-file infra/.env -f infra/compose.yaml restart

# Stop/remove containers and the private network, preserving data volumes.
docker compose --env-file infra/.env -f infra/compose.yaml down --remove-orphans
```

`down` and `restart` preserve the PostgreSQL, RabbitMQ, and MinIO named
volumes. To intentionally erase all local infrastructure data and return to a
clean state, use the explicit destructive reset:

```bash
docker compose --env-file infra/.env -f infra/compose.yaml down --volumes --remove-orphans
```

The next start creates an empty `application_support_dev` database. Application
schema must be created only by future Flyway migrations; do not add SQL init
scripts under this Compose stack. RabbitMQ queues and exchanges are likewise
owned by the future application runtime.

## Verification

CI validates the normalized Compose model and runs a clean lifecycle test. To
run the same lifecycle test locally:

```bash
infra/tests/verify-compose-lifecycle.sh
```

The test uses a unique Compose project, dynamically selected loopback ports,
and disposable development credentials. It verifies healthchecks, exact major
versions, MinIO bucket initialization, persistence across container recreation,
and the explicit `down --volumes` reset. It never creates an application table.
