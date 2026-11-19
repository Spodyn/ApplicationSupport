# Separate provider acceptance

Ordinary PR/scheduled regression uses only synthetic providers. Real Slack acceptance is **NOT RUN / BLOCKED** until a dedicated nonproduction workspace, test-only app, disposable channel, explicit owner authorization and securely supplied short-lived sandbox credential are available. Never reuse the developer `.env`, customer workspace, production bot or repository secrets automatically. Do not publish tokens/payloads in artifacts.

Once explicitly authorized, run the standalone read-only probe:

```bash
USI_SANDBOX_SLACK_BOT_TOKEN=<securely-supplied-test-token> \
USI_SANDBOX_SLACK_TEAM_ID=<expected-test-workspace> \
python3 scripts/testing/provider_sandbox.py --confirm-sandbox
```

This probe checks actual Slack authentication, workspace identity and scoped channel discovery. It does not send messages. Complete sandbox acceptance additionally needs an authorized isolated conversation: signed intake, root/thread grouping, upload/download, send/retry/rate-limit, archived-channel refusal and cleanup, with evidence redacted. No live credential or permission was available in this task; synthetic HTTP success is not live-provider certification.

The Teams text adapter now has synthetic JWT, durable intake, grouping and Connector reply tests; setup and remaining limitations are in [TEAMS_DEVELOPMENT.md](../TEAMS_DEVELOPMENT.md). This is not certification of the complete Teams integration. Teams attachment evidence is recorded in [the 2026-11-19 audit](../TEAMS_TECHNICAL_AUDIT_2026-11-19.md); group-chat live acceptance, recovery and Telegram outbound remain incomplete. Telegram inbound has deterministic webhook and persistence tests. Load, long outage/recovery, physical-device PWA and deployed infrastructure/ClamAV checks remain separate pre-release acceptance, not ordinary PR gates.

Probe reference: [Slack auth.test](https://docs.slack.dev/reference/methods/auth.test/) and [conversations.list](https://docs.slack.dev/reference/methods/conversations.list/) (checked 2026-11-10).
