# Local development workflow

This document is the single day-to-day entry point for starting, stopping,
resetting, checking, and inspecting the local USI stack. The commands are
intentionally repository-root commands and work the same way from PowerShell,
Windows Terminal/WSL, macOS, and Linux as long as the prerequisites are
installed.

## Prerequisites

- Node.js 22.23.2 LTS and Corepack/pnpm 11.18.0.
- Docker Desktop on Windows/macOS or Docker Engine + Compose v2 on Linux.
- Java 25 and Maven are required once the backend bootstrap (`E02-T01`) exists.

No production credentials or production endpoints are used by these commands.

## Clean-checkout happy path

From the repository root:

```bash
corepack enable
pnpm install --frozen-lockfile
pnpm local:infra:up
pnpm local:health
pnpm local:web
```

`local:infra:up` creates `infra/.env` from the checked-in development example
only when the file is missing, validates the Compose model, starts PostgreSQL,
RabbitMQ, and MinIO, waits for their healthchecks, and idempotently creates the
development MinIO buckets. It does not create application tables; Flyway owns
application schema once the backend exists.

Before `E02-T01` is merged, `pnpm local:health` verifies the three
infrastructure services and reports the API check as skipped. After the backend
bootstrap exists, use a second terminal:

```bash
pnpm local:api
pnpm local:health
```

`local:api` creates the ignored root `.env` from `.env.example` only if needed,
loads it into the backend process, and prefers the checked-in Maven wrapper. It
fails with a clear message if the backend bootstrap has not been added yet.

## Commands

| Command | Purpose |
| --- | --- |
| `pnpm local:infra:up` | Validate Compose, start local infra, wait for health, initialize MinIO buckets. |
| `pnpm local:infra:down` | Stop/remove local containers and network while preserving named volumes. |
| `pnpm local:infra:reset` | Explicitly destroy **only local** Compose named volumes and containers. |
| `pnpm local:infra:logs` | Follow local Compose logs (`-- postgres` etc. can narrow the service). |
| `pnpm local:web` | Create `apps/web/.env.local` from its example if missing and run the frontend. |
| `pnpm local:api` | Run the Spring Boot backend through the Maven wrapper when present. |
| `pnpm local:health` | Require PostgreSQL, RabbitMQ and MinIO health; additionally require `/actuator/health` once `apps/api/pom.xml` exists. |
| `pnpm local:check` | Validate Compose configuration, run the repository quality gate, and run backend `clean verify` when the backend exists. |

The reset command is intentionally named and constrained to
`infra/compose.yaml`. Internally it requires an explicit
`--confirm-local-data-loss` flag before issuing `docker compose down --volumes`.
It has no code path for staging or production resources.

To follow one service only:

```bash
pnpm local:infra:logs -- postgres
pnpm local:infra:logs -- rabbitmq
pnpm local:infra:logs -- minio
```

## Local URLs

| Component | URL / address |
| --- | --- |
| Web | <http://localhost:3000> |
| API (after E02-T01) | <http://127.0.0.1:8080> |
| API health (after E02-T01) | <http://127.0.0.1:8080/actuator/health> |
| PostgreSQL | `127.0.0.1:5432` |
| RabbitMQ AMQP | `127.0.0.1:5672` |
| RabbitMQ management | <http://127.0.0.1:15672> |
| MinIO S3 API | <http://127.0.0.1:9000> |
| MinIO console | <http://127.0.0.1:9001> |

Ports can be overridden in the ignored `infra/.env`. The API health URL can be
overridden for local development with `USI_API_HEALTH_URL`.

## Full local verification

For a deterministic repository check that does not delete local data:

```bash
pnpm local:check
```

The command validates the Compose configuration and invokes the normal
repository `pnpm check` gate. Once a Maven backend exists it also runs
`clean verify`. Runtime service availability is intentionally a separate
`pnpm local:health` command, so CI/static checks do not depend on an already
running developer stack.

The developer-command contract itself is covered by Node's built-in test runner
(`pnpm test:dev-tools`) and is part of the normal `pnpm check` gate.
