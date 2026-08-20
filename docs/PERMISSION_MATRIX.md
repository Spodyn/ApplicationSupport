# Role aplikacji i matryca uprawnień

Stan na 20 sierpnia 2026 r. Dokument utrwala decyzję z Jira USI-32 w ramach epika USI-5. Jest kontraktem produktowym i architektonicznym dla przyszłego backendu oraz adapterów frontendu; nie oznacza implementacji logowania, sesji, Spring Security, OpenAPI ani bazy danych w tym zadaniu.

## Role i sposób autoryzacji

Kanoniczne wartości roli aplikacji to dokładnie:

- `USER` — uwierzytelniony, aktywny użytkownik realizujący operacyjny workflow spraw, wiadomości i historię rozmowy zgodnie z ownership oraz stanem domenowym;
- `ADMIN` — wszystkie capabilities `USER` oraz możliwość korzystania z funkcji administracyjnych, jeśli konto ma również wymagany granularny permission.

Funkcja administracyjna jest dozwolona wyłącznie wtedy, gdy oba warunki są spełnione jednocześnie:

1. bieżący, aktywny użytkownik ma rolę `ADMIN`;
2. jego effective permissions zawierają permission wymagany przez daną funkcję.

Wyjątkiem jest sekcja `/settings` → Ogólne, która wymaga roli `ADMIN`, ale nie wymaga granularnego permission. Permission przypisany kontu z rolą `USER` nie daje dostępu administracyjnego i nie może służyć do obejścia kontroli roli. Docelowy zapis lub aktualizacja konta `USER` z administracyjnymi permissions musi zostać odrzucona. Uprawnienia administracyjne są granularnym mechanizmem wyłącznie w obrębie roli `ADMIN`.

Ukrycie trasy, zakładki lub przycisku jest zachowaniem UX, nie zabezpieczeniem. Backend musi powtórzyć kontrolę roli, permission, aktywności konta oraz — niezależnie — reguł ownership i stanu domenowego.

## Zamknięty katalog permissions

Katalog zawiera dokładnie dziewięć stabilnych kodów. Dodanie, zmiana nazwy lub zmiana znaczenia wymaga osobnej decyzji kontraktowej.

| Permission | Zakres |
| --- | --- |
| `manage_users` | Lista kont, tworzenie, edycja profilu/roli/ważności, nadawanie permissions, dezaktywacja, reaktywacja i bezpieczne usuwanie. |
| `manage_integrations` | Konfiguracja, test, ponowna autoryzacja i rozłączenie integracji oraz zarządzanie monitorowanymi/ignorowanymi kanałami. |
| `manage_sla` | Odczyt i zmiana administracyjnej konfiguracji polityk oraz progów SLA. |
| `manage_schedule` | Odczyt i zmiana godzin pracy, wyjątków kalendarza i konfiguracji poza biurem. |
| `manage_notifications` | Odczyt i zarządzanie celami oraz regułami powiadomień. |
| `view_global_statistics` | Widok i API statystyk globalnych oraz per-user dla całej organizacji. |
| `reassign_cases` | Administracyjne przepisanie lub odpięcie ownera sprawy. |
| `force_resolve` | Administracyjne rozwiązanie sprawy poza zwykłą regułą bieżącego ownera. |
| `view_audit` | Wyszukiwanie i odczyt sanityzowanego audytu administracyjnego. |

## Matryca widoków i akcji frontendu

| Widok lub akcja | Wymaganie |
| --- | --- |
| `/cases`, historia rozmowy i zwykłe operacje workflow | Aktywne, uwierzytelnione `USER` lub `ADMIN`; dodatkowe warunki ownership/stanu pozostają częścią kontraktu workflow. |
| `/users`, lista i wszystkie akcje zarządzania kontami/permissions | `ADMIN` + `manage_users`. |
| `/settings` jako trasa oraz zakładka Ogólne | `ADMIN`, bez granularnego permission. |
| `/settings` → SLA | `ADMIN` + `manage_sla`. |
| `/settings` → Godziny pracy / Poza biurem | `ADMIN` + `manage_schedule`. |
| `/settings` → Integracje / Kanały | `ADMIN` + `manage_integrations`. |
| `/settings` → Powiadomienia | `ADMIN` + `manage_notifications`. |
| `/settings` → Uprawnienia | `ADMIN` + `manage_users`. |
| `/statistics` i globalne zestawienia per-user | `ADMIN` + `view_global_statistics`. |
| Reassign lub unassign sprawy | `ADMIN` + `reassign_cases`. |
| Force-resolve sprawy | `ADMIN` + `force_resolve`. |
| Przyszły widok wyszukiwania audytu | `ADMIN` + `view_audit`. |

Nawigacja ma ukrywać `/users` i `/statistics`, gdy warunki nie są spełnione. `/settings` jest widoczne dla każdego `ADMIN`, ponieważ Ogólne nie wymaga granularnego permission; zakładki administracyjne są widoczne tylko z odpowiadającym permission. Bezpośrednie wejście pod URL lub wywołanie API nadal musi zakończyć się `403`, jeżeli brakuje roli albo permission.

## Matryca endpointów

Wszystkie ścieżki są pod publicznym prefiksem `/api/v1`. Wzorzec `/**` obejmuje list/detail/CRUD oraz dedykowane commandy danego zasobu; przyszłe OpenAPI może doprecyzować kształt requestów, ale nie może osłabić przypisanego guardu.

| Endpoint lub rodzina endpointów | Wymaganie |
| --- | --- |
| `GET /auth/me` | Uwierzytelnione, aktywne konto; odpowiedź zawiera kanoniczną rolę i effective permissions. |
| `/cases/**` — list/detail/messages/claim/ignore/ask-customer/resolve/snooze/read-position | Uwierzytelnione, aktywne `USER` lub `ADMIN` oraz właściwe reguły ownership/stanu; brak granularnego permission z katalogu USI-32. |
| `/admin/users/**` | `ADMIN` + `manage_users`. Dotyczy również zmiany roli i permissions; self-escalation, self-lockout oraz utrata ostatniego aktywnego administratora muszą być blokowane niezależnie. |
| `GET|PUT /admin/settings/general` | `ADMIN`, bez granularnego permission. |
| `/admin/sla/**` | `ADMIN` + `manage_sla`. |
| `/admin/schedule/**`, `/admin/out-of-office/**` | `ADMIN` + `manage_schedule`. |
| `/admin/integrations/**`, `/admin/channels/**` | `ADMIN` + `manage_integrations`. |
| `/admin/notifications/**` | `ADMIN` + `manage_notifications`. |
| `/statistics/**` | `ADMIN` + `view_global_statistics`. Obejmuje między innymi `GET /statistics/overview` i projekcje per-user. |
| `POST /admin/cases/{id}/reassign`, `POST /admin/cases/{id}/unassign` | `ADMIN` + `reassign_cases`. |
| `POST /admin/cases/{id}/force-resolve` | `ADMIN` + `force_resolve`. |
| `GET /admin/audit` | `ADMIN` + `view_audit`. |

Zwykłe `resolve` wykonywane przez bieżącego ownera nie jest `force-resolve`. Historia wiadomości i zmian widoczna w kontekście sprawy nie jest administracyjnym audytem; `view_audit` dotyczy przekrojowego API audytowego.

## Persistence i effective permissions

Docelowy model korzysta z:

- `users.role` z constraintem dopuszczającym wyłącznie `USER` i `ADMIN`;
- `permissions(code, description)` z dokładnie dziewięcioma stabilnymi kodami;
- `user_permissions(user_id, permission_code)` z composite primary key i foreign keys.

Backend oblicza effective permissions na podstawie danych serwerowych i nie przyjmuje roli ani permissions z klienta jako źródła autoryzacji. Wiersze permissions dla roli `USER` nie mogą nadawać dostępu; command zapisujący taki stan ma zostać odrzucony. Zmiany roli i permissions wymagają `ADMIN` + `manage_users`, ochrony przed eskalacją oraz audytu.

Brak sesji daje `401`, brak roli lub permission daje `403`, a poprawnie autoryzowany command naruszający ownership albo stan domenowy daje stabilny `409`, jeżeli konflikt jest właściwą semantyką operacji.

## Granica implementacji USI-32

- Brak implementacji backendu, Spring Security, sesji i ekranów logowania.
- Brak migracji DB i wygenerowanego OpenAPI.
- Brak aktywowania niewdrożonych akcji workflow, audytu, reassign i force-resolve w UI.
- Brak zmian zaakceptowanego układu, nawigacji, kolorów i typografii.
- Frontendowe guardy i mocki pozostają pomocą demonstracyjną; przyszły backend jest authoritative.
