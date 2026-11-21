# Unified Support Inbox - Decision Registry

**Status:** FROZEN v1 decisions

## Owner override — 2026-11-19 / provider deletion

The owner requested retained information for deleted messages during the Teams audit. A trustworthy provider delete now preserves the last accepted text, author, timestamps and attachments, with a muted explicit deletion marker. Later edits cannot resurrect it. Normal Case authorization, CLEAN-only attachment access, retention expiry and legal erasure still apply. Previously erased text is not reconstructed automatically. Machine decision: `DEC-PROVIDER-DELETED-CONTENT-001`; source: `OWNER_REQUEST_2026_11_19_TEAMS_AUDIT`.

This is the human-readable index. `decision-registry.yaml` is the machine-readable companion used by agents/orchestrators.

## Interpretation

- `FROZEN` means implementation follows the decision unless a later explicit contract override exists.
- Current-ticket later overrides take precedence over older descriptions/comments.
- A normal technical implementation choice does not become a product blocker merely because Jira omitted a class/index/library detail.

## Decision index

| ID | Area | Frozen decision | Source |
|---|---|---|---|
| DEC-SCOPE-001 | v1 scope | Slack + Teams + Telegram; e-mail/AI out | USI-6 / E00 |
| DEC-TENANCY-001 | tenancy | one customer per deployment, no runtime tenant_id | USI-40 |
| DEC-AUTH-001 | auth | local e-mail/password, server sessions, CSRF | USI-33, E04 |
| DEC-AUTH-002 | session | USI_SESSION, 12h idle, no remember-me | E04 pre-flight |
| DEC-RBAC-001 | roles | only USER and ADMIN | USI-32 |
| DEC-RBAC-002 | permissions | exact nine-code catalog | USI-32 |
| DEC-WF-001 | statuses | six canonical Case statuses | USI-35 |
| DEC-WF-002 | ownership | exactly one owner only in VERIFICATION | USI-35 |
| DEC-WF-003 | terminal | RESOLVED/IGNORED never reopen; linked successor | USI-35 / E07 |
| DEC-WF-004 | claim | explicit only; no auto assignment | USI-35 / E09 |
| DEC-IGNORE-001 | threshold | fixed 2 in v1 | USI-36 / E09 |
| DEC-IGNORE-002 | weight | immutable vote snapshot; default1/temp2 | USI-36 |
| DEC-IGNORE-003 | lifetime | voter can never Claim/Reply same Case | USI-36 |
| DEC-ASK-001 | ask transition | WAITING only after outgoing Ask is SENT | E09 override |
| DEC-ASK-002 | waiting | default24h, range1h-30d, timeout->NEW no follow-up | USI-37 / E09 |
| DEC-RESOLVE-001 | normal resolve | current owner from VERIFICATION | USI-35 |
| DEC-RESOLVE-002 | force resolve | ADMIN+force_resolve, any nonterminal | USI-35 |
| DEC-SNOOZE-001 | snooze | per-user, any visible nonterminal, 5m-30d | USI-37 / E09 |
| DEC-READ-001 | unread | per-user sparse state; customer inbound makes eligible users unread | USI-37 / E10 |
| DEC-MSG-001 | message body | safe plain text/Markdown; no raw executable HTML | E08 |
| DEC-MSG-002 | delivery | SENT=provider accepted; retry 8/24h | E08 |
| DEC-ATT-001 | attachment limits | 25MiB/file, 10 files, 50MiB/message; public reply accepts text and/or CLEAN files; Ask Customer requires text | E08 |
| DEC-ATT-002 | scan | only CLEAN usable; ClamAV default scanner | E08 / E20 |
| DEC-SLA-001 | response clocks and resolution time | FRT 15m from provider time, NRT 30m from first durable follow-up, business warning at 25% remaining, two TTR results, legacy aging retained | E13 + owner approval for response SLA |
| DEC-SLA-002 | snapshot | Case snapshots SLA policy + schedule/timezone | E13 |
| DEC-SCHED-001 | business hours | default Mon-Fri09-17; OOO off; DST aware | E14 |
| DEC-SCHED-002 | invalid schedule | weekly opening required; NO_FUTURE_OPENING fallback | final freeze |
| DEC-NOTIFY-001 | defaults | no external rule/destination active by default | E15 |
| DEC-NOTIFY-002 | retry/dedup | sourceEvent+rule+destination, 8/24h | E15 |
| DEC-STATS-001 | access | own stats for USER; global requires ADMIN+view_global_statistics | E16 |
| DEC-ADMIN-001 | current work | /statistics -> Aktualna praca; ADMIN-only read | USI-39 |
| DEC-CHANNEL-IGNORE-001 | ignored channel | future inbound -> IGNORED_BY_CHANNEL; no Case/Message/SLA/read-state | USI-80 |
| DEC-AUDIT-001 | audit | append-only, sanitized metadata, hash-chain evidence | E17 |
| DEC-SLACK-001 | scope | public/private/Connect, no DM/groupDM | E12 |
| DEC-TEAMS-001 | scope | standard channels + group chats; no private/shared | E18 |
| DEC-TG-001 | scope | private/group/supergroup/topics; no broadcast channels | E19 |
| DEC-SEC-001 | secrets | no prod secrets to browser/repo/Codex/CI | E20 |
| DEC-OPS-001 | hosting | dedicated EU Linux VM, no K8s baseline | E22 |
| DEC-OPS-002 | deployment | staging may auto; prod needs human approval | E22/E25 |
| DEC-DR-001 | RPO/RTO | DB<=5m, attachment<=1h, deployment RTO<=2h | E23/final freeze |
| DEC-PERF-001 | load | 200 concurrent, 5k cases/day, 100 events/s burst | E24 |
| DEC-RELEASE-001 | pilot | 5-10 users, >=5 business days, stop criteria | E25 |

## Non-blocking technical resolutions

These are implementation directions already implied by Jira and architecture and are **not** reasons to ask the Product Owner again:

- **Case view model:** API projections map through one explicit DTO -> stable frontend domain/view mapper. Component fixtures are not a second backend contract (`USI-88`).
- **Canonical user identity:** backend user ID is canonical. `User` and `AdministrationUser` frontend shapes are projections of the same identity; do not synchronize identities merely by e-mail when canonical ID exists.
- **Analytics dimensions:** reporting categories may be derived from canonical data but never become additional `CaseStatus` values.
- **Workflow UI:** accepted Claim/Ignore/Ask/Resolve/Snooze/admin actions use server-calculated action availability and stable problem codes; UI does not implement a second state machine (`USI-113`).

## Human-decision boundary

A human decision is required only when a proposed change alters the frozen contract, including scope, workflow, roles/permissions, security boundary, destructive retention semantics, paid/commercial commitments, production authority, pricing/customer commitments, or new legal/compliance obligations.


Slack intake override (user request, 2026-11-09): discover authorized bot channel membership automatically. Active supported Slack channels without a customer mapping create visible Cases with customer NULL, labeled "Nie przypisano". Preserve existing customer mappings and ignored flags; never guess a customer. See INTEGRATIONS.md section 2.4.

## Owner interface extension, 2026-11-10

- DEC-UX-I18N-001: English default and optional Polish interface, immediate switching, account-scoped browser preference, structured catalogs, unchanged user/provider content and technical enums.
- DEC-UX-BULK-001: explicit bounded selection, per-Case permissions/workflow, confirmation and durable partial results for existing Claim/Assign/Resolve/Ignore; normal Resolve never implies Force Resolve.
- DEC-DIRECT-MESSAGES-001: One-to-one internal text messages between active users. Access and realtime hints are participant-only, including for administrators. See [DIRECT_MESSAGES.md](DIRECT_MESSAGES.md).
- DEC-UX-PRESENTATION-001: gray resolved indicators, accessible button feedback, Enter/Shift+Enter composer, SLA shortcut without arrow, no Customer/Channel inbox filter choices, wrapping long conversation messages.

The grouped owner requests of 2026-11-10 and 2026-11-12 define the interface and bulk decisions. The detailed contract is [UX_BULK_I18N.md](UX_BULK_I18N.md).

## Owner Case conversation extension, 2026-11-15

- DEC-CASE-INTERNAL-NOTES-001: Case-scoped internal text notes use existing Reply eligibility for authorship and Case read access for visibility. Notes have a separate persistence and realtime path from customer delivery. See [Product Contract](PRODUCT_CONTRACT.md#9-messages-and-delivery).

## Simplified Case workflow projection

- DEC-CASE-WORKFLOW-PROJECTION-001: four workflow labels with separate Ignore votes and terminal reason. The unassigned work queue excludes waiting, and Archive is an explicit history filter. Existing v1 writes remain compatible. See [Product Contract](PRODUCT_CONTRACT.md#4-canonical-case-state-machine).

- DEC-CASE-WORKFLOW-COMMANDS-001: administrative undo applies to ordinary and forced resolution. Public replies enter owned waiting after delivery, while Ask Customer enters unassigned waiting. The shared provider-context lock protects active generation uniqueness.

- TECH-TEAMS-SELECTED-FILES-001 (owner approval, 2026-11-19): the sandbox bot receives Graph application `Sites.Selected` and `write` only on USISandbox. A separate transient administrator session assigns the site grant; the bot receives no tenant-wide file access. See [Integration attachment boundaries](INTEGRATIONS.md#teams-attachment-transfer-owner-approved-2026-11-19).

## Owner history recovery extension, 2026-11-20

- DEC-PROVIDER-HISTORY-RECOVERY-001: opt-in Slack and Teams manual from-date/all-history recovery, independent durable automatic schedules, old-thread replies, permanent provenance and FRT/NRT from durable addition. This replaces the older seven-day Slack-only recovery bound. See [History recovery](HISTORY_RECOVERY.md) and [Product Contract](PRODUCT_CONTRACT.md#owner-approved-history-recovery-2026-11-20).

## Owner provider onboarding extension, 2026-11-20

- DEC-PROVIDER-ADMIN-ONBOARDING-001: admin-managed Slack/Teams connections, per-connection discovery, instance-specific bot/callback, one selected Teams team verified before persistence, and a new team-only package with message/channel-list RSC. Supersedes the earlier group-chat intake profile while preserving historical data. See [provider onboarding](PROVIDER_ONBOARDING.md).
