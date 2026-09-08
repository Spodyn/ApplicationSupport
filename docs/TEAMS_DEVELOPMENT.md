# Microsoft Teams development sandbox setup

This guide is the reproducible development-only setup contract for the Unified Support Inbox Teams integration (`E18-T02` / USI-180). It follows the frozen RSC capability matrix in [`TEAMS_RSC_CAPABILITY.md`](TEAMS_RSC_CAPABILITY.md) and the Microsoft Teams app manifest schema 1.21.

Do **not** use a production Microsoft 365 tenant, customer team, production credentials, or production bot registration while following this guide.

## Frozen development contract

USI v1 supports only:

- standard channels through Teams manifest scope `team` + application RSC `ChannelMessage.Read.Group`;
- group chats through Teams manifest scope `groupChat` + application RSC `ChatMessage.Read.Chat`.

Private/shared channels remain deferred and personal/Copilot scopes are out of this v1 setup. Do not add tenant-wide message-read permissions such as `ChannelMessage.Read.All`, `Chat.Read.All`, or `Chat.ReadWrite.All`.

The development bot messaging endpoint is the existing reviewed Teams provider callback placeholder:

```text
https://<dev-tunnel-host>/api/v1/provider-callbacks/teams
```

The HTTPS tunnel forwards to the normal local web ingress on `http://localhost:3000`; Next.js then forwards `/api/*` to Spring Boot. USI-181 owns the authenticated Teams callback runtime at this provider boundary. USI-180 validates registration, manifest/RSC consent and sandbox installation; it does not fake successful inbound processing before USI-181 exists.

## 1. Prerequisites

Use a dedicated non-production Microsoft 365 tenant/account with custom app upload (sideloading) enabled. Current Microsoft Teams Developer CLI documentation requires Node.js 20+ and a public HTTPS tunnel to the bot endpoint.

Install the current Teams Developer CLI outside the repository dependency graph:

```bash
npm install -g @microsoft/teams.cli
teams --version
teams login
teams status
```

`teams status` must report that sideloading/custom app upload is enabled. If it is disabled, this sandbox cannot be validated until the test tenant administrator enables custom app upload. Do not work around tenant policy with a production tenant.

## 2. Start USI and expose the callback

Start the normal local stack and API/web processes, then create an approved HTTPS tunnel to local web port `3000`.

Update only the ignored local `.env` with non-secret values:

```text
USI_TEAMS_CALLBACK_URL=https://<dev-tunnel-host>/api/v1/provider-callbacks/teams
```

The checked-in `.env.example` remains an inert `.invalid` placeholder. The bot endpoint configured in Microsoft must be exactly the same HTTPS callback URL.

## 3. Register the development Teams app/bot

Microsoft's current CLI can create a Teams app plus bot infrastructure from an existing HTTPS server endpoint. **Do not point its `--env` option at the repository `.env` or `.env.example`**, because the CLI writes a generated client secret to that file.

Create a temporary credential file outside the repository:

```bash
TEAMS_TMP_DIR="$(mktemp -d)"
teams app create \
  --name "USI Development" \
  --endpoint "https://<dev-tunnel-host>/api/v1/provider-callbacks/teams" \
  --env "$TEAMS_TMP_DIR/teams-created.env"
```

The command prints the non-secret Teams App ID/install information and writes generated bot/Entra values to the temporary file. Treat that file as secret material from the moment it is created.

Record only these non-secret identifiers in the ignored local `.env`:

```text
USI_TEAMS_CLIENT_ID=<bot-or-entra-client-guid>
USI_TEAMS_TENANT_ID=<development-tenant-guid>
```

Never commit the actual identifiers merely to make CI pass; `.env.example` stays empty because deployments can use different registrations.

## 4. Move the generated secret to the integration secret store

The Teams client secret must **not** remain in `.env`, Spring Environment, Jira, shell history, screenshots, or the repository. It belongs below the external root configured by `USI_INTEGRATION_SECRETS_DIRECTORY`.

Choose a non-secret relative `Integration.secret_ref`, for example:

```text
teams/development-sandbox
```

Store the generated client secret as a single-line file at:

```text
<USI_INTEGRATION_SECRETS_DIRECTORY>/<secret_ref>/teams-client-secret
```

Use owner-only filesystem permissions and a copy/move method that does not echo the secret. After verifying the external secret file exists, securely remove `$TEAMS_TMP_DIR`. The database stores only `secret_ref`; it must never contain the resolved secret.

USI-181 will consume this same key name when it implements Teams inbound authentication. If Microsoft generates additional provider credentials in a later flow, add separate reviewed secret-store keys rather than bulk-importing them into application environment variables.

## 5. Render the reviewed app manifest

The checked-in template is `docs/poc/teams-rsc/manifest.template.json`. It contains no credentials and intentionally has only non-secret identifier placeholders.

Render it to an ignored temporary location:

```bash
python scripts/render_teams_dev_manifest.py \
  --teams-app-id <teams-app-guid> \
  --bot-client-id <bot-or-entra-client-guid> \
  --public-base-url https://<dev-tunnel-host> \
  --output /tmp/usi-teams-manifest.json
```

The renderer fails unless:

- IDs are valid GUIDs;
- the public base URL is HTTPS and contains no credentials/query/fragment;
- manifest schema/version is the reviewed Teams 1.21 schema;
- bot scopes are exactly `team` and `groupChat`;
- RSC permissions are exactly `ChannelMessage.Read.Group` and `ChatMessage.Read.Chat` as `Application` permissions;
- `webApplicationInfo.id`/resource match the standalone bot Entra client ID;
- no known credential fields or unresolved placeholders are present.

The renderer uses the schema literal `groupChat`. This is deliberate: the published v1.21 schema enumerates `groupChat` for `bots[].scopes`, even though some older/current Microsoft examples still contain lowercase `groupchat`.

For an additional Microsoft-side schema/package check, Agents Toolkit supports:

```bash
atk validate --manifest-path /tmp/usi-teams-manifest.json
```

and, for a complete app package with icons:

```bash
atk validate --app-package-file-path <sideload-package.zip>
```

These are vendor validators, not substitutes for the checked-in contract tests.

## 6. Apply the manifest to the registered development app

The Teams Developer CLI supports applying a local manifest to an existing registered app:

```bash
teams app manifest upload <teams-app-id> /tmp/usi-teams-manifest.json
```

Retrieve/download the current sideload-ready package from that registered app using the CLI's `teams app package download` flow, or use Developer Portal for Teams. A sideload package contains the manifest plus required icons at the ZIP root.

Do not commit the downloaded package: it is environment-specific and contains real app identifiers. Do not replace the reviewed RSC permissions interactively in Developer Portal after upload; local contract/template is the source of truth.

## 7. Validate sandbox installation

This is the live acceptance step for USI-180 and requires a real non-production Microsoft 365 tenant session.

### Standard team/channel installation

1. In a dedicated test team, choose the Teams custom-app upload/install flow.
2. Install the rendered development app for the team.
3. Confirm the installation/upgrade dialog requests the reviewed resource-specific channel permission and no tenant-wide message-read permission.
4. Confirm the app is present for the team/standard channel context.

### Group-chat installation

1. Open a dedicated test group chat -> **Manage apps** -> custom app upload/install.
2. Install/re-install the same development app in that chat.
3. Confirm the `ChatMessage.Read.Chat` RSC consent is shown and accepted.
4. Confirm the app is present in the group chat.

Microsoft documents that receive-all chat behavior is enabled only after a new installation or re-installation with `ChatMessage.Read.Chat`; always reinstall after changing that permission.

Because USI-181 owns the callback authentication/runtime, USI-180's live evidence is **successful installation + correct RSC consent in both supported resource types**. End-to-end no-mention message delivery into `inbound_events` is verified after USI-181 is implemented, using this same sandbox registration.

## 8. Evidence that may be recorded

Safe evidence:

- Teams App ID / Entra client ID / tenant ID only when operationally necessary (these are identifiers, not credentials);
- CLI/app package validation success;
- sideloading enabled status;
- app installed in a named development-only test team and test group chat;
- consent screen shows the two reviewed RSC permissions;
- timestamp and sanitized failure codes if setup fails.

Never record:

- client secret value;
- access/refresh tokens;
- authorization headers;
- private keys/certificates containing private material;
- downloaded credential files;
- production/customer conversation content.

## 9. Cleanup/rotation

When the sandbox is no longer needed:

1. remove the app from the test team/group chat;
2. delete the development Teams app/bot/Entra registration if it is no longer shared by an approved test environment;
3. revoke/rotate any generated secret that may have been exposed;
4. remove the local `teams/<sandbox>` secret directory;
5. remove ignored local identifiers from `.env` if the registration no longer exists.

Treat a committed secret as a security incident; deleting a later commit is not sufficient remediation.

## Verification checklist

Before USI-180 can be marked complete:

- [ ] `teams status` confirms sideloading is enabled in a non-production M365 tenant;
- [ ] public HTTPS endpoint matches `USI_TEAMS_CALLBACK_URL` and forwards to local web ingress port 3000;
- [ ] Teams app/bot registration exists only for development/sandbox use;
- [ ] client secret exists only under the external `secret_ref` store as `teams-client-secret` and temporary CLI credential output is removed;
- [ ] rendered manifest passes the repository renderer/contract and Microsoft manifest/package validation;
- [ ] bot scopes are exactly `team` + `groupChat`;
- [ ] application RSC permissions are exactly `ChannelMessage.Read.Group` + `ChatMessage.Read.Chat`;
- [ ] app installs successfully in one test team and one test group chat with the expected RSC consent;
- [ ] private/shared/personal/Copilot contexts are not enabled by this setup;
- [ ] no provider secret appears in git diff/history, Jira, logs, screenshots, browser-visible configuration, or checked-in package files.

## Microsoft references reviewed

- Teams Developer CLI registration quickstart: https://learn.microsoft.com/en-us/microsoftteams/platform/teams-sdk/teams/configuration/manual-configuration
- Teams manifest/sideloading: https://learn.microsoft.com/en-us/microsoftteams/platform/teams-sdk/teams/manifest
- Receive all channel/chat messages with RSC: https://learn.microsoft.com/en-us/microsoftteams/platform/bots/how-to/conversations/channel-messages-for-bots-and-agents
- RSC permissions: https://learn.microsoft.com/en-us/microsoftteams/platform/graph-api/rsc/resource-specific-consent
- Teams app manifest schema 1.21: https://developer.microsoft.com/json-schemas/teams/v1.21/MicrosoftTeams.schema.json
- Agents Toolkit manifest/package validation: https://learn.microsoft.com/en-us/microsoftteams/platform/toolkit/teamsfx-preview-and-customize-app-manifest
