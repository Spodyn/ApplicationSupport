# Application threat model and security requirements matrix

**Decision:** E20-T01 / USI-195  
**Status:** FROZEN v1 implementation baseline  
**Owners:** engineering owns implementation and automated evidence; the deployment owner approves production changes.

This model applies to one customer deployment. Its security boundary is deliberately
not a multi-tenant boundary: every customer has a separately deployed application,
PostgreSQL database, object store, configuration and secret set. The shared source
repository must nevertheless assume that every browser and every provider payload is
hostile.

## Assets, trust boundaries and assumptions

| Asset | Required property | Boundary / principal |
| --- | --- | --- |
| Browser session and CSRF token | confidential, non-forgeable | browser ↔ same-origin Caddy/Spring |
| Cases, messages, workflow state and audit trail | integrity and availability | Spring command boundary ↔ PostgreSQL |
| Provider webhook signing material and API credentials | secret | deployment secret store ↔ provider adapter |
| Attachment objects and metadata | confidentiality, malware-safe access | object store ↔ attachment service/scanner |
| Queue/inbox/outbox records | durable, replay-safe integrity | PostgreSQL ↔ RabbitMQ workers |
| Operational telemetry | useful but secret-free | application ↔ observability systems |
| Production environment | human-controlled | protected deployment path; never Codex/CI |

The application trusts only authenticated server-side identities, persisted
authorization facts and provider callbacks that pass their adapter's verification.
It does not trust UI visibility, client-supplied roles/permissions, forwarded client
IP headers from arbitrary sources, provider message content, attachment names/MIME
types, or URLs supplied by a provider/customer.

## Threat and requirement matrix

| Surface | Threat | Required controls | Verification evidence | Owner |
| --- | --- | --- | --- | --- |
| Browser/session | session theft, fixation, CSRF, clickjacking | server-side `USI_SESSION`; HttpOnly; Secure on staging/prod; SameSite=Lax; rotation/invalidation rules; CSRF on state changes; CSP, `frame-ancestors 'none'`, nosniff and referrer policy | auth/session integration tests; header assertions; browser negative tests | E04, E20-T02 |
| Browser/API | broken access control or client-side authorization | authenticated backend rechecks active account, role, permission, workflow state and ownership; deny CORS by default; no wildcard origins | permission and workflow negative tests | E04, E09, E16 |
| Provider webhooks | forged, replayed or oversized callbacks | provider-specific raw-body signature/secret validation; constant-time comparison; replay window where supported; request/body limits; durable inbox deduplication; per-integration backpressure | adapter signature/replay tests; duplicate delivery tests | E12, E18, E19 |
| Provider outbound calls | token leakage, SSRF, redirects to private networks | object IDs over arbitrary URLs; HTTPS provider allowlist; DNS/IP and redirect revalidation; connect/read/size limits; never log authorization headers | SSRF host/IP/redirect/timeout tests | E20-T04 |
| Attachments | malware, MIME spoofing, zip bombs, unauthorized download | random internal object keys; allow/deny policy and size limits; sniffed content type; quarantine + scan state; only `CLEAN` usable; archive limits | malware/MIME/archive/scan-failure tests | E08, E20-T05 |
| Admin APIs | privilege escalation, destructive actions | ADMIN plus exact permission; last-admin invariant; CSRF; auditable commands; no UI-only controls | authorization matrix and invariant tests | E04, E17 |
| Workflow/database | race-based double actions or unauthorized mutation | short transactions; atomic conditional updates/targeted locks; DB constraints; idempotency keys; append-only audit and hash-chain verification | PostgreSQL concurrency/integration tests | E03, E09, E17 |
| Queue/workers | replay, duplicate delivery, poisoned messages | transactional inbox/outbox; durable idempotency/dedup keys; bounded retry/DLQ; no external I/O inside DB transaction | worker retry, duplicate and crash-recovery tests | E03, E08, E12 |
| Object storage | object enumeration or cross-boundary access | server-mediated authorization; private bucket; random keys; expiring scoped access only if introduced; no direct browser credentials | attachment access negative tests | E08, E20-T05 |
| Logging/audit | disclosure of credentials or customer content | structured allowlisted metadata; redact tokens/cookies/passwords/signatures; audit excludes bodies/attachment content/secrets; retention is controlled deletion only | secret scan and log/audit redaction tests | E17, E22 |
| CI/Codex | production access or secret exfiltration | fake/sandbox-only providers; no production credentials/DB/object-store access; protected deployment approval; no autonomous cutover, rotation, DNS or recovery | CI secret scanning; repository/environment access review | E20, E22, E25 |
| Availability | abusive login/reset/API or resource exhaustion | per-account/IP controls with trusted-proxy policy; stable 429/Retry-After; bounded uploads/callbacks/queues; health and alerting | rate-limit/isolation/load tests | E04, E20-T06, E24 |

## Security requirements checklist

The following requirements are release gates for the owning tickets. An unchecked
future implementation is not an exception to this model; it is a known incomplete
control and must not be represented as complete in release evidence.

- [x] **SR-01: single-deployment isolation.** No runtime tenant selector or
  cross-customer API is introduced; customer isolation is deployment, database,
  object-storage, secret and configuration isolation.
- [x] **SR-02: secret and production boundary.** Repository, browser bundle,
  ordinary logs, audit metadata, Codex and CI receive no production credentials.
  Production deployment/cutover, DNS changes, recovery and secret rotation require
  explicit human authority.
- [x] **SR-03: secure server sessions and authorization baseline.** Server-side
  sessions, CSRF, RBAC and the backend-authoritative permission model are frozen.
- [x] **SR-04: trusted transaction/concurrency baseline.** PostgreSQL is
  authoritative; provider I/O is outside transactions; inbox/outbox and idempotent
  command patterns are mandatory for asynchronous effects.
- [ ] **SR-05: browser policy enforcement.** Implement and test CSP, clickjacking,
  content-type/referrer and strict origin policy in E20-T02.
- [ ] **SR-06: centralized untrusted-content rendering.** Implement and test safe
  provider/customer Markdown/rich-text normalization in E20-T03.
- [ ] **SR-07: SSRF-safe remote retrieval.** Implement the provider/file fetch
  guard in E20-T04 before arbitrary remote downloads are enabled.
- [ ] **SR-08: attachment quarantine/scanning.** Implement file-policy and
  fail-safe scanner handling in E20-T05 before normal attachment use.
- [ ] **SR-09: abuse controls.** Implement reusable rate limits with isolation and
  stable 429 semantics in E20-T06.

## Residual risk and change control

Real provider sandboxes may be unavailable, and self-hosted scanner availability is
an operational dependency. Those conditions do not justify bypassing signature,
quarantine, authorization or production-boundary controls. Record a bounded
degradation and fail closed where the relevant asset could be exposed.

Any change that adds a provider, cross-customer access, production authority,
commercial security service, or a new legal/compliance commitment is a product and
security-boundary change. It requires explicit human approval rather than an
autonomous implementation decision.
