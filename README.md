# Unified Support Inbox

Produkcyjnie stylizowany frontend wspólnej skrzynki wsparcia dla Slacka, Microsoft Teams i Telegrama. Interfejs jest w całości po polsku, działa responsywnie i korzysta wyłącznie z lokalnych danych oraz usług mockowych. Backend nie jest częścią tego repozytorium.

Zamrożony kontrakt wydania v1/GA, wspieranych kanałów i wyłączeń opisuje [`docs/V1_SCOPE.md`](docs/V1_SCOPE.md). Role `USER`/`ADMIN`, katalog permissions i matrycę guardów UI/API utrwala [`docs/PERMISSION_MATRIX.md`](docs/PERMISSION_MATRIX.md). Baseline lokalnego logowania, sesji serwerowej, CSRF i przyszłej granicy OIDC opisuje [`docs/AUTHENTICATION.md`](docs/AUTHENTICATION.md), a providerowe reguły grupowania wiadomości do spraw — [`docs/CASE_GROUPING.md`](docs/CASE_GROUPING.md).

## Uruchomienie

Wymagany jest Node.js 22.23.2 LTS (wersja zapisana w `.nvmrc`) oraz pnpm 11.18.0 wskazany w `package.json`. pnpm 11.18.0 wymaga Node.js co najmniej 22.13. Jedynym wspieranym lockfile jest `pnpm-lock.yaml`.

```bash
pnpm install --frozen-lockfile
pnpm dev
```

Aplikacja będzie dostępna pod adresem `http://localhost:3000`.

## Kontrola jakości

Przed pierwszym uruchomieniem testów E2E zainstaluj przypisaną przeglądarkę:

```bash
pnpm exec playwright install chromium
```

Pełna lokalna bramka jakości obejmuje lint, typecheck, testy jednostkowe/komponentowe, produkcyjny build i testy E2E:

```bash
pnpm check
```

Poszczególne bramki można uruchamiać osobno:

```bash
pnpm lint
pnpm typecheck
pnpm test:unit
pnpm build
pnpm test:e2e
```

`pnpm test:unit:watch` uruchamia Vitest w trybie obserwowania. Samodzielne `pnpm test:e2e` wymaga istniejącego produkcyjnego buildu; `pnpm check` tworzy go automatycznie. Szczegóły znajdują się w [`docs/TESTING.md`](docs/TESTING.md).

## Trasy

| Trasa | Przeznaczenie |
| --- | --- |
| `/` | Przekierowanie do `/cases`. |
| `/cases` | Dwukolumnowa skrzynka czatów: lista rozmów i wybrana rozmowa. |
| `/statistics` | KPI, wykresy i tabela efektywności z zakresami dat oraz filtrami. |
| `/users` | Zarządzanie użytkownikami, rolami, ważnością kont i uprawnieniami. |
| `/settings` | SLA, godziny pracy, out of office, integracje, kanały, powiadomienia i uprawnienia. |

Na telefonie lista czatów i rozmowa są osobnymi widokami z możliwością powrotu do listy. Nieistniejąca trasa `/current-cases` nie jest częścią zaimplementowanego produktu; jej ewentualny przyszły zakres pozostaje decyzją produktową opisaną w [`docs/OPEN_DECISIONS.md`](docs/OPEN_DECISIONS.md).

## Architektura danych mockowych

Kod jest rozdzielony tak, aby warstwę lokalną można było zastąpić klientem wygenerowanym z OpenAPI bez przebudowy komponentów:

```text
komponenty i trasy
        │
        ▼
TanStack Query — lib/services/queries.ts
        │
        ▼
rejestr usług — lib/services/registry.ts
        │
        ▼
typowane interfejsy obszarowe — lib/services/*.ts
        │
        ▼
lokalne implementacje — lib/services/*.ts
        │
        ▼
dane początkowe — mocks/*.ts
```

- `lib/domain/inbox.ts` jest jedynym kanonicznym modelem workflow sprawy.
- `lib/domain/shared.ts` zawiera współdzielone typy frontendu, które nie są typami transportowymi.
- Pliki obszarowe w `lib/services/` definiują kontrakty usług obok ich implementacji mock.
- `lib/services/registry.ts` jest jedynym miejscem wiążącym aplikację z aktualnymi implementacjami.
- `lib/services/queries.ts` udostępnia hooki TanStack Query i centralizuje klucze cache oraz mutacje.
- `mocks/` zawiera wyłącznie realistyczne dane początkowe; komponenty nie importują ich bezpośrednio.

Szczegóły obecnej architektury i modeli opisują [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) oraz [`docs/DOMAIN_MODEL.md`](docs/DOMAIN_MODEL.md).

Zmiany wykonywane w interfejsie są przechowywane w pamięci procesu przeglądarki. Odświeżenie strony przywraca dane początkowe.

## Planowana granica backendu

Backend nie jest częścią obecnej implementacji. Planowana granica integracji to:

`Spring Boot REST/OpenAPI → wygenerowany klient transportowy TypeScript → adapter frontendu → stabilne typy domenowe frontendu`

Kiedy rozpocznie się osobne zadanie backendowe:

1. W Spring Boot opublikuj uzgodniony dokument OpenAPI.
2. Wygeneruj klienta TypeScript obsługującego żądania oraz typowane odpowiedzi. Wygenerowany kod trzymaj w osobnym katalogu, np. `lib/api/generated/`, i nie edytuj go ręcznie.
3. Dodaj adaptery implementujące obszarowe kontrakty z `lib/services/`. Adapter odpowiada za mapowanie DTO API na typy z `lib/domain/` oraz za normalizację błędów HTTP.
4. W `lib/services/registry.ts` zamień lokalne implementacje na adaptery API. Komponenty i hooki zapytań nie powinny wymagać zmian.
5. Dodaj konfigurację bazowego URL, uwierzytelniania i identyfikatora bieżącego użytkownika po stronie serwera. Nie umieszczaj tokenów w kodzie klienta.
6. Ustal strategię unieważniania kluczy TanStack Query po mutacjach i obsługę konfliktów, w tym HTTP 409 dla przejęcia case’a.

Typy generowane z OpenAPI warto traktować jako typy transportowe. Typy domenowe interfejsu pozostają stabilną granicą aplikacji i chronią UI przed zmianami kształtu pojedynczego endpointu.

## PWA i dane wrażliwe

Aplikacja udostępnia manifest, ikony zastępcze oraz rejestruje prosty service worker. Service worker zapisuje wyłącznie publiczny manifest i ikony instalacyjne. Żądania z nagłówkiem `Authorization`, wszystkie ścieżki `/api/`, mutacje oraz strony aplikacji zawsze korzystają z sieci i nie są zapisywane w Cache Storage. Dzięki temu warstwa PWA nie tworzy kopii przyszłych uwierzytelnionych danych API.

## Dostępność i stany interfejsu

- Statusy łączą tekst, ikonę i kolor; kolor nie jest jedynym nośnikiem informacji.
- Interaktywne elementy mają widoczny fokus, etykiety, opisy i obsługę klawiatury.
- Dialogi oraz arkusze zachowują fokus i mają tytuły oraz opisy dla technologii asystujących.
- Widoki zawierają szkielety, stany puste i błędy. Globalny baner informuje o pracy offline, a ustawienia pokazują stan rozłączonych integracji.
- Preferencja `prefers-reduced-motion` ogranicza animacje i przejścia.

## Zakres projektu

Zakres v1/GA obejmuje Slacka, Microsoft Teams i Telegrama. E-mail jako kanał wsparcia oraz wszystkie funkcje AI są poza v1. Wpisy e-mail widoczne w `/cases` są fixture’em prezentacyjnym do usunięcia przy zastąpieniu mocków realnym API; adresy e-mail kont użytkowników pozostają danymi tożsamości, a nie kanałem wsparcia.

Projekt nie zawiera funkcji AI, prawdziwych integracji z komunikatorami ani backendu. Symulowane działania służą wyłącznie do demonstracji przepływów opisanych w interfejsie.
