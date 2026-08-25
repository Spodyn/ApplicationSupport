# Model domenowy frontendu

**Stan:** reconciled with frozen v1 contract, 24 sierpnia 2026.

Ten dokument opisuje stabilne typy i granice frontendu. W razie konfliktu pierwszeństwo mają `PRODUCT_CONTRACT.md`, `WORKFLOW_MATRIX.md` i `decision-registry.yaml`.

## Case workflow

Kanoniczny frontendowy status `InboxCase.status` ma dokładnie sześć wartości i mapuje 1:1 na API/persistence:

- `new` -> `NEW`
- `verification` -> `VERIFICATION`
- `waiting_for_customer` -> `WAITING_FOR_CUSTOMER`
- `partially_ignored` -> `PARTIALLY_IGNORED`
- `ignored` -> `IGNORED`
- `resolved` -> `RESOLVED`

Nie wolno tworzyć drugiego `CaseStatus` ani dodawać stanów typu `open`, `pending`, `on_hold`, `closed`, `snoozed` czy `waiting_team`. Snooze, unread i analytics dimensions są projekcjami/personal state, nie workflow states.

Backend jest authoritative dla transition policy, ownership i action availability. UI nie implementuje drugiej maszyny stanów.

## InboxCase i transport

`InboxCase` jest stabilnym frontend domain/view modelem. Generated OpenAPI DTO są wyłącznie transportem.

Obowiązująca granica:

`OpenAPI DTO -> API adapter/mapper -> stable frontend domain/view model -> TanStack Query -> UI`

`apps/web/components/cases/cases-page.tsx` i lokalne fixture'y prezentacyjne nie są kontraktem backendu. USI-88 zamraża jeden jawny mapper; kierunek nie jest już otwartą decyzją.

## Messages i Activity

`InboxMessage` reprezentuje content rozmowy. Kanoniczni autorzy backendowi to `CUSTOMER`, `SUPPORT`, `SYSTEM`.

Claim, Ignore, Resolve, Reassign i provider-internal events należą do Activity/audit, nie do fake chat messages. Read-position opiera się o stabilną kolejność wiadomości/opaque server cursor, nie sam timestamp.

## Provider grouping

UI otrzymuje już wybrany Case. Grouping wykonuje backend/provider adapter:

- Slack: root + thread,
- Teams standard channel: root + replies,
- Teams group chat: one active Case per chat,
- Telegram topic: one active Case per topic,
- Telegram bez topics: one active Case per chat.

Po terminalnym `IGNORED`/`RESOLVED` nowa customer message tworzy nowy linked `NEW` Case; poprzedni Case nie jest reopenowany.

## Channels

Frontendowy kanał v1 to dokładnie `slack`, `teams`, `telegram`. E-mail support channel i AI są poza v1. E-mail użytkownika jest daną identity, nie providerem supportowym.

## User identity i permissions

Kanoniczna backendowa tożsamość to stabilne `user.id`. Frontendowe `User` i `AdministrationUser` są projekcjami tej samej osoby, nie osobnymi identity models. Ich konsolidacja/mapowanie jest decyzją techniczną i nie wymaga nowego product decision.

Role są dokładnie `USER` i `ADMIN`. Granular permissions są dokładnie dziewięcioma kodami z `PRODUCT_CONTRACT.md`. Starsze fixture roles `agent/supervisor/admin` nie są kontraktem API.

## Read/unread i Snooze

Read state jest per-user i sparse. Nowy user nie dziedziczy historycznych Case jako unread. Customer new/edit może ustawić unread dla eligible users. Samo otwarcie Case **nie oznacza read**; read-position przesuwa się dopiero po actual render/seen i acknowledgement z frontendu.

Snooze jest per-user, nie zmienia statusu, ownera ani SLA. New customer message, terminal state oraz Claim kończą stare snoozes zgodnie z frozen workflow.

## Analytics

`AnalyticsStatusDimension` jest raportową projekcją pochodną. Może zawierać reporting keys inne niż sześć workflow statusów, ale nie rozszerza `CaseStatus`. Sposób technicznego mapowania na dimensions nie jest human decision blockerem.

## Ignored channels

`channel.ignored=true` działa prospektywnie per Channel. Inbound jest uwierzytelniany i deduplikowany z technicznym wynikiem `IGNORED_BY_CHANNEL`, ale nie tworzy `Case`, `Message`, `SLA` ani read-state side effect. Brak runtime ingestion w obecnym repo jest implementation gap, nie otwartą decyzją.

## Tenancy i retention

V1 jest single-tenant-per-deployment bez runtime `tenant_id`, tenant selectora i cross-tenant API. Retencja `messages`, `inbound_events`, `audit_events` i `attachments` wynika z `RETENTION.md` oraz canonical contract.

## Historyczne/legacy elementy

Nie wolno przywracać jako backend contract:

- generic `Case`/`CaseStatus`,
- e-mail jako kanału v1,
- fixture roles `agent/supervisor/admin`,
- component-local presentation data jako source of truth,
- timestamp-only read cursor.

## Pozostałe luki

Realny backend/auth/provider adapters oraz API-backed `/cases` nie są jeszcze zaimplementowane. Workflow UI nie jest jeszcze podłączony do dedykowanych commandów. Są to taski implementacyjne, nie nierozstrzygnięte decyzje. Nowy human blocker można utworzyć wyłącznie zgodnie z `OPEN_DECISIONS.md` i `AGENTS.md`.
