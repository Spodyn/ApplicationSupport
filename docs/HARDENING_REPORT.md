# Raport końcowy hardeningu

> **Historical snapshot.** Walidację wykonano 9 sierpnia 2026 r. dla bazowej rewizji `65a513b`. Sekcja „Remaining decisions” opisuje stan **z dnia audytu**. Od 24 sierpnia 2026 obowiązuje `DECISION FREEZE COMPLETE`; aktualny kontrakt znajduje się w `PRODUCT_CONTRACT.md`, `PRODUCT_SPEC.md`, `decision-registry.yaml` i `OPEN_DECISIONS.md`.

## Summary

Repozytorium zostało uporządkowane i zweryfikowane jako frontend/mock gotowy do rozpoczęcia prac nad API i backend adapters. Zachowano granicę `UI -> queries -> registry -> service interfaces -> mocks`, kanoniczny model workflow (po relokacji w `apps/web/lib/domain/inbox.ts`) oraz zaakceptowany wygląd aplikacji.

W ramach hardeningu:

- ujednolicono instalację na pnpm i deterministycznym `pnpm-lock.yaml`,
- dodano lokalną i CI bramkę jakości,
- usunięto nieużywany konkurencyjny model `Case`,
- usunięto historyczne archiwum z kopią `/current-cases`,
- potwierdzono brak bezpośrednich importów `mocks/` w UI oraz śledzonych sekretów/`.env`.

Na tym etapie celowo nie zbudowano backendu Java/Spring Boot, PostgreSQL, RabbitMQ ani realnych integracji providerów.

## Quality gate z 9 sierpnia 2026

| Polecenie | Wynik |
| --- | --- |
| `pnpm install --frozen-lockfile` | PASS |
| `pnpm lint` | PASS |
| `pnpm typecheck` | PASS |
| `pnpm test:unit` | PASS |
| `pnpm build` | PASS |
| `pnpm test:e2e` | PASS, 8/8 Chromium |

Środowisko: Node.js 22.23.2 LTS, pnpm 11.18.0.

## Visual regression

Cztery kluczowe widoki zachowały baseline z audytu. Baseline jest kontraktem **wizualnym**, nie źródłem nadrzędnym dla później zamrożonych semantics workflow/read-state/integrations.

- `/cases`
- `/statistics`
- `/users`
- `/settings`

Mobilny `/cases` został zweryfikowany w 390x844.

## Additional checks z audytu

- brak przypadkowego redesignu,
- brak śledzonych sekretów,
- brak bezpośrednich importów `mocks/` w komponentach,
- `/current-cases` nie istniało,
- PWA nie cache'owało `/api`, mutacji ani authenticated requests,
- brak markerów `TODO/FIXME/HACK/XXX` w sprawdzonym zakresie.

## Remaining decisions — status historyczny

W dniu 9 sierpnia raport wskazywał jeszcze kwestie dotyczące m.in. presentation source `/cases`, identity usera, analytics dimensions, ignored channels i workflow.

**Te kwestie nie są już otwarte.** Finalny cross-check E00-E25 z 24 sierpnia 2026 rozstrzygnął je następująco:

- `/cases`: generated DTO -> jawny mapper -> stable frontend domain/view model,
- identity: backend `user.id` jest kanoniczne; frontendowe modele są projekcjami tej samej osoby,
- analytics dimension nie rozszerza `CaseStatus`,
- ignored channel: inbound -> techniczny `IGNORED_BY_CHANNEL`, bez Case/Message/SLA/read-state,
- workflow UI korzysta z realnych backend commands i server-calculated action availability,
- admin current-work: `/statistics` -> `Aktualna praca`, API `/api/v1/admin/current-cases`.

Aktualny stan decyzji opisuje `OPEN_DECISIONS.md`: zero znanych unresolved product/architecture blockers.

## Ready for the next phase

**YES.** Historyczny hardening frontendu zakończył się powodzeniem. Po późniejszym decision freeze repo jest przygotowane do realizacji backlogu implementacyjnego zgodnie z kanoniczną dokumentacją i `AGENTS.md`.
