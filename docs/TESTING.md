# Testowanie i CI

Stan na 9 sierpnia 2026 r. Testy korzystają wyłącznie z lokalnych danych mock i nie wymagają poświadczeń Slacka, Microsoft Teams ani Telegrama.

## Wymagania

- Node.js 22.23.2 LTS z `.nvmrc` (pnpm 11.18.0 wymaga Node.js `>=22.13`).
- pnpm 11.18.0 zadeklarowany w `package.json`.
- Chromium Playwright zainstalowany poleceniem `pnpm exec playwright install chromium`.

Instalacja zależności na czystym checkout:

```bash
pnpm install --frozen-lockfile
```

## Polecenia

| Polecenie | Zakres |
| --- | --- |
| `pnpm test:unit` | Jednorazowe uruchomienie Vitest. |
| `pnpm test:unit:watch` | Vitest w trybie obserwowania. |
| `pnpm build` | Produkcyjny build wymagany przez samodzielne E2E. |
| `pnpm test:e2e` | Testy Playwright na lokalnym `next start` pod portem 3100. |
| `pnpm check` | Lint, typecheck, testy jednostkowe, build i E2E. |

## Testy jednostkowe i komponentowe

Konfiguracja znajduje się w `vitest.config.mts`. Środowisko jsdom jest inicjalizowane przez `tests/unit/setup.ts` i rozszerzone o matchery Testing Library.

Aktualny zakres:

- dokładny zestaw statusów kanonicznego workflow inbox,
- dokładny zestaw kanałów v1: Slack, Microsoft Teams i Telegram, bez e-maila,
- dokładne role `USER`/`ADMIN`, katalog dziewięciu permissions i wymóg jednoczesnej roli `ADMIN` oraz permission,
- wyliczanie efektywnego stanu SLA, w tym przekroczenie terminu i wstrzymanie,
- wpisywanie oraz czyszczenie kontrolowanego pola wyszukiwania.

## Playwright

Konfiguracja znajduje się w `playwright.config.ts`. Testy używają produkcyjnego serwera Next.js oraz jednego projektu Chromium.

Chronione przepływy:

- smoke dla `/cases`, `/statistics`, `/users` i `/settings`,
- wybór nieodczytanego czatu, otwarcie rozmowy i oznaczenie wpisu jako odczytany,
- rozpoczęcie odpowiedzi na konkretną wiadomość,
- otwarcie i reset formularza dodawania użytkownika,
- mobilne przejście z listy czatów do rozmowy i z powrotem.

Każdy scenariusz blokuje żądania HTTP poza `http://127.0.0.1:3100`. Service worker jest wyłączony w kontekście testowym, aby cache PWA nie wpływał na deterministyczność.

Przy niepowodzeniu Playwright zapisuje screenshot i trace w `test-results/`, a raport HTML w `playwright-report/`. Oba katalogi są ignorowane przez Git.

## GitHub Actions

Workflow `.github/workflows/ci.yml` działa dla `push` i `pull_request` i wykonuje kolejno:

1. checkout,
2. konfigurację pnpm i Node.js z cache pnpm,
3. `pnpm install --frozen-lockfile`,
4. instalację Chromium wraz z zależnościami systemowymi,
5. lint,
6. typecheck,
7. testy jednostkowe/komponentowe,
8. produkcyjny build,
9. testy Playwright.

Workflow ma wyłącznie uprawnienie `contents: read`, nie korzysta z sekretów produkcyjnych i wysyła artefakty Playwright tylko po błędzie.

## Zmiany domenowe

Po zmianach w `lib/domain/` należy zawsze uruchomić `pnpm check`. Typecheck wykrywa pozostałe importy usuniętych modeli, a E2E chroni bieżące zachowanie czatów, statystyk, użytkowników i ustawień przed skutkami refaktoru typów. Testy nie zatwierdzają nowych reguł workflow — takie reguły wymagają wcześniej jawnej decyzji produktowej.
