# Telegram development sandbox setup

This guide is the development-only setup contract for the Unified Support Inbox
Telegram integration (`E19-T01`). It records the supported Bot API webhook
shape, secret boundary and test-chat assumptions before the authenticated
runtime is implemented in `E19-T02`.

Use a dedicated test bot and test Telegram accounts only. Never use a customer
bot, a production bot, production credentials or a shared production
integration while following this guide.

## Frozen callback and scope contract

USI receives Telegram updates through the Bot API webhook at:

```text
https://<dev-tunnel-host>/api/v1/provider-callbacks/telegram
```

The HTTPS tunnel forwards to the local **web ingress** at
`http://localhost:3000`; Next.js forwards `/api/*` to the local Spring API.
Do not expose a browser-only endpoint, create a second callback route, or call
the Bot API from browser code.

The checked-in `.env.example` contains only the inert callback placeholder.
Update the ignored local `.env` with the public callback URL and the
non-secret bot username:

```text
USI_TELEGRAM_CALLBACK_URL=https://<dev-tunnel-host>/api/v1/provider-callbacks/telegram
USI_TELEGRAM_BOT_USERNAME=<development-bot-username>
```

V1 supports private chats, groups, supergroups and forum topics. Broadcast
channels are out of scope. A forum topic maps to one active Case generation;
a chat without topics maps to one active Case generation. After a terminal
Case, the next customer activity creates a linked new Case. The USI bot's own
outbound messages are never CUSTOMER inbound content.

## 1. Create an isolated bot

1. In Telegram, open `@BotFather` and use `/newbot` to create a clearly
   development-only bot, for example `USI Development <developer>`.
2. Choose a unique development username ending in `bot`; record that username
   only as the non-secret local configuration value above.
3. Save the BotFather-issued token directly into the approved external
   integration-secret directory. Do not paste it into a terminal, shell
   history, `.env`, a ticket, a screenshot, a committed file or browser code.
4. Create a separate random webhook secret token using a CSPRNG. It must meet
   the Bot API's `secret_token` character and length restrictions and is also
   stored only in the external integration-secret directory.

Do not use Telegram Login, Mini Apps, payment features, a local Bot API server,
or any unrelated BotFather capability for the v1 support integration.

## 2. Configure the external secret reference

`Integration.secret_ref` is an opaque relative directory reference below the
root configured by `USI_INTEGRATION_SECRETS_DIRECTORY`. For a development bot,
store only a non-secret locator such as:

```text
telegram/development-bot
```

The associated secret files are resolved below that configured root:

```text
<USI_INTEGRATION_SECRETS_DIRECTORY>/<secret_ref>/telegram-bot-token
<USI_INTEGRATION_SECRETS_DIRECTORY>/<secret_ref>/telegram-webhook-secret-token
```

Each file contains one non-empty value; a single trailing newline is allowed.
Keep the directory outside the repository with owner-only permissions. The
reference must be relative, contain no traversal or backslash segments, and
resolve physically below the configured secret root.

The database stores the locator only. It must never store either resolved value
in `secret_ref`, configuration JSON, errors, audit events or raw callback
payloads. The runtime must not bulk-import provider secrets into Spring's
Environment.

## 3. Register the webhook without exposing a token

Configure the Bot API `setWebhook` request through a secret-aware operator
tool that reads the bot token directly from the external secret file. Do not
place the token in a command-line argument, environment variable, curl command,
temporary repository file or shell history.

Set the `url` parameter to the frozen callback URL, set `secret_token` from the
second secret file, and configure exactly these `allowed_updates`:

- `message` for private chats, groups, supergroups and forum topics;
- `edited_message` for supported customer-message edits;
- `my_chat_member` to observe the bot being added to or removed from a test
  group/supergroup.

Do not configure `channel_post` or `edited_channel_post`: broadcast channels
are outside v1. Do not use `getUpdates` while a webhook is registered; Telegram
does not deliver both mechanisms concurrently. The tunnel endpoint needs a
publicly trusted HTTPS certificate; do not substitute a browser redirect or a
secret path in the URL for the configured webhook header secret.

Before enabling the integration, verify through a token-safe status check that
the configured webhook URL is the exact HTTPS callback above, no provider error
is reported and the expected allowed-update set is present. Never record the
returned token or a full provider diagnostic response in Jira, logs or a
committed artifact.

## 4. Prepare development conversations

Use test content only:

1. Start a private chat with the development bot and send a test message.
2. Create a dedicated test group or supergroup and add the bot.
3. For group/supergroup message testing, adjust the bot's privacy mode only if
   it prevents receiving the test messages required by the scoped integration.
   Do not grant administrator rights unless a later reviewed capability
   specifically requires them.
4. In a dedicated forum-enabled supergroup, create at least two topics and
   send a test message in each one. They must remain distinct conversations.

Do not add the development bot to customer support chats or broadcast channels.
There is no historical import at activation: testing begins with new updates
after the integration is enabled.

## 5. Callback security and runtime boundary

`E19-T02` owns the authenticated Telegram callback runtime. Its boundary must:

1. compare `X-Telegram-Bot-Api-Secret-Token` against the resolved webhook
   secret using a timing-safe comparison before business processing;
2. use the raw authenticated delivery to create/deduplicate a durable inbound
   event before returning HTTP 2xx;
3. acknowledge quickly and leave normalization/Case processing to later
   asynchronous, idempotent work;
4. log neither the bot token, webhook secret, authorization headers nor raw
   message bodies by default.

Telegram retries non-2xx webhook deliveries. This does not authorize duplicate
Case or Message effects; later processing must preserve the provider update
identity and USI's durable deduplication contract.

## Verification checklist

Before handing the sandbox to the next Telegram ticket, confirm:

- the bot, user accounts, groups and topics are development-only;
- the public URL in Telegram and `USI_TELEGRAM_CALLBACK_URL` are identical and
  end in `/api/v1/provider-callbacks/telegram`;
- the HTTPS tunnel forwards to local web port `3000`;
- the integration stores a relative `secret_ref` only, and both secret files
  exist only below the approved external secret root;
- no bot token or webhook secret is in `.env`, Git, logs, screenshots, Jira or
  browser-visible configuration;
- allowed updates are exactly `message`, `edited_message` and
  `my_chat_member`;
- private-chat, group/supergroup and two distinct forum-topic test messages
  are ready for the E19-T02 callback acceptance; and
- no historical messages are treated as imported USI content.

## Cleanup and rotation

If a development credential is exposed, revoke/regenerate it through BotFather,
remove the affected external secret material and treat any committed occurrence
as a security incident. When the sandbox is no longer required, delete the test
bot or revoke its token, clear its webhook, remove its external secret directory
and remove it from all test chats.

## Telegram references

- [Telegram Bot API: `setWebhook` and `allowed_updates`](https://core.telegram.org/bots/api#setwebhook)
- [Telegram webhook guide](https://core.telegram.org/bots/webhooks)
- [Telegram BotFather guide](https://core.telegram.org/bots/features#botfather)
