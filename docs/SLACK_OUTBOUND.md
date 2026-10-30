# Slack outbound delivery

USI sends support replies through Slack Web API `chat.postMessage` using the same Integration that owns the inbound Slack conversation.

## Credential layout

`Integration.secret_ref` remains a non-secret relative directory locator below `USI_INTEGRATION_SECRETS_DIRECTORY`. Slack outbound delivery resolves the bot credential only from:

```text
<USI_INTEGRATION_SECRETS_DIRECTORY>/<secret_ref>/slack-bot-token
```

The existing inbound request verifier continues to resolve:

```text
<USI_INTEGRATION_SECRETS_DIRECTORY>/<secret_ref>/slack-signing-secret
```

Both files must stay outside the repository and outside application configuration. Do not put the bot token in `.env`, Spring properties, browser-visible configuration, database fields, logs, Jira, or committed fixtures. The runtime reads the token only for the selected Integration and clears the transient byte buffer after the provider call.

## Outbound contract

For each SUPPORT Message the provider sends:

- the persisted Slack channel external ID as `channel`;
- the persisted Slack root timestamp as `thread_ts` when the Case belongs to a thread;
- the Message body as `text`;
- `mrkdwn=true` only for MARKDOWN messages;
- the stable logical Message idempotency key as `client_msg_id` on every retry.

For **USI-245** the HTTP client additionally supports the optional `reply_broadcast=true`
argument in the **same** `chat.postMessage` request. It is emitted only when a support
agent explicitly opts in **and** the message has a persisted nonblank `thread_ts`.
Broadcast without a thread is rejected before any HTTP request; ordinary replies
continue to omit the flag. The same logical `client_msg_id` is retained and only
one provider post is sent.

**Implementation checkpoint:** The HTTP client and provider delivery-command boundary
now carry an explicit opt-in flag without changing existing non-broadcast callers.
The provider rejects broadcast without a persisted thread before reading credentials
or sending any HTTP request. Focused provider and HTTP tests cover both paths.
The Slack inbound normalizer now explicitly filters `thread_broadcast` channel echoes
before message persistence, while preserving the original ordinary human thread reply;
a focused regression test covers both paths. This is only a provider-event filter,
**not** proof of complete echo/idempotency acceptance against a live Slack workspace.
The send command now validates Slack thread eligibility, persists the opt-in flag
on the Message row, includes the true intent in the idempotency fingerprint, and
hydrates it for every delivery attempt (including durable retries). Existing
false/default request fingerprints are unchanged. The reserved V41 migration is held
in this draft until competing V35 migrations from PRs #126 and #133 have been
sequenced without Flyway out-of-order upgrades. Tests cover invalid broadcast,
idempotent replay and transient retry preservation. The composer switch,
composer UI, frontend/Playwright tests and live Slack sandbox acceptance are
**not yet implemented**. The OpenAPI request schema, deterministic generated
TypeScript transport type and inbox API adapter now carry optional
`replyBroadcast` with a false-by-default wire contract; adapter tests cover
both opt-in and legacy sends. Do not
offer the option in production or mark USI-245 complete until the entire
end-to-end path is green.

A successful Slack response must contain `ok=true` and `ts`; that timestamp becomes the provider message reference in the common delivery lifecycle.

## Retry and repair contract

Slack rate limiting stays inside the provider-neutral durable retry lifecycle from USI-96. The Slack adapter does not sleep in the HTTP request path and does not create a second retry loop.

- HTTP `429` is transient. A positive integer `Retry-After` header is forwarded to the common scheduler. If the header is absent or unusable, the existing exponential backoff with jitter is used.
- HTTP `408` and `5xx` responses are transient. Other non-2xx responses are permanent unless a future Slack contract explicitly says otherwise.
- Slack API errors `rate_limited`, `ratelimited`, `internal_error`, `fatal_error`, `service_unavailable`, `request_timeout`, `org_login_required`, and `team_added_to_org` are transient.
- Authentication, token, scope, channel membership, archived-channel and workspace-policy errors are permanent and require operator/configuration repair rather than automatic retry.
- Unknown Slack API errors fail permanently by default. A newly documented temporary error must be deliberately added to the reviewed transient allow-list instead of being retried forever.

The provider never overrides the common maximum-attempt or retry-window policy. It only supplies Slack-specific error classification and an optional provider-requested retry delay.

Slack documents `chat.postMessage` as a special-tier method that generally permits about one message per second per channel, with additional workspace-wide limits and burst tolerance. USI therefore treats `Retry-After` as authoritative when Slack provides it rather than encoding a fixed Slack sleep interval.

The Slack app requires the reviewed `chat:write` bot scope. `chat:write.public` is intentionally not required: monitored public channels must explicitly contain the app.
