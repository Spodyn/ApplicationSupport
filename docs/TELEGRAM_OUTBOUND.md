# Telegram outbound text delivery (USI-192)

**Implementation checkpoint: partial, not production acceptance.** This Jira-scoped branch
adds a Telegram `MessageDeliveryProvider` and Bot API `sendMessage` HTTP adapter.
It reuses the existing durable Message, claim/attempt, retry, and idempotent local
command lifecycle from USI-95/USI-96. No new database migration is required.

## Delivery mapping

- `cases.external_conversation_id` is the persisted numeric Telegram chat ID.
  The adapter rejects malformed or zero IDs before any credential read.
- `cases.external_thread_key` (or Message override) is a positive numeric
  `message_thread_id` for forum topics. It is omitted for ordinary chats.
- `text` is the stored support Message body. Text is sent literally with no
  Telegram `parse_mode`, avoiding unsafe MarkdownV2 interpretation and
  provider-specific formatting errors.
- The returned `result.message_id` is saved as the provider message reference.
- Only one `sendMessage` request is made per worker delivery attempt.
  Retries preserve the same local Message ID and do not insert a second local Message.

The Bot API has **no equivalent of Slack's `client_msg_id` idempotency
parameter** for `sendMessage`. If Telegram accepted a request but the HTTP
response was lost, retrying may create another provider-side message. Do not
claim exactly-once remote delivery without an additional reviewed reconciliation
mechanism. Existing durable attempts remain the source of truth.

## Credentials and failure handling

The credential is resolved only from:

```text
<USI_INTEGRATION_SECRETS_DIRECTORY>/<secret_ref>/telegram-bot-token
```

The token is never stored in the database, application properties, browser,
Jira, test fixtures with real credentials, or logs. Telegram's Bot API requires
a token-bearing URL. Never log the request URI, raw HTTP response, or transport
exception text. The adapter translates failures into stable, non-sensitive
`TELEGRAM_*` codes and clears the transient credential byte array.

- HTTP 429 or Bot API `error_code=429` is transient; positive
  `parameters.retry_after` (or a valid `Retry-After` header) is passed to
  the common durable scheduler.
- HTTP 408/5xx are transient and use existing retry-window/max-attempt limits.
- 400/401/403 and other non-transient API errors are permanent, requiring
  configuration/permissions or content repair.
- Missing credentials, invalid chat/topic and invalid/oversize text are
  permanent validation failures before provider I/O.
- Missing `result.message_id`, invalid JSON or transport exceptions are
  transient. No provider error description or token-bearing URL is logged.

## Verification and remaining acceptance

Focused JUnit tests cover forum/no-topic payloads, returned provider IDs,
429 retry delays, permanent auth errors, missing credentials, malformed
identifiers, malformed responses and token-bearing exception redaction.

**NOT RUN:** real Telegram sandbox outbound flow, exact-head GitHub CI until
it completes, and end-to-end retry/attachment behavior. This branch does not
implement attachment upload (USI-193), Telegram integration health (USI-193),
or an exactly-once provider-side deduplication mechanism.

Before considering merge, check the full six-job exact-head CI, security,
current Jira dependencies, lease/review conditions, and required acceptance.
Do not mark USI-192 `Gotowe` based on the code or documentation alone.

Official reference: https://core.telegram.org/bots/api#sendmessage
