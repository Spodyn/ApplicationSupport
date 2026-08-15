# Stan bieżący aplikacji

Audyt wykonano 9 sierpnia 2026 r. dla rewizji `65a513b`, a stan zależności, testów, domeny i dokumentacji zaktualizowano w zadaniach 02–05. Dokument opisuje zastany frontend i nie jest specyfikacją planowanego backendu. Wynik końcowej walidacji zawiera [HARDENING_REPORT.md](HARDENING_REPORT.md).

## Zakres audytu

Sprawdzono kod aplikacji, komponenty układu i design systemu, modele domenowe, granicę usług, mocki, konfigurację Next.js/TypeScript/PWA, skrypty i lockfile. Aplikację uruchomiono lokalnie i zweryfikowano w przeglądarce w rozdzielczości 1440×900 oraz na widoku mobilnym 390×844.

## Stos i uruchomienie

- Next.js 16, React 19, strict TypeScript, Tailwind CSS, TanStack Query i Recharts.
- Wymagane środowisko jest udokumentowane jako Node.js 22.23.2 LTS (`.nvmrc`) i pnpm 11.18.0 (`packageManager`); ta wersja Node spełnia wymaganie pnpm `>=22.13`.
- Jedynym lockfile jest `pnpm-lock.yaml`; `package-lock.json` usunięto, a manifest i lockfile wskazują dokładnie Next.js 16.3.0.
- `pnpm-workspace.yaml` jawnie określa politykę skryptów instalacyjnych: `msw: false`, `sharp: true` i `unrs-resolver: true`.
- Instalacja `pnpm install --frozen-lockfile` jest deterministyczna i zakończyła się sukcesem. Końcową walidację CI wykonano przy użyciu Node.js 22.23.2 i pnpm 11.18.0.
- Lokalny serwer deweloperski uruchomił Next.js 16.3.0 i poprawnie obsłużył wszystkie istniejące trasy.

## Rzeczywiste trasy

| Trasa | Stan | Zachowanie |
| --- | --- | --- |
| `/` | istnieje | Przekierowuje do `/cases`. |
| `/cases` | istnieje | Dwukolumnowa skrzynka czatów: lista i rozmowa. |
| `/statistics` | istnieje | KPI, wykresy, tabela oraz filtry okresu i wymiarów. |
| `/users` | istnieje | Lista kont, filtry, formularze tworzenia/edycji i akcje administracyjne. |
| `/settings` | istnieje | Ogólne, SLA, godziny pracy, poza biurem, integracje, kanały, powiadomienia i uprawnienia. |
| `/current-cases` | nie istnieje | Zwraca 404 i nie występuje w nawigacji. |

Ponadto App Router generuje zasoby metadanych/PWA: manifest, ikony aplikacji i ikony PWA.

README wymienia wyłącznie pięć istniejących tras. Ewentualny powrót `/current-cases` pozostaje jawną decyzją produktową i nie jest opisany jako funkcja bieżąca.

## Granica danych

Docelowy przepływ jest zachowany:

`UI → lib/services/queries.ts → lib/services/registry.ts → obszarowe interfejsy usług → implementacje mock`

- Komponenty aplikacji nie importują bezpośrednio z `mocks/`.
- `lib/services/registry.ts` pozostaje miejscem kompozycji implementacji.
- Operacje skrzynki i administracji są udostępnione przez hooki TanStack Query.
- Istotny wyjątek: `components/cases/cases-page.tsx` zawiera własne dane prezentacyjne listy i rozmowy. Usługa inbox dostarcza tylko część stanu, między innymi odczytanie i wiadomości. Powoduje to rozjazdy między widoczną prezentacją a rekordami domenowymi dla tych samych referencji.

## Modele domenowe

Kanoniczny model workflow znajduje się w `lib/domain/inbox.ts`. Obowiązujące statusy to:

- `new`
- `verification`
- `waiting_for_customer`
- `partially_ignored`
- `ignored`
- `resolved`

Starszy, ogólny `Case` wraz z `CaseStatus`, repozytoriami, hookami i osobnymi mockami został usunięty po potwierdzeniu braku konsumentów. Współdzielone, niekonfliktowe typy kanału, SLA, dostarczenia wiadomości i tożsamości znajdują się w `lib/domain/shared.ts` i są jawnie oddzielone od przyszłych DTO OpenAPI.

`AdministrationUser` częściowo dubluje współdzielony `User`, ale ma osobny model roli, aktywności, ważności i uprawnień. `AnalyticsRecord.status` używa jawnie nazwanego `AnalyticsStatusDimension`; jest wymiarem raportowym, a nie drugim modelem workflow.

## Zweryfikowane zachowanie UI

| Obszar | Wynik |
| --- | --- |
| Wybór czatu | Działa; zmienia zaznaczenie i treść rozmowy. |
| Odczytanie czatu | Działa; po otwarciu „Nieodczytane” znika z wybranego wpisu. |
| Przejęcie sprawy | Przycisk `Przejmij` jest wyłączony dla sprawdzonego stanu. |
| Ignorowanie | Brak przycisku/dialogu ignorowania w aktualnym ekranie `/cases`. |
| Pytanie klienta | Brak przycisku/dialogu w aktualnym ekranie `/cases`. |
| Rozwiązanie | `Zamknij sprawę` i menu zamknięcia są wyłączone. |
| Odłożenie | `Odłóż` jest wyłączone. |
| Wysyłanie odpowiedzi | Kompozytor jest widoczny, ale przycisk wysyłania nie ma podłączonej operacji workflow. |
| Tworzenie użytkownika | Dialog otwiera się i zawiera dane konta oraz cztery aktualne uprawnienia indywidualne. |
| Edycja użytkownika | Dialog otwiera się z poprawnie wypełnionymi danymi. |
| Akcje użytkownika | Menu zawiera edycję, dezaktywację i usunięcie użytkownika. |
| Ustawienia | Wszystkie osiem zakładek otwiera właściwy panel; formularze są dostępne. |
| Ignorowane kanały | UI jasno komunikuje, że wiadomość pozostaje zwykłą wiadomością bez case’a i SLA. |
| Filtry statystyk | Panel ma `position: sticky`; podczas przewijania pozostaje przy górnej krawędzi obszaru treści. |

Po zmianach wymaganych przez ESLint dodatkowo sprawdzono reset formularza tworzenia użytkownika, zachowanie wartości w konfiguracji integracji i przełączanie motywu. Widok `/users` pozostał pikselowo zgodny z plikiem baseline przy jego rzeczywistym wymiarze 1280×720.

Testy interakcji nie zapisywały zmian w danych mock. Konsola przeglądarki nie zarejestrowała ostrzeżeń ani błędów aplikacji.

## Jakość, testy i CI

- Dostępne skrypty obejmują `lint`, `typecheck`, `test:unit`, `test:unit:watch`, `build`, `test:e2e` i zbiorczy `check`.
- Vitest 4 z Testing Library i jsdom chroni kanoniczne statusy inbox, logikę SLA oraz interakcję pola wyszukiwania: 3 pliki, 6 testów.
- Playwright uruchamia 8 deterministycznych testów Chromium dla czterech tras, wyboru/odczytania czatu, odpowiedzi, formularza użytkownika i mobilnego przepływu `/cases`.
- Testy E2E blokują zewnętrzne żądania HTTP, wyłączają service worker i korzystają wyłącznie z lokalnego serwera oraz danych mock.
- `.github/workflows/ci.yml` uruchamia pełną bramkę na `push` i `pull_request`, korzysta z cache pnpm i wysyła raport oraz artefakty Playwright po niepowodzeniu.
- ESLint 9.39.5 i `eslint-config-next` 16.3.0 są bezpośrednimi zależnościami deweloperskimi. Projekt korzysta z flat config obejmującego reguły Next.js Core Web Vitals i TypeScript.
- W kodzie nie znaleziono znaczników `TODO` ani `FIXME`.

Wyniki czystej bramki końcowej po zadaniu 05:

- `pnpm install --frozen-lockfile` — sukces.
- `pnpm lint` — sukces.
- `pnpm typecheck` — sukces.
- `pnpm test:unit` — sukces, 6/6 testów.
- `pnpm build` — sukces, 13 tras/zasobów App Router.
- `pnpm test:e2e` — sukces, 8/8 testów Chromium.
- `pnpm check` — sukces dla całej powyższej sekwencji.

## Bezpieczeństwo i PWA

- Nie znaleziono plików `.env`, kluczy, tokenów ani zmiennych środowiskowych eksponowanych do klienta.
- Jedynym użyciem `process.env` jest serwerowe sprawdzenie `NODE_ENV` dla Analytics.
- Service worker nie buforuje żądań z nagłówkiem autoryzacji ani tras `/api`; żądania inne niż GET także omijają cache.
- Cache PWA obejmuje tylko publiczne zasoby manifestu i ikon.

## Wizualny baseline

Zrzuty są częścią audytu i stanowią punkt odniesienia przed dalszym hardeningiem:

- [Czaty — desktop](baseline/2026-08-09/desktop-cases-1440x900.png)
- [Statystyki — desktop](baseline/2026-08-09/desktop-statistics-1440x900.png)
- [Użytkownicy — desktop](baseline/2026-08-09/desktop-users-1440x900.png)
- [Ustawienia — desktop](baseline/2026-08-09/desktop-settings-1440x900.png)
- [Czaty — mobile](baseline/2026-08-09/mobile-cases-390x844.png)

## Najważniejsze ryzyka

1. Widok `/cases` nie korzysta z pełnego, istniejącego workflow usługi inbox, a część prezentacji jest zdublowana w komponencie.
2. `User` i `AdministrationUser` nadal częściowo opisują te same osoby; docelowa granica tożsamości wymaga decyzji przed projektem OpenAPI.
3. Ustawienie ignorowanego kanału jest dostępne w administracji, ale obecny mock inbox nie implementuje punktu przyjmowania wiadomości, w którym reguła byłaby egzekwowana.
4. Obecny zestaw jest celowo lekki i chroni krytyczne smoke flow; pełne mutacje workflow wymagają rozszerzenia testów po potwierdzeniu ich docelowej semantyki produktowej.
