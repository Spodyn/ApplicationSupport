# Raport końcowy hardeningu

Walidację końcową wykonano 9 sierpnia 2026 r. na Windows, dla bazowej rewizji `65a513b` i pełnego zestawu zmian z zadań 01–05.

## Summary

Repozytorium zostało uporządkowane i zweryfikowane jako frontend/mock gotowy do rozpoczęcia prac nad kontraktem API oraz przyszłymi adapterami backendu. Zachowano granicę `UI → queries → registry → interfejsy usług → mocki`, kanoniczny model workflow z `lib/domain/inbox.ts` oraz zaakceptowany wygląd aplikacji.

W ramach hardeningu:

- ujednolicono instalację na pnpm i deterministycznym `pnpm-lock.yaml`,
- dodano pełną lokalną oraz CI-ową bramkę jakości,
- usunięto nieużywany, konkurencyjny model `Case` i jego martwy łańcuch danych,
- zsynchronizowano dokumentację z rzeczywistymi trasami, zachowaniem i granicami domeny,
- usunięto śledzone archiwum `zzz.zip`, które zawierało nieaktualną kopię usuniętej trasy `/current-cases`,
- potwierdzono brak bezpośrednich importów `mocks/` w UI, sekretów i śledzonych plików `.env`.

Celowo nie zbudowano backendu Java/Spring Boot, bazy PostgreSQL, RabbitMQ ani rzeczywistych integracji Slack/Teams/Telegram. Nie dodawano też nowych reguł workflow i nie przeprojektowywano interfejsu.

## Quality gate

Walidację rozpoczęto od usunięcia lokalnych artefaktów zależności i buildów, a następnie wykonano świeżą instalację z zamrożonego lockfile. Wszystkie wymagane kroki zakończyły się sukcesem.

| Polecenie | Wynik | Czas | Narzędzie / wersja |
| --- | --- | ---: | --- |
| `pnpm install --frozen-lockfile` | PASS; 740 pakietów, lockfile bez zmian | 8,91 s | pnpm 11.18.0 |
| `pnpm lint` | PASS; 0 błędów i ostrzeżeń | 6,20 s | ESLint 9.39.5 |
| `pnpm typecheck` | PASS; strict TypeScript | 3,18 s | TypeScript 5.7.3 |
| `pnpm test:unit` | PASS; 3 pliki, 6/6 testów | 3,80 s | Vitest 4.1.10 |
| `pnpm build` | PASS; 13 tras/zasobów App Router | 8,09 s | Next.js 16.3.0 |
| `pnpm test:e2e` | PASS; 8/8 testów Chromium | 6,34 s | Playwright 1.62.1 |

Środowisko walidacji: Node.js 22.23.2 LTS i pnpm 11.18.0. Oficjalne metadane pnpm 11.18.0 wymagają Node.js `>=22.13`; `.nvmrc`, README i dokumentacja testów wskazują ten sam kompatybilny runtime. `enableGlobalVirtualStore: false` jest ustawione jawnie, aby instalacja lokalna i CI używały zgodnego układu `node_modules`.

README prowadzi od czystego checkoutu przez wymagane wersje, `pnpm install --frozen-lockfile`, instalację Chromium i `pnpm check`. Workflow `.github/workflows/ci.yml` odtwarza tę samą kolejność na `push` i `pull_request`, ma wyłącznie uprawnienie `contents: read` i nie wymaga poświadczeń produkcyjnych.

## Visual regression

Aktualne zrzuty wykonano w tej samej sesji przeglądarki i przy rzeczywistym rozmiarze baseline’u 1280×720. Nazwy plików baseline zawierają historyczne `1440x900`, lecz metadane obrazów wskazują 1280×720. Każdy z czterech kluczowych widoków jest binarnie identyczny z baseline’em.

| Trasa | Baseline | Wynik | SHA-256 |
| --- | --- | --- | --- |
| `/cases` | `desktop-cases-1440x900.png` | PASS — identyczny pikselowo | `9276973392EFD7AFCE10BDDA426A690D59D3DA24082775316F9744F2813A7E91` |
| `/statistics` | `desktop-statistics-1440x900.png` | PASS — identyczny pikselowo | `FA0320C45ECB4680B7FCFCA27B0CDA01ADBF12F016D74F02F748D898AA6F49B1` |
| `/users` | `desktop-users-1440x900.png` | PASS — identyczny pikselowo | `70E6D315A930563B7627823C58952E539760D1C5A228D644ABDF0CFCF66F13CD` |
| `/settings` | `desktop-settings-1440x900.png` | PASS — identyczny pikselowo | `A97012F267FAF341F912D92D6EB2E4FCF80B916BDC6C08583B9EC27376DCA972` |

Mobilny przepływ `/cases` jest objęty testami Playwright w widoku 390×844 i przeszedł walidację. Ręczne przewinięcie `/statistics` potwierdziło, że panel zakresu czasu i filtrów pozostaje przyklejony do górnej krawędzi ekranu.

## Additional checks

| Kontrola | Wynik |
| --- | --- |
| Zakres diffu | PASS — zmiany odpowiadają zadaniom 01–05; nie znaleziono przypadkowego refaktoru ani redesignu. |
| Sekrety i `.env` | PASS — brak śledzonych `.env`, kluczy, tokenów i danych uwierzytelniających. |
| Granica mocków | PASS — komponenty i trasy nie importują bezpośrednio z `mocks/`. |
| Trasy i nawigacja | PASS — `/`, `/cases`, `/statistics`, `/users`, `/settings`; `/current-cases` nie istnieje i nie jest linkowane. |
| Nawigacja w przeglądarce | PASS — linki między czterema głównymi widokami prowadzą do właściwych tras. |
| Krytyczne dialogi | PASS — formularz użytkownika, potwierdzenie usunięcia i konfiguracja integracji mają dostępne role, nazwy, etykiety i anulowanie. |
| Kanały ignorowane | PASS — panel jasno definiuje zwykłą wiadomość bez utworzenia sprawy i bez SLA. |
| Konsola | PASS — brak błędów i ostrzeżeń aplikacji na sprawdzonych trasach. |
| PWA / service worker | PASS — `sw.js`, manifest i wszystkie zadeklarowane ikony zwracają 200; cache nie obejmuje API, mutacji ani żądań autoryzowanych. |
| Znaczniki hardeningu | PASS — brak `TODO`, `FIXME`, `HACK` i `XXX` w kodzie aplikacji. |
| Dokumentacja | PASS — README, stan bieżący, architektura, domena, testy i decyzje otwarte opisują aktualną implementację. |
| `AGENTS.md` | PASS — instrukcje odpowiadają aktualnym trasom, modelowi domenowemu, testom i etapowi projektu. |

## Remaining decisions

Nierozstrzygnięte na dzień raportu kwestie produktowe i kontraktowe opisano w [OPEN_DECISIONS.md](OPEN_DECISIONS.md). Późniejszy ticket USI-6 zamknął decyzję o kanałach: aktualny kontrakt v1/GA znajduje się w [V1_SCOPE.md](V1_SCOPE.md). Pozostałe otwarte kwestie dotyczą źródła prawdy dla prezentacji `/cases`, wspólnej tożsamości użytkownika, wymiarów analitycznych, egzekwowania ignorowanych kanałów oraz docelowego workflow akcji sprawy.

## Ready for the next phase

**YES.** Repozytorium spełnia końcową bramkę hardeningu i jest gotowe do rozpoczęcia prac nad kontraktem OpenAPI oraz adapterami przyszłego backendu. Nie ma blokad technicznych w zweryfikowanym zakresie. Otwarte decyzje powinny zostać rozstrzygnięte przed utrwaleniem odpowiadających im pól API lub reguł workflow, bez zgadywania ich semantyki we frontendzie.
