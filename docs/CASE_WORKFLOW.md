# Kanoniczna maszyna stanów Case

**Stan: 26 listopada 2026 - aktualizacja decyzji Product Ownera.** Ten dokument konsoliduje USI-35/36/37 oraz późniejsze E09 overrides. Szczegółowa macierz znajduje się także w `WORKFLOW_MATRIX.md`.

## Statusy

Dokładnie sześć: `NEW`, `VERIFICATION`, `WAITING_FOR_CUSTOMER`, `PARTIALLY_IGNORED`, `IGNORED`, `RESOLVED`.

Frontend mapuje je 1:1 na lower snake case. Unread, Snooze, SLA severity i analytics categories nie są CaseStatus.

## Ownership

| Status | Owner |
|---|---|
| NEW | null |
| PARTIALLY_IGNORED | null |
| VERIFICATION | dokładnie jeden aktywny owner |
| WAITING_FOR_CUSTOMER | dotychczasowy owner, historyczne sprawy mogą być bez ownera |
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
| VERIFICATION / WAITING_FOR_CUSTOMER | Reply | eligible current owner | successful delivery enters or keeps owned waiting |
| unassigned WAITING_FOR_CUSTOMER | Claim | eligible claimant without lifetime Ignore vote | VERIFICATION with claimant as owner |
| WAITING_FOR_CUSTOMER | customer reply | inbound customer message | VERIFICATION with same owner, or NEW if unassigned |
| WAITING_FOR_CUSTOMER | timeout | waiting_until reached | VERIFICATION with same owner, or NEW if unassigned, plus `WAITING_TIMEOUT`; no automatic follow-up |
| VERIFICATION / WAITING_FOR_CUSTOMER | Resolve | current owner | RESOLVED, owner preserved |
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

## Oczekuje na klienta

Po potwierdzeniu pierwszej odpowiedzi przez dostawcę sprawa przechodzi z OPEN do ASKED i zachowuje właściciela. Kolejne potwierdzone odpowiedzi zachowują bieżący termin oczekiwania. Wysyłka w kolejce, błąd albo ponowienie nie zmieniają stanu sprawy. Obowiązuje [kanoniczny kontrakt zgodności API](PRODUCT_CONTRACT.md#7-public-replies-and-waiting).

## Resolve category

Optional/nullable for normal and force resolve. If present, controlled codes are `SOLVED`, `NO_ACTION_REQUIRED`, `DUPLICATE`, `OTHER`; no free-text category.

## Terminal semantics

`IGNORED` remains terminal. An administrative undo can return a resolved Case to NEW when no active generation exists. A later customer message uses the active generation or creates exactly one linked NEW successor.

## Concurrency

Every command is server-authorized and idempotent where retryable. State/owner/message/audit/outbox effects that form one business result are atomic or durably coordinated. Race losers receive stable conflicts/refetch hints; no partial transition and no double owner.
