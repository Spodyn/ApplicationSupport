# Realtime event envelope (v1)

WebSocket/STOMP notifications are hints only: clients must refetch REST state before relying on an update.

Every `MESSAGE` body is a JSON object with `eventType`, integer `version`, `entityId`, ISO-8601 `occurredAt`, `correlationId`, and a minimal object `payload`. Unknown event types, future versions, duplicates, or malformed bodies require a safe targeted refetch rather than an error or local state mutation.

`message.delivery_updated` v1 is sent to `/topic/cases/{caseId}`. Its `entityId` is the message ID and its payload may contain `deliveryStatus`, `errorCategory`, `errorCode`, and `nextRetryAt`; it contains no credentials or per-user read state.
