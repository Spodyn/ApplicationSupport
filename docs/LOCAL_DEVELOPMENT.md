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
| Browser API path | <http://localhost:3000/api/v1> |
| Browser WebSocket path | `ws://localhost:3000/ws` |
| API process (after E02-T01) | <http://127.0.0.1:8080> |
| API health (after E02-T01) | <http://127.0.0.1:8080/actuator/health> |
| PostgreSQL | `127.0.0.1:5432` |
| RabbitMQ AMQP | `127.0.0.1:5672` |
| RabbitMQ management | <http://127.0.0.1:15672> |
| MinIO S3 API | <http://127.0.0.1:9000> |
| MinIO console | <http://127.0.0.1:9001> |

Ports can be overridden in the ignored `infra/.env`. The API health URL can be
overridden for local development with `USI_API_HEALTH_URL`.

## Same-origin API and WebSocket routing

The browser always uses the reviewed relative paths `NEXT_PUBLIC_API_BASE_URL=/api/v1`
and `NEXT_PUBLIC_WS_BASE_URL=/ws`. It never receives the backend process origin.
This keeps `USI_SESSION` cookies, future CSRF handling and WebSocket handshakes on
the same browser origin instead of solving local development with permissive
CORS.

During `next dev`, Next.js proxies both `/api/:path*` and `/ws/:path*` to the
local Spring Boot process. The default target is `http://127.0.0.1:8080`. A
developer who intentionally runs the API on another local port may set the
server-only process variable before starting the web app:

```bash
USI_DEV_BACKEND_ORIGIN=http://127.0.0.1:9080 pnpm local:web
```

PowerShell equivalent:

```powershell
$env:USI_DEV_BACKEND_ORIGIN = "http://127.0.0.1:9080"
pnpm local:web
```

`USI_DEV_BACKEND_ORIGIN` is local Next.js tooling, not browser-public
configuration and not a production deployment variable. It accepts only a
loopback HTTP origin (`127.0.0.1` or `localhost`) without credentials, path,
query or fragment. Remote targets fail Next configuration instead of turning the
web process into an arbitrary forwarding proxy. Production `/api` and `/ws`
routing remains owned by Caddy as defined in `docs/ARCHITECTURE.md`.

The Next `proxy.ts` guard runs only for `/api/*` and `/ws/*`. A browser `Origin`
header must match the incoming web origin exactly. Cross-origin or malformed
Origins receive `403` with no `Access-Control-Allow-Origin` header, including
cross-origin `OPTIONS` preflights and WebSocket requests. Requests with no
`Origin` header remain possible for server-to-server traffic such as provider
callbacks. The Spring backend remains authoritative for session validation,
authorization and CSRF; the web ingress guard is defense in depth, not a
replacement for backend checks.

## CORS strategy

Normal browser traffic is same-origin, so `USI_CORS_ALLOWED_ORIGINS` is empty by
default and CORS is deny/off. Never use `*` as a development workaround. If an
explicit cross-origin client is deliberately enabled by a deployment within the
frozen contract, origins must be listed exactly as comma-separated origins;
paths, queries, credentials and wildcards are invalid, and staging/production
entries must use HTTPS.

The backend implementation must preserve the same rule: only an exact configured
Origin may receive CORS allow headers, blocked preflights must not receive
`Access-Control-Allow-Origin`, and credentialed browser access must never be
combined with wildcard CORS. CORS does not replace CSRF protection for
state-changing authenticated commands.

## Provider callback tunnel and trusted proxy rule

For Slack, Teams or Telegram callback development, expose the **web ingress**
through a public HTTPS tunnel and configure provider callback URLs below that
origin, for example:

```text
https://<dev-tunnel-host>/api/v1/providers/slack/events
https://<dev-tunnel-host>/api/v1/provider-callbacks/teams
https://<dev-tunnel-host>/api/v1/provider-callbacks/telegram
```

Slack's exact scopes, Events API subscriptions, credential boundary, install
steps and URL-verification expectations are documented in
[`SLACK_DEVELOPMENT.md`](SLACK_DEVELOPMENT.md). Telegram's development bot,
webhook, secret-reference and test-chat setup is documented in
[`TELEGRAM_DEVELOPMENT.md`](TELEGRAM_DEVELOPMENT.md).

The tunnel should forward to the local web port; the same `/api` rewrite then
reaches Spring Boot. Do not expose provider credentials to the browser and do not
add direct Slack/Teams/Telegram API calls from UI code.

Forwarded client identity is trusted only from explicitly configured ingress
proxies. Application code must not treat arbitrary `Forwarded` or
`X-Forwarded-*` headers supplied by a direct client as authoritative identity,
IP, scheme or authorization input. The local Next hop is only a transport hop;
production trusted-proxy handling belongs to the Caddy/Spring deployment
boundary and must use an explicit proxy allowlist/configuration rather than
blanket forwarded-header trust.

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
(`pnpm test:dev-tools`) and is part of the normal `pnpm check` gate. Same-origin
proxy/origin behavior is covered by frontend unit tests, while the API
environment suite verifies explicit CORS origin-list validation.
