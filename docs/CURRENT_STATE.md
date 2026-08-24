# Stan bieżący aplikacji

**Frontend audit baseline:** 9 sierpnia 2026  
**Contract reconciliation:** 24 sierpnia 2026  

Ten dokument opisuje **rzeczywiście istniejący frontend/mock** oraz jawnie oddziela go od zamrożonego kontraktu v1. Nie jest źródłem nadrzędnym dla przyszłego backendu ani zachowania produkcyjnego. W razie konfliktu obowiązuje kolejność z `docs/README.md`, przede wszystkim `PRODUCT_CONTRACT.md` i `decision-registry.yaml`.

## 1. Aktualny stos i uruchomienie

- Next.js 16, React 19, strict TypeScript, Tailwind CSS, TanStack Query i Recharts.
- Node.js 22.23.2 LTS (`.nvmrc`) i pnpm 11.18.0 (`packageManager`).
- Jedyny lockfile: `pnpm-lock.yaml`.
- Aktualny frontend korzysta z lokalnych danych/mock services; repo nie zawiera jeszcze produkcyjnego Spring Boot backendu, PostgreSQL schema ani realnych adapterów Slack/Teams/Telegram.

## 2. Rzeczywiste trasy frontendu

| Trasa | Stan aktualny | Uwagi |
| --- | --- | --- |
| `/` | istnieje | Przekierowuje do `/cases`. |
| `/cases` | istnieje | Dwukolumnowa skrzynka: lista spraw i rozmowa. |
| `/statistics` | istnieje | KPI, wykresy, tabela i filtry. Docelowo zawiera również ADMIN-only sekcję **Aktualna praca**. |
| `/users` | istnieje | Lista kont i formularze administracyjne. |
| `/settings` | istnieje | Ogólne, SLA, godziny pracy, OOO, integracje, kanały, powiadomienia i permissions. |
| `/current-cases` | nie istnieje | I zgodnie z finalnym freeze **nie ma wracać jako osobna top-level trasa**. Current-work trafia do `/statistics`; backend używa `GET /api/v1/admin/current-cases`. |

## 3. Zamrożony zakres v1

V1 obsługuje dokładnie:

- Slack,
- Microsoft Teams,
- Telegram.

E-mail jako kanał supportowy oraz funkcje AI są poza v1. E-mail widoczny w lokalnych fixture'ach `/cases` jest wyłącznie elementem starej prezentacji mockowej i ma zniknąć przy podmianie danych na realny API flow.

## 4. Granica danych frontendu

Aktualna granica pozostaje celowa:

`UI -> lib/services/queries.ts -> lib/services/registry.ts -> typed service interfaces -> mock/API implementation`

Zasady docelowe:

- komponenty nie importują bezpośrednio z `mocks/`,
- `registry.ts` jest composition boundary,
- generated OpenAPI DTO są typami transportowymi,
- jeden jawny adapter/maper mapuje DTO na stabilne frontend domain/view models,
- lokalny model prezentacyjny w komponencie nie jest drugim kontraktem backendu.

`components/cases/cases-page.tsx` nadal zawiera część własnych fixture'ów/prezentacji. To **implementation debt**, a nie nierozstrzygnięta decyzja produktowa. Kierunek został zamrożony: realne dane mają pochodzić z API przez service boundary i mapper.

## 5. Kanoniczny workflow

Frontendowy workflow używa dokładnie sześciu statusów:

- `new` -> `NEW`,
- `verification` -> `VERIFICATION`,
- `waiting_for_customer` -> `WAITING_FOR_CUSTOMER`,
- `partially_ignored` -> `PARTIALLY_IGNORED`,
- `ignored` -> `IGNORED`,
- `resolved` -> `RESOLVED`.

Backend będzie authoritative dla transition policy, ownership i action availability. Snooze, unread i analityczne dimensions nie są `CaseStatus`.

Terminalne `IGNORED` i `RESOLVED` nie są reopenowane. Nowa customer message po terminalnym Case tworzy nowy linked Case.

## 6. Tożsamość i permissions

Kanoniczna backendowa tożsamość to jeden `User` identyfikowany stabilnym user ID. Obecne frontendowe `User` i `AdministrationUser` są różnymi projekcjami tej samej osoby; ich techniczna konsolidacja/mapowanie **nie wymaga nowej decyzji produktowej**.

Role są dokładnie `USER` i `ADMIN`, a katalog granular permissions zawiera dokładnie dziewięć kodów zamrożonych w `PRODUCT_CONTRACT.md`.

Aktualny frontend nie implementuje jeszcze produkcyjnego auth. Docelowy kontrakt to local e-mail/password, Spring Session JDBC i server-side session cookie.

## 7. Aktualne zachowanie mock UI a kontrakt docelowy

Poniższe rozbieżności są świadomie oznaczone, aby current-state snapshot nie został pomylony z wymaganiem v1:

| Obszar | Obecny mock | Zamrożony kontrakt v1 |
| --- | --- | --- |
| Odczytanie | Otwarcie nieodczytanego czatu obecnie usuwa badge. | Samo otwarcie Case **nie oznacza read**. Read cursor przesuwa się dopiero po faktycznym wyrenderowaniu/zobaczeniu wiadomości i acknowledgement z frontendu. |
| Ignorowany kanał | Stare copy UI może sugerować, że wiadomość pozostaje zwykłą wiadomością bez Case/SLA. | `channel.ignored=true` powoduje techniczny `IGNORED_BY_CHANNEL`; nie powstaje **Case, Message, SLA ani read-state**. |
| Workflow buttons | Część akcji jest wyłączona lub niepodłączona. | Claim/Ignore/Ask/Resolve/Snooze mają zostać podłączone do dedykowanych backend commands i server-calculated action availability. |
| E-mail fixture | Może być widoczny w prezentacji. | E-mail support channel jest poza v1 i nie występuje w realnym dataset. |

Testy obecnego mocka mogą chronić zachowanie zastane, ale przy implementacji odpowiednich ticketów muszą zostać zaktualizowane tak, aby chronić zamrożony kontrakt, a nie historyczną imitację.

## 8. Analityka

`AnalyticsStatusDimension` jest projekcją raportową, nie drugą maszyną stanów. Może zawierać pochodne kategorie, ale nigdy nie rozszerza sześciu kanonicznych `CaseStatus`.

Admin current-work jest już rozstrzygnięty: `/statistics` -> **Aktualna praca**, API `GET /api/v1/admin/current-cases`, read access `ADMIN` only. Nie jest to otwarta decyzja.

## 9. Provider grouping i ingestion

Repo nie zawiera jeszcze realnych inbound adapterów. Zamrożony kontrakt określa jednak jednoznacznie:

- Slack: root + thread,
- Teams standard channel: root + replies; group chat: one active Case per chat,
- Telegram forum topic: one active Case per topic; bez topic: one active Case per chat,
- ignored/unmapped/disabled contexts nie mogą przypadkowo tworzyć business records niezgodnie z kontraktem.

Bieżące fixture'y nie są źródłem prawdy dla tych reguł.

## 10. Jakość, testy i CI - stan obecny

Repo posiada skrypty:

- `lint`,
- `typecheck`,
- `test:unit`,
- `test:unit:watch`,
- `build`,
- `test:e2e`,
- `check`.

Istniejący frontend gate obejmuje lint, typecheck, Vitest, produkcyjny build i Playwright Chromium. Testy E2E korzystają z lokalnego serwera/mocków, blokują zewnętrzne HTTP i wyłączają service worker dla deterministyczności.

Po dodaniu backendu gate ma zostać rozszerzony o Maven/Java, backend unit/integration, Testcontainers, Spring Modulith architecture verification, OpenAPI generation/compatibility oraz wymagane security checks.

## 11. Bezpieczeństwo i PWA - stan obecny

- Repo nie zawiera produkcyjnych sekretów.
- Authenticated API responses nie mogą być cache'owane przez PWA service worker.
- Browser może otrzymać wyłącznie bezpieczne public config values.
- Codex/CI/testy nie mogą otrzymać production DB/provider/object-storage credentials.

## 12. Wizualny baseline

Zrzuty z audytu 9 sierpnia pozostają referencją wizualną:

- [Czaty - desktop](baseline/2026-08-09/desktop-cases-1440x900.png)
- [Statystyki - desktop](baseline/2026-08-09/desktop-statistics-1440x900.png)
- [Użytkownicy - desktop](baseline/2026-08-09/desktop-users-1440x900.png)
- [Ustawienia - desktop](baseline/2026-08-09/desktop-settings-1440x900.png)
- [Czaty - mobile](baseline/2026-08-09/mobile-cases-390x844.png)

Baseline chroni layout/styl, ale **nie może nadpisywać późniejszego kontraktu zachowania**.

## 13. Pozostałe ryzyka implementacyjne - nie otwarte decyzje

1. `/cases` nadal zawiera presentation fixtures i wymaga podmiany na API mapper/service flow.
2. Backend, DB, auth i provider adapters nie są jeszcze zaimplementowane.
3. Workflow UI nie jest jeszcze podłączony do server commands.
4. Ignored-channel ingestion nie istnieje runtime mimo zamrożonej semantyki.
5. Retention/legal hold/backup są kontraktem, ale nie mają jeszcze runtime implementation.
6. Testy zastanego mocka trzeba etapami przestawiać z historycznego zachowania na frozen contract podczas realizacji właściwych Jira tickets.

Żaden z tych punktów nie jest obecnie znaną nierozstrzygniętą decyzją produktową/architektoniczną. `OPEN_DECISIONS.md` zawiera zasady tworzenia nowego human blockera, a `PRODUCT_CONTRACT.md` i `decision-registry.yaml` pozostają źródłami nadrzędnymi.