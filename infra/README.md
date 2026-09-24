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

## MinIO source-build provenance

MinIO Community is no longer consumed from a prebuilt community registry image.
The development/test images are built locally from immutable official upstream
Git tags and verified commit IDs:

| Image | Upstream tag | Verified source commit |
| --- | --- | --- |
| `usi/minio:RELEASE.2025-09-07T16-13-09Z` | `minio/minio` `RELEASE.2025-09-07T16-13-09Z` | `07c3a429bfed433e49018cb0f78a52145d4bedeb` |
| `usi/minio-mc:RELEASE.2025-08-13T08-35-41Z` | `minio/mc` `RELEASE.2025-08-13T08-35-41Z` | `7394ce0dd2a80935aded936b09fa12cbb3cb8096` |

`infra/minio/Dockerfile.server` and `infra/minio/Dockerfile.mc` fetch the named
official tag, verify that its checked-out Git commit exactly matches the reviewed
commit above, build with the original release metadata, verify the resulting
binary version, and copy the upstream AGPL license into the runtime image. The
builder is pinned to Go 1.24.6 on Alpine 3.22 and the runtime to Alpine 3.22.
Compose uses `pull_policy: never` for these local image names, so it cannot
silently fall back to a third-party or withdrawn prebuilt MinIO image.

These source-built images are suitable only for this loopback development/test
stack. They must not be promoted to staging or production. The runtime
secret/config-tree contract is documented in [`../config/README.md`](../config/README.md);
production image selection and deployment remain outside this local Compose stack.

## Prerequisites

- Docker Engine or Docker Desktop
- Docker Compose v2 with `docker compose up --wait` support
- outbound HTTPS access to the official GitHub repositories and public Go module
  dependencies when the MinIO build cache is empty

## Build MinIO images

From the repository root:

```bash
# Build both source-pinned local images.
bash infra/minio/build-source-images.sh all

# Or build only the server image needed by backend Testcontainers.
bash infra/minio/build-source-images.sh server
```

Docker layer caching avoids recompiling unchanged sources on normal repeated
local runs. CI intentionally supports a clean runner with no cached vendor image
or registry credentials.

## First start

From the repository root:

```bash
cp infra/.env.example infra/.env
docker compose --env-file infra/.env -f infra/compose.yaml config --quiet
docker compose --env-file infra/.env -f infra/compose.yaml build minio minio-init
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
# Rebuild MinIO after changing its Dockerfiles or source pins.
docker compose --env-file infra/.env -f infra/compose.yaml build minio minio-init

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
schema must be created only by Flyway migrations; do not add SQL init scripts
under this Compose stack. RabbitMQ queues and exchanges are likewise owned by
the application runtime.

## Backend integration tests

The shared Testcontainers MinIO definition references the locally built server
image. On a clean machine, prepare it before running the integration group:

```bash
bash infra/minio/build-source-images.sh server
cd apps/api
./mvnw --batch-mode test -Dgroups=integration
```

CI performs the same preparation explicitly before the integration suite.

## Verification

CI validates the normalized Compose model and runs a clean lifecycle test. To
run the same lifecycle test locally:

```bash
infra/tests/verify-compose-lifecycle.sh
```

The lifecycle script first builds the source-pinned MinIO and `mc` images, then
uses a unique Compose project, dynamically selected loopback ports, and disposable
development credentials. It verifies healthchecks, exact major/release versions,
MinIO bucket initialization, persistence across container recreation, and the
explicit `down --volumes` reset. It never creates an application table.
