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

For a valid callback from a channel without an active Customer mapping:

- verify/authenticate it;
- persist/deduplicate it as `UNMAPPED_CHANNEL` technical state;
- do not create Case, Message or SLA;
- surface the configuration problem in Settings/admin tooling.

### 2.5 Edits and deletes

- Customer edit updates the existing Message and `edited_at`; it produces activity and unread.
- Customer delete marks the existing Message deleted (`deleted_at`/safe marker), preserves identity/history and does not create/reopen a Case.

### 2.6 Request verification and ACK

- Verify Slack request signature/HMAC over the raw request body.
- Reject replay outside a 5-minute timestamp window.
- Use constant-time signature comparison.
- Never log signing secrets.
- Provider ACK must satisfy Slack's required deadline; USI internal target is p95 <500ms and p99 <1s, with heavy work asynchronous after durable ingest.

### 2.7 History and recovery

- No automatic full historical import on first connection.
- `monitoring_started_at` is the boundary for normal live Case creation.
- Recovery/backfill is for gaps after that boundary: default 24 hours, one admin run max 7 days.
- Larger historic imports are outside v1.
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

All providers inherit the same attachment security pipeline:

1. authenticated access to the provider attachment or upload;
2. SSRF-safe fetch rules where a server-side fetch is needed;
3. streaming to object storage;
4. MIME/type checks;
5. malware/archive safety scan;
6. only `CLEAN` can be normally sent/downloaded.

The provider's lower size/type limit always wins over the global USI limit.

## 6. Provider-neutral guarantees

Provider adapters must not decide:

- who may Claim/Reply/Resolve;
- Case state transitions;
- SLA pause/resume semantics;
- read/unread rules;
- audit retention;
- permission catalog.

They normalize provider capabilities into the frozen domain contract. Unsupported vendor behavior is surfaced explicitly rather than changing the product model silently.
