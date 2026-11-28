# Audyt funkcjonalny ApplicationSupport — 28 listopada 2026

## Zakres i pochodzenie dowodów

Raport roboczy inicjatywy stabilizacyjnej. Inwentaryzacja obejmuje 37 istniejących identyfikatorów z `config/regression-features.json`. Link do testu oznacza powiązanie, a nie potwierdzenie wszystkich scenariuszy funkcji. Wyniki automatyczne, obserwacje stagingu i akceptację u dostawcy raportujemy osobno. Brak testu nie jest wynikiem PASS.

Punkt wyjścia: `origin/main` = `1101c14749f88ebda7e0c395b451e80fccb01f64`, po scaleniu PR #165–#171. [CI main](https://github.com/Spodyn/ApplicationSupport/actions/runs/46153518193) ma osiem zakończonych sukcesem zadań; [wdrożenie staging](https://github.com/Spodyn/ApplicationSupport/actions/runs/46154407145) zakończyło się sukcesem dla tego SHA. To dowody GitHub, nie niezależny odczyt rewizji z publicznego runtime. Publiczny odczyt rewizji: NOT TESTED.

Obserwacje wykonano przez połączone rozszerzenie Brave na `https://application-support.duckdns.org`, kontem administratora, bez zmieniania wspólnych polityk, kalendarza, uprawnień, sekretów i resync. Nie uruchomiono dodatkowych dostawców. Dostęp do bieżących ticketów Jira jest BLOCKED: połączony konektor zwraca HTTP 403 (aplikacja nie jest zainstalowana w instancji). Obowiązują aktualne polecenia właściciela i zamrożone dokumenty repozytorium.

## Potwierdzone usterki i luki

| ID | Priorytet | Oczekiwane / zaobserwowane / przyczyna | Stan i dalsza weryfikacja |
| --- | --- | --- | --- |
| AUDIT-SLA-01 | P1 | Po terminie opóźnienie FRT/NRT rośnie również poza godzinami pracy (doprecyzowanie właściciela 28.11). W sobotę badge FRT pozostaje `7 hrs 45 mins 0 secs overdue`. Backend mierzy zaległość w czasie roboczym; frontend dodaje wyłącznie otwarte przedziały. Follow-up działa w czasie rzeczywistym. | FAIL na stagingu. Poprawka i testy w oddzielnej gałęzi `codex/sla-overdue-elapsed`; akceptacja po integracji jeszcze NOT TESTED. |
| AUDIT-SET-01 | P2 | W Channels wybrany klient powinien mieć nazwę. Zamiast `Teams Sandbox` widać UUID, a przy braku mapowania techniczne `__unmapped__`. | FAIL prezentacji na stagingu; wpływ na zapis wymaga osobnego testu. |
| AUDIT-SET-02 | P2 | Formularz Add notification destination powinien wybierać istniejącą integrację albo wymagać jej wyboru. Początkowo pokazuje `int-slack`, mimo rzeczywistych integracji o innych identyfikatorach. | FAIL stanu początkowego. Zapis na stagingu NOT TESTED — nie zmieniano wspólnych reguł powiadomień. |
| AUDIT-I18N-01 | P2 | Przykład SLA powinien używać aktywnego języka. Angielski formularz zawiera polskie `pon.`. | FAIL lokalizacji przykładu; bez wpływu na dowód kalkulacji backendu. |
| AUDIT-CI-01 | P2 | Nowe wymaganie: rozszerzona regresja co noc. Obecny cron `23 3 * * 1` działa raz w tygodniu. | Luka względem nowego wymagania; potrzebna osobna zmiana workflow i test jego kontraktu. |
| AUDIT-DEPLOY-01 | P1 | Każde wdrożenie powinno przejść bezpieczny, uwierzytelniony smoke. `deploy.sh` wykonuje pełny smoke tylko przy pierwszym bootstrapie; później sprawdza HTTPS `/login` i zdrowie kontenerów. | Luka w weryfikacji późniejszych wdrożeń; nie wolno przywracać zależności od początkowego hasła administratora. |

Osobno: ruleset GitHub wymienia sześć kontekstów (`configuration`, `backend`, `integration`, `infrastructure`, `openapi`, `validate`); `web` i `fullstack` są wymagane pośrednio przez fail-closed `validate` oraz niezależny guard. Zalecenie: dopisać oba konteksty bezpośrednio. Nie zmieniono rulesetu ani ochrony gałęzi.

Obserwacja lokalizacyjna do osobnego odtworzenia: angielskie szczegóły CASE-21 pokazują polskie `Nie przypisano`. Nie wolno tłumaczyć dowolnych nazw klientów jako kluczy i18n.

Podejrzenie do odtworzenia: po mutacji odpowiedzi Slack pojawiło się `Edited` na nieedytowanej w tym audycie wiadomości głównej. Sam odczyt UI nie ustala przyczyny; nie liczymy tego jeszcze jako potwierdzonej usterki.

## Ustawienia — rzeczywiste obserwacje

| Sekcja | Stan live | Zaobserwowane / oczekiwane / brakujące scenariusze |
| --- | --- | --- |
| General | PARTIAL | Nazwa organizacji, język i compact mode są widoczne. Zapis organizacji/compact, walidacja, współbieżność i utrwalenie: NOT TESTED na wspólnym stagingu; wymagają izolowanych fixture. |
| SLA | PARTIAL | FRT 15, NRT 30, follow-up 1440, przypomnienia 15/30, starsze ostrzeżenie 15, ponawianie 120, pause waiting włączone. AUDIT-SLA-01 i AUDIT-I18N-01. Nie zmieniano polityki, by przyspieszyć testy. |
| Business hours | PARTIAL | UTC, pon.–pt. 09:00–17:00, weekend zamknięty; widoczne dni, przerwy i formularz wyjątków. Zapis, odrzucenia, CLOSED/OPEN/OVERRIDE, overnight i DST wymagają izolowanego stosu; nie są live PASS. |
| Out of office | PARTIAL | Wyłączone, polski szablon z placeholderami, send once włączone, widoczny preview następnego otwarcia. Rzeczywista wysyłka OOO/konflikt wersji/no future opening: NOT TESTED. |
| Integrations | PARTIAL | Slack healthy; Teams needs attention. Widoczne discovery, callback, setup, resync, retry attachments. Zachowano konfigurację. Teams `support-test` pokazuje historyczny `TEAMS_HISTORY_BOT_ROUTE_MISSING`; nie uruchamiano importu. |
| Customers | PARTIAL | Klient `Teams Sandbox` widoczny; dostępne Add/Edit/Deactivate. Nie modyfikowano istniejącego klienta ani mapowań. |
| Channels | FAIL | Widoczne rzeczywiste kanały Slack i Teams, monitoring i grouping; AUDIT-SET-01. Wszystkie Last message pokazują None mimo rozmów — wymaga potwierdzenia semantyki i testu. |
| Notifications | FAIL | Brak destinations i deliveries, dostępne formularze i pięć typów zdarzeń; AUDIT-SET-02. Nie wysyłano zewnętrznych alertów. |
| Permissions | PARTIAL | Dziewięć uprawnień, USER przełączniki wyłączone, ADMIN włączone. Nie zmieniano dostępu. Backendowe odmowy i aktualizacja aktywnej sesji wymagają osobnych testów. |

## Inwentarz funkcji i stan akceptacji live

Oczekiwane zachowanie i istniejące pliki testów pozostają w maszynowym inwentarzu oraz `REGRESSION_MATRIX.md`. Poniższe stany dotyczą wyłącznie zakresu rzeczywiście obserwowanego w tym audycie.

| ID | Stan live | Dowód / brakująca weryfikacja |
| --- | --- | --- |
| AUTH | PARTIAL | Istniejąca sesja administratora działa. Nowy login, logout, reset i wygaśnięcie jeszcze NOT TESTED. |
| USERS | PARTIAL | Odczyt katalogu trzech kont, ról, statusu i workload oraz formularza dodawania z dziewięcioma uprawnieniami. CRUD, deaktywacja i zmiany uprawnień aktywnej sesji: NOT TESTED live. |
| CUSTOMERS | PARTIAL | Lista i akcje odczytane; brak modyfikacji wspólnych danych. |
| INTEGRATIONS | PARTIAL | Odczyt zdrowia i konfiguracji; bez sekretów i ponownego onboardingu. |
| CHANNELS | FAIL | Nazwy kanałów poprawne, błędne etykiety mapowania w Settings. |
| SLACK_IN | PARTIAL | Historyczne dedykowane wiadomości są widoczne. Nowa odpowiedź klienta jeszcze niepotwierdzona. |
| SLACK_OUT | PARTIAL | Nowa testowa odpowiedź CASE-00000009 pojawiła się w rzeczywistym właściwym wątku Slack. Dotyczy tekstu; nowe załączniki i awarie NOT TESTED live. |
| CASE | PARTIAL | Lista, szczegóły i powiązane źródła działają; nowa sprawa i terminalny successor jeszcze NOT TESTED. |
| CLAIM | PARTIAL | Właściciel istniejącej sprawy testowej widoczny; nowy Claim/race nieprzeprowadzony live. |
| REPLY | PARTIAL | Odpowiedź z ASKED zachowuje ASKED i właściciela, ustawia follow-up. Trzy nowe odpowiedzi i customer reopening jeszcze nieprzeprowadzone w tym audycie. |
| RESOLVE | PARTIAL | Akcja dostępna w ASKED; niepotwierdzona nową operacją live. |
| IGNORE | PARTIAL | Przycisk poprawnie niedostępny dla właściciela; głosowania i terminalność nieweryfikowane live. |
| SNOOZE | NOT TESTED | Zmiana, wygaśnięcie i osobisty zakres jeszcze nieweryfikowane live. |
| SLA | FAIL | AUDIT-SLA-01; follow-up odlicza bez refresh w kolejce, rozmowie i szczegółach. |
| SCHEDULE | PARTIAL | Odczyt tygodnia i następnego otwarcia; zapis i granice nieprzeprowadzone live. |
| NOTIFICATIONS | PARTIAL | Dzwonek zawiera trwałe przypomnienie follow-up CASE-20 z 28.11 00:04 oraz cztery przypomnienia wsparcia. CASE-20 pozostaje ASKED z właścicielem. Nowa dostawa realtime, read-all, izolacja i before/after statystyki: NOT TESTED live; kontrolowane testy są odrębne. |
| READ | PARTIAL | Wskaźniki unread widoczne; pełna izolacja i seen/ack jeszcze nieweryfikowane live. |
| ANALYTICS | PARTIAL | W polskim widoku 22–28 listopada: FRT 3, unikalne historyczne naruszenia 3, NRT 1, aktualnie zaległe odpowiedzi 1, follow-up 0. Definicje są rozdzielone. Niezależne przeliczenie live całego zbioru i porównania: NOT TESTED. |
| HISTORY | PARTIAL | Dedykowana rozmowa i activity odczytane; pełna paginacja i wyszukiwanie jeszcze NOT TESTED. |
| CASE_NOTES | NOT TESTED | Widoczny tryb notatki; brak nowej operacji w tym audycie. |
| REALTIME | PARTIAL | Statusy nowej wysyłki i mutacji zmieniają się bez refresh. Dwa różne konta, reconnect i bursta jeszcze NOT TESTED live. |
| ATTACHMENTS | NOT TESTED | Nowe live operacje załączników jeszcze niewykonane. |
| RETRY | NOT TESTED | Celowo nie wymuszano awarii prawdziwego dostawcy. |
| PERSISTENCE | PARTIAL | Odczyt danych starszych wdrożeń; restart/migracje/PITR nie są przedmiotem live manipulacji. |
| AUDIT | PARTIAL | Widoczne activity request/success dla mutacji; pełny audit/retention nieweryfikowany live. |
| BROWSER | PARTIAL | EN/PL, light/dark i układ mobilny około 355 px bez poziomego overflow odczytane. Nie zastępuje to pełnego audytu dostępności ani fizycznego urządzenia. |
| API | PARTIAL | UI korzysta z REST; nie zastępuje to pełnej macierzy autoryzacji endpointów. |
| CI | PARTIAL | Baseline ma osiem zielonych kontroli; wymagane rozszerzenie nightly i traceability. |
| PROVIDERS | PARTIAL | Rzeczywisty Slack potwierdzony dla wysyłki i mutacji tekstu; Teams i Telegram osobno. |
| I18N | FAIL | Większość odczytanego angielskiego UI zlokalizowana; polski dzień w przykładzie SLA. |
| BULK | NOT TESTED | Nie wykonano jeszcze nowych działań zbiorczych. |
| DIRECT_MESSAGES | PARTIAL | Nawigacja, lista pusta, wyszukiwanie i New message widoczne. Nie wysyłano wiadomości do istniejących współpracowników; brak dedykowanego drugiego konta testowego w live. |
| STAGING | PARTIAL | HTTPS i istniejąca sesja działają; dowód deployment GitHub, brak niezależnego publicznego runtime SHA. |
| HISTORY_RECOVERY | PARTIAL | Odczyt historycznej ukończonej i niekompletnej próby Teams. Nowy import celowo nieuruchamiany. |
| PROVIDER_ONBOARDING | PARTIAL | Formularze/readiness/scope odczytane; instalacja i nowe granty nieprzeprowadzane. |
| INBOX_PREFERENCES | PARTIAL | Unified → Separate by platform → reload utrwala wybór; przywrócono Unified. Przetestowano osobiste EN/PL i light/dark, następnie przywrócono English/light. Drugie niezależne konto live: NOT TESTED. |
| MESSAGE_MUTATIONS | PARTIAL | Slack tekst: send/edit/copy/delete PASS w UI, edycja i usunięcie potwierdzone niezależnie w Slack. Zachowano placeholder i historię; termin follow-up pozostał 29.11 o 19:17. Teams, pliki, błędy i inne konto jeszcze NOT TESTED live. |

## Dziennik sandboxu Slack

28.11, około 19:17 Europe/Warsaw: CASE-00000009, prywatny `usi-intake-test-20261109`, istniejący dedykowany wątek z markerami poprzedniego audytu. Wysłano wyłącznie nową wiadomość `USI FUNCTIONAL AUDIT 28NOV R1`, edytowano ją, skopiowano potwierdzony tekst i usunięto z potwierdzeniem. Slack pokazał wiadomość od aplikacji, zmieniony tekst, a następnie zniknięcie tej odpowiedzi. ApplicationSupport zachował treść i znacznik usunięcia. Innych wiadomości nie edytowano ani nie usuwano. Follow-up przesunął się po wysyłce na 29.11 o 19:17; edycja i usunięcie go nie zmieniły. ASKED i właściciel pozostali.

Próba nowej syntetycznej odpowiedzi klienta w UI Slack nie dała potwierdzonej wysyłki. Nie zaliczono customer reopening. Pozostał testowy szkic do sprawdzenia/posprzątania; nie należy ponawiać wysyłki bez sprawdzenia wątku. Nie jest to dowód błędu odbioru w ApplicationSupport.

## Dostarczone poprawki i automatyczna regresja

- [PR #172](https://github.com/Spodyn/ApplicationSupport/pull/172), `f3259aca2ed5677c92688cb332d14a8aed1a12d2`: FIXED w kodzie i kontrolowanym stosie — po terminie FRT/NRT rośnie czas rzeczywisty także poza godzinami pracy. Przed terminem nadal obowiązuje snapshot kalendarza. Historia SLA bez zmian. Oba komplety ośmiu kontroli [push](https://github.com/Spodyn/ApplicationSupport/actions/runs/46176777793) i [PR](https://github.com/Spodyn/ApplicationSupport/actions/runs/46176782570) PASS. Lokalnie backend 346, integracja 354, Vitest 144, browser 61, full-stack 30. Dodano 4 jednostkowe backend + 1 integracyjny + 6 frontend + 4 browser + 2 full-stack. Moduł operational SLA ma 100% w czterech wymiarach pokrycia; to nie jest pokrycie całej aplikacji.
- [PR #173](https://github.com/Spodyn/ApplicationSupport/pull/173), `ccb03d25ad46d5f592a0744369ba46b20ed20f31`: FIXED w kodzie i izolowanych testach — trzy problemy Settings. Przed poprawką 8 z 9 nowych testów komponentowych FAIL, po poprawce 9 PASS. Łącznie 147 Vitest, 60 browser i 29 full-stack PASS lokalnie. Dodano 9 komponentowych + 3 browser + 1 full-stack. Kontrole finalnego HEAD są publikowane na PR; pending nie oznacza PASS.
- Inwentarz obejmuje 37 funkcji, 111 definicji scenariuszy pozytywnych/negatywnych/brzegowych i identyfikatory istniejących suite z warstwą oraz CI. Definicja scenariusza nie oznacza kompletnego pokrycia. Walidator wykrywa usunięte funkcje, brakujące pliki/źródła, nieaktualne identyfikatory, niespójne statusy i niewyjaśnione usunięcie powiązanego testu względem dokładnej bazy Git.
- Rozszerzona regresja: nightly 03:23 UTC i manual, trzy niezależne powtórzenia bez retries plus mutation smoke. Obowiązkowe osiem zadań push/PR pozostaje bez zmian. JUnit, HTML, SHA, inventory i scoped coverage są zachowywane również przy sukcesie.

Stan wdrożenia tych poprawek: NOT DEPLOYED przez ten audyt. PR-y pozostają do niezależnej integracji; nie wykonywano merge/deploy. Akceptacja po wdrożeniu: NOT TESTED.

## Konkretne ograniczenia dostępu

- Teams web `support-test`: dostawca pokazał błąd ładowania wiadomości; po Odśwież nie uzyskano działającej rozmowy. Live send/edit/delete/attachments: NOT TESTED. Kontrolowane adaptery Slack i Teams przechodzą niezależną regresję.
- Brak drugiego dedykowanego konta USI z dostępną sesją. Dwa odrębne konta są testowane automatycznie, nie zaliczone live.
- Jira connector odrzuca dostęp (app unavailable/403). Odczyt aktualnych ticketów: BLOCKED. Nie zmieniano statusów Jira.
- Publiczny runtime SHA nie jest eksponowany; zgodność ma dowód dokładnego workflow deployment i image labels, nie niezależny odczyt w aplikacji.
- Pełny auth smoke kolejnych wdrożeń wymaga dedykowanego konta i chronionych plików na hoście. Nie tworzono konta ani sekretów. Brak takiego dostępu nie jest PASS.
- Istniejący [PR #145](https://github.com/Spodyn/ApplicationSupport/pull/145) zawiera pracę nad pełnymi CLOSED/OPEN/OVERRIDE i OOO. Nie duplikowano równolegle tego wdrożenia i nie scalano go w audycie.

## Pozostałe prace

1. Osobny PR SLA, pełna lokalna regresja i push/PR CI dla dokładnego HEAD, niezależna integracja i akceptacja po wdrożeniu.
2. Odtworzyć błędy formularzy na izolowanych fixture; poprawić etykiety, domyślne wartości i lokalizację bez zmieniania wspólnego stagingu.
3. Dokończyć dostępne operacje live, drugie konto, języki/tematy, statystyki i Teams; wskazać dokładnie brakujące uprawnienia lub dostęp.
4. Rozszerzyć istniejący inwentarz o źródła, identyfikatory scenariuszy, warstwy, granice, wyniki i pochodzenie dowodów; walidować brakujące powiązania i niewyjaśnione redukcje.
5. Oddzielny PR CI/deployment: nightly + manual, zachowanie ośmiu obowiązkowych zadań, raporty z SHA/licznikami, bezpieczny smoke kolejnych wdrożeń, bez niekontrolowanego rollbacku i bez ponownego użycia hasła bootstrap.

Nie jest to końcowe potwierdzenie gotowości produkcyjnej ani deklaracja braku pozostałych usterek.
