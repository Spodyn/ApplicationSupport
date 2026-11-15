# Przejście na cztery statusy sprawy

Analiza bazowa dla SHA `e3ab05e1fe3086fa45657972877193c524826583`. Dokument rejestruje stan bazowy, potwierdzone elementy i plan dalszych zmian. Wiążące reguły obecnej wersji opisują [kontrakt produktu](PRODUCT_CONTRACT.md#4-canonical-case-state-machine), [macierz przejść](WORKFLOW_MATRIX.md) i [rejestr decyzji](decision-registry.yaml).

## Kontrakt obecny

| Obszar | Obowiązująca reguła | Egzekwowanie i regresja |
| --- | --- | --- |
| Status i owner | `NEW`, `VERIFICATION`, `WAITING_FOR_CUSTOMER`, `PARTIALLY_IGNORED`, `IGNORED` i `RESOLVED`. Aktywny owner występuje tylko w `VERIFICATION`. `RESOLVED` może przechować ostatniego ownera. | [V17](../apps/api/src/main/resources/db/migration/V17__case_persistence.sql), `CaseTransitionPolicy`, `CaseTransitionPolicyTests`. |
| Rozwiązanie | Owner w `VERIFICATION` może wykonać zwykłe Resolve. `ADMIN + force_resolve` może rozwiązać aktywną sprawę. Operacja jest audytowana i terminalna. | `CaseResolveService`, `CaseAdministrationService`, `CaseBulkServiceIntegrationTests` i [full-stack](../apps/web/tests/fullstack/workflows.spec.ts). |
| Kontakt w zamkniętym wątku | Nowa wiadomość klienta tworzy jedną powiązaną sprawę `NEW` albo trafia do istniejącego aktywnego następcy. Terminalny poprzednik zachowuje historię. | `CaseCreationDomainService`, `CaseTerminalLinkingIntegrationTests`, test full-stack z równoległymi odpowiedziami. |
| Claim i dostawa | Claim nadaje ownera i przechodzi do `VERIFICATION`. Zwykły Reply pozostaje w tym stanie po dostawie i po błędzie. Dopytaj oczekuje na `SENT`, przechodzi w `WAITING_FOR_CUSTOMER`, zwalnia ownera i wstrzymuje zegar. Odpowiedź klienta lub timeout przywraca `NEW`. | `CaseTransitionPolicyTests`, `AskCustomerService`, `WaitingTimeoutService`, regresje sukcesu i błędu dostawcy w [full-stack](../apps/web/tests/fullstack/workflows.spec.ts). |
| Ignore | Skuteczna waga zapisywana z głosem, próg 2. Poniżej progu stan `PARTIALLY_IGNORED`. Próg oznacza terminalne `IGNORED`. Reset dezaktywuje głos, zachowując historię i trwałe ograniczenia Claim/Reply. | `CaseTransitionPolicyTests` i `CaseBulkServiceIntegrationTests`. |
| Quick Views | „Nieprzypisane” obejmuje także widoczne oczekujące. „Moje” pokazuje aktywną własność. „SLA” pomija oczekujące, wstrzymane i terminalne. „Wszystkie / Archiwum” obejmuje terminalne, a zaawansowany filtr może pokazać tylko terminalne. Wszystkie liczniki stosują te same predykaty co lista. | `CaseListService`, `CaseListServiceIntegrationTests`, regresje dwóch sesji i reconnect. |
| Notatki | Autor musi mieć uprawnienie do Reply. Istniejące notatki są czytelne także po zamknięciu. | `CaseInternalNoteService`, test full-stack notatek. |

## Decyzje wymagane przed wdrożeniem

1. **Cofnięcie rozwiązania.** Zakres force resolve lub oba tryby, uprawnienie aktora, docelowy stan, owner i zasady SLA. Gdy istnieje aktywny następca, trzeba ustalić odpowiedź API, docelowy ekran i ewentualny audyt próby. Dla danych historycznych warunkiem cofnięcia wyłącznie force resolve jest wiarygodny dowód z audytu.
2. **Nowa wiadomość po rozwiązaniu.** Pozostawienie powiązanego następcy, reaktywacja istniejącej sprawy albo ustawienie administracyjne. Wybór wpływa na grupowanie webhooków i na to, która sprawa przyjmuje odpowiedź.
3. **Ignore i mapowanie.** `NEW` oraz `RESOLVED` mają odpowiedniki nazw. `VERIFICATION → OPEN` i `WAITING_FOR_CUSTOMER → WAITING_ON_CUSTOMER` zmieniają API i etykiety. `PARTIALLY_IGNORED` oraz `IGNORED` wymagają zatwierdzenia odrębnej projekcji głosów i terminalnego powodu, w tym wpływu na statystyki. Zachowanie progu i uprawnień pozostaje częścią decyzji.
4. **Claim, Reply i Dopytaj.** Oddzielnie zatwierdzić przejście Claim, zachowanie zwykłego Reply, regułę Dopytaj po dostawie, ownera i SLA w oczekiwaniu, reakcję na odpowiedź klienta oraz timeout. Ustalić również znaczenie zaległych i ponawianych wysyłek.
5. **Quick Views.** Zatwierdzić widoczność oczekujących w „Nieprzypisane” i „Moje” oraz sposób użycia filtra archiwum. Wpływa to na listy, liczniki i zaznaczenie masowe.

## Plan migracji i kolejność wydania

1. **Inwentaryzacja.** Zmierzyć rozkład statusów i ownerów, oczekujące terminy, głosy aktywne/historyczne, powiązania i ostatnie zdarzenia rozwiązania oraz wiadomości wychodzące w toku. Zweryfikować unikalność dla dokładnego klucza `(integration_id, channel_id, provider, external_conversation_id, COALESCE(external_thread_key, ''))`. Sprawy bez wiarygodnego pochodzenia rozwiązania oznaczyć do raportu, jeżeli pochodzenie warunkuje cofnięcie.
2. **Expand.** Dodać pola lub projekcje potrzebne do zatwierdzonego modelu bez wymiany istniejącej kolumny `status` w pierwszym wydaniu. Zachować ID, referencję, powiązania, głosy, historyczne timestampy i oryginalne audyty. Projekt nowego powodu terminalnego i historia cofnięć zależą od decyzji. Kopia bazy i scenariusze syntetyczne razem powinny pokryć wszystkie sześć stanów, brak właściciela, rekordy po timeout i dane po częściowym Ignore.
3. **Zgodność API.** Stare odczyty, filtry i klienci muszą mieć zdefiniowane mapowanie przez okres zgodności. Jeżeli oczekiwanie może mieć ownera, stare API i stary kod opierają się na rozłącznej regule status/owner. Samo tłumaczenie kodów jej nie zachowa. Wtedy wymagana jest odrębna wersja/projekcja zapisu albo skoordynowany cutover i jawna ścieżka rollbacku. Opublikować OpenAPI, wygenerować klienta i objąć testami oba zakresy, które mają być wspierane równolegle.
4. **Backfill i weryfikacja.** Wykonać powtarzalny backfill z zatwierdzoną tabelą mapowania. Porównać liczby, właścicieli, daty i dostępność akcji. Podział `resolved` i `ignored` w statystykach musi zachować historię. Zweryfikować `cases`, `case_sla`, audyt, notatki, powiązania i wiadomości publiczne. Nie odtwarzać kategorii historycznych z aktualnego statusu, jeśli dostępna jest osobna zapisana informacja.
5. **Współbieżność.** Obecne grupowanie znajduje aktywną sprawę i opiera się na częściowym unikalnym indeksie oraz konflikcie przy wstawianiu. Blokada Slacka dotyczy tożsamości wiadomości, więc nie synchronizuje cofnięcia z pierwszą nową wiadomością w tym samym wątku. Rozważyć wspólną blokadę transakcyjną dla pełnego klucza kontekstu w intake i cofaniu. Po blokadzie ponownie odczytać aktywną sprawę i zablokować właściwy wiersz. Utrzymać spójną kolejność blokad i unikalny indeks jako ochronę końcową. Przełączenie, audyt, zapis idempotencji i zdarzenie do listy muszą się zatwierdzać atomowo.
6. **Projekcje i przełączenie.** Przejrzeć uprawnienia pojedyncze i bulk, komendy Reply/Dopytaj/Note/Ignore, filtry i liczniki, SLA i timeouty, publikację zdarzeń, statystyki oraz kolejki wiadomości w toku. Nie włączać nowych zapisów, dopóki kompatybilne odczyty i rollback nie przejdą na kopii. Dopiero osobne wydanie po upływie okna rollbacku usuwa przestarzałe reprezentacje.

## Próby bazowe

Na lokalnej bazie testowej V45 odczytano 25 spraw, w tym 22 `RESOLVED` (20 z zachowanym ownerem), 2 `IGNORED` i 1 `VERIFICATION`. Ostatni audyt rozwiązania wskazuje 5 wpisów CASE_FORCE_RESOLVE i 17 wpisów CASE_RESOLVE. Były 4 zapisane, aktywne głosy, a w chwili odczytu nie było odpowiedzi SUPPORT ze stanem QUEUED lub SENDING. Nie było powiązanych następców ani duplikatów aktywnego kontekstu. Dane tej bazy nie pokrywają wszystkich starych statusów, więc scenariusze brakujące wymagają osobnych fixture.

Kopia bazy została odtworzona w jednorazowym PostgreSQL 18.6. Flyway pomyślnie wykonał jedyną zatwierdzoną zaległą migrację V45 → V46 z PR #155 i zweryfikował łańcuch. Porównanie pełnych wierszy wykazało zgodność 25 spraw, 4 głosów, 25 rekordów SLA, 87 wiadomości, 123 wpisów audytu oraz pustego zestawu Snooze. V46 utworzyła pustą tabelę notatek. Stan źródłowy jest nadal V45. Próba weryfikuje aktualną bazę i obowiązujący łańcuch migracji. Wdrożenie mapowania czterech statusów wymaga zatwierdzenia reguł.

## Potwierdzone elementy i wynik V47

Potwierdzono mapowanie Ignore i filtr archiwum. V47 dodaje wyliczane pole workflow_status przy zachowaniu kontraktu legacy i istniejących komend. OpenAPI udostępnia workflowStatus, closureReason oraz opcjonalne parametry workflowStatus i actionableQueue. Nowy interfejs używa czterech etykiet. Rozkład i filtry statystyk korzystają z projekcji, a metryki rozdzielają zwykłe rozwiązania od Ignore. Utworzenie generacji sprawy blokuje pełny kontekst dostawcy w transakcji.

Na kolejnej kopii tej samej lokalnej bazy Flyway wykonał V45 → V47. Potwierdzono zgodność starych pól 25 spraw, 4 głosów, 25 rekordów SLA, 87 wiadomości i 123 wpisów audytu. Projekcja dała 24 pozycje RESOLVED, w tym 2 z historycznym Ignore, oraz 1 OPEN. Pozostałe przejścia mapowania są sprawdzane na danych syntetycznych w teście CaseListServiceIntegrationTests.

Właściciel potwierdził, że zakres cofnięcia obejmuje także zwykłe Resolve. Uruchomienie komendy zależy jeszcze od reguły SLA po cofnięciu. Osobno pozostaje kwestia, czy zwykła dostarczona odpowiedź ma uruchamiać oczekiwanie, oraz szczegóły Dopytaj i timeout przy ewentualnej zmianie reguł.

## Kryteria dalszego odbioru

- Macierz uprawnień z adminem bez wymaganego uprawnienia, zwykłym agentem i właścicielem. Replay, konflikt klucza i brak skutków po odmowie.
- Cofnięcie po force resolve, normalne Resolve zgodnie z decyzją, istniejąca aktywna sprawa i wyścig z dwiema wiadomościami w jednym wątku. Test audytu i wznowienia odpowiedzi.
- Sukces, błąd trwały i ponowienie zwykłego Reply/Dopytaj, odpowiedź klienta i timeout. Sprawdzić zgodność SLA, ownera i kolejki wysyłki.
- Ignore przed i po progu, po resecie głosów oraz w operacji masowej. Sprawdzić historyczne oznaczenia, statystyki i osobista historia Snooze.
- Wspólne predykaty list i liczników z filtrem archiwum, wyszukiwaniem, stronami, masowym zaznaczaniem, drugą sesją i reconnect. Test z klientem kompatybilnym i docelowym.
