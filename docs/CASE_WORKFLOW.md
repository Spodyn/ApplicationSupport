# Kanoniczna maszyna stanów Case

Stan na 20 sierpnia 2026 r. Dokument utrwala decyzję z Jira USI-35 w ramach epika USI-5. Jest kontraktem produktowym i architektonicznym dla przyszłego backendu, OpenAPI i adaptera frontendu; nie implementuje backendu, migracji, administracyjnego force-resolve ani nowych elementów UI.

## Statusy i mapowanie frontendu

Kanoniczne kody persistence/API to dokładnie:

- `NEW`
- `VERIFICATION`
- `WAITING_FOR_CUSTOMER`
- `PARTIALLY_IGNORED`
- `IGNORED`
- `RESOLVED`

Stabilny model frontendu `InboxStatus` używa odpowiadających im wartości lower snake case: `new`, `verification`, `waiting_for_customer`, `partially_ignored`, `ignored`, `resolved`. Przyszły adapter transportowy mapuje te wartości wprost; nie tworzy dodatkowych stanów. Unread, snooze i SLA warning są niezależnymi właściwościami lub projekcjami i nie są `CaseStatus`.

Stany nieterminalne to `NEW`, `VERIFICATION`, `WAITING_FOR_CUSTOMER` i `PARTIALLY_IGNORED`. `IGNORED` oraz `RESOLVED` są terminalne.

## Invariants ownership

| Status | `owner_user_id` |
| --- | --- |
| `NEW` | `null` |
| `VERIFICATION` | Wymagany; dokładnie jeden bieżący owner. |
| `WAITING_FOR_CUSTOMER` | `null` |
| `PARTIALLY_IGNORED` | `null` |
| `IGNORED` | Zawsze `null`. |
| `RESOLVED` | Zachowany ostatni owner, jeżeli sprawa miała ownera; w przeciwnym razie `null`. |

Claim ustanawia ownera i `claimed_at` atomowo z wejściem do `VERIFICATION`. Wejście do `WAITING_FOR_CUSTOMER` zeruje ownera. Zwykły Resolve zachowuje bieżącego ownera. Force-resolve sprawy nieprzypisanej nie przypisuje administratora: `owner_user_id` pozostaje `null`, a actor operacji trafia do audit/history.

## Pełna transition matrix

| Stan źródłowy | Command lub zdarzenie | Warunek | Stan docelowy i skutek |
| --- | --- | --- | --- |
| brak | Utworzenie sprawy przez provider grouping | Pierwsza wiadomość nowego aktywnego klucza | `NEW`, bez ownera. |
| `NEW` | Claim | Uprawniony `USER` lub `ADMIN`; sprawa nadal nieprzypisana | `VERIFICATION`; actor staje się jedynym ownerem. |
| `NEW` | Ignore vote | Reguły głosowania z USI-36 | `PARTIALLY_IGNORED` poniżej progu albo `IGNORED` po osiągnięciu progu; bez ownera. |
| `PARTIALLY_IGNORED` | Claim | Inny uprawniony użytkownik zgodnie z polityką vote reset z USI-36 | `VERIFICATION`; claimant staje się jedynym ownerem, a głosy są resetowane zgodnie z USI-36. |
| `PARTIALLY_IGNORED` | Nowa wiadomość klienta | Provider przypisał wiadomość do aktywnej sprawy | `NEW`; częściowe głosy są resetowane, owner pozostaje pusty. |
| `PARTIALLY_IGNORED` | Ignore vote | Reguły głosowania z USI-36 i osiągnięcie progu | `IGNORED`; bez ownera. |
| `VERIFICATION` | Ask Customer | Wyłącznie bieżący owner; command zawiera niepustą outgoing message | Wiadomość i przejście są jednym skutkiem: `WAITING_FOR_CUSTOMER`, owner wyzerowany, `waiting_until` ustawione. |
| `VERIFICATION` | Resolve | Wyłącznie bieżący owner | `RESOLVED`; ostatni owner zostaje zachowany. |
| `WAITING_FOR_CUSTOMER` | Odpowiedź klienta | Nowa customer message w aktywnej sprawie | `NEW`; bez ownera. |
| `WAITING_FOR_CUSTOMER` | Timeout | Kontrolowany command po osiągnięciu `waiting_until` | `NEW`; bez ownera. |
| dowolny nieterminalny | ADMIN force-resolve | Rola `ADMIN` i effective permission `force_resolve` | `RESOLVED`; istniejący owner zostaje zachowany, brak ownera pozostaje `null`, actor trafia do audit/history. |

Macierz dozwolonych par statusów, bez utraty rozróżnienia commandów powyżej, jest więc następująca:

| Z | Do |
| --- | --- |
| `NEW` | `VERIFICATION`, `PARTIALLY_IGNORED`, `IGNORED`, `RESOLVED` |
| `VERIFICATION` | `WAITING_FOR_CUSTOMER`, `RESOLVED` |
| `WAITING_FOR_CUSTOMER` | `NEW`, `RESOLVED` |
| `PARTIALLY_IGNORED` | `NEW`, `VERIFICATION`, `IGNORED`, `RESOLVED` |
| `IGNORED` | brak |
| `RESOLVED` | brak |

Wysłanie zwykłej odpowiedzi przez ownera w `VERIFICATION` nie zmienia statusu, więc nie jest osobnym przejściem w tabeli. Szczegółowa polityka głosów, progu i resetu należy do USI-36; nie może dodać przejścia spoza zamrożonej macierzy.

## Terminal semantics

`IGNORED` i `RESOLVED` nigdy nie są reopenowane. Customer/provider message dla klucza, którego ostatnia sprawa jest terminalna, tworzy nową sprawę `NEW` powiązaną z poprzednią zgodnie z `CASE_GROUPING.md`. Jest to utworzenie kolejnej generacji sprawy, nie przejście terminalnej sprawy do `NEW`.

Operacja na stanie terminalnym nie może zmienić statusu, ownera, timestampów ani historii wcześniejszej sprawy. Force-resolve jest niedozwolony dla `IGNORED` oraz `RESOLVED`.

## Command API i egzekwowanie

Każda zmiana stanu ma dedykowany command endpoint dla Claim, Ignore vote, Ask Customer, zwykłego Resolve lub ADMIN force-resolve. Operacje zwykłe pozostają w rodzinie `/api/v1/cases/**`, a force-resolve jest osobnym `POST /api/v1/admin/cases/{id}/force-resolve` zamrożonym przez USI-32. Dokładne ścieżki i requesty zwykłych commandów utrwali przyszłe OpenAPI bez łączenia ich w generic `PATCH status` lub wspólny command `set-status`. Klient nie może obejść ownership lub permission przez bezpośrednie podanie statusu docelowego.

Backend jest authoritative i dla każdego commandu niezależnie sprawdza aktywną sesję, rolę/permission, stan źródłowy, ownership oraz dane commandu. Ukrycie przycisku w UI nie jest zabezpieczeniem. Zwykły Resolve nie korzysta z `force_resolve`; administracyjny force-resolve nie obchodzi innych permissions przez ogólną aktualizację statusu.

Zmiana statusu, ownera, wymaganych timestampów, wiadomości i wpisów audit/history należących do jednego skutku musi być spójna. Równoległy command wykonany na nieaktualnym stanie lub ownerze zostaje odrzucony bez częściowego zapisu. Dokładny mechanizm blokady i stabilne kody konfliktów zostaną utrwalone w OpenAPI/backendzie bez osłabiania tej reguły.

## Docelowa persistence

Przyszły rekord `cases` zawiera co najmniej `status`, nullable `owner_user_id`, `claimed_at`, `waiting_until`, `resolved_at` i `ignored_at`. Constraint statusu dopuszcza wyłącznie sześć kanonicznych kodów. Constraints i logika commandów muszą utrzymać invariants ownership także poza ścieżką UI.

Audit/history zachowuje actorów i skutki commandów, w tym administratora wykonującego force-resolve. Pole ownera opisuje odpowiedzialność za Case, dlatego nie może być używane zastępczo jako actor audytowy.

## Action availability frontendu

Frontend może prezentować wyłącznie akcje zgodne ze stanem i ownership, ale wynik commandu z backendu pozostaje źródłem prawdy przy wyścigu lub nieaktualnym cache. Obecny UX i niepodłączone akcje pozostają bez zmian; USI-35 nie aktywuje nowych dialogów ani przycisku force-resolve.

## Obowiązkowe testy implementacji backendowej

Testy commandów muszą objąć każdą dozwoloną pozycję macierzy, każde niedozwolone przejście, dokładnie jednego ownera w `VERIFICATION`, stany wymagające pustego ownera, zachowanie ownera w `RESOLVED`, brak automatycznego przejęcia przez force-resolving administratora, audit actora, atomowość outgoing message z Ask Customer, brak reopen stanów terminalnych oraz utworzenie nowej powiązanej sprawy po terminalnej wiadomości.

Testy autoryzacji muszą osobno wykazać, że force-resolve wymaga jednocześnie roli `ADMIN` i permission `force_resolve`, a zwykły Resolve jest dostępny wyłącznie bieżącemu ownerowi w `VERIFICATION`.

## Poza zakresem USI-35

- backend, Spring State Machine, OpenAPI i migracje bazy;
- implementacja głosowania i szczegółowej polityki resetu z USI-36;
- implementacja niezawodnego outbound messaging z USI-37;
- implementacja force-resolve, reassign, audytu i nowych elementów UI;
- zmiana zaakceptowanego layoutu, etykiet, kolorów lub nawigacji.
