# Outbound message editing and deletion

Owner-approved extension, 2026-11-28, DEC-OUTBOUND-MESSAGE-MUTATIONS-001. This supersedes earlier exclusions of outbound editing/deletion. It does not add a Case status, permission, SLA cycle or provider scope.

## Eligibility and presentation

Active authenticated USER/ADMIN accounts may mutate their own successfully delivered public SUPPORT messages when they can access the Case, have never voted Ignore on it, and the provider exposes a safe target and supported operation. Administrators cannot override authorship. Customer messages, internal notes, system messages, Telegram and unconfirmed deliveries are ineligible. Assignment is not authorship: later reassignment or resolution does not transfer message ownership or reopen the Case. Backend capabilities are authoritative.

The compact action menu supports hover, keyboard focus and touch. Copy text requires no provider call. Edit uses an inline draft and Save/Cancel; deletion requires confirmation. English and Polish show pending states, failure explanations, edited labels and retained deleted-message placeholders. HTTP 202 from ApplicationSupport accepts a durable command; it does not confirm a provider mutation. Content changes only after a positive final provider receipt or matching authenticated callback.

## Provider capabilities

| Provider/resource | Text edit | Complete deletion |
| --- | --- | --- |
| Slack public support text | `chat.update`, original channel + actual `ts`, same bot token | `chat.delete`, same target/bot |
| Slack text plus uploaded files | Update separate text resource, leave files untouched | Unavailable: deleting text alone leaves separate resources |
| Teams text with actual Connector activity ID | Connector PUT using original service URL, conversation, activity and bot | Connector DELETE using same target |
| Teams text plus files with actual activity ID | Preserve exact original attachment payload on PUT | Unavailable: external files may remain |
| Teams activity containing only inline images | Add/edit text, preserving inline images | Supported with actual activity ID; one resource |
| Attachment-only message | Teams can gain text with actual activity ID and saved attachment payload; Slack has no separate text resource | Teams inline-only activity with actual ID supported; separate files unavailable |
| Teams accepted without activity ID | Unavailable | Unavailable |

Microsoft documents that an update can change the original activity type, including an attachment-only activity. Teams caption edits preserve the saved attachment payload; no-ID acceptance remains unavailable.

Slack uses the documented `chat.update` parse option for plain text versus markdown and disables automatic name expansion. Slack requires the existing bot `chat:write` authorization; no new scopes, impersonation or file deletion privileges are introduced. Teams uses the existing Bot Connector token flow and safe Microsoft service URL allowlist, exact integration/tenant credential reference, stored bot identity and approved standard-channel/group-chat context. Existing selected-team intake restrictions remain unchanged. No Graph chat-message mutation permission is requested. A valid Teams HTTP 202 without an ID remains a successful send, never a fabricated mutation target. Historical Teams messages without an immutable target snapshot are unavailable; historical Slack messages can use an actual stored timestamp with the current configured bot, which the provider must authorize.

Primary references reviewed for this change:

- [Slack chat.update](https://docs.slack.dev/reference/methods/chat.update/)
- [Slack chat.delete](https://docs.slack.dev/reference/methods/chat.delete/)
- [Slack files.delete](https://docs.slack.dev/reference/methods/files.delete/) describes a separate resource operation which this feature deliberately does not invoke.
- [Teams bot conversational capabilities](https://learn.microsoft.com/en-us/microsoftteams/platform/bots/build-conversational-capability)
- [Bot Connector REST reference](https://learn.microsoft.com/en-us/azure/bot-service/rest-api/bot-framework-rest-connector-api-reference?view=azure-bot-service-4.0)

## Durable commands and races

`POST /api/v1/messages/{messageId}/mutations` takes EDIT/DELETE, expectedRevision and an Idempotency-Key. The retry endpoint takes the original operation ID and a fresh idempotency key. V59 adds targets, version history and durable mutation operations without rewriting historical data. Per-message locks and a partial unique active-operation index serialize edit/delete/retry. Every attempt rechecks current account, authorship, Ignore restrictions, integration state, delivery and capabilities. Provider HTTP runs outside database transactions. The existing outbox/Rabbit pipeline dispatches committed commands; ordinary message delivery is never invoked.

States are QUEUED, RUNNING, RETRY, CONFIRMED, FAILED and UNCERTAIN. Only known-safe non-acceptance (rate limiting or connection/token acquisition before dispatch) is retried with the existing bounded backoff policy. Timeouts, ambiguous server responses and worker lease expiry become UNCERTAIN and block further mutations. They are never blindly replayed. Matching authenticated callbacks, or an original late response with the same retained lease fence, can confirm the operation. Manual retry is offered only for a known-safe retryable failure on the latest unchanged revision. Uncertain operations without a provider confirmation remain blocked; operator investigation must preserve evidence and must not invent success or reset the operation to resend it.

Callbacks pass existing signature/JWT, tenant, channel, membership and monitoring boundaries. They match provider, integration, channel, original Connector conversation and actual activity ID together; IDs scoped to another thread cannot affect this message. Historical Slack timestamps retain the same channel scope. They order edits by the accepted provider-mutation watermark, and fence callback confirmation to the dispatched attempt. An old callback with matching text cannot confirm a new request. Duplicate/old echoes never resurrect deletions or enqueue a feedback mutation. Provider moderation deletions are retained. Concurrent external changes invalidate/freeze an in-flight operation rather than silently overwriting it.

## History, previews, realtime and retention

Each accepted change preserves the previous content version. Deletion retains last accepted text, author, send timestamps and authorized clean attachment access under DEC-PROVIDER-DELETED-CONTENT-001. Version/operation/target rows follow Message retention by cascading foreign keys; there is no new purge policy. Audits contain operation identity/action/outcome and sanitized codes, not message text, tokens or provider diagnostics. Credential references and attachment snapshots remain server-only.

Confirmed mutations update the authoritative conversation and deleted preview marker and publish existing message.updated/case.updated hints through the transactional outbox. Connected sessions refetch targeted REST scopes; no optimistic content success or page reload. They do not change message send timestamps, Case last activity/order, workflow version, ownership, historical FRT/NRT/TTR/breaches or active operational deadlines.

## Validation boundaries

Regression inventory MESSAGE_MUTATIONS maps deterministic PostgreSQL command/race tests, controlled Slack and Teams HTTP, UI capability/pending tests and two independent browser sessions through the real backend/outbox/STOMP stack. Ordinary tests use disposable resources and synthetic credentials only. Live Slack and Teams sandbox mutation acceptance is NOT RUN until explicitly authorized usable provider access is available; controlled results do not establish real tenant policy acceptance.
