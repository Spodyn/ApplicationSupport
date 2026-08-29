# Slack development sandbox setup

This guide is the development-only setup contract for the Unified Support Inbox
Slack integration (`E12-T01` / `E12-T02`). It explains how to create an isolated
Slack app, which permissions and Events API subscriptions are required, how to
expose the local callback through HTTPS, and how the runtime resolves the Slack
signing secret without storing credentials in application configuration.

Do **not** use a production Slack workspace, production credentials, or a shared
production integration while following this guide.

## Frozen callback contract

USI uses Slack's HTTP Events API, not Socket Mode. The Slack Request URL is:

```text
https://<dev-tunnel-host>/api/v1/providers/slack/events
```

For local development the HTTPS tunnel must forward to the **web ingress** on
`http://localhost:3000`. Next.js forwards `/api/*` to the local Spring API, so
Slack follows the same ingress path as the deployed application. Do not tunnel
directly to a browser-only endpoint and do not introduce a second callback path.

The same URL belongs in the ignored local `.env` as
`USI_SLACK_CALLBACK_URL`. The checked-in `.env.example` contains only an inert
`.invalid` placeholder. Runtime configuration rejects retired pre-E12 Slack
callback routes.

## 1. Create an isolated Slack app

1. Open Slack's app management page and choose **Create New App** ->
   **From scratch**.
2. Use an unmistakably development-only name, for example `USI Development -
   <developer>`.
3. Select a dedicated test workspace. Never select a customer or production
   workspace for local development.
4. In **Basic Information**, note the non-secret Client ID if you need it for
   local configuration. `USI_SLACK_CLIENT_ID` may contain this identifier.
5. Do not commit exported app configuration containing credentials or any file
   copied from Slack's credential screens.

A committed Slack manifest is intentionally not part of the USI contract. The
Slack app configuration is small enough to review directly and this avoids
turning an exported credential-bearing artifact into repository state.

## 2. Configure bot token scopes

Under **OAuth & Permissions** -> **Scopes** -> **Bot Token Scopes**, configure
only the scopes required by the current USI Slack feature set:

| Bot scope | Why USI needs it |
| --- | --- |
| `channels:read` | Discover/read metadata for public channels. |
| `channels:history` | Receive/read messages in public channels the app belongs to. |
| `groups:read` | Discover/read metadata for private channels the app belongs to. |
| `groups:history` | Receive/read messages in private channels the app belongs to. |
| `im:read` | Read metadata for direct-message conversations with the app. |
| `im:history` | Receive/read direct messages sent to the app. |
| `mpim:read` | Read metadata for group direct-message conversations the app belongs to. |
| `mpim:history` | Receive/read group direct messages for the app. |
| `chat:write` | Send support replies as the Slack app/bot. |

Do not add `chat:write.public`: monitored public channels must explicitly contain
the app. Do not add workspace-admin, user-token, or unrelated scopes "just in
case". A future feature that genuinely needs another scope must introduce it in
a reviewed ticket and update this guide.

After changing OAuth scopes, reinstall the app to the development workspace so
the granted bot token reflects the current scope set.

## 3. Configure Events API subscriptions

Open **Event Subscriptions**, enable events, and set the Request URL to the
frozen callback URL:

```text
https://<dev-tunnel-host>/api/v1/providers/slack/events
```

Under **Subscribe to bot events**, subscribe to exactly these message event
families for the current integration:

- `message.channels`
- `message.groups`
- `message.im`
- `message.mpim`

USI receives the normal message stream and later filters unsupported subtypes,
message edits/deletes, ignored channels, and the app's own bot messages in the
provider normalization layer. Do not subscribe to broad unrelated event families
as a substitute for that filtering.

If direct-message behavior is part of the sandbox test, enable the Slack app's
Messages/App Home capability so a test user can message the bot.

## 4. Expose the callback through a public HTTPS tunnel

Start the normal local stack first. The local web ingress remains
`http://localhost:3000` and the API remains behind its `/api` development proxy.
Use any approved HTTPS tunnel that forwards the public origin to local port
`3000`; no tunnel vendor is part of the product contract.

Then update only the ignored local `.env`:

```text
USI_SLACK_CALLBACK_URL=https://<dev-tunnel-host>/api/v1/providers/slack/events
USI_SLACK_CLIENT_ID=<non-secret-client-id>
```

Never put a Slack bot token, signing secret, or client secret in `.env`,
`.env.example`, Next.js configuration, browser code, command-line arguments,
Jira, or a committed file.

A valid public TLS certificate is required. If the tunnel hostname changes,
update both the Slack Events API Request URL and `USI_SLACK_CALLBACK_URL` so they
stay identical.

## 5. Install the app and configure `secret_ref`

Install/reinstall the Slack app to the development workspace after the scopes
are correct.

Two sensitive values are associated with the Slack integration:

- **Signing Secret** from the app's Basic Information/App Credentials area.
- **Bot User OAuth Token** from OAuth & Permissions after installation.

`E12-T02` currently consumes only the signing secret. The bot token is needed by
later outbound Slack work and must follow the same external-secret boundary.

`Integration.secret_ref` is an opaque **relative directory reference** below the
root configured by `USI_INTEGRATION_SECRETS_DIRECTORY`. For example, an
integration may persist the non-secret locator:

```text
slack/development-workspace
```

The runtime then resolves the signing secret from:

```text
<USI_INTEGRATION_SECRETS_DIRECTORY>/<secret_ref>/slack-signing-secret
```

The reference must be relative, may not contain traversal/backslash segments,
and the resolved file must remain physically below the configured secret root.
The resolver follows the same mounted-file model for `filesystem` and
`configtree` backends and does not bulk-import provider secrets into Spring's
Environment.

Create the directory/file using a method that does not put the secret value in
shell history. The signing-secret file must contain one non-empty value (an
ordinary trailing newline is accepted) and should have owner-only filesystem
permissions. Never commit that file or place it below the repository tree.

The database must never store the resolved signing secret or bot token in
`secret_ref`, `config_json`, workspace fields, errors, audit events, or raw
payloads. `secret_ref` stores the locator only.

## 6. Add the bot to monitored conversations

The app receives channel history/events only for conversations it is allowed to
access. After installation:

1. Invite the app to each public test channel that USI should monitor.
2. Explicitly invite it to each private test channel.
3. For DM testing, send a direct message to the app from a test user.
4. For group-DM testing, add the app only to a dedicated sandbox conversation.

Use test content only. Do not connect the development app to customer support
channels.

## 7. Request verification and acknowledgement contract

The implemented Slack HTTP boundary preserves these rules:

1. Read the **raw request body before JSON parsing**.
2. Read `X-Slack-Request-Timestamp` and `X-Slack-Signature`.
3. Reject timestamps outside the five-minute replay window.
4. Build the Slack v0 signature base string from the version, timestamp and
   exact raw body; verify HMAC-SHA256 with the signing secret using a
   timing-safe comparison.
5. Resolve candidate signing secrets only from non-disabled Slack Integrations;
   after signature verification, use the payload `team_id` to select the exact
   configured workspace (or one not-yet-bound configuring integration).
6. Handle `type=url_verification` only after request authenticity succeeds and
   return the supplied challenge in the required response shape.
7. For `event_callback`, require `event_id`, durably write/deduplicate the
   authenticated delivery in `inbound_events`, and only then return HTTP 2xx.
8. Heavy normalization/case processing does not run in the request thread; later
   tickets consume the durable inbound row asynchronously/idempotently.
9. Never log the signing secret, bot token, complete authorization headers, or a
   secret-bearing diagnostic dump.

The deprecated verification token from a URL verification payload is not a
replacement for signing-secret verification. The webhook endpoint is exempt
from browser CSRF because Slack cannot supply a browser CSRF token, but only the
exact POST callback route is anonymous/CSRF-exempt; authenticity is enforced by
the Slack HMAC boundary itself.

## 8. Application-side metadata

The Settings/integration model may display non-secret operational information
only, such as:

- configured/connected status;
- Slack workspace external ID and display name;
- health/last-event information;
- an indication that a secret reference is configured.

It must never return or render the resolved bot token or signing secret. Do not
paste credentials into workspace identity fields as a shortcut.

## Verification checklist

Before considering the development Slack app ready for provider work, confirm:

- the app belongs only to a test workspace;
- bot scopes match the reviewed table above and no broad extra scopes remain;
- bot event subscriptions are exactly `message.channels`, `message.groups`,
  `message.im`, and `message.mpim` for the current feature set;
- the Request URL and `USI_SLACK_CALLBACK_URL` both end in
  `/api/v1/providers/slack/events` and use the same public HTTPS tunnel origin;
- the tunnel forwards to local web port `3000`;
- `.env` is ignored by Git and contains no Slack secrets;
- the Integration row has a relative `secret_ref` and the corresponding
  `slack-signing-secret` file exists only below the approved external
  integration-secret root;
- no token/signing-secret value is present in `git diff`, logs, Jira, screenshots
  committed to the repo, or browser-visible configuration;
- Slack shows the Request URL as verified after the Integration/secret reference
  is configured;
- a signed test event receives HTTP 2xx and produces one durable `inbound_events`
  row even when Slack retries the same `event_id`.

## Cleanup and rotation

If a development credential is exposed, rotate/revoke it in Slack immediately,
remove the local secret material, and treat any committed occurrence as a
security incident rather than merely deleting the newest line. When a sandbox
is no longer needed, uninstall/delete the development app and remove its local
secret directory.

## Slack references

- Events API: https://docs.slack.dev/apis/events-api/
- HTTP Request URLs / URL verification: https://docs.slack.dev/apis/events-api/using-http-request-urls
- Request signature verification: https://api.slack.com/authentication/verifying-requests-from-slack
- Scope reference: https://docs.slack.dev/reference/scopes/
