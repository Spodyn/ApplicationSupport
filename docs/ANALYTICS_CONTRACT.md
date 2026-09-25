# Analytics event and KPI contract

**Status:** FROZEN for v1 by USI-163 / E16-T01.  This contract is the
authority for analytics projections.  It complements, rather than replaces,
the canonical Case, Message, SLA and audit records.

## Scope and time model

- Analytics are derived from durable canonical domain/audit events. Browser
  state, page views, local clocks and provider event timestamps are never KPI
  sources.
- Every event is stored and queried as an absolute `timestamptz` instant. A
  reporting day is the half-open civil-time interval `[00:00, 00:00)` in the
  deployment's configured IANA organization timezone. DST changes therefore
  make a reporting day 23 or 25 hours when applicable.
- The event instant selects a date range; the Case's policy and
  schedule/timezone snapshot selects business-time calculation. A later
  settings change never rewrites a historical metric.
- A range is inclusive by reporting date. The API converts it to an absolute
  half-open interval before querying. Default range is the latest 30 reporting
  dates; interactive ranges are at most 366 dates.

## Canonical event vocabulary

| Analytics event | Durable source and instant | Attribution | Notes |
| --- | --- | --- | --- |
| `CASE_CREATED` | accepted customer intake / Case creation | none | Each post-terminal linked Case is a new Case. Test notifications are excluded. |
| `CASE_CLAIMED` | successful Claim audit event | claiming user | Admin assignment/reassignment is not a Claim. |
| `FIRST_RESPONSE_SENT` | first human SUPPORT or Ask Message transition to `SENT` | message sender | Queued, failed, OOO and SYSTEM messages do not qualify. One event per Case. |
| `CASE_RESOLVED` | normal Resolve or force-resolve audit event | resolving owner; force actor separately | Only `RESOLVED`, never `IGNORED`. Force events carry `forced=true`. |
| `CASE_IGNORED` | threshold-reaching Ignore audit event | none | Reported separately and never increases resolution rate. |
| `SLA_WARNING` / `SLA_BREACH` | first durable transition of each Case/SLA clock | none | Monotonic and idempotent per `(case, sla_type, severity)`. |

The projection may retain the source event ID and Case ID for idempotency and
traceability. A replay of the same source event must not add another count.

## KPI formulas and inclusion rules

| KPI | Formula | Inclusion / exclusion |
| --- | --- | --- |
| Created cases | count `CASE_CREATED` in range | Includes linked post-terminal Cases; excludes test notifications and deleted/non-durable attempts. |
| Claimed cases | count `CASE_CLAIMED` in range | Attribute only to the successful claiming user; exclude admin assignment/reassignment. |
| First-response count | count `FIRST_RESPONSE_SENT` in range | One per Case; no-response Cases remain pending and are never treated as zero duration. |
| Resolved cases | count `CASE_RESOLVED` in range | `IGNORED` is excluded and reported as ignored cases. |
| Resolution rate | `resolved / (resolved + ignored)` | `null` when denominator is zero; force-resolved Cases are excluded from agent-performance views by default, but included in operational totals with a `forced` dimension. |
| First-response duration | business time from `CASE_CREATED` to `FIRST_RESPONSE_SENT` | Uses the creation snapshot and waiting-pause policy; only Cases with the qualifying sent event contribute. |
| Resolution duration | gross elapsed and business/support time from `CASE_CREATED` to `CASE_RESOLVED` | Main KPI uses business/support time; terminal ignored Cases do not contribute. |
| Unclaimed / in-progress duration | accumulated business time on their SLA clocks | Unclaimed: `NEW`/`PARTIALLY_IGNORED`; in-progress: `VERIFICATION`. Waiting pauses when the snapshot policy says so; reassign never resets accumulation. |
| SLA warning/breach | count first `SLA_WARNING` / `SLA_BREACH` events in range by clock | A Case may contribute once per SLA type and severity. Historical breach stays reportable after terminal transition. |
| Current workload | count nonterminal Cases at query time | Dimension by current owner/status; this is a snapshot, not an event-range total. |

For every duration distribution, return `count`, `average`, `p50`, `p90` and
`p95` in business minutes. If the population is empty, each aggregate is
`null`; the API must never manufacture `0` minutes or `100%`.

## API/projection requirements

- Global/team metrics require `ADMIN + view_global_statistics`; a USER can see
  only metrics whose user dimension is that user.
- Results expose the effective organization timezone, requested reporting
  dates, inclusion rules/version and whether force-resolved Cases were
  excluded. Reporting dimensions may be derived but must not add Case workflow
  statuses.
- `SLA_WARNING`, `SLA_BREACH`, Claim, response and resolution projections are
  driven by their durable audit/domain event IDs and remain retry-safe.
- Customer/support conversation content, raw provider payloads, credentials and
  attachment URLs are not analytics dimensions.

## Contract examples

| Scenario | Expected result |
| --- | --- |
| Customer intake at 16:55 Friday; first human `SENT` at 09:10 Monday in the Case snapshot timezone | Created event is Friday; first-response duration is 15 business minutes, not elapsed weekend time. |
| Support message is queued then permanently fails | No `FIRST_RESPONSE_SENT`; response duration is absent/pending. |
| Admin assigns a NEW Case to Ada | Workload may change, but no `CASE_CLAIMED` and Ada receives no claim attribution. |
| Ada Claims then resolves the Case | One claim for Ada, one normal resolution for Ada; Case contributes to resolution duration. |
| Admin force-resolves an unowned Case | Operational resolved total records `forced=true`; default agent-performance resolution excludes it. |
| Two Ignore votes reach threshold | One ignored event; resolved count/rate do not increase. |
| SLA clock warns, waits, resumes and breaches | One warning and one breach for that Case/SLA type; pause/resume cannot duplicate either event. |
| A terminal Case receives a later customer message | The linked new Case emits its own `CASE_CREATED` and is counted separately. |
