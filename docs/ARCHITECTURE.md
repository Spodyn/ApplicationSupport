# Architektura Unified Support Inbox

**Stan kontraktu: 24 sierpnia 2026 - FROZEN v1.** Dokument rozdziela aktualny frontend/mock od zamrożonego docelowego baseline produkcyjnego.

## 1. Aktualny frontend i granica danych

Frontend: Next.js 16, React 19, strict TypeScript, Tailwind, istniejący shadcn/base-ui style, TanStack Query, Recharts i PWA.

Obowiązująca granica:

```text
UI
 -> lib/services/queries.ts
 -> lib/services/registry.ts
 -> typed service interfaces
 -> mock implementation teraz / OpenAPI adapter docelowo
```

Komponenty nie importują bezpośrednio z `mocks/`. `registry.ts` jest composition boundary. Generated DTO nie są typami domenowymi.

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
