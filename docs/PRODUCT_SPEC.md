# Unified Support Inbox - System Specification

**Version:** v1 frozen contract  
**Status:** Decision freeze complete  
**Audience:** Product Owner, support operations, developers, reviewers and autonomous agents

## 1. Product purpose and v1 boundary

Unified Support Inbox (USI) provides one support workspace for customer conversations arriving through Slack, Microsoft Teams and Telegram. It groups provider conversations into Cases, protects workflow from double ownership/duplicate events, tracks personal read state, measures SLA in business time, supports personal reminders, provides operational/admin oversight and preserves an audited history subject to retention.

E-mail as a support channel and AI functionality are outside v1. Each customer runs in a separate deployment/data boundary.

## 2. Users and administration

A `USER` can view all Cases in the deployment, Claim eligible unowned work, work Cases they own, Reply/Ask/Resolve when workflow permits, vote Ignore, use personal Snooze, maintain their own read state and view their own statistics.

An `ADMIN` has the same operational visibility plus granular permissions. The exact permission catalog is frozen: `manage_users`, `manage_integrations`, `manage_sla`, `manage_schedule`, `manage_notifications`, `view_global_statistics`, `reassign_cases`, `force_resolve`, `view_audit`.

The system must always retain at least one active ADMIN with `manage_users`. Self-promotion and self-grant of a missing permission are forbidden. A USER does not gain admin capability simply because a permission value is present.

## 3. Authentication and account lifecycle

Users authenticate with normalized lower-case e-mail and password. Sessions are server-side and use the secure HttpOnly `USI_SESSION` cookie with 12-hour idle timeout and CSRF protection. There is no remember-me in v1.

Invitations are one-time hashed high-entropy tokens valid for 24 hours. Password reset uses separate one-time hashed tokens valid for 30 minutes; successful reset invalidates all existing sessions.

Hard-delete of a user is allowed only when no historical data refers to the account. Otherwise normal administration uses Deactivate; privacy/legal erasure is a controlled retention/compliance process.

Presence is presentation-only: ONLINE means a recent active connection/heartbeat. Presence never decides permissions, Claim eligibility or assignment.

## 4. Customers, integrations and channels

Customer name is required but not globally unique. Optional `external_ref` is unique when present. A Customer cannot be deactivated while active monitored Channels still point to it.

Integration configuration separates **status** (`CONFIGURING`, `ENABLED`, `DISABLED`) from **health** (`UNKNOWN`, `HEALTHY`, `DEGRADED`, `UNAVAILABLE`). Disabling preserves history, stops normal outbound and prevents new Case/Message/SLA business content from new callbacks.

A Channel may be marked `ignored` without disabling the entire integration. This is prospective and per Channel: inbound still authenticates/deduplicates and records `IGNORED_BY_CHANNEL`, but creates no Case, Message, SLA or read-state side effect. Existing Case history is unchanged and ignored inbound content is not shown as an ordinary inbox message.

## 5. Inbox, search and ordering

Every active USER/ADMIN can see the common Case inbox. Ownership controls actions, not visibility. Views can include Wszystkie, SLA, Moje, Nieprzypisane and Nieodczytane.

Default ordering prioritizes SLA breach, then SLA warning, then unread NEW work, then remaining active work; within a bucket use nearest active deadline, latest activity and deterministic ID tie-break.

Search v1 covers Case reference, Customer, Channel and provider conversation/thread identifiers/display labels. Global full-text search over all message bodies is outside v1.

Each Case has a human reference such as `CASE-00001234` plus a technical UUID.

## 6. Canonical statuses

Exactly six statuses exist:

- `NEW` - active, unassigned work.
- `PARTIALLY_IGNORED` - unassigned work with Ignore points below threshold.
- `VERIFICATION` - active work owned by exactly one user.
- `WAITING_FOR_CUSTOMER` - Ask Customer was successfully accepted by provider; Case is unassigned while waiting.
- `IGNORED` - terminal, no owner.
- `RESOLVED` - terminal, preserving previous owner when one existed.

Terminal Cases never reopen. A later customer message creates exactly one linked new Case generation.

## 7. Claim

Claim is explicit; there is no auto-assignment. Successful Claim atomically moves eligible NEW/PARTIALLY_IGNORED work to VERIFICATION, makes the actor the sole owner, deactivates active partial Ignore votes while preserving history, clears stale Snoozes, pauses unclaimed SLA and starts/resumes in-progress SLA.

A user who ever successfully voted Ignore in that Case is permanently ineligible to Claim it.

## 8. Reply and delivery

Normal Reply is current-owner-only in VERIFICATION. Sending creates one durable logical SUPPORT Message and asynchronous provider-delivery work; provider HTTP never runs in the business DB transaction.

`SENT` means provider accepted the message. `DELIVERED` is used only when provider has a trustworthy delivery acknowledgement. Transient failures retry the same Message with `Retry-After`, exponential backoff and jitter, max 8 attempts/24h. Permanent failures become `FAILED`. Retry never creates a second logical customer-facing Message.

The first human Message completing provider `SENT` completes first-response SLA; local queued/PENDING state does not.

## 9. Ignore voting

Ignore uses a fixed v1 threshold of 2 points. Default effective user weight is 1; `ADMIN + manage_users` may grant temporary weight 2.

Each vote stores the effective weight as an immutable snapshot. One active vote per user/Case. Repeating Ignore while that active vote exists is idempotent and does not add points twice.

Below threshold -> PARTIALLY_IGNORED; threshold reached -> terminal IGNORED. New customer message before threshold or Claim by another user deactivates active partial votes but preserves history. A user may vote again after reset, but anyone who ever cast a successful Ignore vote in the Case remains permanently blocked from Claim and Reply in that Case.

## 10. Ask Customer

Ask Customer is current-owner-only from VERIFICATION and requires an outgoing human support Message.

The Case **stays VERIFICATION with the same owner while delivery is queued/retried**. Only when provider accepts the Ask Message (`SENT`) does USI atomically move it to WAITING_FOR_CUSTOMER, clear owner and set `waiting_until`.

Permanent delivery failure leaves the Case with the owner in VERIFICATION. Default wait is 24 hours; allowed override is 1 hour to 30 days.

Customer reply before timeout returns Case to NEW, marks it unread for eligible users, wakes Snoozes and resumes applicable timers with accumulated time preserved. Timeout also returns to NEW and records `WAITING_TIMEOUT`; v1 does not send an automatic follow-up message.

## 11. Resolve, force-resolve and assignment

Normal Resolve is current-owner-only from VERIFICATION. Resolution category is optional; if supplied it is one of `SOLVED`, `NO_ACTION_REQUIRED`, `DUPLICATE`, `OTHER` and is not free text.

Resolution ends SLA timers, clears Snoozes and records activity/audit. `ADMIN + force_resolve` may resolve any nonterminal Case. If the Case was unowned, the administrator does not become owner; force-resolve is tagged `forced=true` for analytics/audit.

`ADMIN + reassign_cases` may assign eligible NEW/PARTIALLY_IGNORED work, reassign VERIFICATION or unassign VERIFICATION back to NEW. Target must be active and not a lifetime Ignore voter. Reassignment does not reset in-progress SLA; administrative assignment is not counted as the target user's Claim metric.

## 12. Linked Cases after terminal outcome

RESOLVED/IGNORED are immutable. The first later customer message for the same provider conversation identity creates exactly one linked NEW successor. Concurrency protection ensures a burst cannot create multiple successor Cases; subsequent messages route to the same new generation.

## 13. Personal Snooze

Snooze is per-user, not a workflow status. Any active user who can view a nonterminal Case may Snooze it for 5 minutes to 30 days. Snooze marks the Case read for that user but changes neither owner/status nor SLA.

It ends when due, manually canceled, on a new customer message, terminal closure, or a new Claim that makes the reminder stale.

## 14. Read/unread

Read state is independent per user and sparse. New users do not inherit the historical database as unread.

A new customer Message or customer edit makes the Case unread for all active eligible viewers. Human outgoing, system/audit-only and status-only changes do not.

Opening a Case is not enough to mark it read; the frontend advances the stable server read cursor only after content was actually rendered/seen and acknowledged. Batch resolved-read applies only to the current user. Personal read/Snooze state is private; v1 does not expose "who read this Case".

## 15. Conversation, Activity and attachments

Conversation-visible authorship is CUSTOMER, SUPPORT or SYSTEM only when genuinely customer-facing (for example OOO). Claim/Resolve/Reassign/internal provider events belong in Activity/audit, not fake chat messages.

Body formats are safe PLAIN_TEXT/Markdown; raw executable provider HTML is never canonical.

Attachment limits are 25 MiB/file, 10 files and 50 MiB/message, with lower provider limit winning. Uploads stream through backend/object storage. Common images, PDF, text/CSV, OOXML and ZIP are baseline allowed classes; executables/scripts/active HTML are blocked. Scanning states are PENDING/CLEAN/INFECTED/ERROR; only CLEAN is normally usable. Default self-hosted scanner is ClamAV. There is no "allow anyway" path for infected/error files.

## 16. SLA

Three core measurements use business time:

- First human response: warning 12m, breach 15m.
- Unclaimed: warning 5m, breach 15m.
- In progress: warning 30m, breach 60m.

`pause_waiting=true` by default. A Case snapshots SLA policy plus business-hours schedule/timezone version at creation, so later configuration changes do not retroactively move its deadlines.

First response starts at durable USI customer intake and completes on first human SUPPORT/Ask `SENT`. Unclaimed accumulates in NEW/PARTIALLY_IGNORED; in-progress accumulates in VERIFICATION. Pause/resume preserves accumulated business time. Reassign never resets in-progress time. Warning/breach is monotonic per SLA type; terminal states stop timers.

## 17. Business hours and OOO

One IANA organization timezone per deployment. Bootstrap default is UTC; production explicitly configures intended timezone. Default schedule is Mon-Fri 09:00-17:00, OOO OFF.

Multiple intervals/day, date exceptions and DST-safe calculation are supported. A date exception overrides weekly schedule. Active configuration must include at least one weekly opening; corrupted/no-future-opening state reports `NO_FUTURE_OPENING` and never invents a date.

OOO is sent at most once per Case per continuous closure period, is retry-safe and does not count as first human response. Templates allow only predefined safe variables.

## 18. Notifications and escalations

No external notification rule/destination is active by default. Escalations contain operational metadata/deep links, not full customer message bodies or attachment content by default.

Business dedup key is `(sourceEventId, ruleId, destinationId)`. Transient delivery retries max 8/24h. DLQ replay requires `ADMIN + manage_notifications`.

If a Case becomes terminal before a pending warning's first send, the warning is suppressed. A real breach/too-long event remains a historical incident and is sent once. Before every send/retry, enabled state of rule/destination is rechecked; disabling cancels/suppresses pending work, and re-enabling does not resurrect old canceled notifications.

## 19. Realtime

WebSocket/STOMP is a synchronization signal, not source of truth. PostgreSQL remains authoritative. Heartbeat is 10s; about 30s without traffic means dead connection. Reconnect uses roughly 1/2/5/10/30s + jitter followed by bounded refetch/resync.

Events are at-least-once and duplicate/out-of-order safe. Personal unread/Snooze events use backend-authorized user-scoped destinations.

## 20. Statistics and admin current work

Every active USER can see own statistics. Team/global statistics require `ADMIN + view_global_statistics`.

Created, resolved and ignored metrics remain distinct; IGNORED does not inflate resolution rate. Duration APIs expose count, average, p50, p90 and p95. Default range is 30 days, interactive max 366 days. No-data is null/empty rather than fake zero/success.

Inside `/statistics`, ADMIN has **Aktualna praca**, backed by `GET /api/v1/admin/current-cases`. It includes all nonterminal statuses and read access requires ADMIN only, not `view_global_statistics`. Reassign/unassign and force-resolve remain separately permission-gated.

## 21. Audit and retention

Audit is append-only for normal application DB role and stores sanitized actor/action/entity/Case/correlation/time metadata, never raw message bodies, attachment content, secrets, passwords, cookies or tokens. Hash chaining provides tamper evidence. Search/export requires `ADMIN + view_audit`; large CSV/NDJSON exports expire after 24 hours.

Retention defaults: messages 730d, inbound provider events 30d, audit 2555d, attachment binaries 730d, backups 35d. Per-class purge semantics preserve necessary tombstone/reference integrity. Deployment-level legal hold can pause purge. After restore, retention is reapplied before normal service resumes.

## 22. Provider behavior

### Slack
V1: public/private/Slack Connect channels accessible to app; no DM/group DM. Root thread is Case grouping identity. Unmapped valid channel creates no Case/SLA. Edits update existing Message + unread; delete creates marker. HMAC over raw body with 5-minute replay window. No full historical import; bounded recovery default24h/max7d. Lightweight health check about every15m. Agent signature OFF by default.

### Microsoft Teams
V1: standard channels + group chats; private/shared channels out. Use RSC least privilege. Standard channel root/replies = Case; group chat = one active Case/chat. No full history import; bounded recovery where API supports it. Normal outbound uses supported app/bot path.

### Telegram
V1: private chats, groups/supergroups and forum topics; broadcast channels out. Topic = one active Case; without topics one active Case/chat. Staging/prod webhook secret-token validation required. No historical import. Own bot messages never become CUSTOMER. If provider requires multiple delivery parts, USI still stores one logical Message.

## 23. Reliability and release

Target monthly availability: 99.9% excluding agreed maintenance. Typical API p95 <500ms / p99 <1.5s excluding provider latency; local workflow p95 <300ms; callback durable ACK p95 <500ms; realtime p95 <1s; 30-day analytics p95 <2s.

Acceptance baseline: 200 concurrent logged-in users and WebSockets, 5k Cases/day, burst 100 inbound events/sec for 60s without data loss and deterministic drain, and 24h soak without growing leaks.

PostgreSQL RPO <=5m via WAL/PITR; attachment RPO <=1h initial v1; full deployment RTO <=2h. Weekly restore smoke and quarterly DR drill prove backups.

Staging is isolated and visibly marked. Initial pilot is about 5-10 support users for at least five business days. Production cutover requires protected human approval and is followed by 72h hypercare.

## 24. Agent autonomy boundary

Agents solve normal implementation choices without Product Owner interruption: naming, packages/classes, indexes implied by query/race requirements, equivalent locking, retries, tests, local refactors, compatible vendor SDK details and Git conflict resolution.

They stop only for a real frozen-contract change, production authority/credentials, new legal/compliance requirement, paid/commercial commitment or another explicitly protected human-decision boundary.
