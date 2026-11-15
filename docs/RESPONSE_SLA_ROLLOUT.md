# Response SLA and resolution time

Baseline main commit: 7a828b57e572a0a99a9d5e3d0d4fff0c0b31f121 (merged workflow PR #156).

| Area | Before | After |
| --- | --- | --- |
| FRT | Case creation timestamp and a persisted deadline. A seeded installation often had a 60-minute target. | First customer provider timestamp and the Case's policy/calendar snapshot. Default 15 business minutes. Successful human public delivery ends FRT. |
| NRT | No durable follow-up clock. | First durable customer follow-up after a confirmed human reply opens a 30-business-minute cycle. Retries, edits, notes and further messages preserve its anchor. Accepted human delivery or terminal workflow ends it. |
| Warning and queue | Legacy state advanced by a scheduled worker and mixed the FRT, unclaimed and in-progress limits. | Warning at 25% of the active response target remaining. The list, filter, count and Case panel use the same SQL projection. Waiting displays a pause and terminal Cases are neutral. |
| TTR | Elapsed time from Case creation. | Elapsed time and working business time minus configured waiting pauses and closed intervals between resolution and reopen. Unclaimed/in-progress diagnostics and reports remain available. |

The Case row serializes customer acceptance, successful delivery checkpoint and terminal changes. When a customer message is committed before an in-flight send is confirmed, the delivery can complete the outstanding clock but cannot switch the conversation to waiting on stale information. A new customer message committed after confirmation can open NRT. Provider HTTP remains outside the database transaction. Slack equivalence and Slack/Telegram event delivery use the existing inbound deduplication.

The active state changes at the persisted warning and deadline instants. A completed reply at the deadline meets it. The badge reports signed business time and shows the localized deadline on hover. The API remains authoritative; the web queries refresh the queue and counters within 15 seconds without messaging activity and refresh on reconnect. The aging notifications and case-scoped FRT notification behavior retain their existing deduplication.

## Migration V49

Keep original Case, message, audit and SLA rows intact. Existing first-response anchors and deadlines retain their original semantics, including known terminal stops. The seeded version-one 60-minute default is updated to 15 minutes for new Cases, while customized FRT configuration is preserved. Existing cases gain an NRT policy snapshot at their first provable new follow-up after cutover. Earlier NRT cycles cannot be reconstructed reliably from all historical messages, and historical TTR working time remains unknown without a verifiable calendar. Known elapsed TTR is backfilled. A legacy active FRT without a calendar snapshot still displays elapsed time.

The tested PostgreSQL 18.6 clone of the local test database migrated from V45 to V49 and passed Flyway validation. Checks on the original columns found identical contents for 25 Cases, 87 messages, 25 SLA rows, 123 audit events and 4 Ignore votes. The migration backfilled 22 elapsed TTR observations, preserved 16 completed FRTs and left NRT history empty at cutover. The source database stayed on V45.

The API and generated client are additive. Existing fields and canonical workflow payloads remain available. Apply the Flyway expansion before starting the new code. Avoid a mixed-version SLA handover during deployment: if the prior binary handles inbound or delivery after cutover, resume with the same migration and retain the persisted history, with that interval treated as a gap in NRT coverage.
