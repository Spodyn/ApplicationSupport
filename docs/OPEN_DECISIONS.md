# Otwarte decyzje

**Stan: 24 sierpnia 2026 - DECISION FREEZE COMPLETE.**

Na podstawie ponownego cross-checku Jira E00-E25 oraz repozytorium **nie ma obecnie znanej nierozstrzygniętej decyzji produktowej ani architektonicznej, która powinna blokować autonomiczną implementację**.

## Hierarchia źródeł prawdy

1. Aktualny Jira ticket i jego późniejsze `FINAL DECISION FREEZE`, `CONTRACT OVERRIDE` lub `USER_DECISION_RESOLVED` comments.
2. `PRODUCT_CONTRACT.md`.
3. `decision-registry.yaml`.
4. `PRODUCT_SPEC.md`, `WORKFLOW_MATRIX.md`, `ARCHITECTURE.md`, `INTEGRATIONS.md`, `SECURITY.md`, `OPERATIONS.md`.
5. Starsze dokumenty obszarowe tylko w zakresie niesprzecznym z powyższymi.

Starsze opisy "do decyzji" są materiałem historycznym, jeżeli późniejszy freeze rozstrzygnął temat.

## Kwestie wcześniej wymienione jako otwarte - rozstrzygnięte

### Admin current-work overview
USI-39: funkcja znajduje się w `/statistics` jako ADMIN-only zakładka **Aktualna praca**, a backend używa `GET /api/v1/admin/current-cases`. Nie wraca osobna trasa `/current-cases`.

### Źródło danych prezentacji `/cases`
USI-88: transportowe DTO mapuje jeden jawny mapper na stabilny frontend domain/view model. Lokalny component fixture nie jest drugim kontraktem backendu.

### Ignorowane kanały
USI-80: `channel.ignored=true` działa per Channel i prospektywnie. Inbound jest uwierzytelniany/deduplikowany i zapisany jako `IGNORED_BY_CHANNEL`, ale nie tworzy Case, Message, SLA ani read-state. Existing history pozostaje bez zmian; ponieważ nie powstaje Message, inbound nie jest zwykłą wiadomością widoczną w inboxie.

### Tożsamość użytkownika
Backendowy user ID jest kanoniczną tożsamością; `User` i `AdministrationUser` są frontendowymi projekcjami/mapowaniami tej samej osoby. Konsolidacja typów jest decyzją techniczną i nie zmienia ról `USER`/`ADMIN`, permissions ani auth.

### Status jako wymiar analityczny
`AnalyticsStatusDimension` może być pochodną kategorią raportową, ale nie jest `CaseStatus`. Workflow ma dokładnie sześć zamrożonych statusów.

### Workflow na ekranie czatów
USI-113: Claim, Ignore, Ask Customer, Resolve, Snooze i akcje admina są podłączane do realnego API; action availability pochodzi z backendu, UI obsługuje pending i stabilne błędy 401/403/409/provider failure/retry i nie implementuje drugiej maszyny stanów. Akceptowany design pozostaje baseline.

## Kiedy wolno dodać nową otwartą decyzję

Tylko gdy implementacja ujawni realną zmianę kontraktu, której nie da się rozstrzygnąć powyższą hierarchią - np. rozszerzenie v1, fundamentalną zmianę workflow, nową rolę/permission, nową security boundary, destrukcyjną semantykę danych, płatną usługę/commitment, produkcyjne uprawnienia, pricing/customer commitment lub nowy wymóg prawno-compliance.

Nazwy klas, indeksów, lokalne refaktoryzacje, sposób implementacji blokad/retry, test design i aktualne szczegóły SDK providera są delegowanymi decyzjami technicznymi i **nie trafiają na tę listę**.
