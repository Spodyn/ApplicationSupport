# Otwarte decyzje

Stan na 20 sierpnia 2026 r. Poniższe kwestie nie są rozstrzygane przez bieżący kod ani dokumentację.

Lista kanałów nie jest już otwartą decyzją. USI-6 zamroził v1/GA do Slacka, Microsoft Teams i Telegrama, z e-mailem oraz funkcjami AI poza v1; aktualny kontrakt znajduje się w [V1_SCOPE.md](V1_SCOPE.md).

Retencja i model wdrożenia nie są już otwartą decyzją. USI-40 zamroził konfigurację retencji per single-tenant deployment, purge semantics, legal hold per deployment i zasady backup/restore; aktualny kontrakt znajduje się w [RETENTION.md](RETENTION.md). Bezterminowa retencja jako zwykła konfiguracja oraz legal hold per Case/user pozostają jawnie poza v1, a nie na tej liście do samodzielnego rozstrzygnięcia przez implementację.

## 1. Ewentualny zakres `/current-cases`

Trasa i pozycja nawigacji nie istnieją, a README wymienia wyłącznie aktualnie zaimplementowane widoki. Funkcja nie powinna być przywracana na podstawie starych opisów.

Do decyzji produktowej pozostaje, czy osobny widok bieżących spraw będzie kiedykolwiek potrzebny. Jeśli tak, wymaga nowej specyfikacji; nie jest funkcją bieżącą ani automatycznie planowaną.

## 2. Źródło prawdy dla prezentacji `/cases`

Lista i rozmowa używają danych prezentacyjnych osadzonych w `components/cases/cases-page.tsx`, a część stanu łączą z `InboxCase` po referencji. Dla niektórych wpisów pola prezentacyjne nie odpowiadają rekordom domenowym.

Do decyzji: czy `InboxCase` ma dostarczać cały model widoku, czy potrzebny jest osobny mapper/prezenter zasilany wyłącznie przez usługę. Lokalny fixture komponentu nie powinien stać się drugim kontraktem backendowym.

## 3. Egzekwowanie ignorowanych kanałów

Ustawienia → Kanały przechowują `ManagedChannel.ignored` i opisują oczekiwane zachowanie: zwykła wiadomość, bez sprawy i bez SLA. Obecny mock inbox nie ma procesu przyjmowania wiadomości, który sprawdza tę regułę.

Do decyzji:

- czy ignorowane wiadomości nadal będą widoczne w skrzynce czatów,
- jak zmiana reguły wpłynie na istniejące sprawy,
- czy zakres reguły będzie per kanał, workspace i klient.

USI-34 ustalił, że providerowy inbound adapter stosuje konfigurację Channel przed utworzeniem lub odnalezieniem Case. Nie rozstrzyga to powyższej polityki ignorowania ani jej zasięgu.

## 4. Wspólna tożsamość użytkownika

`User` z `lib/domain/shared.ts` oraz `AdministrationUser` opisują częściowo te same osoby, ale różnią się rolami, aktywnością, ważnością i uprawnieniami.

USI-32 zamknął wartości docelowej roli do `USER` i `ADMIN` oraz zasadę `ADMIN` + wymagany permission. Wartości `agent/supervisor/admin` w obecnym modelu prezentacyjnym nie są kontraktem backendu.

Do realizacji pozostaje konsolidacja obu frontendowych reprezentacji wokół kanonicznej tożsamości z backendu i jawnego mappera. Dane nie powinny być synchronizowane wyłącznie przez adres e-mail bez uzgodnionego identyfikatora. Ta praca nie może zmienić zamrożonej matrycy z `PERMISSION_MATRIX.md`.

USI-33 zamknął mechanizm v1 do lokalnego e-maila i hasła z sesją serwerową oraz ustalił, że przyszłe OIDC mapuje do tej samej kanonicznej tożsamości bez nadawania roli z claims. Nadal otwarty jest kształt konsolidacji istniejących frontendowych reprezentacji; mechanizm logowania nie rozstrzyga mappera domenowego.

## 5. Status jako wymiar analityczny

`AnalyticsStatusDimension` jest celowo oddzielony od `InboxStatus`. Dane mock analityki zawierają również klucze raportowe takie jak `waiting_team` i `snoozed`, których nie ma w kanonicznym workflow.

Do decyzji: które wymiary raportowe mają być bezpośrednią projekcją `InboxStatus`, a które są niezależnymi kategoriami. Mapowanie powinno należeć do przyszłego adaptera lub backendu analitycznego, nie do komponentów wykresów.

## 6. Oczekiwany workflow na ekranie czatów

Warstwa usług zawiera `claim`, `ignore`, `askCustomer`, `resolve`, `snooze` i `sendMessage`, ale aktualny ekran `/cases` nie podłącza pełnego zestawu operacji. W zweryfikowanym stanie część przycisków jest wyłączona, a dialogów ignorowania i pytania klienta brak.

Do decyzji: czy jest to celowy zakres makiety, czy brak względem zaakceptowanego produktu. Nie należy podłączać operacji bez potwierdzenia przejść, uprawnień i obsługi konfliktów.
