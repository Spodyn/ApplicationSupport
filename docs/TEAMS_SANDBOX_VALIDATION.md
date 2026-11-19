# Teams text sandbox validation — 2026-11-19

Publication copy: sandbox case labels are anonymized; original local evidence is not checked in.

This document records the earlier text-only phase. Later attachment/HTTP 202/deletion
fixes and live evidence supersede its outstanding-attachment note; see
[audit section 11](TEAMS_TECHNICAL_AUDIT_2026-11-19.md#11-poprawki-wykonane-po-audycie)
and the [publication validation handoff](TEAMS_PUBLICATION_2026-11-19.md).

Local implementation based on `main@f519bfc`; draft PR #46 (`agent/USI-180@fa8eda2`) was reviewed and its setup assets were reconciled locally. It remains a separate unmerged draft with conflicts. Current Jira USI-180 through USI-184 and E18 freeze were read. The owner's current request explicitly resumes the Teams sandbox work previously deferred by the epic. No Jira completion or merge is claimed.

## Implementation

- `provider/teams/internal`: Bot Connector RS256 authentication, durable PostgreSQL intake/outbox, RabbitMQ workers, supported-context normalization, persisted thread routes and normal Bot Connector text replies.
- Shared inbound boundary: accept Teams and deduplicate equivalent provider messages across terminal generations; null-thread mutations support linear group chats.
- `V50__teams_conversation_routes.sql`: additive routing state; N-1 ignores it. Flyway clean/previous-snapshot upgrade tests pass.
- OpenAPI callback operation and generated client refreshed through the generator.
- Setup renderer/package helper, explicit sandbox context seed and regression mapping added. No provider secrets or real tenant/app identifiers are checked in.
- Next.js/eslint-config-next moved from 16.4.1 to 16.4.6 after the build's vulnerability gate rejected the old patch. The workspace version assertion was updated; security checks were preserved.

## Local validation

| Check | Result |
| --- | --- |
| `pnpm install --frozen-lockfile` | PASS |
| `pnpm test:config` | PASS, 68 tests |
| Repository configuration, secret hygiene, OpenAPI lint/generation/client compile/adapter smoke | PASS |
| `pnpm --filter @usi/web check` | PASS: lint, typecheck, 104 unit tests, production build, 34 Chromium E2E |
| Maven `verify -DexcludedGroups=integration` | PASS, 298 tests after live-discovered fixes |
| Maven `test -Dgroups=integration` | PASS, 276 tests after live-discovered fixes |
| Teams/Slack provider suites | PASS, included in the complete final backend suites above |
| `pnpm test:fullstack` | PASS, final rerun: 20 tests including real disposable PostgreSQL/RabbitMQ/MinIO and synthetic Slack HTTP |
| Compose lifecycle | PASS, isolated project; Windows Python CRLF output normalized by the launcher |
| `pnpm audit --audit-level=high` | PASS, no known findings after the Next.js patch |
| Maven/OSV dependency scan | PASS, 250 packages, no findings |
| OpenAPI compatibility against `f519bfc` | PASS, additive |
| `git diff --check` / regression inventory | PASS |

The first behavioral regression failed because Teams was explicitly rejected by the shared inbound mapper. It passes after implementation. A separate failing package regression reproduced acceptance of unwanted personal static tabs; the renderer now rejects them.

## Sandbox evidence and remaining acceptance

- Teams-managed bot registration and Entra client credentials were created in the explicitly authorized new sandbox tenant. Token issuance returned HTTP 200; decoded non-secret application/tenant identifiers matched the configuration. Credentials remain in the existing external `secret_ref` store; temporary credential output was removed.
- Public HTTPS forwards to loopback Spring Boot. An unsigned callback returns 401; this proves reachability and rejection, not successful message intake.
- ZIP 0.1.1 has exactly `manifest.json`, `color.png` (192x192) and `outline.png` (32x32) at its root. PNG structure/CRC and ZIP integrity pass. Full official schema 1.21 validation with URI formats reports no errors. RSC remains exactly `ChannelMessage.Read.Group` and `ChatMessage.Read.Chat`; bot scopes remain `team` and `groupChat`.
- The CLI/service export reintroduces personal `conversations`/`about` static tabs. The reviewed local package excludes them. The original upload produced `Unknown`; that message alone does not prove causation. The corrected ZIP was installed in **USI Sandbox**; the Apps page shows version 0.1.1 and detailed read-team/read-chat permissions.
- **PASS, live 18:05–18:06 Europe/Warsaw:** a new support-test post without a mention created **sandbox-case-D3**. Two subsequent human replies without mentions entered the same Case. The inbox displayed all three customer messages. The Test User claimed the Case and sent a normal public reply through the UI. PostgreSQL records one SUPPORT Message with `SENT` and the provider ID; Teams displays that exact reply from USI Sandbox inside the same root-D thread as its third reply. Final Case total: three CUSTOMER Messages plus one SUPPORT Message. Integration health is `HEALTHY`.
- The live run discovered and reproduced two implementation bugs before their fixes: the Microsoft key document exceeded the original 256 KiB cap (observed 775,899 characters / 222 keys), and threaded-channel replies supplied `channelData.replyChain.id` without `replyToId`. The corrected loader reads a bounded 2 MiB and preserves JWT checks; the normalizer uses reply-chain or Connector conversation root identity. Synthetic regressions now cover both failures, oversized rejection/cache behavior and safe diagnostic logs.
- Earlier diagnostic posts/cases remain as test evidence; the successful complete scenario is sandbox-case-D3. No historical onboarding import was performed.
- **BLOCKED / NOT RUN:** group-chat directions. The tenant currently has one user and no group chat. Another participating account and a chat-specific install/RSC consent are required.

This is not completion of the full E18 contract. Attachments, recovery and complete installation/removal lifecycle remain outstanding. Connector HTTP has an unavoidable acceptance/recording crash window; ambiguous sends are surfaced for verification instead of automatically replayed. CI on a published exact PR HEAD and independent integration remain separate gates.
