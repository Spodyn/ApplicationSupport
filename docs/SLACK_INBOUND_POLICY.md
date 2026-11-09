# Slack inbound filtering and normalization policy

**Status:** USI-130 / E12-T04

This document refines the frozen Slack behavior in `INTEGRATIONS.md` without changing product scope.

## Accepted v1 message semantics

Only Slack Events API `message` events from a configured, active, non-ignored Channel can cross the provider-neutral inbound boundary.

- Message without a subtype -> `CREATE`.
- `message_changed` -> `EDIT` of the existing external message identity.
- `message_deleted` -> `DELETE` of the existing external message identity; no message body is fabricated.
- `thread_ts`, when present, is the stable conversation/thread key. Otherwise the message `ts` is the root key.
- Slack `ts`/`event_ts` values are normalized to `Instant`; raw Slack payloads do not cross into the core command contract.

Case creation/grouping, Message persistence and mutations are downstream responsibilities. Existing-message edits/deletes update the original Message in place, including in terminal Cases, and publish `message.updated` after commit. They never create/reopen Cases. Out-of-order edits and duplicate mutations are suppressed; an original Message not yet persisted retries durably.

## Filtered events

Filtering is intentional technical processing, not a retryable failure.

| Condition | `inbound_events.processing_outcome` | Business effect |
| --- | --- | --- |
| Channel has `ignored=true` | `IGNORED_BY_CHANNEL` | None |
| Channel is not discovered yet | Retry with `SLACK_CHANNEL_DISCOVERY_PENDING` | No business writes until authorized membership discovery |
| Discovered active Slack channel has no Customer | None | Create visible intake Case/Message/SLA with customer NULL, labeled "Nie przypisano" (user-approved 2026-11-09 override) |
| Channel exists but is inactive | `INACTIVE_CHANNEL` | None |
| Bot/app-originated message, including the installed app user | `IGNORED_BOT_MESSAGE` | None |
| Non-message event or unsupported message subtype | `UNSUPPORTED_PROVIDER_EVENT` | None |

Bot/app activity is excluded because v1 CUSTOMER content is human content. In addition to Slack bot/app markers, a message authored by a `user_id` present in a callback authorization entry with `is_bot=true` is treated as USI's own activity, preventing outbound-reply loops.

## Malformed supported events

A payload that claims to be a supported human message mutation but lacks required identity/content fields, or contains an invalid Slack timestamp, is malformed rather than silently ignored. It follows the existing controlled inbound DLQ policy with a stable sanitized error code.

## Worker activation boundary

The shared/test baseline keeps the durable Slack worker disabled. The local profile
enables inbound/outbound and realtime workers plus membership reconciliation every
30 seconds. Membership discovery calls Slack outside database transactions and
replaces the snapshot only after all pages succeed. Existing channel mappings and
ignored flags are preserved. A callback racing discovery retries durably.
