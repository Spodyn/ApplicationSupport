# Realtime event envelope (v1)

WebSocket/STOMP notifications are hints only: clients must refetch REST state before relying on an update.

Application projections also use `/user/queue/application`, resolved from the authenticated principal. Database triggers append minimal scope hints to the existing outbox for settings, users, permissions, presence, attachments and SLA changes, including discovery and resync workers. Rollback removes the hint with its source change. Delivery rechecks active recipients. Administrative settings hints reach administrators; other users receive an empty catalog hint. Identity changes send the affected user a self-refresh hint and others an empty directory hint. No record bodies, secrets, session IDs or private state are carried.

| Event or destination | Invalidated query scopes |
| --- | --- |
| Case lifecycle | Case list/detail/activity, statistics and workload; SLA also refreshes personal reminders |
| Conversation | Matching messages/detail/activity, Case list, statistics and workload |
| Personal Case queue | Own Case list/detail/activity |
| Direct Message queue | Current account's Direct Message projections |
| `application.settings_changed` | Settings, resync, provider setup, filter catalog, statistics |
| `application.catalog_changed` | Case list, filter catalog, authorized statistics |
| `application.directory_changed` | Users, Direct Message recipients, owner filters/assignees, statistics |
| `application.identity_changed` | All authenticated active query scopes |
| `application.reminders_changed` | Affected user's reminder indicators |
| `application.case_changed` | Matching messages/detail/activity, Case list/reminders, statistics, workload |

The frontend batches duplicate keys in fixed 100 ms windows. Reconnect and unknown/future envelopes reconcile active inbox, Direct Message, recipient, identity, administrative and statistics scopes. Cached data remains visible during refetch. Invalidation does not replace form state or selections. Bounded fallback polling includes 30-second user-directory refresh for presence aging without a database write.

Every `MESSAGE` body is a JSON object with `eventType`, integer `version`, `entityId`, ISO-8601 `occurredAt`, `correlationId`, and a minimal object `payload`. Unknown event types, future versions, duplicates, or malformed bodies require a safe targeted refetch rather than an error or local state mutation.

Case lifecycle hints are sent to `/topic/cases`. Supported v1 event types are `case.created`, `case.claimed`, `case.updated`, and `case.sla_changed`. Their `entityId` is the Case ID. Payloads are intentionally minimal and may contain only Case projection fields such as `caseId`, `status`, `ownerUserId`, `version`, `slaState`, `warningAt`, and `dueAt`. Customer content, provider credentials, provider URLs, and per-user read/snooze state are never broadcast on the global topic. These events originate from durable outbox delivery, so the realtime publish happens only after the business transaction commits.

The browser subscribes to `/topic/cases` after the STOMP `CONNECTED` frame and restores that subscription after reconnect. Receiving a lifecycle event invalidates the relevant REST-backed Case query instead of patching pending local workflow state.

Conversation hints are sent only to `/topic/cases/{caseId}` while that Case is actively subscribed in the browser. `message.created` v1 uses the message ID as `entityId` and carries only `messageId` and `caseId`; the full Message is always refetched from REST. The event is written through the transactional outbox together with inbound/support Message creation, so websocket delivery cannot precede the committed Message row.

`message.delivery_updated` v1 is sent to `/topic/cases/{caseId}`. Its `entityId` is the message ID and its payload may contain `deliveryStatus`, `errorCategory`, `errorCode`, and `nextRetryAt`; it contains no credentials or per-user read state. Duplicate or out-of-order conversation hints are safe because the client invalidates the stable Message query rather than appending websocket payloads locally.
