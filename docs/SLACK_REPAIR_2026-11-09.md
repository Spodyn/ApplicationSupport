# Slack, development ingress and realtime repair — 2026-11-09

## Scope

Repair of the existing local Slack sandbox using the external server-side secret
resolver. No OAuth onboarding, new permission, provider scope expansion, production
deployment, database reset or infrastructure volume deletion was performed.
The user explicitly approved visible unassigned Slack intake; canonical product
and integration documents and the decision registry record that override.

## Root causes and changes

- Next.js 16 blocked development assets for the HTTPS tunnel origin. Added an exact,
  server-only USI_DEV_PUBLIC_ORIGIN allowlist and a development-only origin exception
  requiring both the configured Origin and matching Host. No wildcard CORS.
- The sandbox Slack callback was verified but unsaved, and its existing integration
  was disabled. The local setup was repaired using its existing secret reference.
  Credentials remain external to the repository and browser configuration.
- Channel discovery was manual-only. Added periodic authorized bot-membership
  reconciliation, complete-snapshot replacement, preservation of mappings/ignored
  flags, and durable retry for messages racing discovery.
- Unknown-customer channels previously discarded business intake. Slack Cases now
  persist customer NULL and display “Nie przypisano”. Existing non-null UUID read DTOs
  use a documented reserved zero UUID for that projection only, never a Customer.
- Local WebSocket origin handling excluded loopback web access after switching to
  an HTTPS public URL. Added narrowly scoped local-profile handling, preserving
  session authentication, loopback proxy validation and production origin checks.
- Bot filtering now checks is_bot before excluding an authorization user.
- Auth forms explicitly use POST if JavaScript hydration is unavailable.
- The downstream inbound handler previously rejected EDIT and DELETE. Mutations now
  lock and update the original Message, including terminal Case generations, with
  audit, unread and transactional message.updated/case.updated hints. Stale or duplicate
  edits are suppressed, deletion clears content while retaining identity, and late
  edits cannot resurrect deleted content. Missing originals retry durably.
- The audit timeline query uses jsonb_exists instead of a JDBC-conflicting question
  mark operator. Notification configuration tests choose an ephemeral HTTP port.

## Local functional verification before GitHub integration

- All 26 referenced public assets returned HTTP 200; an unapproved Origin remained 403.
- Login and authenticated Case reads returned 200. STOMP authenticated successfully
  over both loopback and the HTTPS tunnel; reconnect resumed updates after API restart.
- A real public-channel root created one Case. A support reply reached its Slack thread
  with delivery status SENT; a human thread reply appeared without a page refresh.
- A fresh private sandbox channel was discovered after inviting the bot, without SQL
  channel registration. Its human message appeared as unassigned intake and survived
  reload. Separate authenticated sessions returned identical persisted history.
- Signed root, reply, edit and delete callbacks were processed; duplicates were
  idempotent. Invalid signatures returned 401. Authenticated WebSockets received both
  conversation and global list update hints.
- Four existing failed edit/delete events were replayed against their original
  Messages after the fix. Attempts and identities were retained, replay was audited,
  and inbound DLQ was empty afterward.

## Validation before GitHub integration

These results belong to the local repair before integrating newer remote work;
validation of the final published commit is recorded in its pull request and CI.

- Frozen-lockfile installation, lint, TypeScript, production web build: PASS.
- Frontend unit tests: 70 passed; development-tool tests: 9 passed.
- Playwright: 9 passed.
- Maven verify: 449 passed, no failures, errors or skipped tests.
- Configuration/security contract tests under Linux: 46 passed.
- OpenAPI lint, generation consistency, client checks and additive compatibility: PASS.
- Secret scan, YAML parse and diff checks: PASS.

Earlier notification-delivery timing failures were retained in local diagnostics;
subsequent isolated and complete runs passed without weakening their assertions.
The Linux Maven wrapper exists; the prior local Windows run used installed Maven
because a Windows wrapper launcher was unavailable.

## Limits and operational notes

A physical second computer and real Slack Connect workspace were not available;
independent-session history and fake discovery responses cover those applicable paths.
Membership reconciliation runs every 30 seconds in the local profile. A new quick
HTTPS tunnel requires updating the exact public origin, callback configuration and
saved Slack Request URL, as described in OPERATIONS.md. No manual Slack authorization
step remained pending after the local acceptance test.

Discovery follows [Slack conversations.list](https://docs.slack.dev/reference/methods/conversations.list/)
and supported channel message scopes follow the [Slack message event contract](https://docs.slack.dev/reference/events/message/).
