# Slack and Teams administrator onboarding

Owner request: 2026-11-20. Each independent instance has its own public HTTPS
origin, provider bot and server secret references. Preserve existing local bots
and callbacks when adding staging.

## Administrator flow

1. Settings → Customers → Add customer: create the default Teams customer.
2. Settings → Integrations → Add Slack or Add Teams. Slack accepts connection name
   and workspace ID; Advanced optionally selects another server secret reference.
   Teams accepts name, a team link containing `groupId` or its GUID, and an active
   default customer. The server validates the link/tenant. Editing uses Configure.
3. Copy Callback URL. Complete provider setup below, then Test and Enable.
4. Automatically discover channels defaults on for new connections. Refresh
   channels requests durable discovery immediately; repeated requests respect API
   cooldowns. The panel shows readiness, last discovery, channel count and a safe
   error code. Existing installations retain effective settings.
5. Settings → Channels lists discovered channels. Change Customer or Monitoring
   individually. Rediscovery retains those choices. New Teams standard channels
   inherit the default customer; Slack may create an unassigned Case.

Teams readiness distinguishes missing VPS bot configuration, missing team/default
customer, pending installation/consent, discovery, ready and error. A successful
channel-list read does not silently enable an integration or prove outbound
delivery; finish the acceptance checklist below.

## Server configuration and secret store

Use the existing `USI_INTEGRATION_SECRETS_DIRECTORY` mount. Files must be readable
by API UID/GID 10001 and protected from other host users (for example root:10001
directories 0750 and files 0640). Never put token/secret values in browser forms,
runtime environment variables, repository files or logs.

| Setting | Meaning |
| --- | --- |
| `USI_JAVA_PREFER_IPV4` | Prefer IPv4 when a VPS has no usable container IPv6 route; staging defaults true |
| `USI_PUBLIC_BASE_URL` | This instance's HTTPS origin |
| `USI_SLACK_DEFAULT_SECRET_REF` | Default reference, e.g. `slack/staging` |
| `USI_TEAMS_DEFAULT_SECRET_REF` | Default reference, e.g. `teams/staging` |
| `USI_TEAMS_CLIENT_ID` | Separate bot/application GUID |
| `USI_TEAMS_TENANT_ID` | Entra tenant GUID |
| `USI_TEAMS_APP_ID` | Optional Teams manifest app GUID; defaults to client ID |
| `USI_SLACK_CHANNEL_DISCOVERY_ENABLED` | Legacy Slack fallback; staging defaults true |

The reference directory contains `slack-bot-token` and `slack-signing-secret` for
Slack, or `teams-client-secret` for Teams. Existing explicitly selected references
are preserved. A Teams draft saved before VPS setup adopts the first configured
server tenant during discovery/test, audits that binding, and remains CONFIGURING;
a previously configured tenant is never silently replaced. Apply nonsecret deployment configuration through the normal gated
deployment; file-store credentials are resolved on demand. No development secrets
are copied. `V55` adds configuration state without rewriting existing channels,
secret references, resync controls or conversation history.

## Slack account steps

Create a separate app in the test workspace. Bot scopes:
`channels:read`, `channels:history`, `groups:read`, `groups:history`, `chat:write`,
`files:read`, `files:write`. Install it and place its Bot User OAuth Token and
Signing Secret in the protected server store. No user token, DM scope or
`chat:write.public` is required.

In Event Subscriptions, enable Events API and set Request URL to
`https://<instance>/api/v1/providers/slack/events`. Verify the challenge and
subscribe to `message.channels` and `message.groups`; save. Invite the staging bot
to a test channel. The existing local app's Request URL remains unchanged.

Official references: [scopes](https://docs.slack.dev/reference/scopes/),
[Events API](https://docs.slack.dev/apis/events-api/),
[conversations.list](https://docs.slack.dev/reference/methods/conversations.list/).

## Teams account steps and consent

Create a separate Teams-managed bot in
Developer Portal → Tools → Bot management → Teams-managed bots → New bot.
Configure → HTTPS endpoint:
`https://<instance>/api/v1/provider-callbacks/teams`. Create a client secret and
save its **value**, not the secret ID, in the protected server store. Set its
public bot/application GUID and tenant GUID in the instance configuration.

After selecting the team in Application Support, use Settings → Integrations →
the Teams card → Download Teams package. The authenticated ZIP contains exactly
`manifest.json`, `color.png` (192×192) and `outline.png` (32×32), with instance IDs
and HTTPS URLs. It contains no secret. It uses the separately versioned
`apps/api/src/main/resources/teams/manifest.template.json`; the older development POC
template remains historical evidence and must not be reused for this flow.

In Teams: Apps → Manage your apps → Upload an app → Upload a custom app → select
the ZIP → Add to a team → choose the selected team and test channel → Add.
For an existing installation, Manage your apps → the app → Upload/Update the
package, then review the renewed permission consent. Labels can vary with Teams
language/version or tenant custom-app policy. A team owner or tenant administrator
must allow custom app installation if that action is unavailable.

Approve only these resource-specific **Application** permissions for the chosen
team (the wording is localized by Microsoft):

| Permission | Consent meaning |
| --- | --- |
| `ChannelMessage.Read.Group` | Read this team's channel messages, without a bot mention |
| `ChannelSettings.Read.Group` | Read this team's channel names and settings for discovery |

The manifest bot scope is only `team`. No tenant-wide `ChannelMessage.Read.All`
or `Channel.ReadBasic.All`, group-chat grant, or automatic file-site grant is
requested. RSC consent applies to the installed team. File transfer retains its
separately authorized selected-site model.

The team link's `groupId` is the Graph `/teams/{team-id}` GUID. Bot activities may
instead carry an opaque `channelData.team.id` beginning with `19:`. Do not store
that opaque value as the Graph team ID. Authenticated `aadGroupId`, or Connector
team-details lookup of that opaque ID, must match the selected GUID before raw
inbound payload persistence. The verified mapping is cached against that exact
selection. Tenant identity alone, another team's event and group chats are denied.

Official references:
[channel list and RSC](https://learn.microsoft.com/en-us/graph/api/channel-list),
[RSC permissions](https://learn.microsoft.com/en-us/microsoftteams/platform/graph-api/rsc/resource-specific-consent),
[team context](https://learn.microsoft.com/en-us/microsoftteams/platform/bots/how-to/get-teams-context),
[channel lifecycle events](https://learn.microsoft.com/en-us/microsoftteams/platform/bots/how-to/conversations/subscribe-to-conversation-events).

## Acceptance evidence

Automated suites use synthetic provider HTTP and disposable PostgreSQL, with
workers driven deterministically. They cover pagination, selected-team identity,
foreign-team rejection before persistence, immediate first message, preserved
customer/ignored flags, lifecycle discovery, overdue reconciliation, expired leases,
stale-worker fencing, API cooldowns, missing bot state and team-only ZIP contents.
Existing resync tests continue to cover message identity, SLA and recovery.

Real acceptance must separately record: Slack invite/new channel/immediate message;
Teams new standard channel/immediate message; Cases and replies to the original
thread; discovery after API restart; foreign team denied before persistence;
per-channel customer and monitoring edits. Test-specific account authorization is
required. A prepared bot or a green synthetic test is not a live acceptance pass.

Staging acceptance and deployment progress are recorded in
[PROVIDER_ONBOARDING_VALIDATION.md](PROVIDER_ONBOARDING_VALIDATION.md).
