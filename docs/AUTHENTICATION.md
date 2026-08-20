# Kontrakt uwierzytelniania

Stan na 20 sierpnia 2026 r. Dokument zamraża baseline z Jira USI-33 w ramach epika USI-5. Jest kontraktem produktowym i architektonicznym dla przyszłego backendu; nie oznacza implementacji logowania, Spring Security, sesji, OpenAPI, wysyłki wiadomości ani bazy danych w tym zadaniu.

## Baseline v1

- Użytkownik uwierzytelnia się lokalnym adresem e-mail i hasłem.
- Role `USER` i `ADMIN` korzystają z tego samego mechanizmu logowania. Rola i permissions nie są danymi wejściowymi logowania.
- Po poprawnym logowaniu backend tworzy sesję serwerową. Przeglądarka przechowuje wyłącznie identyfikator sesji w cookie `HttpOnly`.
- Stan sesji jest docelowo przechowywany przez Spring Session JDBC. PostgreSQL pozostaje źródłem prawdy dla konta, jego aktywności, ważności, roli i permissions.
- OIDC/SSO jest rozszerzeniem po v1. Nie zastępuje ani nie rozgałęzia kanonicznego modelu użytkownika.

Backend weryfikuje konto przy logowaniu oraz przy każdym chronionym żądaniu. Konto nieaktywne, jeszcze nieważne albo wygasłe nie może utworzyć ani kontynuować sesji. Odpowiedź logowania nie może ujawniać, czy podany adres istnieje, konto jest nieaktywne albo hasło jest błędne.

## Sesja i cookie

Cookie sesyjne musi być:

- `HttpOnly`, aby kod JavaScript nie mógł odczytać identyfikatora sesji;
- `Secure` poza kontrolowanym lokalnym środowiskiem deweloperskim;
- ograniczone do właściwego hosta i niezbędnej ścieżki;
- skonfigurowane z polityką `SameSite` zgodną z docelową topologią wdrożenia, bez traktowania jej jako zamiennika ochrony CSRF.

Identyfikator sesji jest losowy, nie zawiera danych użytkownika i podlega rotacji po uwierzytelnieniu. Logout unieważnia stan po stronie serwera i wygasza cookie. Dezaktywacja, wygaśnięcie konta lub administracyjna zmiana wymagająca ponownego uwierzytelnienia musi uniemożliwić dalsze użycie istniejącej sesji.

Nazwa cookie, czas bezczynności i maksymalny czas życia są konfiguracją backendu i wdrożenia. USI-33 nie ustala ich wartości liczbowych; implementacja nie może osłabić powyższych własności bezpieczeństwa.

## API

Publiczny prefiks pozostaje `/api/v1`.

| Operacja | Kontrakt |
| --- | --- |
| `POST /auth/login` | Przyjmuje lokalny e-mail i hasło. Sukces ustanawia sesję przez cookie; odpowiedź nie zwraca bearer tokenu. |
| `POST /auth/logout` | Unieważnia bieżącą sesję po stronie serwera i wygasza cookie. Operacja jest idempotentna z perspektywy klienta. |
| `GET /auth/me` | Zwraca kanoniczną tożsamość bieżącego użytkownika, rolę `USER`/`ADMIN` i effective permissions obliczone przez backend. |
| Invitation | Dedykowany command administracyjny wymaga `ADMIN` + `manage_users` i wydaje jednorazowy token aktywacyjny. |
| Password reset | Dedykowane request/confirm commands wydają i zużywają jednorazowy token resetu bez ujawniania istnienia konta. |

Dokładne ścieżki i payloady invitation/reset zostaną utrwalone w przyszłym OpenAPI bez zmiany powyższej semantyki. Błędy API używają `application/problem+json` i stabilnych kodów zgodnie z kontraktem v1.

## CSRF i przechowywanie po stronie klienta

Każda mutacja przeglądarkowa uwierzytelniana cookie wymaga ochrony CSRF egzekwowanej przez backend. Token CSRF nie jest tokenem sesyjnym, musi być powiązany z sesją i przesyłany w sposób, którego żądanie cross-site nie może odtworzyć. Kontrole `Origin`/`Referer` oraz `SameSite` mogą wzmacniać ochronę, ale nie zastępują uzgodnionego mechanizmu CSRF.

Frontend nie może przechowywać identyfikatora sesji, hasła, invitation/reset tokenu ani przyszłego tokenu OIDC w `localStorage`, `sessionStorage`, IndexedDB lub Cache Storage. Service worker nie buforuje `/api/**`, mutacji ani odpowiedzi uwierzytelnionych.

## Invitation i reset hasła

- Konta tworzy lub zaprasza `ADMIN` z `manage_users`.
- Token invitation/reset jest losowy, jednorazowy, ma ograniczoną ważność i jest przechowywany w bazie wyłącznie jako hash.
- Zużycie, wygaśnięcie lub zastąpienie tokenu uniemożliwia jego ponowne użycie.
- Hasło jest przechowywane wyłącznie jako wynik adaptacyjnej funkcji haszującej z parametrami zarządzanymi przez backend; plaintext nie trafia do bazy, logów ani eventów.
- Kanał dostarczenia invitation/reset oraz wartości TTL są szczegółami późniejszej implementacji. Nie zmieniają zakresu kanałów wsparcia zamrożonego w USI-6.

## Granica przyszłego OIDC/SSO

OIDC może zostać dodane jako alternatywny sposób potwierdzenia tożsamości. Po poprawnym callbacku backend wiąże zewnętrzną tożsamość z istniejącym kanonicznym użytkownikiem i ustanawia tę samą sesję serwerową co logowanie lokalne.

- Provider OIDC nie tworzy drugiego modelu `User`.
- Role, permissions, aktywność i ważność konta nadal pochodzą z lokalnego źródła prawdy; claims providera nie nadają dostępu administracyjnego.
- Stabilne powiązanie zewnętrznego subjectu jest utrzymywane po stronie serwera i nie może opierać się wyłącznie na zmiennym adresie e-mail.
- Frontend po zalogowaniu korzysta z tego samego `GET /auth/me` i nie rozróżnia mechanizmu uwierzytelnienia w domenowych komponentach.

## Frontend i UX

Docelowa `/login` znajduje się poza `AppShell`. Po zalogowaniu zaakceptowany układ `/cases`, `/statistics`, `/users` i `/settings` pozostaje baseline. Routing chroniony, formularz logowania i stany `401` nie są implementowane w USI-33.

## Wymagane testy przyszłej implementacji

- poprawne i błędne logowanie bez enumeracji kont;
- atrybuty cookie, rotacja sesji, logout i unieważnienie serwerowe;
- blokada konta nieaktywnego, jeszcze nieważnego i wygasłego, także dla istniejącej sesji;
- odrzucenie mutacji bez poprawnego CSRF;
- brak tokenów sesyjnych w web storage i cache PWA;
- jednorazowość, hash i wygaśnięcie invitation/reset tokenów;
- OIDC mapujące do tego samego użytkownika bez zaufania do roli/permissions z claims.

## Poza zakresem USI-33

- implementacja Spring Security, Spring Session JDBC i migracji DB;
- ekran oraz trasa `/login`;
- endpointy, OpenAPI i klient wygenerowany;
- wysyłka invitation/reset i integracja z providerem OIDC;
- zmiana zaakceptowanego UX po zalogowaniu.
