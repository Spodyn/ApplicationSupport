# Kanoniczna maszyna stanów Case

**Stan: 24 sierpnia 2026 - FROZEN.** Ten dokument konsoliduje USI-35/36/37 oraz późniejsze E09 overrides. Szczegółowa macierz znajduje się także w `WORKFLOW_MATRIX.md`.

## Statusy

Dokładnie sześć: `NEW`, `VERIFICATION`, `WAITING_FOR_CUSTOMER`, `PARTIALLY_IGNORED`, `IGNORED`, `RESOLVED`.

Frontend mapuje je 1:1 na lower snake case. Unread, Snooze, SLA severity i analytics categories nie są CaseStatus.

## Ownership

| Status | Owner |
|---|---|
| NEW | null |
| PARTIALLY_IGNORED | null |
| VERIFICATION | dokładnie jeden aktywny owner |
| WAITING_FOR_CUSTOMER | null |
| IGNORED | null |
| RESOLVED | zachowuje ostatniego ownera, jeśli istniał; inaczej null |

## Transition matrix

| Source | Command/event | Guard | Result |
|---|---|---|---|
| brak | inbound creates Case | valid mapped nonignored provider context | NEW, unowned |
| NEW | Claim | eligible user, unowned, not lifetime ignore voter | VERIFICATION, claimant owner |
| PARTIALLY_IGNORED | Claim | eligible non-voter claimant | VERIFICATION; active partial votes deactivated |
| NEW/PARTIALLY_IGNORED | Ignore | backend-calculated vote | PARTIALLY_IGNORED below2; IGNORED at >=2 |
| PARTIALLY_IGNORED | customer message | before threshold | reset active votes -> NEW |
| VERIFICATION | Reply | current owner only | VERIFICATION |
| VERIFICATION | Ask Customer | current owner + outgoing Ask | stays VERIFICATION/owned until Message `SENT`; then WAITING_FOR_CUSTOMER, owner null, waiting_until set |
| WAITING_FOR_CUSTOMER | customer reply | inbound customer message | NEW |
| WAITING_FOR_CUSTOMER | timeout | waiting_until reached | NEW + `WAITING_TIMEOUT`; no automatic follow-up |
| VERIFICATION | Resolve | current owner | RESOLVED, owner preserved |
| any nonterminal | Force Resolve | ADMIN + force_resolve | RESOLVED; existing owner preserved, unowned remains null |
| NEW/PARTIALLY_IGNORED | Admin assign | ADMIN + reassign_cases, eligible target | VERIFICATION, target owner |
| VERIFICATION | Admin reassign | ADMIN + reassign_cases, eligible target | VERIFICATION, target owner |
| VERIFICATION | Admin unassign | ADMIN + reassign_cases | NEW, owner null |

No generic `PATCH status`.

## Ignore semantics

- v1 threshold fixed at2.
- Default effective weight1; temporary weight2 may be granted by `ADMIN + manage_users` with optional `valid_until`.
- Vote stores immutable weight snapshot.
- One active vote user/Case; duplicate active POST is idempotent success.
- Reset deactivates votes but preserves history; same user may vote again later.
- Lifetime invariant: anyone who ever successfully cast Ignore in a Case may never Claim or Reply in that Case.

## Ask Customer

- Default wait24h; override1h-30d.
- Local queued/PENDING message is not enough: transition occurs only after provider acceptance `SENT`.
- Permanent delivery failure keeps Case in VERIFICATION with same owner.
- Timeout returns to NEW and audits `WAITING_TIMEOUT`; v1 sends no automatic follow-up.

## Resolve category

Optional/nullable for normal and force resolve. If present, controlled codes are `SOLVED`, `NO_ACTION_REQUIRED`, `DUPLICATE`, `OTHER`; no free-text category.

## Terminal semantics

`IGNORED` and `RESOLVED` never reopen. A later customer message for the same provider context creates exactly one linked NEW successor Case. Force-resolve is invalid on terminal state.

## Concurrency

Every command is server-authorized and idempotent where retryable. State/owner/message/audit/outbox effects that form one business result are atomic or durably coordinated. Race losers receive stable conflicts/refetch hints; no partial transition and no double owner.
