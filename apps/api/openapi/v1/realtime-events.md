# Realtime event envelope (v1)

WebSocket/STOMP notifications are hints only: clients must refetch REST state before relying on an update.

Every `MESSAGE` body is a JSON object with `eventType`, integer `version`, `entityId`, ISO-8601 `occurredAt`, `correlationId`, and a minimal object `payload`. Unknown event types, future versions, duplicates, or malformed bodies require a safe targeted refetch rather than an error or local state mutation.

Case lifecycle hints are sent to `/topic/cases`. Supported v1 event types are `case.created`, `case.claimed`, `case.updated`, and `case.sla_changed`. Their `entityId` is the Case ID. Payloads are intentionally minimal and may contain only Case projection fields such as `caseId`, `status`, `ownerUserId`, `version`, `slaState`, `warningAt`, and `dueAt`. Customer content, provider credentials, provider URLs, and per-user read/snooze state are never broadcast on the global topic. These events originate from durable outbox delivery, so the realtime publish happens only after the business transaction commits.

The browser subscribes to `/topic/cases` after the STOMP `CONNECTED` frame and restores that subscription after reconnect. Receiving a lifecycle event invalidates the relevant REST-backed Case query instead of patching pending local workflow state.

`message.delivery_updated` v1 is sent to `/topic/cases/{caseId}`. Its `entityId` is the message ID and its payload may contain `deliveryStatus`, `errorCategory`, `errorCode`, and `nextRetryAt`; it contains no credentials or per-user read state.
