# Unified Support Inbox - Product Contract

**Status:** FROZEN for v1  
**Primary authority:** Jira USI-5 (E00) and later approved freeze/override comments

This document is normative. Words such as **must**, **must not**, **only**, **always** and **never** describe product requirements, not suggestions.

## 1. V1 scope and tenancy

1. V1 supports **Slack, Microsoft Teams and Telegram**.
2. E-mail support channels and AI features are outside v1.
3. Every customer has a separate deployment, PostgreSQL database, object storage, secrets and configuration.
4. Runtime tables do not use a `tenant_id` in v1 and the application exposes no cross-tenant API or tenant selector.
5. The codebase must remain shared: no customer-specific code forks.

## 2. Roles and permissions

Roles are exactly `USER` and `ADMIN`.

The granular permission catalog is exactly:

- `manage_users`
- `manage_integrations`
- `manage_sla`
- `manage_schedule`
- `manage_notifications`
- `view_global_statistics`
- `reassign_cases`
- `force_resolve`
- `view_audit`

Rules:

- Administrative capability normally requires `ADMIN` plus the relevant permission.
- `USER` plus an admin permission does not grant an admin capability.
- General settings are ADMIN-only and do not introduce another permission.
- New ADMIN accounts receive the complete current permission set by default; an ADMIN with `manage_users` may later reduce it.
- The deployment must always retain at least one active ADMIN with `manage_users`.
- Self-promotion and self-grant of a missing permission are forbidden.

## 3. Authentication contract

- Primary v1 authentication is local e-mail/password.
- Session state is server-side using Spring Session JDBC.
- Cookie name: `USI_SESSION`; `HttpOnly`; `Secure` in staging/production; `SameSite=Lax`; `Path=/`.
- Idle session timeout: 12 hours. No remember-me in v1.
- CSRF protection is mandatory for authenticated state-changing browser requests.
- User e-mail is trimmed, lower-cased before persistence and uniquely constrained in normalized form.
- Password hashing uses Argon2id. Password length is 12-128 Unicode characters. No forced composition rules, periodic rotation or security questions.
- Login failures are generic. Hash parameters are rehashed on successful login when they become outdated.
- Invitation tokens use at least 256 bits of CSPRNG entropy, are stored only as a hash, expire after 24 hours, are one-time-use, and a new invitation invalidates older active invitations for that user.
- Password reset tokens use at least 256 bits of CSPRNG entropy, are stored only as a hash, expire after 30 minutes, and a successful reset invalidates all reset tokens and all sessions for that user.
- The first administrator is created by a controlled local/server bootstrap command, not a public endpoint. It only works when no active ADMIN exists; the password must not be placed on the command line or in logs.
- Future OIDC/SSO, if introduced, maps to the same canonical user/session model and must not grant permissions from untrusted identity-provider claims by default.

## 4. Canonical Case state machine

The only Case statuses are:

`NEW`, `VERIFICATION`, `WAITING_FOR_CUSTOMER`, `PARTIALLY_IGNORED`, `IGNORED`, `RESOLVED`.

Core invariants:

- `VERIFICATION` is the only status with an active owner, and it has exactly one owner.
- `NEW`, `PARTIALLY_IGNORED` and `WAITING_FOR_CUSTOMER` are unassigned.
- `IGNORED` is terminal and has no owner.
- `RESOLVED` is terminal and preserves the last owner if there was one; a force-resolve from an unassigned state leaves owner `null`.
- Terminal Cases are never reopened. A later customer message creates exactly one new linked Case.
- Workflow state changes are performed through dedicated commands, never a generic `PATCH status`.

Human-readable Case references are immutable sequential references such as `CASE-00000001`; UUID remains the technical primary identifier.

## 5. Claim, reply, assignment and resolution

- There is no automatic assignment in v1.
- A successful Claim moves an eligible unowned active Case to `VERIFICATION` and sets the current user as owner.
- A normal Reply is available only to the current owner in `VERIFICATION`.
- A normal Resolve is available only to the current owner in `VERIFICATION`.
- `resolution_category` is optional. If provided in v1 it is a controlled code from `SOLVED`, `NO_ACTION_REQUIRED`, `DUPLICATE`, `OTHER`; it is not free text.
- `ADMIN + reassign_cases` may assign an eligible user to `NEW` or `PARTIALLY_IGNORED`, or reassign `VERIFICATION`; the target user must be active and may not be a lifetime ignore voter for that Case. Result: `VERIFICATION` with the target owner.
- Admin unassign moves `VERIFICATION` to `NEW`, clears the owner and retains ownership history in audit.
- `ADMIN + force_resolve` may force-resolve any nonterminal status. The administrator is the audit actor but does not become owner merely by force-resolving.

## 6. Ignore voting

- Ignore threshold is fixed at **2 points** in v1.
- Default effective vote weight is 1.
- `ADMIN + manage_users` may grant temporary weight 2 with optional `valid_until`.
- A vote stores the effective weight as an immutable snapshot. Expiry affects only future votes.
- One active vote per user per Case.
- Repeating Ignore while the same user already has an active vote is idempotent success and never adds points twice.
- Below threshold -> `PARTIALLY_IGNORED`; reaching threshold -> terminal `IGNORED`.
- Claim by another user and a new customer message before threshold deactivate active partial votes while preserving history.
- A user whose vote was reset may vote again.
- **Lifetime restriction:** a user who ever successfully cast an Ignore vote in a Case may never Claim or Reply in that same Case, even after the vote was reset.

## 7. Ask Customer and waiting

- Ask Customer is available only to the current owner in `VERIFICATION` and requires an outgoing human support message.
- The Case remains `VERIFICATION` with the same owner until the provider actually accepts the outgoing Ask message and it becomes `SENT`.
- On `SENT`: move to `WAITING_FOR_CUSTOMER`, clear owner, set `waiting_until`, and apply the configured waiting SLA pause.
- Permanent delivery failure leaves the Case in `VERIFICATION` with the same owner. Transient failure follows the normal message retry policy.
- Default wait is 24 hours; command override range is 1 hour to 30 days.
- Customer reply while waiting -> `NEW`, clear waiting and resume applicable SLA while preserving accumulated business time.
- Timeout -> `NEW`, clear waiting, emit/audit `WAITING_TIMEOUT`. V1 sends no automatic follow-up message on timeout.

## 8. Snooze and read/unread

Snooze:

- Per-user, never a Case status.
- Any active USER/ADMIN who can view a nonterminal Case may snooze it; ownership is not required.
- Range: 5 minutes to 30 days, using presets or a custom future time.
- Snooze marks the Case read for that user, does not alter owner/status, and does not pause SLA.
- Due time or manual cancel ends it.
- New customer message wakes the user immediately.
- `RESOLVED`/`IGNORED` clears all snoozes for that Case.
- Claim by anyone clears existing snoozes to avoid stale reminders.

Read/unread:

- Read state is independent per user and implemented sparsely; the system must not create O(users x cases) state rows for every new Case.
- New users do not inherit the historic backlog as unread.
- New customer messages make the Case unread for every active user allowed to see it; customer edits also cause unread/activity update.
- Human outgoing, system/audit-only events and status-only changes do not make the Case unread for others.
- Opening a Case does not itself mark it read. The read cursor advances only after the frontend actually renders/sees content and acknowledges it.
- Batch marking resolved Cases read affects the current user only and requires confirmation.
- Read position uses stable message ordering/an opaque server cursor, never timestamp alone.
- Personal read/snooze state is private; v1 does not expose per-Case "who read this" to administrators.

## 9. Messages and delivery

- Conversation-visible message authorship is `CUSTOMER`, `SUPPORT` or `SYSTEM`.
- Claim, resolve, reassign and internal provider events belong in Activity/audit, not fake chat messages.
- Supported body formats are `PLAIN_TEXT` and `MARKDOWN`. Provider rich content is normalized to safe text/Markdown. Raw executable HTML is never canonical content.
- A send command creates a durable local Message plus transactional outbox work; the external provider call is asynchronous.
- `SENT` means the provider accepted the message successfully and supplied its success/provider identifier where applicable.
- `DELIVERED` is used only when the provider supplies a trustworthy delivery acknowledgement. Otherwise `SENT` is the final successful state.
- Transient delivery errors: exponential backoff + jitter, honor `Retry-After`, maximum 8 attempts over 24 hours.
- Permanent errors become `FAILED` without endless automatic retry.
- Retry never creates a second logical customer-facing Message.
- Manual retry is available only for potentially repairable failures and reuses the same Message.

## 10. Attachments

- Max 25 MiB per file, 10 files and 50 MiB total per logical message. A stricter provider limit wins.
- Default allowed classes: common images, PDF, plain text/CSV, Office OOXML (`docx`, `xlsx`, `pptx`) and ZIP.
- Executables, scripts and active HTML are blocked by default.
- Both declared and detected MIME are validated.
- Uploads are backend-managed streaming/two-phase uploads; the full file must not be buffered in application memory.
- Pending/orphan uploads are cleaned after 24 hours.
- Normal downloads go through an authorized backend endpoint. A signed URL may be used only as a later optimization after Case authorization and with maximum 5-minute TTL.
- Scan states: `PENDING`, `CLEAN`, `INFECTED`, `ERROR`. Only `CLEAN` may be normally sent/downloaded. There is no "allow anyway" path for `ERROR` or `INFECTED`.
- Filename is display metadata, max 255 characters; object storage keys are internal/random and never derived directly from filename.

## 11. Case visibility, search and ordering

- Every active USER and ADMIN can view every Case in the deployment. Ownership limits actions, not read access.
- Search v1 covers Case reference, Customer name, Channel name, provider conversation/thread identifiers and display labels.
- Full-text search over all message bodies is outside v1.
- Default ordering prioritizes: SLA breached > SLA warning > unread NEW > remaining active work. Within a bucket use nearest active SLA deadline, then `last_activity_at DESC`, then `id` as deterministic tie-breaker.

## 12. SLA and business time

Defaults, in business time:

- First human response: target/breach 15 minutes, warning at 12 minutes.
- Unclaimed: warning 5 minutes, breach 15 minutes.
- In-progress: warning 30 minutes, breach 60 minutes.
- `pause_waiting=true` by default.

Rules:

- One active SLA policy per deployment, configured by `ADMIN + manage_sla`.
- A Case snapshots the SLA policy and business-hours schedule/timezone version at creation. Later configuration changes apply prospectively and do not rewrite historic Case deadlines.
- First-response clock starts when USI durably accepts the first customer message, not from an earlier provider timestamp.
- First response completes only when a human SUPPORT/Ask message becomes provider-success `SENT`. OOO/SYSTEM does not complete it.
- Unclaimed accumulates business time while active and unowned in `NEW`/`PARTIALLY_IGNORED`; Claim pauses it; later unassign/return from waiting resumes the accumulated time.
- In-progress accumulates business time in `VERIFICATION`; reassign does not reset it; unassign/WAITING pauses; later Claim resumes accumulated time.
- Warning/breach is monotonic per Case/SLA type and is not emitted twice after pause/resume/reassign.
- Terminal states end active SLA timers.
- Overall Case SLA severity is the maximum of `BREACH > WARNING > OK`.

## 13. Business hours and Out of Office

- One IANA organization timezone per deployment. Bootstrap default is UTC; production configuration must explicitly choose the intended timezone.
- Default weekly schedule is Monday-Friday 09:00-17:00 in organization timezone. OOO is OFF by default.
- Multiple disjoint intervals per day are supported.
- Overnight hours are represented as two ranges across adjacent days.
- A date-specific exception entirely overrides the weekly schedule for that date.
- Active weekly configuration must contain at least one opening interval. If corrupted configuration yields no future opening, return/report `NO_FUTURE_OPENING`; never invent a date.
- Calculations are timezone-aware and DST-safe.
- One continuous closed interval maps to one `closure_key`; a lunch break may therefore be a separate closure.
- OOO can be sent at most once per Case per closure period, is retry-safe, includes derived next opening when known and does not count as first human response.
- OOO templates allow only safe predefined variables and no executable HTML or arbitrary expression language.

## 14. Notifications and escalations

- No external notification destination/rule is enabled by default.
- Notification metadata may include Case ref, customer, provider/channel, SLA/state/age and a deep link. Full customer message body and attachment content are not sent to escalation channels by default.
- Business-intent deduplication key is `(sourceEventId, ruleId, destinationId)`.
- Transient delivery follows `Retry-After` plus exponential backoff/jitter, max 8 attempts/24h; permanent failures go to failure/DLQ state.
- DLQ/manual replay requires `ADMIN + manage_notifications` and may not duplicate an already successful delivery.
- Test notifications are marked TEST, audited, and create no Case/SLA/analytics business event.
- Pending SLA warning/checking reminder is suppressed if the Case is terminal before the first send; actual breach/too-long notification is still delivered once as a historical incident.
- Personal Snooze reminders are user-scoped/in-app, not broadcast to shared escalation channels by default.
- Before every external send/retry, the rule and destination enabled flags are rechecked. If disabled, the pending delivery becomes canceled/suppressed; re-enabling later does not resurrect it automatically.

## 15. Statistics and admin current work

- Global/team statistics: `ADMIN + view_global_statistics`.
- Every active USER may view only their own statistics without the global-statistics permission.
- `createdCases` uses Case creation date; each linked post-terminal Case counts as a new Case.
- `resolvedCases` counts only `RESOLVED`; `IGNORED` is separate.
- First-response duration uses business time creation -> first successful human `SENT`; no-response Cases are not averaged as zero and remain pending/breached.
- Resolution exposes gross elapsed and business/support time; main UI average is business time honoring waiting-pause policy.
- Per-user attribution: first response -> sender; normal Resolve -> resolving owner; Claim -> only real Claim action; admin assign/reassign is not a Claim.
- Force resolve is flagged `forced=true` and excluded by default from normal agent-performance resolution metrics.
- Duration analytics expose at least count, average, p50, p90, p95.
- Statistics default range: last 30 days; maximum interactive range: 366 days.
- No-data results are null/empty, never synthetic 0ms or 100%.

Admin current work:

- Lives in `/statistics` as ADMIN-only tab/section **Aktualna praca**; no separate top-level `/current-cases` navigation item.
- Backend: `GET /api/v1/admin/current-cases`.
- Includes all nonterminal statuses: `NEW`, `VERIFICATION`, `WAITING_FOR_CUSTOMER`, `PARTIALLY_IGNORED`.
- Read access requires ADMIN only, not `view_global_statistics`.
- Reassign/unassign still requires `ADMIN + reassign_cases`; force-resolve still requires `ADMIN + force_resolve`.

## 16. Integrations - common contract

- One active integration per `(provider, workspace_external_id)` in a deployment.
- Configuration status is separate from health: `CONFIGURING|ENABLED|DISABLED` vs `UNKNOWN|HEALTHY|DEGRADED|UNAVAILABLE`.
- `DISABLED` preserves historical data; inbound may be authenticated/deduped for technical accounting but creates no Case/Message/SLA; outbound is not delivered.
- Channel discovery does not hard-delete missing channels; it marks them inactive, and rediscovery reactivates the same identity.
- `channel.ignored=true` is scoped to that Channel record. Future inbound events for it are still authenticated and deduplicated, recorded with technical outcome `IGNORED_BY_CHANNEL`, but create **no Case, Message, SLA or read-state side effect**. Existing Cases/messages are never rewritten by toggling the flag. Because no Message is created, an ignored-channel inbound is not shown as ordinary conversation content in the inbox.
- Manual test-connection timeout is 10 seconds and may update health/sanitized error, never silently enable/disable integration.
- Provider callbacks and delivery operations must be idempotent and concurrency-safe.

## 17. Retention and legal hold

Per deployment defaults and configuration ranges:

- messages: 730 days, range 30-3650; purge content/PII while retaining a sanitized tombstone/reference where required for integrity.
- inbound provider events: 30 days, range 7-365; hard delete.
- audit events: 2555 days, range 365-3650; controlled hard delete.
- attachment binaries: 730 days, range 30-3650; hard-delete binary and retain only sanitized tombstone metadata when needed.
- backup retention: 35 days, range 7-90; data removed from runtime may remain only until natural backup expiry.

Legal hold is deployment-level operations configuration in v1. It pauses purge and is audited. Per-Case/per-user legal hold is outside v1. After restore, retention/purge must be re-applied before normal service resumes.

## 18. Product boundaries that require human approval to change

Agents must stop and request a human only for a real contract change, including:

- expanding v1 product scope;
- changing the fundamental workflow/state machine;
- adding/changing roles or the permission catalog;
- introducing a new security boundary;
- changing destructive data semantics outside the frozen retention contract;
- introducing a paid third-party service/customer financial commitment;
- using production credentials or executing high-risk production actions;
- pricing/customer commercial commitments;
- legal/compliance requirements not already frozen.

Everything else that is a technically equivalent implementation under this contract is delegated.
