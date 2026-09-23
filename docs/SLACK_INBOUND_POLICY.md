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

Case creation/grouping and Message persistence are deliberately downstream responsibilities. USI-130 produces the provider-neutral command; E12-T05/E12-T06 consume it after the Core Case and Message domain dependencies exist.

## Filtered events

Filtering is intentional technical processing, not a retryable failure.

| Condition | `inbound_events.processing_outcome` | Business effect |
| --- | --- | --- |
| Channel has `ignored=true` | `IGNORED_BY_CHANNEL` | None |
| Channel is unknown for the integration | `UNMAPPED_CHANNEL` | None |
| Channel exists but is inactive | `INACTIVE_CHANNEL` | None |
| Bot/app-originated message, including the installed app user | `IGNORED_BOT_MESSAGE` | None |
| Non-message event or unsupported message subtype | `UNSUPPORTED_PROVIDER_EVENT` | None |

Bot/app activity is excluded because v1 CUSTOMER content is human content. In addition to Slack bot/app markers, a message authored by a `user_id` present in the callback authorization set is treated as USI's own activity, preventing outbound-reply loops.

## Malformed supported events

A payload that claims to be a supported human message mutation but lacks required identity/content fields, or contains an invalid Slack timestamp, is malformed rather than silently ignored. It follows the existing controlled inbound DLQ policy with a stable sanitized error code.

## Worker activation boundary

The durable Slack worker remains disabled by default after USI-130. This is intentional: E07/E08 Case/Message persistence and E12-T05's provider-neutral consumer are not available yet. Processing a valid customer message as successful before that consumer exists would lose the durable event.

Once E12-T05 provides the unique `InboundMessageCommandHandler`, the worker can be enabled without changing Slack-specific normalization semantics.
