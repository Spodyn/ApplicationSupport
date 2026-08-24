# Unified Support Inbox - Operations, Deployment and Reliability

**Status:** FROZEN v1 operational baseline

## 1. Hosting model

- One customer = one dedicated deployment.
- Initial production: dedicated Linux VM/server, no Kubernetes.
- Recommended first provider: Hetzner Cloud, while Terraform/module boundaries preserve provider portability.
- Production region: EU, preferably close to the customer/users.
- Host baseline: Ubuntu 24.04 LTS.
- Initial capacity benchmark: 8 vCPU, 32 GB RAM, fast NVMe. Validated E24 load-test capacity has precedence over this starting benchmark.

## 2. Network and reverse proxy

Public exposure: 443, with 80 only for redirect/ACME where needed; SSH only from trusted IP/VPN/admin network. PostgreSQL, RabbitMQ, MinIO/S3 admin endpoints, Prometheus, Grafana and actuator/admin ports remain private.

Default reverse proxy is Caddy, with same-origin routing:

```text
/       -> Next.js
/api/*  -> Spring Boot
/ws/*   -> Spring Boot WebSocket/STOMP
```

TLS uses ACME, TLS 1.2/1.3 with preference for TLS 1.3 and automatic renewal.

## 3. Release artifact

- Registry: GHCR.
- Production identifies immutable image digest; never deploy mutable `latest`.
- Version: semantic `MAJOR.MINOR.PATCH` plus exact Git SHA/build metadata.
- `main` remains mainline; a release is a concrete commit/tag, not a long-lived production branch.

## 4. Deployment gates

Staging may deploy automatically after required quality/security gates. Production always requires protected explicit human approval. Codex may prepare artifacts, validation and runbooks but receives no production credentials and cannot perform cutover itself.

Pre-deploy order:

1. CI/security green.
2. Immutable artifact identified.
3. Backup/preflight checks.
4. Migration compatibility validation.
5. Deploy.
6. Readiness passes.
7. Smoke tests pass.

Initial strategy is controlled recreate/rolling on one host with a short maintenance window if required. Do not introduce premature blue/green complexity.

Readiness/smoke failure supports controlled rollback to the previous compatible image/config. DB migrations are not automatically rolled down; schema changes use expand-contract and N-1 application compatibility.

## 5. Database migration operations

- Flyway migrations append-only; never edit an applied migration.
- Schema must support current and immediately previous application version where rollback is expected.
- Destructive removal occurs only after older code no longer depends on the field/table.
- Rename pattern: add -> backfill/migrate -> switch -> cleanup later.
- Default PostgreSQL isolation: READ COMMITTED.
- Provider HTTP calls are outside DB transactions.

## 6. Observability

### Logs
- Production default INFO.
- DEBUG/TRACE only temporarily and per module.
- Online retention 30 days; optional operational archive up to 90 days.
- Logs do not replace business audit.

### Traces
- Staging may sample 100%.
- Production baseline: 10% normal traces plus 100% error/slow traces where telemetry backend supports it.
- Slow API threshold: 2 seconds; provider calls may use vendor-specific thresholds.

### Metrics
- Prometheus scrape baseline: 15 seconds.
- Never use Case/Message/user IDs as high-cardinality labels.

### Health
Liveness means the process is alive; dependency failure must not create restart loops. Readiness is dependency-aware and refuses traffic when safe core operation is impossible. Partial dependency problems are exposed as degraded metrics.

## 7. Alert baseline

| Signal | Warning | Critical |
|---|---|---|
| App readiness unavailable | - | >2 min |
| Application 5xx rate | >2% for 5 min | >5% for 5 min |
| Oldest queue/outbox pending | >2 min | >10 min or growing without drain |
| SLA scheduler lag | >60 sec | >5 min |
| Provider `UNAVAILABLE` | >2 min | >10 min |
| Disk/storage use | 75% | 90% |

Every alert has dedup/cooldown, explicit recovery event and runbook; do not resend the same incident every minute.

## 8. Backup and disaster recovery

### PostgreSQL
- RPO <=5 minutes.
- Continuous WAL/PITR.
- Encrypted full/base backup daily.

### Attachment storage
- Initial v1 RPO <=1 hour.
- Independent/off-host synchronization at least hourly.
- Restore tooling validates DB/object consistency and explicitly reports missing objects; never silently pretend an attachment exists.

### Full deployment
- RTO <=2 hours.
- At least one encrypted backup off-host/in a separate failure domain.
- Encryption in transit and at rest.
- Backup key is not stored in the backup.
- Secret values are not copied into ordinary backup artifacts; back up non-secret config/manifests plus secret references/recovery procedure.

Backup retention default 35 days, configurable 7-90 days. Runtime data purged under retention may remain only until natural backup expiry. After restore, retention/purge is reapplied before normal service resumes.

Restore validation:
- weekly automated restore smoke on isolated non-production sample;
- quarterly full DR drill covering DB/PITR, object storage, application integrity and measured RPO/RTO;
- a green backup job alone does not prove recoverability.

Target PITR, destructive recovery, production secret recovery/rotation, DNS/provider callback cutover and return-to-service require explicit human approval.

## 9. Performance and resilience SLOs

Availability target: 99.9% monthly application availability excluding agreed maintenance windows.

Nominal-load targets:
- normal interactive API p95 <500ms, p99 <1.5s excluding provider latency;
- first Case-list page p95 <500ms;
- local workflow commands not waiting on provider p95 <300ms;
- authenticated provider callback durable-ingest ACK p95 <500ms;
- business commit -> realtime update p95 <1s;
- typical 30-day analytics p95 <2s;
- 366-day analytics p95 <5s;
- steady-state application-generated 5xx <0.5%, provider failures reported separately.

Acceptance-load baseline:
- 200 simultaneously logged-in users;
- 200 WebSocket connections;
- 5,000 Cases/day;
- at least 100 inbound events/second for 60 seconds with no data loss and deterministic backlog drain;
- 24-hour realistic soak with no growing memory/thread/connection/file-handle/queue leak.

Performance tuning is evidence-driven; significant query/index/cache/config tuning requires before/after measurements. Web/API/workers may scale horizontally later, but DB/Rabbit/storage HA is not added without measured need.

## 10. Staging and UAT

Staging is production-like but isolated: separate DB, object storage, secrets, domain and provider sandbox/test credentials; no production data by default. Staging UI is visibly marked.

UAT covers auth/users, full Case workflow, unread/snooze, SLA/OOO/notifications, all three providers, attachments, statistics/admin/audit and failure/reconnect paths. Critical/High product/security defects block release; Medium requires documented risk and follow-up.

## 11. Pilot and go-live

Initial pilot: approximately 5-10 support users, limited real/controlled channels, minimum five business days unless a blocker stops it earlier.

Immediate stop/rollback criteria include data loss/corruption, incorrect customer delivery, auth/security incident, repeated duplicate messages, or critical workflow/SLA failure.

Go-live requires green CI/security, completed UAT, load/resilience acceptance, restore/DR evidence, provider acceptance, monitoring/alerts/runbooks, no unresolved release blockers and protected human approval for production cutover.

After first go-live: 72 hours heightened monitoring/hypercare, followed by formal release review. Noncritical improvements go to backlog rather than silently expanding v1.
