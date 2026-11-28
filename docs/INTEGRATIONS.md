# Unified Support Inbox - Provider Integrations

**Status:** FROZEN for v1

USI uses a provider-neutral core. Provider-specific details belong in adapters; Case workflow, Message semantics, SLA, unread and permissions remain backend-owned product rules.

## 1. Common integration model

### 1.1 Integration identity

- One active integration per `(provider, workspace_external_id)` in one deployment.
- Configuration status: `CONFIGURING`, `ENABLED`, `DISABLED`.
- Runtime health: `UNKNOWN`, `HEALTHY`, `DEGRADED`, `UNAVAILABLE`.
- Configuration status and health are independent.

### 1.2 Disabled integration

Disabling an integration:

- does not delete historic Cases or Messages;
- blocks normal outbound delivery;
- may still authenticate/deduplicate new callbacks for safe technical accounting;
- does not create new Case/Message/SLA business content from those events.

### 1.3 Channel discovery

A previously known channel that disappears from discovery is marked inactive, not hard deleted. Rediscovering the same external identity reactivates the same record.

### 1.4 Ignored channels

`channel.ignored=true` is a per-Channel prospective ingestion rule. Provider requests are still authenticated and deduplicated and the inbound event is recorded with `IGNORED_BY_CHANNEL`, but no Case, Message, SLA or read-state is created. Existing Cases/messages are not rewritten and ignored inbound content is not shown as ordinary inbox content.

### 1.5 Test connection

Manual Test connection:

- timeout: 10 seconds;
- may update health and a sanitized error code;
- must not silently enable/disable the integration;
- must not modify Case workflow.

### 1.6 Inbound pipeline

Conceptual flow:

```text
Provider callback
  -> authenticate / verify signature
  -> durable inbound-event insert + dedup identity
  -> fast acknowledgement to provider
  -> asynchronous normalization
  -> resolve integration/channel/customer/conversation identity
  -> provider-neutral Message/Case command
  -> transactional DB changes + outbox
  -> realtime projection
```

Heavy provider processing must not hold open the callback request unnecessarily.

### 1.7 Outbound pipeline

```text
USI workflow command
  -> durable logical Message
  -> transactional outbox
  -> provider delivery worker
  -> provider adapter
  -> Message SENT / DELIVERED / FAILED
  -> workflow continuation if the command depends on provider success
```

Provider HTTP calls never occur inside an open business database transaction.

### 1.8 Retry

Transient provider delivery failures:

- honor `Retry-After`;
- exponential backoff + jitter;
- max 8 attempts in 24 hours.

Permanent errors become `FAILED`. Retry always reuses the same logical Message.

## 2. Slack

### 2.1 V1 scope

Supported:

- public channels the app can access;
- private channels the app can access;
- Slack Connect channels the app can access.

Out of v1:

- direct messages;
- group DMs.

### 2.2 Grouping

A Slack root message/thread identity maps to one active Case generation. Replies in the thread belong to that Case. After a terminal Case, the next customer activity creates a new linked Case generation rather than reopening the terminal Case.

### 2.3 Who is the customer

In a monitored customer channel, a human message not originating from the USI app/bot is CUSTOMER content. Supported support replies are sent through USI. A support employee typing directly in Slack is not a separate supported agent workflow in v1.

### 2.4 Unmapped channels

User-approved local Slack intake override (2026-11-09): enabled integrations reconcile
bot membership every 30 seconds. Discovery accepts only supported public/private/Slack
Connect channels where `is_member=true`, preserves existing mappings and ignored flags,
and marks missing channels inactive only after a complete successful paginated snapshot.
Discovery failures preserve the previous snapshot. No history is imported.

Authenticated human messages in discovered, active, non-ignored Slack channels create
Cases and Messages even when the channel has no Customer mapping. The Case customer
is NULL and inbox/detail/analytics display **Nie przypisano**. No synthetic or real
Customer is guessed. Existing Case read DTOs retain a non-null UUID field: reserved
zero UUID `00000000-0000-0000-0000-000000000000` represents this unassigned
projection, never a Customer record. Other providers retain their existing customer-mapping requirement.
Messages arriving before discovery retry durably rather than being silently discarded.

### 2.5 Edits and deletes

- Customer edit updates the existing Message and `edited_at`; it produces activity and unread.
- Owner override 2026-11-19: customer delete marks the existing Message deleted (`deleted_at`) and preserves its last accepted body, author, timestamps and attachments under normal retention. The UI shows a muted deletion marker and retained information. It does not create/reopen a Case; later edits cannot resurrect it. Historic erased body is not automatically reconstructed.
- Edits/deletes target the original provider Message across Case generations, with row locks and stale/duplicate suppression. Missing originals retry as `MESSAGE_MUTATION_TARGET_PENDING`; they never create a Case. Edits of read Messages reset eligible read projections; deleted Messages cannot be resurrected by a late edit.
- A transactional `message.updated` outbox event triggers an authenticated per-Case realtime hint; clients refetch the persisted conversation, activity and list.

### 2.6 Request verification and ACK

- Verify Slack request signature/HMAC over the raw request body.
- Reject replay outside a 5-minute timestamp window.
- Use constant-time signature comparison.
- Never log signing secrets.
- Provider ACK must satisfy Slack's required deadline; USI internal target is p95 <500ms and p99 <1s, with heavy work asynchronous after durable ingest.

### 2.7 History and recovery

- No automatic full historical import on first connection.
- `monitoring_started_at` is the boundary for normal live Case creation.
- Owner override 2026-11-20: optional manual from-date/all-available-history and durable automatic interval/window recovery for Slack and Teams supersede the seven-day limit. Normal live intake retains its monitoring boundary; explicitly requested recovery can precede it.
- Recovery is disabled by default and does not start at first connection. See [History recovery](HISTORY_RECOVERY.md).
- All backfill is deduplicated against live intake.

### 2.8 Health

Silence does not mean failure. Enabled Slack integrations perform a lightweight auth/health check about every 15 minutes.

- revoked/invalid auth -> `UNAVAILABLE`;
- repeated transient faults -> `DEGRADED`;
- successful health restores `HEALTHY`.

### 2.9 Outgoing identity

Optional agent signature exists but is OFF by default. USI does not silently append an agent name to customer-facing text without configuration.

### 2.10 Vendor evolution

Exact Slack scopes, API paths and compatible SDK versions are chosen from current official vendor documentation at implementation time. Updating those technical details does not require a product decision when the frozen behavior remains unchanged.

## 3. Microsoft Teams

### 3.1 V1 scope

Supported:

- standard Teams channels;
- group chats.

Out of v1:

- private channels;
- shared channels.

Future vendor support does not automatically expand v1.

### 3.2 Permissions

Use resource-specific consent (RSC) and the least permissions needed for the configured context. Avoid organization-wide message read when it is not necessary.

### 3.3 Standard channel grouping

- One root post/thread = one active Case generation.
- Replies belong to that Case.
- Customer activity after terminal -> new linked Case.

### 3.4 Group chat grouping

A group chat is linear for v1: exactly one active Case per group chat. After terminal state, the next customer message starts a linked Case.

### 3.5 Customer identity

Human content in a monitored context that does not originate from the USI app/bot is CUSTOMER content. Support agents reply through USI.

### 3.6 Edits/deletes

- Edit updates the existing Message, creates activity and unread.
- Delete is represented only if Microsoft supplies a trustworthy event; do not fabricate missing delete history.

### 3.7 History/recovery

- No full historic import at onboarding.
- `monitoring_started_at` is the normal boundary.
- Recovery default 24 hours, max 7 days where the specific Teams API/context supports it.

### 3.8 Outbound

Use supported normal app/bot posting mechanisms. Migration-only posting APIs are not normal support delivery. Agent signature is OFF by default.

## 4. Telegram

### 4.1 V1 scope

Supported:

- private chats;
- groups/supergroups;
- forum topics in groups/supergroups.

Out of v1:

- broadcast channels.

### 4.2 Grouping

- Forum topic/thread -> one active Case generation per topic.
- Chat without topics -> one active Case per chat.
- After terminal, the next customer message starts a linked Case.

### 4.3 Webhook authentication

Staging and production require a random Telegram `secret_token`. The callback verifies `X-Telegram-Bot-Api-Secret-Token` before business processing.

Subscribe only to update types actually needed by USI.

### 4.4 History

There is no historical import at connection time. USI handles new updates after integration activation/monitoring start.

### 4.5 Edits and loop prevention

- `edited_message` updates the existing logical Message and creates unread/activity.
- USI bot's own outbound messages are never interpreted as CUSTOMER inbound.

### 4.6 Provider message limits

If Telegram requires one logical USI Message to be split into multiple provider delivery parts, the database still exposes one logical Message with ordered provider-part delivery metadata.

## 5. Attachments across providers

Slack inbound replies, including `thread_broadcast`, use the event's
`thread_ts` to select the Case and the integration, channel and message `ts`
to identify the reply. The broadcast's embedded `root` is presentation metadata.
Equivalent callbacks may contribute new file IDs to the existing reply.
The message and its file references commit before Slack file I/O. A separate
worker uses `files.info`, the authenticated Slack download and the attachment
scanner. History displays download and scan failures independently of the
message. Only a clean file has a download link.

All providers inherit the same attachment security pipeline:

1. authenticated access to the provider attachment or upload;
2. SSRF-safe fetch rules where a server-side fetch is needed;
3. streaming to object storage;
4. MIME/type checks;
5. malware/archive safety scan;
6. only `CLEAN` can be normally sent/downloaded.

The provider's lower size/type limit always wins over the global USI limit.

### Teams attachment transfer (owner-approved, 2026-11-19)

The Teams Connector callback commits text and a durable per-message file job before
provider I/O. Images are read from trusted provider endpoints or Graph
`hostedContents`; reference documents are resolved within the explicitly configured
SharePoint drive. Customer HTML image URLs are never a source of server-side fetches.
The existing object storage, file policy and scanner gate download and outbound use.
Limits are 10 files, 25 MiB per file and 50 MiB per logical message.

RSC `ChannelMessage.Read.Group` / `ChatMessage.Read.Chat` authorizes message reads;
it does not grant access to referenced SharePoint/OneDrive files. The owner approved
Graph application `Sites.Selected` and `write` on the USISandbox site for the sandbox.
Another site requires a separate explicit administrator grant. No tenant-wide file
permission is granted to the bot. Per-context `metadata_json.teamsFiles` holds the
approved drive ID, destination folder ID and exact SharePoint host allowlist.
Group-chat OneDrive files outside the selected storage remain a visible authorization
error until their storage is explicitly approved; the adapter never broadens access.

Outbound uses the normal Bot Connector reply in the original conversation/root.
Small supported images use native image attachments; other clean images/documents
are uploaded to the configured channel folder and linked in an Adaptive Card in
the same reply. These links retain Microsoft 365 membership-based authorization;
they are neither anonymous shares nor USI download URLs. Stable per-attachment
paths and stored drive/item checkpoints prevent duplicate uploads on ordinary retry.
Recorded Connector acceptance skips both upload and a second customer-visible send.
Connector HTTP 202 may carry no `ResourceResponse`. Its actual HTTP acceptance is
checkpointed separately, without inventing a provider message ID; it finalizes SENT
and the shared human-response SLA at delivery finalization, rather than enqueue time.
An ambiguous Connector send remains non-retryable pending inspection of the thread.

Failed inbound transfers remain visible. An administrator with `manage_integrations`
can use Settings → Integrations → Retry Teams attachments after repairing consent
or configuration. The endpoint only schedules existing failed jobs for that enabled
Teams integration; it creates no message or Case and does not alter SLA. Policy and
quarantine failures cannot be bypassed by retry. Automatic transient retries are
bounded to eight attempts and 24 hours, respecting provider Retry-After.

## 6. Provider-neutral guarantees

Provider adapters must not decide:

- who may Claim/Reply/Resolve;
- Case state transitions;
- SLA pause/resume semantics;
- read/unread rules;
- audit retention;
- permission catalog.

They normalize provider capabilities into the frozen domain contract. Unsupported vendor behavior is surfaced explicitly rather than changing the product model silently.

## Owner-approved admin onboarding (2026-11-20)

[Provider onboarding](PROVIDER_ONBOARDING.md) defines create/edit/test/enable, callback copy, initial/lifecycle/periodic discovery and the new selected-team Teams security boundary. New connections default automatic discovery on; a nullable migrated setting retains the legacy Slack global flag. Staging enables the legacy flag. API requests run outside database transactions; durable token leases fence snapshots, renew during long scans and resume expired manual work. Transient failures, including Retry-After cooldowns, remain queued even when periodic discovery is off. A manual refresh cannot bypass an active provider cooldown. Partial snapshots never deactivate previously known channels.

New Teams packages are separate from the historical team/group-chat POC template: they install only in a team and request exactly `ChannelMessage.Read.Group` and `ChannelSettings.Read.Group`. Signed activities must prove the selected team's canonical Graph GUID before the inbox stores payloads. Group chats do not meet that condition. Standard channels alone are discovered; customer/ignored settings on known identities are retained.

## Author support-message mutations

Slack chat.update/chat.delete use the same bot and actual channel/ts. Teams Connector PUT/DELETE use an immutable real activity target, stored bot/conversation/service URL and existing approved context. HTTP 202 send acceptance without an ID remains safe and unavailable for mutation. Text edits preserve attachments; complete deletion of messages with separate file resources is unavailable. Teams inline-only image activities with an actual activity ID can be deleted as one resource. No new scopes or provider channel types. [Capability matrix and official references](OUTBOUND_MESSAGE_MUTATIONS.md#provider-capabilities).
