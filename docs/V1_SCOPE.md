# Zakres v1, kanały wspierane i wyłączenia

**Stan: 24 sierpnia 2026 - FROZEN.** Dokument opisuje granicę wydania v1/GA po finalnym pre-flight E00-E25.

## Granica wydania

| Obszar | GA / v1 | Poza v1 |
|---|---|---|
| Kanały wsparcia | Slack, Microsoft Teams, Telegram | E-mail jako kanał wsparcia |
| AI | Brak funkcji AI | Wszystkie funkcje AI |
| Tenancy | Jeden klient = osobny deployment/DB/storage/secrets/config | Runtime multi-tenant i cross-tenant API |

Pozycja poza v1 nie jest obietnicą późniejszego terminu. Jej dodanie wymaga nowego jawnego kontraktu.

## Provider scope

- **Slack:** monitorowane public/private channels i Slack Connect channels dostępne dla app; DM/group DM poza v1.
- **Microsoft Teams:** standard channels oraz group chats; private/shared channels poza v1.
- **Telegram:** private chats, groups/supergroups i forum topics; broadcast channels poza v1.

E-mail widoczny w historycznych mock fixtures `/cases` nie jest czwartym kanałem domenowym. Adres e-mail użytkownika jest danymi konta, nie kanałem wsparcia.

## Powierzchnie produktu v1

- `/cases`: wspólny inbox, wyszukiwanie/filtry, rozmowa, Claim, Reply, Ignore, Ask Customer, Resolve, Snooze oraz akcje administracyjne zgodne z permission/state projection.
- `/statistics`: własne statystyki użytkownika; globalne statystyki dla `ADMIN + view_global_statistics`; ADMIN-only zakładka `Aktualna praca` według USI-39.
- `/users`: administracja użytkownikami dla `ADMIN + manage_users`.
- `/settings`: ustawienia Ogólne dla ADMIN i pozostałe zakładki według granular permissions.
- Audit/admin oversight, SLA, business hours/OOO, notifications, attachments, realtime oraz trzy integracje providera zgodnie z dokumentami kanonicznymi.

## Current-work placement

Nie implementować osobnej `/current-cases`. Aktualny kontrakt: `/statistics` -> zakładka **Aktualna praca**, backend `GET /api/v1/admin/current-cases`. Odczyt wymaga roli ADMIN; konkretne akcje reassign/unassign/force-resolve nadal wymagają odpowiednich granular permissions.

## Visual baseline

Istniejący frontend jest zaakceptowanym baseline UX. Produkcjonizacja podłącza realne dane/akcje i stany błędów bez nieuzgodnionego redesignu.
