# Slack and Microsoft Teams history recovery

Owner-requested contract extension, 2026-11-20. This replaces the older Slack-only
seven-day recovery cap. The Jira connector returned 403 (app not installed) while
resolving this request; no Jira state, comments, approval or completion was invented.

## Administrator controls

Open **Settings → Integrations → Slack / Microsoft Teams → Message recovery
(resync)** (Polish: **Ustawienia → Integracje → Odzyskiwanie wiadomości (resync)**).
Enable **Enable resync / Włącz resync**, then **Save resync settings**.
Existing integrations are disabled by default. ADMIN and `manage_integrations`
are required for all three API operations.

- Manual: select **From date / Od daty**, enter local date/time, or select
  **From the beginning of available history / Od początku dostępnej historii**;
  click **Start resync / Uruchom resync**. The server captures an upper boundary.
- Automatic: enable **Automatic resync**, set interval and history window in
  minutes, then save. `30` and `2880` mean every 30 minutes / last two days.
  Polling/browser presence is unnecessary for scheduling. Last/next run and
  per-channel results remain available after a restart.

Each page enters durable inbound processing immediately. The panel distinguishes
scanning, retry waiting, processing inbound messages, completion and incomplete
recovery, with scanned/added/pending counts and sanitized channel error codes.
Added counts count actual new business Messages, not duplicate provider events.
An active integration scan must finish before another manual scan starts.
An unfinished automatic scan resumes its original range; an overdue schedule
remains due and scans the current configured window when it can start.

Both providers scan old roots to discover in-window replies. This can require
many reads even for a short window. There is no arbitrary message/page cap on
all-history; the accessible provider history, retention and permissions are the
limits. Older roots used only for context do not create out-of-range Messages.

## Provider setup

Slack: retain the existing app membership/discovery and access restrictions for
public/private/Slack Connect channels; no DM/group DM import. Configure
`channels:history` and `groups:history` as appropriate and reinstall/reauthorize
the app after scope changes. `files:read` remains required for attachments.
Use the existing `slack-bot-token`; an optional `slack-history-token` file under
the integration's existing server-side `secret_ref` can supply an authorized
history/user token if channel thread reads reject the bot token. No token enters
the panel or database. A missing scope/channel membership remains a visible
incomplete result. [History API](https://docs.slack.dev/reference/methods/conversations.history/)
and [replies API](https://docs.slack.dev/reference/methods/conversations.replies/).

Teams uses Microsoft Graph **v1.0**, not Bot Connector, to enumerate old Messages.
Keep the existing approved `standardChannel` or `groupChat` mapping. For channel
posts/replies, prefer resource-specific application `ChannelMessage.Read.Group`
on the installed team. For group chats, use `ChatMessage.Read.Chat` on the
installed chat. `ChannelMessage.Read.All` / `Chat.Read.All` require broader
administrator consent and are not granted by this change. Existing sandbox file
access remains `Sites.Selected` on explicitly approved sites; no new storage
grant is made. [Channel posts](https://learn.microsoft.com/en-us/graph/api/channel-list-messages),
[replies](https://learn.microsoft.com/en-us/graph/api/chatmessage-list-replies?view=graph-rest-1.0),
[chat messages](https://learn.microsoft.com/en-us/graph/api/chat-list-messages).

The existing Teams app client/tenant and `teams-client-secret` supply Graph
application tokens. For standard channels, recovery needs the Graph team GUID:
`channels.metadata_json.teamsTeamId`, or `channelData.team.aadGroupId` from a
previous authenticated stored activity in that channel. It also needs a stored
Bot Connector route from a previous activity to preserve existing outbound/file
routing. Missing context is shown as `TEAMS_HISTORY_TEAM_CONTEXT_MISSING` or
`TEAMS_HISTORY_BOT_ROUTE_MISSING`; receive a sandbox activity or repair the
approved channel setup, then start another scan. Private/shared channels remain
outside scope. Graph 403 is `TEAMS_HISTORY_HTTP_403`; repair RSC/installation
consent before rerunning. Permissions and routes are never guessed.

## Persistence, SLA and safety

V54 is additive and compatible with the immediately previous application:
settings, runs, per-channel/thread tasks and nullable recovery references.
No existing rows are opted in. Each page has an independent lease token;
an expired worker cannot commit after another worker reclaims its task. Provider
HTTP happens outside transactions. Inbound events, thread discovery and page
checkpoints commit together; failed pages are safe to replay. Retryable provider
outages have durable retry deadlines without an attempt cap; rate-limit delays
also defer queued work for that integration. Read permission failures are terminal
for that task and visible per channel, while independent channels continue.

The existing provider Message identity lock serializes webhook/resync insertion
across Case generations. The first persisted Message owns its provenance and
SLA. Only server-written inbound task references establish recovery provenance;
provider JSON cannot label a Message as recovered. Original send time is stored
in `provider_created_at`, addition time in `created_at`, recovery source in
`recovery_source`. For recovered Messages, transaction time aligns new Case FRT
and Message insertion; NRT uses the same insertion timestamp. Existing business
calendars, cycle guards, unread and attachment processing remain authoritative.

## Validation boundaries

Automated suites cover both providers' paging/old-thread replies/range filters,
manual mode requests, durable schedule windows, 240 consecutive 30-second retry
intervals modeling a two-hour outage, lease recovery/fencing, partial permission
failure, duplicate webhook commands, provenance/unread and insertion-time FRT/NRT.
Graph HTTP tests use a loopback server. Database tests use disposable PostgreSQL.
Frontend tests cover transport mapping, controls and the orange conversation
badge. See [regression inventory](REGRESSION_MATRIX.md).

Real Slack/Teams **resync** sandbox acceptance is **NOT RUN** in this change.
Historical Teams text/file evidence is not resync certification. No local `.env`,
production provider, live token or tenant permissions were used by automated
tests. A dedicated authorized sandbox and securely supplied test credentials
are required for real outage/recovery acceptance. Full repository gate results
are recorded separately in the completion report; a green focused suite alone
does not establish release acceptance.
