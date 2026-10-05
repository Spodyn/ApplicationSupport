# Slack sandbox acceptance — USI-138

This runbook is the manual acceptance gate for the Slack integration. Automated tests stay deterministic and must not require real Slack credentials; the real-provider pass is executed only against the isolated staging/sandbox environment.

## Preconditions

- Use staging only. Do not use production data, production Slack credentials, or production channels.
- Use a dedicated Slack sandbox workspace and test channel mapped to a test Customer in USI.
- Use a dedicated active test agent account in USI.
- Provide Slack credentials through the existing secret-reference mechanism. Never commit tokens, signing secrets, cookies, or credential files to Git.
- Confirm the integration and mapped channel are enabled and healthy before starting.
- Record only non-secret evidence: Case references, timestamps, Slack `ts` / `thread_ts`, correlation IDs, statuses, and screenshots with sensitive values hidden.

## Minimum live text smoke test

Use this shorter path as soon as the sandbox Slack app is connected. It proves the basic two-way text flow before running the complete acceptance sequence below.

1. Start the local/staging USI stack according to the repository README and expose the web ingress (`localhost:3000`) through a public HTTPS tunnel.
2. Configure the dedicated Slack development app exactly as described in `docs/SLACK_DEVELOPMENT.md`, with the Events API callback ending in `/api/v1/providers/slack/events`.
3. Install/reinstall the app in the sandbox workspace, invite it to the test channel, and keep the Slack signing secret and bot token only in the external integration-secret directory referenced by `Integration.secret_ref`.
4. Confirm the Slack Integration is `ENABLED`/healthy and the sandbox channel is mapped to the intended test Customer.
5. Send one new root message from Slack. Pass condition: exactly one `NEW` Case appears in USI with that message.
6. Claim the Case and send one support reply from USI. Pass condition: the reply appears once in the same Slack thread.
7. Send one customer reply inside that Slack thread. Pass condition: it is appended to the same active Case and the Case becomes unread for the eligible test user.

If steps 5–7 pass, the basic Slack text integration is live-testable. Continue with the full resolve/successor, attachment, and controlled retry/error paths before marking USI-138 complete.

## Acceptance flow

### 1. Root Slack message creates the first Case

1. Post a new root customer message in the mapped Slack channel.
2. Confirm exactly one `NEW` Case is created in USI.
3. Confirm the first customer Message is visible in the Case and the Case points to the expected Slack integration/channel/customer.
4. Confirm the Slack root timestamp becomes the Case thread key.
5. Refresh/reload and verify the Case is not duplicated.

Evidence: Slack root `ts`, Case reference, Case status, customer Message timestamp.

### 2. Claim and support reply

1. Claim the Case as the test agent.
2. Confirm the Case moves to `VERIFICATION` and the agent is the owner.
3. Send a support reply from USI.
4. Confirm the outgoing Message is queued/sent and appears in the same Slack thread.
5. Confirm a retry/reload does not create a duplicate Slack or USI Message.

Evidence: Case reference, owner, support Message ID, Slack reply `ts`, parent `thread_ts`, final delivery state.

### 3. Customer thread reply and unread state

1. Reply from Slack inside the same thread.
2. Confirm the reply is appended to the same active Case.
3. Confirm no second Case is created while the first Case is non-terminal.
4. Confirm the customer reply marks the Case unread for eligible users according to the product contract.
5. Open/render the Case and acknowledge read state; confirm the current user's unread state clears without changing other users' state.

Evidence: Case reference, customer reply `ts`, message count before/after, unread state for the test user.

### 4. Resolve and linked successor

1. Resolve the current Case through the supported USI workflow.
2. Confirm the existing Case is terminal `RESOLVED` and remains unchanged afterwards.
3. Send another customer reply in the same Slack thread.
4. Confirm USI creates exactly one new `NEW` Case instead of reopening the resolved Case.
5. Confirm `new.related_case_id` points to the immediately previous resolved Case.
6. Send one more customer reply in the same Slack thread.
7. Confirm it is routed to the new successor Case and does not create a third Case.

Evidence: old and new Case references, old/new statuses, `related_case_id`, Slack `thread_ts`, message-to-Case mapping.

## Attachment path

Run this only with a small, safe test file supported by the configured Slack sandbox.

1. Send a customer Slack message with an attachment.
2. Confirm the attachment is represented on the correct Case/Message and follows the configured scan/download policy.
3. If outbound attachments are enabled in this environment, send one clean test attachment from USI and confirm it appears in the correct Slack thread.
4. Confirm no token-bearing provider URL or credential is exposed in UI, application logs, or persisted evidence.

Evidence: filename, size, attachment/Message IDs, scan/delivery state. Do not capture signed/private URLs.

## Controlled error and retry path

Use a reversible sandbox-only failure, for example temporarily supplying an invalid sandbox credential through the secret store or otherwise forcing a provider-side delivery failure without changing production configuration.

1. Trigger one outgoing Slack delivery failure.
2. Confirm the Message/attempt enters the expected retry/failure state and integration health/audit information is updated.
3. Restore the valid sandbox configuration.
4. Allow/re-run the supported retry path.
5. Confirm the Message is delivered once and no duplicate Case or Message is created.

Evidence: correlation ID, attempt/delivery states, health transition, final Slack `ts`. Never record the credential itself.

## Automated regression gates

Before accepting USI-138, all required repository checks must be green, including the relevant backend integration tests and the standard web/build/E2E/security gates required by `AGENTS.md`.

The automated Slack regression suite must cover at least:

- root message -> one Case and one Message;
- inbound idempotency/deduplication;
- active-thread reply -> existing Case;
- terminal-thread reply -> one linked successor Case with immutable terminal predecessor;
- subsequent replies -> the same successor generation;
- configured retry/rollback paths.

Real Slack credentials are intentionally not a public-CI dependency. CI proves deterministic application behavior; this runbook proves the live sandbox contract.

## Exit criteria

USI-138 can move to Done only when all of the following are true:

- automated repository gates are green;
- the complete happy path above passes in the isolated Slack sandbox;
- the terminal successor link is verified with real Slack traffic;
- attachment behavior is verified for the configured environment or explicitly marked not enabled/not applicable;
- one controlled error/retry path has been verified;
- no duplicate Cases or Messages are observed;
- no secrets or private provider URLs are committed, logged in evidence, or pasted into Jira/PR comments;
- Jira contains the final non-secret evidence and the tested build/commit reference.
