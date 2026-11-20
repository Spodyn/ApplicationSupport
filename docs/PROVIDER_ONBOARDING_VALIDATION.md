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
