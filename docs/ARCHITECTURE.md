# Architektura Unified Support Inbox

**Stan kontraktu: 24 sierpnia 2026 - FROZEN v1.** Dokument rozdziela aktualny frontend/mock od zamrożonego docelowego baseline produkcyjnego.

## 1. Aktualny frontend i granica danych

Frontend: Next.js 16, React 19, strict TypeScript, Tailwind, istniejący shadcn/base-ui style, TanStack Query, Recharts i PWA.

Obowiązująca granica:

```text
UI
 -> apps/web/lib/services/queries.ts
 -> apps/web/lib/services/registry.ts
 -> typed service interfaces
 -> mock implementation teraz / OpenAPI adapter docelowo
```

`apps/web/app` i `apps/web/components` nie importują bezpośrednio z `apps/web/mocks` ani `packages/api-client`. `apps/web/lib/services/registry.ts` jest composition boundary. Generated DTO w `packages/api-client/src/generated` nie są typami domenowymi.

Układ produkcyjnego monorepo rozdziela odpowiedzialności: `apps/web` zawiera
frontend, `apps/api` backend i backend-owned OpenAPI, `packages/api-client`
wyłącznie generated transport client, a `infra` definicje deployment/IaC.
Konfiguracje i testy specyficzne dla frontendu pozostają w `apps/web`; root
utrzymuje wspólny pnpm workspace, lockfile i komendy jakości.

## 2. Docelowy modularny monolit

```text
Browser / PWA
      |
      | same origin HTTPS
      v
Caddy
  |-- /      -> Next.js
  |-- /api/* -> Spring Boot REST/OpenAPI
  `-- /ws/*  -> Spring Boot WebSocket/STOMP
                     |
       +-------------+-------------+----------------+
       |             |             |                |
   PostgreSQL     RabbitMQ    S3/MinIO        Observability
   + Flyway       async work  attachments     OTel/Micrometer
       |             |                              |
       `---- transactional inbox/outbox ------------'

Slack / Teams / Telegram
       -> authenticated provider adapters
       -> durable inbound events
       -> provider-neutral Case/Message core
```

Backend baseline:
- Java 25 LTS;
- Spring Boot 4.1.x latest compatible stable patch;
- Spring Modulith 2.1.x latest compatible stable patch;
- Maven Wrapper;
- PostgreSQL 18.x + Flyway;
- RabbitMQ 4.3.x;
- S3-compatible object storage / MinIO;
- WebSocket/STOMP;
- OpenTelemetry/Micrometer + Prometheus/Grafana.

No Kubernetes or microservice split in v1 without explicit later contract change.

## 3. Same-origin and API boundary

Public browser routing: `/` -> Next.js, `/api/*` -> Spring Boot, `/ws/*` -> WebSocket. CORS deny/off by default; no wildcard convenience.

OpenAPI is the transport contract. Generated TS client is isolated and mapped to stable frontend domain/view models. API errors use `application/problem+json` with stable problem codes.

Long feeds use signed opaque versioned cursor pagination: default 50, max100, TTL24h. Retryable commands use `Idempotency-Key` max128, scoped by user + command, retained24h. Same key/same canonical request replays same result; same key/different payload ->409.

Valid `X-Correlation-ID` max128 safe ASCII is preserved; malformed/missing input is replaced with UUIDv7 and propagated through request/outbox/jobs/provider calls.

## 4. Persistence and concurrency

- PostgreSQL is authoritative source of truth.
- UUID technical IDs; prefer UUIDv7 for new sortable IDs. Case also has immutable sequence-backed human reference `CASE-00000001` etc.
- Absolute timestamps use timezone-aware semantics/`timestamptz`; DB names snake_case.
- Default isolation `READ COMMITTED`.
- Atomic conditional UPDATE/row locks for workflow races; `FOR UPDATE SKIP LOCKED` for schedulers/workers.
- `SERIALIZABLE` only for a concrete tested invariant.
- Provider HTTP never in an open DB transaction.
- Transactional inbox/outbox protects asynchronous effects.
- Flyway append-only; expand -> backfill/migrate -> switch -> cleanup later; N-1 application compatibility where rollback requires it; executed migrations never edited.

### 4.1 Transaction boundary

The application/service command owns the database transaction. Controllers, provider adapters and message consumers enter a command boundary; they do not keep a transaction open around network I/O. A transaction contains only the reads/locks needed to validate the invariant, the authoritative domain writes and any inbox/outbox/audit rows that must commit atomically with them.

Provider HTTP, RabbitMQ publish and object-storage calls happen after the database transaction commits. When an external effect must follow a domain write, commit an outbox row in the same transaction and let a worker perform the external call outside that transaction. The worker records delivery success/failure in a separate short transaction.

Do not set a global isolation level above PostgreSQL `READ COMMITTED`. A use case that genuinely requires stronger isolation must declare it at that transaction boundary, document the invariant that requires it and include deterministic retry/concurrency tests. `SERIALIZABLE` failures (`SQLSTATE 40001`) may only be retried with a bounded policy around an idempotent command.

### 4.2 Single-row command races

Prefer one atomic conditional statement over read-then-write locking for claim-like state transitions. The predicate contains every state/ownership condition that makes the command legal and the update returns the changed row, for example:

```sql
UPDATE cases
SET owner_user_id = :user_id,
    status = 'VERIFICATION',
    claimed_at = CURRENT_TIMESTAMP,
    updated_at = CURRENT_TIMESTAMP
WHERE id = :case_id
  AND owner_user_id IS NULL
  AND status IN ('NEW', 'PARTIALLY_IGNORED')
RETURNING id, owner_user_id, status, claimed_at, updated_at;
```

Exactly one concurrent caller can win. Zero returned rows means the caller lost the race or the precondition is no longer true: refetch authoritative state and return HTTP `409` using the stable conflict problem contract (`CONFLICT` until an owning workflow ticket defines a more specific stable code). Never convert a lost race into last-write-wins and never blindly retry a business command that changed eligibility.

Use explicit row locks only when one atomic statement cannot protect a multi-row invariant. Lock the smallest possible row set in a deterministic order and keep the transaction free of provider/network calls.

### 4.3 Durable scheduler and worker claims

Workers that claim due rows use `FOR UPDATE SKIP LOCKED` at `READ COMMITTED` so multiple workers can make progress without waiting on the same row. Selection has a deterministic tie-breaker and the claimed state is persisted before any external effect. A representative batch claim is:

```sql
WITH picked AS (
    SELECT id
    FROM outbox_events
    WHERE status = :ready_status
      AND next_attempt_at <= CURRENT_TIMESTAMP
    ORDER BY next_attempt_at, id
    FOR UPDATE SKIP LOCKED
    LIMIT :batch_size
)
UPDATE outbox_events AS event
SET status = :claimed_status
FROM picked
WHERE event.id = picked.id
RETURNING event.*;
```

The concrete ready/claimed states belong to the outbox implementation ticket; the invariant here is atomic, non-blocking ownership of a due row before external I/O. Commit the claim promptly. Publishing to RabbitMQ/provider HTTP happens after commit; completion/retry metadata is persisted in a later transaction. Crash recovery relies on durable status/attempt timestamps and idempotent effects, not on holding a database lock during I/O.

### 4.4 Isolation policy by use case

| Use case | Isolation / locking | Conflict/retry rule |
| --- | --- | --- |
| Normal CRUD and projections | `READ COMMITTED` | No implicit retry of business commands |
| Claim or single-row state command | `READ COMMITTED` + atomic conditional `UPDATE ... RETURNING` | zero rows -> refetch + stable `409` |
| Multi-row invariant | `READ COMMITTED` + targeted row locks in deterministic order | conflict handled by owning command |
| Due scheduler/outbox worker | `READ COMMITTED` + `FOR UPDATE SKIP LOCKED` | another worker owning a row is normal, not an error |
| Proven invariant impossible to protect above | explicitly scoped `SERIALIZABLE` only | bounded retry on `40001`, only for idempotent work |

Do not use a global `REPEATABLE READ`/`SERIALIZABLE` setting as a substitute for modeling the race at the query/transaction boundary.

### 4.5 Query-shaped indexes

The migration that introduces a table owns the indexes required by its concurrency/query pattern. Do not create placeholder production tables or indexes ahead of the owning domain ticket. At minimum, later migrations must provide query-shaped indexes equivalent to:

- unowned claim candidates: leading status/eligibility fields plus stable `id`, normally with a partial predicate for `owner_user_id IS NULL`;
- due timers: due timestamp followed by stable `id`, with status/active predicates matching the worker query;
- outbox polling: `(status, next_attempt_at, id)` (or an equivalent partial index over unpublished/due rows) matching the exact `SKIP LOCKED` predicate and order.

Index names, UNIQUE constraints and CHECK constraints are explicit in the owning Flyway migration. Concurrency tests must run against PostgreSQL/Testcontainers and must not depend on sleeps for correctness.

## 5. Realtime

Business commit -> transactional outbox -> RabbitMQ -> WebSocket/STOMP. Semantics are at-least-once and duplicate/out-of-order safe. DB/refetch is source of truth; WebSocket is a signal, not durable event store.

Heartbeat10s, dead connection about30s. Reconnect roughly1/2/5/10/30s + jitter, followed by bounded list/counter/current-Case/message resync.

Personal unread/Snooze events use backend-controlled user destinations; clients cannot subscribe to arbitrary user IDs.

## 6. Provider adapters

Adapters own authentication/signature mechanics, provider IDs, payload normalization, current vendor API calls/rate limits and provider-specific attachment access. They **do not** own Case workflow, authorization, SLA, read state, retention or audit semantics.

Frozen provider scope:
- Slack public/private/Connect channels, no DM/group DM;
- Teams standard channels + group chats, no private/shared;
- Telegram private chats/groups/supergroups/topics, no broadcast channels.

Exact SDK/API patch details are technical choices based on current official vendor documentation and must not expand product scope.

## 7. Security and secrets boundary

Backend rechecks session, role/permission, active account, workflow state and ownership. Browser visibility is never authorization. Provider/customer content is untrusted and sanitized.

Development uses dummy/dev env values and `.env.example`. Production uses runtime secret injection/deployment store and DB stores secret references rather than provider-secret plaintext. Codex/CI never receive production secrets or production DB/storage access.

## 8. Single tenancy

Each customer has separate deployment, PostgreSQL DB, object storage, secrets and config using one shared codebase. Runtime v1 has no `tenant_id`, tenant selector or cross-tenant API.

## 9. Deployment baseline

Initial production baseline is a dedicated EU Linux VM (recommended first provider Hetzner Cloud), Ubuntu24.04 LTS, Caddy, Docker/Compose and immutable GHCR digest. Initial benchmark 8vCPU/32GiB/NVMe; validated performance tests determine actual safe sizing.

Staging may auto-deploy after gates. Production cutover requires protected human approval; Codex does not receive production credentials.

## 10. Source hierarchy

For implementation precedence use `AGENTS.md`: current Jira + later final overrides -> `PRODUCT_CONTRACT.md` -> `decision-registry.yaml` -> canonical area docs -> older historical/current-state docs -> code fixtures only where non-conflicting.
