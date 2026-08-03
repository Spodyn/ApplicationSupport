# Unified Support Inbox

Produkcyjnie stylizowany frontend wspólnej skrzynki wsparcia dla Slacka, Microsoft Teams i Telegrama. Interfejs jest w całości po polsku, działa responsywnie i korzysta wyłącznie z lokalnych danych oraz usług mockowych. Backend nie jest częścią tego repozytorium.

## Uruchomienie

Wymagany jest Node.js 20+ oraz pnpm.

```bash
pnpm install
pnpm dev
```

Aplikacja będzie dostępna pod adresem `http://localhost:3000`. Kontrola jakości:

```bash
pnpm exec tsc --noEmit
pnpm build
```

## Trasy

| Trasa | Przeznaczenie |
| --- | --- |
| `/cases` | Trójkolumnowa skrzynka: foldery, kolejka, rozmowa, szczegóły i lokalne workflow. |
| `/current-cases` | Gęsta tabela administracyjna aktywnych case’ów z filtrami i operacjami audytowalnymi. |
| `/statistics` | KPI, wykresy i tabela efektywności z zakresami dat oraz filtrami. |
| `/users` | Zarządzanie użytkownikami, rolami, wagami głosów i uprawnieniami. |
| `/settings` | SLA, godziny pracy, out of office, integracje, kanały, powiadomienia i uprawnienia. |

Na telefonie nawigacja, lista case’ów i rozmowa są osobnymi widokami. Akcje rozmowy są dostępne w dolnym arkuszu, a edytor odpowiedzi pozostaje przy dolnej krawędzi. Na tablecie można schować listę i panel szczegółów; na desktopie zachowany jest zwarty układ wielokolumnowy.

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
typowane interfejsy — lib/services/types.ts
        │
        ▼
lokalne implementacje — lib/services/*.ts
        │
        ▼
dane początkowe — mocks/*.ts
```

- `lib/domain/` zawiera typy domenowe, statusy i etykiety niezależne od Reacta.
- `lib/services/types.ts` definiuje kontrakty repozytoriów i usług.
- `lib/services/registry.ts` jest jedynym miejscem wiążącym aplikację z aktualnymi implementacjami.
- `lib/services/queries.ts` udostępnia hooki TanStack Query i centralizuje klucze cache oraz mutacje.
- `mocks/` zawiera wyłącznie realistyczne dane początkowe; komponenty nie importują ich bezpośrednio.

Zmiany wykonywane w interfejsie są przechowywane w pamięci procesu przeglądarki. Odświeżenie strony przywraca dane początkowe.

## Podłączenie przyszłego API Java

Docelowy backend może działać w Java 25 i Spring Boot 4.1. Zalecany przepływ integracji:

1. W Spring Boot opublikuj dokument OpenAPI obejmujący użytkowników, case’y, wiadomości, integracje, ustawienia i statystyki.
2. Wygeneruj klienta TypeScript obsługującego żądania oraz typowane odpowiedzi. Wygenerowany kod trzymaj w osobnym katalogu, np. `lib/api/generated/`, i nie edytuj go ręcznie.
3. Dodaj adaptery implementujące kontrakty z `lib/services/types.ts`. Adapter odpowiada za mapowanie DTO API na typy z `lib/domain/` oraz za normalizację błędów HTTP.
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

Projekt nie zawiera funkcji AI, prawdziwych integracji z komunikatorami ani backendu. Symulowane działania służą wyłącznie do demonstracji przepływów opisanych w interfejsie.
