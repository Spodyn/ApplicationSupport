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

The legacy storage and v1 mutation contracts use the following Case statuses.

`NEW`, `VERIFICATION`, `WAITING_FOR_CUSTOMER`, `PARTIALLY_IGNORED`, `IGNORED`, `RESOLVED`.

Core invariants:

- `VERIFICATION` requires an active owner. `WAITING_FOR_CUSTOMER` retains the owner after a delivered public reply.
- `NEW` and `PARTIALLY_IGNORED` are unassigned. Historical waiting rows can be unassigned.
- `IGNORED` is terminal and has no owner.
- `RESOLVED` is terminal and preserves the last owner if there was one; a force-resolve from an unassigned state leaves owner `null`.
- A customer message after terminal closure uses the active generation or creates one linked Case. An explicit administrative undo may reopen `RESOLVED`. Ignore freezes that generation.
- Workflow state changes are performed through dedicated commands, never a generic `PATCH status`.

The v1 Case projection exposes `NEW`, `OPEN`, `WAITING_ON_CUSTOMER` and `RESOLVED`. Existing v1 commands and the legacy `status` field retain the operational representation above for compatibility. `VERIFICATION` projects as `OPEN`, `WAITING_FOR_CUSTOMER` as `WAITING_ON_CUSTOMER` (displayed as **ASKED** in English and **Oczekuje na klienta** in Polish), and `PARTIALLY_IGNORED` as `NEW` with its vote score. Terminal Ignore projects as `RESOLVED` and has `closureReason: IGNORED`. Simplified inbox views exclude waiting from Unassigned and provide an Archive filter. Statistics group by the simplified state and preserve distinct resolved and ignored totals.

Human-readable Case references are immutable sequential references such as `CASE-00000001`; UUID remains the technical primary identifier.

## 5. Claim, reply, assignment and resolution

- There is no automatic assignment in v1.
- A successful Claim moves an eligible unowned active Case to `VERIFICATION` and sets the current user as owner. An eligible user can explicitly claim a historical unassigned waiting Case from its detail, which clears its deadline and resumes any waiting pause.
- Public replies and internal notes are available to the eligible current owner in `VERIFICATION` and `WAITING_FOR_CUSTOMER`.
- A normal Resolve is available only to the current owner in `VERIFICATION` and `WAITING_FOR_CUSTOMER`.
- `resolution_category` is optional. If provided in v1 it is a controlled code from `SOLVED`, `NO_ACTION_REQUIRED`, `DUPLICATE`, `OTHER`; it is not free text.
- `ADMIN + reassign_cases` may assign an eligible user to `NEW` or `PARTIALLY_IGNORED`, or reassign `VERIFICATION`; the target user must be active and may not be a lifetime ignore voter for that Case. Result: `VERIFICATION` with the target owner.
- Admin unassign moves `VERIFICATION` to `NEW`, clears the owner and retains ownership history in audit.
- `ADMIN + force_resolve` may force-resolve any nonterminal status. The administrator is the audit actor and does not become owner merely by force-resolving.
- `ADMIN + force_resolve` may undo either ordinary or forced resolution. An existing active Case in the same provider context is returned as the target, with the original still archived. Otherwise the original Case returns to `NEW` without an owner, resumes the unclaimed clock from the undo and preserves the first response record and prior SLA in its audit history. The original resolution and undo are auditable.

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

## 7. Public replies and waiting

- Provider-confirmed public delivery enters owned WAITING_FOR_CUSTOMER from VERIFICATION. The Case version, ownership and intervening customer activity determine whether confirmation is current.
- A current confirmation begins or restarts the customer-follow-up deadline, default 24 elapsed hours. Further successful replies preserve the status and owner. Queued messages, failures and stale confirmations leave the active deadline intact.
- A new durable customer message replaces the follow-up deadline with a support-response deadline. Owned waiting returns to OPEN with its owner. Historical unassigned waiting returns to NEW. The existing pause and resume bookkeeping continues to feed historical measures.
- Follow-up expiry generates an internal reminder and leaves the Case waiting. An eligible agent can still explicitly claim historical unassigned waiting.
- The deprecated POST /api/v1/cases/{caseId}/ask-customer queues a public reply using its compatibility request and idempotency scope. New replies use the configured waiting duration. Previously queued legacy intents can retain their persisted duration.

## 8. Snooze and read/unread

Snooze:

- Per-user, never a Case status.
- Any active USER/ADMIN who can view a nonterminal Case may snooze it; ownership is not required.
- Range: 5 minutes to 30 days, using presets or a custom future time.
- Snooze marks the Case read for that user, does not alter owner/status, and does not pause SLA.
- Due time or manual cancel ends it. The viewer sees their own Snooze deadlines and recorded end events in Case history.
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
- A Case may also contain a text-only internal note. The current owner can add a note in `VERIFICATION` or `WAITING_FOR_CUSTOMER` under the existing Reply eligibility rules. An eligible USER or ADMIN with Case read access can read notes, including in terminal Case history.
- A note records the authenticated author, their display name at creation and the server timestamp. It is stored within USI and does not enter provider delivery, public previews, customer unread state, waiting transitions or FRT/NRT. The dedicated command uses the standard Case-scoped idempotency window.
- Claim, resolve, reassign and internal provider events belong in Activity/audit, not fake chat messages.
- Supported body formats are `PLAIN_TEXT` and `MARKDOWN`. Provider rich content is normalized to safe text/Markdown. Raw executable HTML is never canonical content.
- Owner override, 2026-11-19: a trustworthy provider deletion marks the existing Message deleted without erasing its last accepted text, author, timestamps or attachment metadata/bytes. The feed presents a muted deleted marker alongside retained information; CLEAN attachment access remains subject to normal Case authorization. Stale edits cannot resurrect it. This does not exempt data from retention/purge or legal erasure. Previously erased text is not reconstructed automatically.
- A send command creates a durable local Message plus transactional outbox work; the external provider call is asynchronous.
- `SENT` means the provider accepted the message successfully and supplied its success/provider identifier where applicable.
- `DELIVERED` is used only when the provider supplies a trustworthy delivery acknowledgement. Otherwise `SENT` is the final successful state.
- Transient delivery errors: exponential backoff + jitter, honor `Retry-After`, maximum 8 attempts over 24 hours.
- Permanent errors become `FAILED` without endless automatic retry.
- Retry never creates a second logical customer-facing Message.
- Manual retry is available only for potentially repairable failures and reuses the same Message.

## 10. Attachments

- A public Case reply requires nonblank text, at least one `CLEAN` attachment, or both.
- Max 25 MiB per file, 10 files and 50 MiB total per logical message. A stricter provider limit wins.
- Case attachments support PNG, JPEG, GIF, WebP, AVIF, BMP, TIFF, HEIC/HEIF, PDF, plain text/CSV and validated Office OOXML (`docx`, `xlsx`, `pptx`). General archives, active markup and executable formats are blocked.
- The Case feed can display verified PNG, JPEG, GIF, WebP, AVIF and BMP raster images inline. TIFF and HEIC/HEIF use a download card. The preview and download endpoints require the same Case access and a `CLEAN` scan. Unsent drafts require current Case ownership.
- Executables, scripts and active HTML are blocked by default.
- Both declared and detected MIME are validated.
- Uploads are backend-managed streaming/two-phase uploads; the full file must not be buffered in application memory.
- Pending/orphan uploads are cleaned after 24 hours.
- Normal downloads go through an authorized backend endpoint. A signed URL may be used only as a later optimization after Case authorization and with maximum 5-minute TTL.
- Scan states include `PENDING`, `SCANNING`, `CLEAN`, `INFECTED` and `ERROR`. Only `CLEAN` may be sent, previewed or downloaded. There is no "allow anyway" path for `ERROR` or `INFECTED`.
- Filename is display metadata, max 255 characters; object storage keys are internal/random and never derived directly from filename.

## 11. Case visibility, search and ordering

- Every active USER and ADMIN can view every Case in the deployment. Ownership limits actions, not read access.
- Search v1 covers Case reference, Customer name, Channel name, provider conversation/thread identifiers and display labels.
- Full-text search over all message bodies is outside v1.
- Default ordering prioritizes: SLA breached > SLA warning > unread NEW > remaining active work. Follow-ups due share the warning priority and use a distinct badge. Within a bucket use nearest active operational deadline, then `last_activity_at DESC`, then `id` as deterministic tie-breaker.

## 12. SLA and business time

Defaults, in business time:

- First human response (FRT): 15 business minutes by default.
- Next human response (NRT): 30 business minutes by default.
- Each response clock warns when 25% of its business target remains (11m 15s into the default FRT and 22m 30s into the default NRT).
- Unclaimed: warning 5 minutes, breach 15 minutes.
- In-progress: warning 30 minutes, breach 60 minutes.
- `pause_waiting=true` by default.

Rules:

- One active SLA policy per deployment, configured by `ADMIN + manage_sla`.
- A Case snapshots the SLA policy and business-hours schedule/timezone version at creation. Later configuration changes apply prospectively and do not rewrite historic Case deadlines.
- Historical FRT starts at the original provider timestamp and completes with the first provider-confirmed human reply. Historical NRT starts with the first durable customer message in each response cycle. Those anchors, completions, terminal outcomes and recorded breaches remain intact.
- The operational FRT starts with the first customer message. Every subsequent durable customer message resets the operational deadline, using FRT until the first human response and NRT thereafter. The first FRT uses the provider timestamp, bounded by durable intake. Subsequent starts use durable intake order and time. Recovered messages use durable ingestion time throughout.
- Successful, current provider delivery starts a wall-clock customer follow-up. The active SLA policy configures its elapsed minutes. Response deadlines use the Case policy and business calendar snapshot, including holidays and split overnight hours. Warnings start with 25% of the business target remaining.
- The inbox, SLA filter, count, conversation and details use the operational deadline. Due support responses are breaches. Due customer follow-ups appear in attention with their own state and never create a support breach. The server supplies the observation instant, signed remaining time and clipped UTC business windows for a shared client countdown.
- Each expired operational deadline generates one durable internal reminder per recipient. Route to the active eligible owner, or to eligible support users when the Case has no eligible owner. Delivery to the inbox notification bell uses authenticated, account-scoped reads. Public Case messages are never generated by deadline expiry.
- TTR reports elapsed time from Case creation to resolution and business working time minus accepted waiting pauses and closed periods between resolution and reopening. Historical records without a verifiable calendar preserve wall time and return an unknown working time.
- Unclaimed accumulates business time while active and unowned in `NEW`/`PARTIALLY_IGNORED`; Claim pauses it; later unassign/return from waiting resumes the accumulated time.
- In-progress accumulates business time in `VERIFICATION`; reassign does not reset it; unassign/WAITING pauses; later Claim resumes accumulated time.
- Operational deadline history retains earlier breaches when another message restarts the active countdown. Legacy aging metrics keep their existing reporting and notifications.
- Terminal states end active SLA timers.
- Terminal Cases have a neutral presentation. Prior breaches remain visible in historical details and reports.

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

### Response SLA on the Statistics dashboard

- The first-response chart, breach count, and customer attainment use the same Case-creation cohort and original FRT deadlines. A successful first reply at or before its deadline is on time. A late reply, an unanswered original deadline that has elapsed, or closure without a reply at or after the deadline is a missed deadline. Pending deadlines, unresolved measurement data, and closure before the deadline are excluded from the attainment denominator.
- Historical response breaches count unique Cases with recorded FRT or NRT breaches, including operational support breaches preserved after a deadline restart. Cases with a recorded NRT breach are a subset and count once even across several cycles. Legacy unclaimed/in-progress classifications are not response outcomes.
- Currently overdue support responses and customer follow-ups due show separate active Case counts at the latest server observation. Both respect the selected Case-creation cohort and filters. Only historical cohort metrics are compared to the preceding range of the same length. Customer follow-ups never count as support breaches.
- Dashboard reporting dates use the organization's active IANA timezone. Presets and comparison ranges use reporting dates, and SLA calculations continue to honor each Case's original policy snapshot. The owner filter selects the current Case owner; user first-response results are attributed to the confirmed reply author within the visible cohort. Unanswered Cases and replies without an identifiable author are not attributed to an individual.

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
- Slack intake override approved by the user on 2026-11-09: reconcile authorized bot channel membership automatically; active supported Slack channels without a Customer mapping create visible Cases with a null customer ("Nie przypisano"). Never guess a customer.
- Channel discovery does not hard-delete missing channels; it marks them inactive, and rediscovery reactivates the same identity.
- `channel.ignored=true` is scoped to that Channel record. Future inbound events for it are still authenticated and deduplicated, recorded with technical outcome `IGNORED_BY_CHANNEL`, but create **no Case, Message, SLA or read-state side effect**. Existing Cases/messages are never rewritten by toggling the flag. Because no Message is created, an ignored-channel inbound is not shown as ordinary conversation content in the inbox.
- Manual test-connection timeout is 10 seconds and may update health/sanitized error, never silently enable/disable integration.
- Provider callbacks and delivery operations must be idempotent and concurrency-safe.

### Owner-approved history recovery (2026-11-20)

Slack and Teams integrations offer opt-in recovery in Settings → Integrations. Existing integrations default to disabled. ADMIN with `manage_integrations` may independently enable automatic recovery and configure its interval/history window, or run manual recovery from a selected sent date or from all API-accessible history. This explicitly supersedes the former bounded Slack-only recovery restriction; connecting an integration still does not automatically import history.

Both modes scan active monitored contexts under existing access/mapping rules, including replies sent inside the requested window to older roots. Older roots may be read as context without creating out-of-window business Messages. Results enter the existing Case/unread/attachment pipeline as pages arrive. Provider identity is identical to live intake: a concurrent webhook, rescan or restarted task creates one Message, one Case association and one applicable SLA effect. Already received normal Messages retain provenance and SLA.

Persist `manual|automatic` recovery source, original provider sent time and durable addition time. Display the orange “Pominięta wiadomość · odzyskana przez resync” label. For both modes, new applicable FRT/NRT clocks start at durable insertion into the Case, using existing business hours and cycle rules. Duplicate imports preserve active deadlines and historical cycles. Original sent time remains the conversation timestamp.

Backend scheduling/checkpoints survive restarts. An overdue schedule computes the current configured window; an in-flight run resumes its captured range, then the overdue current-window run may start. Per-channel progress includes scanned/added/pending counts and sanitized reasons for incomplete recovery. API permission failures are visible, never silently reported as complete. No production/sandbox credentials or permission grants are authorized by this product change.

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

## Owner-authorized interface extension (2026-11-10, updated 2026-11-12)

Polish/English interface preferences, bounded bulk Claim/Assign/Resolve/Ignore using existing individual rules, and the eight UX corrections are specified in [UX_BULK_I18N.md](UX_BULK_I18N.md). English is the default. Authenticated users can select Polish independently on each browser. This explicit owner extension changes no CaseStatus, permission, individual workflow rule, provider scope or production authority.

## Owner-authorized private messaging extension (2026-11-12)

Active users can exchange private one-to-one text messages with other active users in the same deployment. Only the two participants can read the conversation or receive its realtime hints. Administrators have the same participant-only access. Account deactivation preserves history for the other participant. Direct Messages are internal to USI and use no provider channels or Case workflow. See [DIRECT_MESSAGES.md](DIRECT_MESSAGES.md).

## Owner-approved provider onboarding (2026-11-20)

Settings → Integrations supports Slack and Teams create/edit, public callback copy, readiness, test and explicit enable. Provider credentials stay in the server secret store. Automatic channel discovery is on for new connections; existing installations preserve their effective setting and individual channel mappings/ignored choices.

Each instance selects exactly one Teams team. Its Graph GUID (`groupId` from a team link) is the authorization boundary before durable inbound activity insertion. The opaque Bot Framework team ID is resolved to `aadGroupId` using authenticated team details when necessary. Tenant membership alone is insufficient. New installation packages use team scope only and RSC `ChannelMessage.Read.Group` plus `ChannelSettings.Read.Group`. This owner request supersedes the earlier group-chat intake scope for this deployment profile; historical conversation data and the old development package remain available.

Initial and periodic Graph reconciliation plus `channelCreated` hints discover standard channels of that team. New channels inherit the chosen default customer and monitoring; existing channel settings survive discovery. Slack membership discovery supports an unassigned customer. A first message preceding discovery is durably retried through the normal pipeline. See [provider onboarding](PROVIDER_ONBOARDING.md).
