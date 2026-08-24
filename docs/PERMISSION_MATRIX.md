# Role aplikacji i matryca uprawnień

**Stan: 24 sierpnia 2026 - FROZEN.**

## Role

Kanoniczne role są dokładnie dwie: `USER` i `ADMIN`.

Normalna funkcja administracyjna wymaga jednocześnie roli `ADMIN` i właściwego granular permission. Wyjątkiem są ustawienia Ogólne oraz odczyt admin current-work overview, które wymagają samej roli `ADMIN` zgodnie z finalnym freeze.

Permission zapisany przy `USER` nie nadaje admin capability. Backend jest authoritative; ukrycie przycisku/trasy nie jest zabezpieczeniem.

## Zamknięty katalog permissions

| Permission | Zakres |
|---|---|
| `manage_users` | Konta, role, ważność, permissions, deactivate/reactivate/delete; czasowa waga Ignore2. |
| `manage_integrations` | Integracje, monitorowane/ignorowane Channels, test/re-auth/disconnect. |
| `manage_sla` | Polityka SLA. |
| `manage_schedule` | Business hours, exceptions i OOO. |
| `manage_notifications` | Destinations/rules, DLQ replay i test notifications. |
| `view_global_statistics` | Statystyki globalne/per-user dla organizacji. |
| `reassign_cases` | Administracyjny assign/reassign/unassign. |
| `force_resolve` | Administracyjny force-resolve nieterminalnego Case. |
| `view_audit` | Wyszukiwanie/eksport sanitizowanego audytu. |

Nie dodawać nowego permission bez jawnej zmiany kontraktu.

## Widoki i akcje

| Widok/akcja | Wymaganie |
|---|---|
| `/cases`, Case detail/messages, zwykłe workflow | Aktywne USER/ADMIN + state/ownership guards. |
| Własne statystyki | Aktywne USER/ADMIN. |
| Global/team statistics | `ADMIN + view_global_statistics`. |
| `/statistics` -> `Aktualna praca` (odczyt) | `ADMIN` bez `view_global_statistics`. |
| `/users` | `ADMIN + manage_users`. |
| `/settings` -> Ogólne | `ADMIN`. |
| `/settings` -> SLA | `ADMIN + manage_sla`. |
| `/settings` -> Godziny/OOO | `ADMIN + manage_schedule`. |
| `/settings` -> Integracje/Kanały | `ADMIN + manage_integrations`. |
| `/settings` -> Powiadomienia | `ADMIN + manage_notifications`. |
| `/settings` -> Uprawnienia | `ADMIN + manage_users`. |
| Reassign/unassign | `ADMIN + reassign_cases`. |
| Force-resolve | `ADMIN + force_resolve`. |
| Audit search/export | `ADMIN + view_audit`. |

UI może ukrywać niedostępne zakładki/akcje, ale backend ponownie sprawdza role, permissions, aktywność, ownership i state.

## Endpoint families

Wszystkie ścieżki pod `/api/v1`.

- `/cases/**`: aktywne USER/ADMIN + state/ownership guards.
- `/admin/users/**`: `ADMIN + manage_users`.
- `/admin/settings/general`: ADMIN.
- `/admin/sla/**`: `ADMIN + manage_sla`.
- `/admin/schedule/**`, `/admin/out-of-office/**`: `ADMIN + manage_schedule`.
- `/admin/integrations/**`, `/admin/channels/**`: `ADMIN + manage_integrations`.
- `/admin/notifications/**`: `ADMIN + manage_notifications`.
- `/statistics/me/**`: current active user.
- `/statistics/**` global/team: `ADMIN + view_global_statistics`.
- `GET /admin/current-cases`: ADMIN.
- `/admin/cases/{id}/reassign|unassign`: `ADMIN + reassign_cases`.
- `/admin/cases/{id}/force-resolve`: `ADMIN + force_resolve`.
- `/admin/audit/**`: `ADMIN + view_audit`.

## Administrative invariants

- Nowy ADMIN dostaje domyślnie komplet dziewięciu permissions; `ADMIN + manage_users` może je ograniczyć.
- Nie wolno samemu podnieść swojej roli ani nadać sobie brakującego permission.
- System zawsze musi zachować co najmniej jednego aktywnego ADMIN-a z `manage_users`.
- Brak sesji -> `401`; brak roli/permission -> `403`; autoryzowany command kolidujący z workflow/state zwraca stabilny conflict/problem code zgodnie z endpointem.
