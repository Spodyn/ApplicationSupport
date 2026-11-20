# Provider onboarding validation

Target: `https://application-support.duckdns.org`. Work in progress; no acceptance
pass is claimed until the new code is deployed and observed.

- Staging preflight: SSH and HTTPS available; running application revision
  `064ab5dad74de4fffd24220ebe2086bd5e36792f` before this change.
- Separate Slack app `Application Support Staging`, app ID `A0CKLHER5EE`, test
  workspace `TestApp` (`T0C6P3JEDU5`). OAuth installation consent approved by owner.
  Existing `USI Development` app and local callback preserved.
- Separate Teams bot `Application Support Staging`, ID
  `c8ef68ac-093d-4fa9-90cb-e0d87d9eefa0`; HTTPS callback configured to staging.
- USI Sandbox canonical Team ID read from the Teams channel link:
  `16e93378-cfa9-4f5e-a0fb-e0cb4350e91a`; tenant
  `88648ad9-4425-4ec7-a1ab-cecff56b1fa5`.
- Public bot identifiers, server secret references and legacy Slack discovery flag
  saved in the protected staging runtime configuration; restart deferred.
- Credential entry is an owner handoff directly to hidden VPS terminal prompts.
  No secret values are included in source or this record.
- Creating `Teams Sandbox` through the existing staging panel exposed an existing
  null external-reference audit failure; regression test and fix included here.

| Scenario | Result |
| --- | --- |
| New code staging deployment | NOT RUN — requires reviewed main and all required CI gates |
| Slack callback challenge and immediate message/reply | NOT RUN |
| Teams package installation/RSC approval | NOT RUN |
| Teams new channel, immediate message/reply | NOT RUN |
| Teams restart discovery / foreign-team real callback | NOT RUN |

Local and CI commands/results will be appended after execution.

## Executed checks

- Clean worktree install: `pnpm install --frozen-lockfile` passed, no local `.env` copied.
- Configuration/gate/inventory/OpenAPI checks passed during `pnpm check`; its first
  browser run exposed an overly exact test label selector, corrected and rerun.
- `pnpm --filter @usi/web check`: lint, strict typecheck, 111 unit tests, production
  build and all 40 Playwright browser scenarios passed.
- Backend unit/architecture `verify -Pcoverage -DexcludedGroups=integration`:
  340 passed at the initial implementation snapshot. New throttling test passed
  in its targeted follow-up.
- Initial full PostgreSQL/provider integration run: 310 executed, one generic
  fake-tester fixture collided with the newly implemented real Teams tester.
  The generic probe now uses Telegram; all 8 integration-service assertions and
  3 parser/rate-limit tests passed in the follow-up. Final CI must run the complete
  integration gate on the final HEAD; this initial failing run is not a green gate.
- Targeted onboarding/channel-discovery/migration/customer/API suite: 27 passed.
  Customer creation without external reference was first reproduced as HTTP 500,
  then fixed and verified as HTTP 201 with transactional audit.
- `pnpm test:fullstack`: all 21 passed, including real admin create/edit/default
  customer persistence, missing-VPS readiness, signed Slack intake/replies,
  attachments, SLA, CSRF/session and authenticated realtime.
- `pnpm audit --audit-level=high`: no known vulnerabilities.
- `pnpm openapi:compat -- --base-ref 6a85ab0...`: additive compatibility passed.
- Staging Teams ZIP exported using the application's generator and validated
  against Microsoft's full 1.21 JSON schema: PASS. Owner must select it manually
  in Teams because the Brave extension cannot currently upload local files.

## Live staging follow-up

- Existing trusted main deployment was retried using the normal GitHub workflow
  after the previous DNS failure. Run 44737825608 succeeded; live revision is now
  `6a85ab077c2f14d12b023aabd67909b6e7f43308`. This does not include PR #163.
- Teams Sandbox customer and Slack staging connection were created through the
  administrator UI. Existing local Slack app configuration was preserved.
- Slack bot token exists in the protected store; live `auth.test` from the host
  passed for the expected test workspace. Signing Secret is still missing at the
  last inspection. The entry helper now resumes missing fields without replacing
  a working token; initial invalid signing-secret input no longer blocks resumption.
- Teams secret exists with protected permissions; client-credentials acquisition
  of a Microsoft Graph token passed. This does not prove installed-team RSC consent.
- Java HTTP inside the API timed out with the default network stack. The identical
  non-authenticated Slack request returned HTTP 200 with `preferIPv4Stack=true`.
  PR #163 adds the instance-specific `USI_JAVA_PREFER_IPV4` switch (staging default
  true), retaining TLS checks. Current-main connection-test failure is recorded;
  the new startup option requires the new image/deployment.
- Full live message/reply acceptance remains NOT RUN pending the missing Slack
  Signing Secret, Teams installation/consent, and gated deployment of PR #163.
