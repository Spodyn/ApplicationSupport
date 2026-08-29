# ADR: Microsoft Teams RSC capability matrix

**Status:** FROZEN for v1  
**Ticket:** USI-179 / E18-T01  
**Vendor evidence reviewed:** 2026-08-29  
**Decision owner:** provider integration architecture

## 1. Decision

Unified Support Inbox v1 supports Microsoft Teams ingestion from:

- standard channels;
- group chats.

Private channels and shared channels remain **deferred/out of v1**. Microsoft now supports installing agents/apps in those channel types, but that vendor capability does not silently expand the frozen USI product scope. They require channel-specific installation and additional membership, privacy, storage, and (for shared channels) cross-tenant handling that must be designed and tested explicitly before a later scope change.

For ordinary messages without an `@mention`, the Teams app/agent uses **Resource-Specific Consent (RSC)** instead of tenant-wide message-read permissions.

The v1 manifest contract is:

- bot/agent manifest scope `team` for standard-channel coverage;
- bot/agent manifest scope `groupChat` for group-chat coverage;
- application RSC permission `ChannelMessage.Read.Group` for messages in the installed team;
- application RSC permission `ChatMessage.Read.Chat` for messages in the installed chat;
- no `ChannelMessage.Read.All`, `Chat.Read.All`, `Chat.ReadWrite.All`, or comparable organization-wide message-read permission merely to implement v1 inbound monitoring.

The machine-readable POC contract is checked in at `docs/poc/teams-rsc/manifest.contract.json` and is enforced by `scripts/tests/test_teams_rsc_capability_contract.py` in the configuration CI job.

### Manifest casing note

USI materializes a Microsoft Teams app manifest using schema **1.21**. The published schema at `https://developer.microsoft.com/json-schemas/teams/v1.21/MicrosoftTeams.schema.json` defines `bots[].scopes` values as `team`, `personal`, `groupChat`, and `copilot`. Some Microsoft samples still show historical lowercase `groupchat`; USI follows the schema literal `groupChat` so generated packages pass schema validation.

## 2. Why RSC

RSC grants access to a specific resource instance instead of the whole Microsoft 365 tenant. Microsoft documents team-, chat-, and meeting-scoped application RSC. A Teams app declares the requested resource-specific permissions in `authorization.permissions.resourceSpecific` in the app manifest; consent is associated with installation in the relevant resource.

For the receive-all-messages agent scenario, Microsoft currently documents that:

- `ChannelMessage.Read.Group` lets an installed app/agent receive channel messages without being directly mentioned;
- `ChatMessage.Read.Chat` lets an installed app/agent receive chat messages without being directly mentioned;
- the app manifest can contain both permissions when the app supports both team and chat installation;
- bot/agent manifest scopes are `team` and `groupChat` for those two installation models;
- chat receive-all behavior requires a new installation or re-installation after the RSC permission is present.

This is the least-privilege model aligned with the existing USI integration boundary: an Integration represents a configured Teams installation/resource, while credentials remain external through `secret_ref`.

## 3. Capability matrix

| Context | Microsoft platform capability | USI v1 disposition | Install/consent model for this POC | Receive ordinary messages without @mention | Notes |
|---|---|---|---|---|---|
| Standard channel | Supported | **SUPPORTED** | Install app in the host team (`team` scope); resource owner grants `ChannelMessage.Read.Group` RSC for that team | **YES** | One team-level RSC grant covers channels in that installed team; USI still discovers/configures individual monitored Channels separately. |
| Group chat | Supported | **SUPPORTED** | Install/re-install app in the target group chat (`groupChat` scope); grant `ChatMessage.Read.Chat` for that chat | **YES** | Consent is chat-specific. New/re-install is required after adding receive-all chat RSC. |
| Private channel | Microsoft supports agents/apps in private channels, with channel-specific enablement | **DEFERRED / OUT OF V1** | Host-team installation plus app added to the specific private channel; extra channel membership/privacy semantics apply | **NOT CLAIMED FOR V1** | Do not infer team membership equals private-channel membership. A later scope ticket must prove non-mention inbound behavior, member isolation, files/storage and removal behavior in a real sandbox. |
| Shared channel | Microsoft supports agents/apps in shared channels, with channel-specific enablement | **DEFERRED / OUT OF V1** | Host-team installation plus app added to the specific shared channel; host-team/host-tenant context and cross-tenant membership matter | **NOT CLAIMED FOR V1** | Incoming shared channels and cross-tenant users add installation and identity constraints. A later scope ticket must explicitly test same-tenant and cross-tenant cases. |
| 1:1 personal chat | Teams app platform supports personal agents, but this is not part of the frozen E18 v1 scope | **OUT OF V1** | `personal` scope would be a separate product decision | **NOT APPLICABLE** | The E18 contract is standard channels + group chats only. |

`SUPPORTED` above means supported by the frozen USI v1 contract and by the current Microsoft receive-all-messages RSC flow. `DEFERRED` means Microsoft has platform building blocks, but USI intentionally does not promise that behavior in v1.

## 4. POC manifest contract

The POC intentionally uses a **contract fragment**, not deployable credentials. Real Microsoft Entra app IDs, bot IDs, client secrets, certificates, tenant IDs and tokens must not be committed to the repository.

A deployable E18-T02 manifest must materialize the following shape with real non-secret identifiers supplied by its environment:

```json
{
  "bots": [
    {
      "scopes": ["team", "groupChat"]
    }
  ],
  "authorization": {
    "permissions": {
      "resourceSpecific": [
        {"type": "Application", "name": "ChannelMessage.Read.Group"},
        {"type": "Application", "name": "ChatMessage.Read.Chat"}
      ]
    }
  }
}
```

`webApplicationInfo.id` is the Microsoft Entra application ID. Microsoft requires `webApplicationInfo.resource` to be present for this RSC manifest flow even though the resource string itself has no RSC operation. E18-T02 owns the real app registration, bot/agent registration, package identifiers, callback endpoint, and secret-store wiring.

## 5. Verification plan and evidence

This ticket freezes capability before tenant-specific setup. It therefore separates **vendor capability verification** from **environment smoke testing**:

### 5.1 Verified in this ticket

1. Current Microsoft documentation explicitly states that RSC can deliver all channel/chat messages to an installed agent without `@mention` using `ChannelMessage.Read.Group` / `ChatMessage.Read.Chat`.
2. Current Microsoft documentation gives installation validation flows for both a team/channel and a group chat, including sending a non-mentioned message and observing receipt.
3. Current Microsoft documentation states that RSC is resource-scoped and declared in the Teams app manifest, avoiding organization-wide data access.
4. Current Microsoft documentation for private/shared channels requires separate channel enablement and warns not to treat team membership/storage/context as equivalent to those channels.
5. The checked-in POC contract is executable in CI and rejects scope/permission drift.

### 5.2 Live sandbox smoke owned by E18-T02 setup

No tenant credentials or production Microsoft 365 credentials are introduced merely to close this capability freeze. When E18-T02 creates the development Teams app/Entra/bot configuration, its sandbox installation must use the rendered schema-valid manifest and a non-production Microsoft 365 tenant. Actual inbound authentication/durable persistence belongs to E18-T03.

The E18-T02 installation smoke is:

- Teams Developer CLI login reports sideloading/custom app upload enabled;
- the app package installs successfully in one test team and one test group chat with the reviewed RSC consent;
- do **not** enable private/shared monitoring for v1;
- record only non-secret app/tenant identifiers and installation evidence; never record the generated client secret/token.

Once E18-T03 provides the USI Teams callback runtime, the same installed sandbox is used for end-to-end non-mention message receipt.

## 6. Private/shared channel decision

The current Microsoft channel model is more capable than the original v1 freeze: agents and tabs can be enabled for private/shared channels. That does **not** make them drop-in equivalents of standard channels.

Microsoft documents, among other differences:

- a host-team installation alone automatically covers standard channels but not private/shared channels;
- the app must be added to each private/shared channel;
- channel membership can differ from team membership;
- private/shared channels have separate SharePoint storage boundaries;
- shared channels can contain indirect/cross-tenant membership and expose host-team/host-tenant context;
- apps should use capability-based APIs rather than hard-code assumptions from `membershipType`/`channelType`.

USI depends on correct customer identity, channel mapping, access boundaries and provider grouping. Enabling private/shared channels without designing those semantics could leak or misroute customer data. They therefore remain deferred even though the vendor platform can host apps there.

## 7. Required follow-up boundaries

- **E18-T02 / USI-180:** create the real Teams app/Entra/bot development configuration from this contract, render/upload the schema-valid manifest, wire secrets only through the external secret store and validate sandbox installation in a test team + group chat.
- **E18-T03 / USI-181:** authenticate/validate inbound Teams activities or notifications, durably ingest them, and run the end-to-end no-mention message receipt smoke against the installed sandbox. It must not broaden context scope.
- Later Teams normalization tickets may create provider-neutral Channel/Message/Case commands only for contexts marked `SUPPORTED` here.
- Supporting private/shared channels requires an explicit scope change plus dedicated tests for membership isolation, channel install/remove lifecycle, cross-tenant identity, attachments/storage, and no-mention message delivery.

## 8. Vendor references reviewed

Authoritative Microsoft documentation/schema reviewed on 2026-08-29:

- `https://learn.microsoft.com/en-us/microsoftteams/platform/bots/how-to/conversations/channel-messages-for-bots-and-agents`
- `https://learn.microsoft.com/en-us/microsoftteams/platform/graph-api/rsc/grant-resource-specific-consent`
- `https://learn.microsoft.com/en-us/microsoftteams/platform/graph-api/rsc/resource-specific-consent`
- `https://learn.microsoft.com/en-us/microsoftteams/platform/build-apps-for-shared-private-channels`
- `https://learn.microsoft.com/en-us/graph/teams-changenotifications-chatmessage`
- `https://developer.microsoft.com/json-schemas/teams/v1.21/MicrosoftTeams.schema.json`

Vendor behavior can evolve. Technical implementation may follow newer compatible Microsoft guidance, but the frozen **USI v1 supported-context set may not expand without an explicit product decision**.
