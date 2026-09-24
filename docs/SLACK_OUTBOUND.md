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

A successful Slack response must contain `ok=true` and `ts`; that timestamp becomes the provider message reference in the common delivery lifecycle. HTTP 429 is transient and its `Retry-After` value is forwarded to the common retry scheduler. HTTP 5xx and selected Slack service failures are transient; invalid configuration, authorization, or conversation responses are permanent provider failures.

The Slack app requires the reviewed `chat:write` bot scope. `chat:write.public` is intentionally not required: monitored public channels must explicitly contain the app.
