# Realtime event envelope (v1)

WebSocket/STOMP notifications are hints only: clients must refetch REST state before relying on an update.

Every `MESSAGE` body is a JSON object with `eventType`, integer `version`, `entityId`, ISO-8601 `occurredAt`, `correlationId`, and a minimal object `payload`. Unknown event types, future versions, duplicates, or malformed bodies require a safe targeted refetch rather than an error or local state mutation.

Case lifecycle hints are sent to `/topic/cases`. Supported v1 event types are `case.created`, `case.claimed`, `case.updated`, and `case.sla_changed`. Their `entityId` is the Case ID. Payloads are intentionally minimal and may contain only Case projection fields such as `caseId`, `status`, `ownerUserId`, `version`, `slaState`, `warningAt`, and `dueAt`. Customer content, provider credentials, provider URLs, and per-user read/snooze state are never broadcast on the global topic. These events originate from durable outbox delivery, so the realtime publish happens only after the business transaction commits.

The browser subscribes to `/topic/cases` after the STOMP `CONNECTED` frame and restores that subscription after reconnect. Receiving a lifecycle event invalidates the relevant REST-backed Case query instead of patching pending local workflow state.

Personal read/unread/Snooze hints are sent only through the authenticated current-user destination `/user/queue/personal`. Supported v1 event types are `case.unread_changed`, `case.read_position_changed`, `case.snoozed`, and `case.snooze_cancelled`; their `entityId` is the Case ID and the public payload contains only stable identifiers needed to refetch current-user projections, plus `snoozedUntil` for the actor's Snooze hint when present. The server resolves the target user from committed read-state data or a server-created outbox event. Clients never choose a target user ID, direct `/queue/...` subscriptions are rejected, and `/user/{userId}/...` subscriptions are not accepted. Personal events invalidate only current-user inbox projections and are never forwarded to `/topic/cases`.

Conversation hints are sent only to `/topic/cases/{caseId}` while that Case is actively subscribed in the browser. `message.created` v1 uses the message ID as `entityId` and carries only `messageId` and `caseId`; the full Message is always refetched from REST. The event is written through the transactional outbox together with inbound/support Message creation, so websocket delivery cannot precede the committed Message row.

`message.delivery_updated` v1 is sent to `/topic/cases/{caseId}`. Its `entityId` is the message ID and its payload may contain `deliveryStatus`, `errorCategory`, `errorCode`, and `nextRetryAt`; it contains no credentials or per-user read state. Duplicate or out-of-order conversation hints are safe because the client invalidates the stable Message query rather than appending websocket payloads locally.
