# ApplicationSupport security audit and remediation — 2026-11-09

## Executive summary

**Production readiness: NOT APPROVED.** This audit implements security fixes and verifies isolated regression scenarios. It does not certify complete security or ASVS conformance. Deployment and remaining acceptance controls below require additional evidence.

Base: `2df2621` on `main`; branch: `codex/security-audit-2026-11-09`. The owner explicitly grouped this audit/remediation scope. The initial worktree was clean, main matched origin/main, and open PRs/worktrees were reconciled. No existing database, service volume, running application, production system, provider setting or customer conversation was modified. No real provider credential was used in tests. No deployment, credential rotation or merge was performed.

**Suspected credential exposure:** the audited revision already used an explicit HTML POST form, JSON POST transport and a POST-only backend login controller. No confirmed credential-in-URL exposure was found. New browser tests verify the exact URL/body with JavaScript and native POST submission without JavaScript. GET/query input cannot authenticate. Historical deployments, access logs, browser history and traces were NOT TESTED. If operators identify historical GET submissions containing credentials, rotate affected credentials through the protected process and restrict/sanitize affected edge/application/analytics/telemetry records without copying credential values into tickets. This audit found no evidence establishing such a breach.

Jira discovery returned HTTP 403 (Atlassian app not installed). Current ticket/E00/parent comments could not be independently reconciled. Work follows the owner's request and the frozen repository product, authentication, security and orchestrator contracts. No Jira mutation occurred.

## Baselines, architecture and method

Baselines: [OWASP ASVS 5.0](https://asvs.owasp.org/), [OWASP Top 10:2025](https://top10.owasp.org/2025/), [API Security Top 10:2023](https://api-security.owasp.org/editions/2023/en/0x11-t10/), [SECURITY.md](SECURITY.md), [THREAT_MODEL.md](THREAT_MODEL.md). Authentication/session, access control, validation, browser, file, communications, configuration, supply-chain and logging families were reviewed. This is a risk-based implementation review, not a completed clause-by-clause ASVS assessment.

Trust boundaries and sensitive flows:

1. Browser → same-origin ingress → Next.js documents or Spring `/api/v1` and `/ws`. The service registry maps generated transport DTOs into frontend domain models. JDBC sessions identify users; CSRF protects mutations; UI visibility is never authority.
2. Spring → PostgreSQL: accounts, permissions, workflow, messages, session/attachment metadata and audit are authoritative. Prepared SQL, conditional updates/targeted locks and inbox/outbox protect command/concurrency integrity.
3. Provider callback → signature/secret verification → durable inbound event → worker → Case/Message transaction → outbox/realtime. Signature correctness and workspace/channel identity are separate checks.
4. Provider HTTP/file access → guarded fetch → random storage key → quarantine/scan → authenticated CLEAN-only send/download. External secret references are server-only.
5. Spring ↔ RabbitMQ/S3 storage; application → logs/traces/audit; developer/CI → source and fake infrastructure. Each is a separate confidentiality/integrity boundary.

The frozen product uses a separate deployment/database/storage/secret set per customer. Active users have deployment-wide Case read visibility; ownership/permissions govern mutations. No runtime tenant model or new role was introduced.

Methods: source/route/data-flow review, negative/regression tests, real disposable PostgreSQL/RabbitMQ/MinIO Testcontainers, production frontend build and Chromium tests, npm audit, resolved Maven package queries to [OSV](https://google.github.io/osv.dev/api/), worktree/history secret scans, workflow and Compose assertions. Semgrep results are recorded in the final ledger. No destructive/load/active third-party or production security test was run.

## Findings and fixes

Severity describes the original finding. FIXED means locally remediated with regression evidence; it does not mean deployed.

| ID | Severity | Status | Root cause, impact and change |
| --- | --- | --- | --- |
| AUTH-01 | INFORMATIONAL | VERIFIED | Suspected GET credential transport was absent in the audited revision. Exact POST URL/body, no-store/same-origin transport, no-JavaScript POST and GET denial have regressions. Historical exposure remains NOT TESTED. |
| AUTH-02 | HIGH | FIXED | Unlimited guessing/expensive authentication work. PostgreSQL now atomically commits shared account/peer reservations before credential verification: login 10/account/15m and 50/peer/15m; progressive delay after five attempts; reset 5/account/hour and 20/peer/hour. Reset suppression retains neutral 202. Token completion has a separate 50/peer/15m budget. Login/completion throttles return stable 429 and Retry-After; expiry permits recovery without permanent lockout. |
| AUTH-03 | MEDIUM | FIXED | Unknown/uninitialized accounts skipped Argon2 work, exposing account existence through timing. A random process-local dummy hash now incurs expensive verification for validly shaped credentials. Tests assert verification work rather than unreliable wall-clock equality. |
| AUTH-04 | HIGH | FIXED | Token consumption unconditionally set active=true, allowing an old reset/invitation to reactivate a disabled account. Consumption now rejects inactive users and preserves the administrative active flag. Tests cover one-time use, hash-only persistence and session invalidation. |
| AUTHZ-01 | HIGH | FIXED | Profile PUT bypassed the dedicated permission command's self-grant guard. It now rejects adding the caller's own missing permissions. Other-user administration and last-manager protection are retained. |
| WS-01 | HIGH | FIXED | A socket authenticated only at upgrade could retain subscriptions after logout/reset/deactivation. Actual server session ID, expiry and account eligibility are checked before inbound frames and every outbound transmission, including broadcasts/heartbeats. Invalid/unavailable state closes with policy violation. Heartbeats do not renew idle expiry. |
| API-01 | MEDIUM | FIXED | JSON was allocated before a size guard. A filter bounds auth at 8 KiB and other non-multipart API bodies at 2 MiB, including chunked bodies, before parsing/HMAC. Exact raw webhook bytes are preserved. Multipart limits are explicit at 25 MiB/file and 50 MiB/request; message-level limits remain. |
| LOG-01 | MEDIUM | FIXED | Generic errors logged complete exception/cause chains that could contain SQL/provider/customer payloads. Logs now contain failure class and existing correlation context. Tests inspect returned errors and captured log events. Framework-wide local DEBUG was replaced by INFO. Historical logs remain NOT TESTED. |
| WEB-01 | MEDIUM | FIXED | API CSP did not protect Next.js HTML. Documents now use a fresh nonce, strict script policy, frame denial, nosniff, no-referrer, restrictive Permissions-Policy and private/no-store. Caller nonce/CSP headers are overwritten; Next.js/theme scripts receive the nonce. Production scripts allow neither unsafe-inline nor unsafe-eval. Existing chart/component inline styles retain a documented style exception. Browser tests verify hydration and rejection of parser-inserted hostile scripts. Edge HSTS is NOT TESTED. |
| WEB-02 | LOW | FIXED | Logout cleared only identity, retaining customer projections in query memory. Successful logout now clears QueryClient. Credential mutations are immediately eligible for collection when unobserved; forms clear sensitive fields after completion. This is memory lifecycle hygiene, not JavaScript heap zeroization. |
| WEB-03 | LOW | FIXED | Production HTML automatically loaded external Vercel Analytics. Automatic analytics and its dependency were removed; no replacement service was introduced. |
| SLACK-01 | MEDIUM | FIXED | Signed events could fall back to an integration without a bound workspace. Business intake now requires exact verified workspace matching. Unique-secret URL verification without team_id remains supported. Regression verifies rejection/no persistence for an unbound business event. |
| SCA-01 | CRITICAL | FIXED | Initial npm audit: 116 advisory records, including 6 critical and 44 high. Next/eslint-config-next are pinned to 16.4.1 and Vitest to 4.1.12; targeted transitive fixes are locked. Unused shadcn generator dependency was removed; its unchanged MIT-licensed 4.8.0 CSS is retained locally. Final npm audit: zero advisories. Severity is not proof every affected code path was exploitable here. |
| SCA-02 | CRITICAL | FIXED | Initial OSV scan: 22 advisory records across five packages in 248 Maven artifacts. Boot 4.1.2 supplies Tomcat 11.0.27/Jackson 3.1.7; Modulith is 2.1.2, RabbitMQ client 5.37.1 and OTel BOM 1.67.0. Final scan: 247 artifacts, zero advisories. OS/container layers are not covered by this scan. |
| CI-01 | MEDIUM | FIXED | Mutable major action tags and no enforced dependency advisory gate. Workflow actions now use verified upstream SHAs. Existing required backend/validate jobs run fail-closed Maven OSV and high/critical npm gates. Scanner input/network failures and unknown advisory severity cannot pass. No protection/security assertion was disabled. |
| SCAN-01 | MEDIUM | NOT FIXED / DEPLOYMENT REVIEW | Semgrep detected raw TCP in the ClamAV INSTREAM client. Attachment contents can be exposed if this connection crosses an untrusted network. Native ClamAV transport is not TLS; constrain it to a protected local boundary or provide a verified TLS transport before remote deployment. No scanner warning was suppressed. |

Primary implementation references: [OWASP authentication/timing/throttling](https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html), [Spring sessions](https://docs.spring.io/spring-security/reference/servlet/authentication/session-management.html), [Spring CSRF](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html), [Slack raw-body verification](https://docs.slack.dev/authentication/verifying-requests-from-slack/), [Next Windows RCE advisory](https://github.com/vercel/next.js/security/advisories/GHSA-p293-qw3h-jr36), [Tomcat security](https://tomcat.apache.org/security-11.html), [Jackson 3.1.7](https://github.com/FasterXML/jackson/wiki/Jackson-Release-3.1.7). Bundled Next CSP/proxy guidance was read before edits.

## Subsystem coverage and limits

| Area | Evidence and practical limit |
| --- | --- |
| Auth/session | Argon2id 64 MiB/3 iterations/parallelism 2; normalized email; generic failures; JDBC session persistence; rotation, cookie flags, logout, inactive/expired checks, CSRF positive/negative and reset invalidation. HTTPS/Secure-cookie deployment behavior is NOT TESTED. No browser token storage shortcut. |
| Authorization/workflow | Deny-by-default filter chain, role/effective-permission refresh, ADMIN+permission service guards, last-manager invariant, Case owner/state guards, personal read/snooze destinations. Existing negative/concurrency suites rerun. [REST inventory](security/ENDPOINT_INVENTORY.md) lists 83 source mappings, not an ASVS conformance claim. |
| API/input | Prepared SQL/JPA, controlled dynamic column/placeholders, cursors, typed requests, errors and idempotency reviewed. Searched application sources revealed no raw provider HTML execution, shell execution or Java object-deserialization endpoint; this is not a formal absence proof. Resource/backpressure work remains below. |
| Slack | Raw HMAC/constant-time compare, ±300s window, tamper/replay tests, workspace binding, durable integration/event dedup, bot filtering, channel membership, disabled/ignored policies, owner checks, retries and quarantine gates. Fake HTTP/Testcontainers evidence only; no live scope/callback/OAuth acceptance. |
| Teams/Telegram | Configuration/secret/adapter boundaries and existing tests reviewed/rerun. Real Teams consent/RSC and Telegram bot delivery NOT TESTED. No scope expansion. |
| PostgreSQL/RabbitMQ | Append-only migrations, prepared SQL, targeted locking, idempotency/outbox/inbox and isolated persistence. V41 only adds a budget table/index; N-1 data/schema remain. Dedicated V40→V41 preservation/validation test added. Real DB roles/TLS/encryption/backup recovery NOT TESTED. |
| Attachments | Random keys, owner-only upload/removal, authenticated deployment-wide Case reads, Case/attachment membership, CLEAN-only usage, MIME/extension/file/message limits, attachment Content-Disposition, fail-closed ClamAV and S3 tests. Archives currently rejected wholesale; actual scanner availability/signatures/bucket policy not established. |
| SSRF | HTTPS/provider-host allowlist, private/link-local/metadata rejection, DNS/redirect revalidation, 25 MiB cap and timeouts reviewed/tested. Final connection IP pinning and independent streamed-body deadline remain incomplete. |
| Browser/PWA | Central React text/link parser blocks dangerous schemes; no provider HTML execution; origin guard, no-store API and static-allowlist-only SW. Theme storage has no session secrets. Browser tests intercept API and block external network; full live SW/backend acceptance remains separate. |
| Infra/CI | Local loopback Compose binds/private network, external secrets, production Secure cookies, health-only actuator, checked Maven wrapper, pinned MinIO source and trusted-main integration guards reviewed. Compose assertions pass. No deployable production app/Caddy/TLS/Cloudflare manifests exist in the repository. Actual edge/network/account controls and container CVEs NOT TESTED. |
| Logging/retention | Audit sanitization/append-only triggers and diagnostic redaction reviewed; exception logging strengthened. Complete retention/deletion, audit hash-chain verification and backup restore are not proven end to end. No logs/data were purged. |

## Verification ledger

Local logs are retained outside Git in task-specific `usi-security-*` temporary files. Final exact-HEAD GitHub CI must be checked independently.

| Check | Result |
| --- | --- |
| Frozen-lockfile install | PASS, pnpm 11.18.0 |
| Web lint/typecheck/unit/build/E2E | PASS: 78 unit tests and 18 Chromium tests |
| Full Maven Wrapper clean verify | PASS: 484 tests, 108 suites, zero failures/errors/skips; includes V40→V41 upgrade and backend production JAR |
| Python config/security/scanner tests | PASS: 54 under isolated Linux Python 3.13; native Windows has two existing path/symlink failures |
| Integration/cleanup guard Node tests | PASS: 21 |
| OpenAPI tests/lint/generated/client/adapter smoke | PASS: 11 generator tests, 44 operations/74 schemas; additive compatibility against origin/main PASS |
| npm audit | PASS: zero advisories |
| Maven OSV | PASS: 247 packages, zero advisories |
| Secrets | PASS: worktree and available Git history; worktree repeated after fixes |
| Compose config | PASS; existing volumes untouched; full image rebuild/lifecycle NOT RUN |
| Semgrep | REVIEWED, PARTIAL: 1.183.0, 286 Java/TS/Python/Docker rules, 456 tracked files including new source; one MEDIUM ClamAV warning remains, one TypeScript partial-parse limitation |
| YAML/diff/source precedence | PASS: 7 YAML files parsed, actionlint passed, 89 relative source links verified, diff clean; Jira reconciliation unavailable as stated above |
| GitHub exact-HEAD CI | NOT RUN until commits/PR exist |

A concurrency regression detected false limiter backoff because transaction-start now() preceded a concurrent lock winner. Reading DB wall time after the row lock fixes it; the exact 50-of-60 admission assertion was retained. Browser CSP attacks are injected at the HTML parser boundary, not privileged DevTools execution. Version/matcher snapshots were updated; no failing security assertion was skipped or weakened.

## Remaining risks, manual actions and continuation

1. **DEPLOY-01 — NOT TESTED, release gate.** Provide staging ingress/TLS/HSTS/Cloudflare manifests and proof of one public entry point, blocked direct data/API access, health/admin policy and WebSocket upgrades. Forwarding headers remain ignored by default. The limiter currently uses the transport peer, conservatively sharing its IP budget behind a proxy; verify trusted client attribution and legitimate multi-agent volume before rollout. Do not globally trust X-Forwarded-For.
2. **SSRF-01 — MEDIUM, PARTIALLY FIXED baseline.** The Java HTTP client re-resolves after DNS validation; final-IP pinning is not proven. Streamed InputStream reads also need an independent total-body deadline. Implement pinned-address transport with correct TLS hostname verification or verified egress proxy, then controlled rebinding/slow-body/redirect tests.
3. **ATTACH-01 / SCAN-01 — MEDIUM/functional gap.** Verify actual ClamAV deployment, protected local or verified TLS transport, fresh signatures, failure recovery and least-privilege storage/bucket policy. ZIP/OOXML are currently rejected. Implement encrypted-archive/nesting/entry/expanded-size safeguards before enabling them; run isolated archive/malware acceptance. Never bypass CLEAN.
4. **OPS-01 — NOT TESTED, release gate.** Verify least-privilege runtime DB/storage/broker roles, encrypted transport/storage/off-host backups, restore/PITR and post-restore retention. Local developer/root bootstrap service accounts are not production evidence. Audit hash chaining/periodic verification and complete retention require further implementation/evidence. Production recovery and rotations require protected human approval.
5. **SCA-03 — NOT TESTED.** Scan exact application/base/PostgreSQL/RabbitMQ/MinIO image digests, embedded Go modules and OS packages with a maintained container scanner. Patch confirmed findings, rebuild and rerun isolated lifecycle/storage tests. npm/Maven scans do not establish image safety. Never reset existing local volumes.
6. **PROVIDER-01 — NOT TESTED, release gate.** Verify isolated Slack public/private/Connect root/reply/edit/delete, attachment, membership removal, disabled/ignored channels and delivery retries with external sandbox secrets. Inspect actual bot scopes/workspace identity. Repeat supported Teams/Telegram acceptance in their sandboxes. Do not alter production callbacks.
7. **API-02 — MEDIUM, partial coverage.** Reconcile the 83 source mappings with the 44-operation OpenAPI document and complete a per-route role/permission/owner negative matrix. General API quotas, callback concurrency/backpressure, per-integration isolation and controlled resource tests remain incomplete.
8. **BROWSER-01 — NOT TESTED in deployed topology.** Browser E2E uses API interception; backend tests exercise services separately. Run one isolated full-stack proxy+SW test covering actual login/logout/reset, realtime, Case messaging and clean/quarantined downloads. Measure durable per-frame session-check cost at frozen realtime load. CSP inline styles remain for accepted components/charts; never enable unsafe-inline scripts to fix failures.
9. **AUTH-05 — operational caveats.** Neutral reset request delivery is not demonstrated; no production e-mail service was added. Benchmark Argon2 on deployment hardware. Budget hashes are pseudonyms, not guaranteed anonymization; expired rows are cleaned in bounded batches. Review real DTO/header/trace/log privacy and historical exposure separately.
10. **ACCESS-01 — external evidence blocked.** Restore Jira read access and reconcile current acceptance/freeze comments before integration if they add requirements. Verify final exact-HEAD CI/security results. Developer workers do not merge or mark Jira Gotowe.

## Persistent checklist and reproduction

- [x] Reconcile clean Git/main/open PR/worktree reality and isolate branch.
- [x] Trace auth/authorization/realtime; fix confirmed vulnerabilities and add regressions.
- [x] Review each major subsystem boundary and explicitly record missing controls.
- [x] Patch npm/Maven advisories, pin actions and add repeatable supply-chain gates.
- [x] Complete final backend/Semgrep/compatibility/diff review and update ledger.
- [ ] Commit coherent groups, push and create PR with exact validation and release limitations.
- [ ] Independently check final-HEAD required CI; no developer merge.
- [ ] Complete the ten continuation items before production approval.

From repository root unless indicated:

```text
pnpm install --frozen-lockfile
pnpm check
pnpm audit --audit-level=high
node --test scripts/tests/ci_merge.test.mjs scripts/tests/delete_merged_branch.test.mjs scripts/tests/prune_merged_branches.test.mjs
python3 -m unittest discover -s scripts/tests -p 'test_*.py'
python3 scripts/secret_scan.py
cd apps/api
./mvnw --batch-mode clean verify
./mvnw --batch-mode dependency:list -DoutputFile=target/security-dependencies.txt
cd ../..
python3 scripts/security_dependencies.py --maven-list apps/api/target/security-dependencies.txt
pnpm openapi:compat -- --base-ref origin/main
git diff --check
```

On Windows, use Git Bash for the repository's Unix Maven wrapper. Run Python contract tests in an isolated Linux container with a read-only repository mount. Empty native discovery is not proof that 54 tests passed. Earlier failed attempts remain in local diagnostics and are not counted as passes. No known issue may be represented as remediated solely because an unrelated gate is green.

Machine-readable local evidence: [verification-2026-11-09.json](security/verification-2026-11-09.json).
Semgrep's TypeScript parser could not fully parse the valid dynamic import type at
`apps/web/lib/services/administration.ts:59`; TypeScript compilation passed and the
affected signature was manually inspected. Default scanner ignores, tests/generated
source filtering and the reported 1 MiB file limit constrain scan completeness.
The scanner image was pinned for the final run to
`semgrep/semgrep@sha256:2e45517a85e13a9cca14a2a00df6bb48affd66b8ebfae1b0991cd57578362fb0`.
No confirmed unresolved CRITICAL/HIGH application finding remains in this reviewed
source scope. That statement does not establish safety of untested images,
deployment infrastructure or the remaining release gates above.
