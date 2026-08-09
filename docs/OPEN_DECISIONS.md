# Otwarte decyzje

Stan na 9 sierpnia 2026 r. Poniższe kwestie nie są rozstrzygane przez bieżący kod ani dokumentację.

## 1. Ewentualny zakres `/current-cases`

Trasa i pozycja nawigacji nie istnieją, a README wymienia wyłącznie aktualnie zaimplementowane widoki. Funkcja nie powinna być przywracana na podstawie starych opisów.

Do decyzji produktowej pozostaje, czy osobny widok bieżących spraw będzie kiedykolwiek potrzebny. Jeśli tak, wymaga nowej specyfikacji; nie jest funkcją bieżącą ani automatycznie planowaną.

## 2. Źródło prawdy dla prezentacji `/cases`

Lista i rozmowa używają danych prezentacyjnych osadzonych w `components/cases/cases-page.tsx`, a część stanu łączą z `InboxCase` po referencji. Dla niektórych wpisów pola prezentacyjne nie odpowiadają rekordom domenowym.

Do decyzji: czy `InboxCase` ma dostarczać cały model widoku, czy potrzebny jest osobny mapper/prezenter zasilany wyłącznie przez usługę. Lokalny fixture komponentu nie powinien stać się drugim kontraktem backendowym.

## 3. Obsługiwane kanały

Kanoniczny `Channel` obejmuje Slack, Microsoft Teams i Telegram, natomiast lista `/cases` pokazuje również e-mail. Nagłówek rozmowy ma też część danych prezentacyjnych niezależnych od rekordu inbox.

Do decyzji: czy e-mail jest planowanym kanałem domenowym, czy wyłącznie pozostałością prezentacji. Nie rozszerzać `Channel` bez potwierdzenia produktu.

## 4. Egzekwowanie ignorowanych kanałów

Ustawienia → Kanały przechowują `ManagedChannel.ignored` i opisują oczekiwane zachowanie: zwykła wiadomość, bez sprawy i bez SLA. Obecny mock inbox nie ma procesu przyjmowania wiadomości, który sprawdza tę regułę.

Do decyzji:

- w którym adapterze lub serwisie reguła będzie egzekwowana,
- czy ignorowane wiadomości nadal będą widoczne w skrzynce czatów,
- jak zmiana reguły wpłynie na istniejące sprawy,
- czy zakres reguły będzie per kanał, workspace i klient.

## 5. Wspólna tożsamość użytkownika

`User` z `lib/domain/shared.ts` oraz `AdministrationUser` opisują częściowo te same osoby, ale różnią się rolami, aktywnością, ważnością i uprawnieniami.

Do decyzji: czy administracja rozszerza wspólny model tożsamości, czy pozostaje osobnym obszarem z jawnym mapperem. Dane nie powinny być synchronizowane wyłącznie przez adres e-mail bez uzgodnionego identyfikatora.

## 6. Status jako wymiar analityczny

`AnalyticsStatusDimension` jest celowo oddzielony od `InboxStatus`. Dane mock analityki zawierają również klucze raportowe takie jak `waiting_team` i `snoozed`, których nie ma w kanonicznym workflow.

Do decyzji: które wymiary raportowe mają być bezpośrednią projekcją `InboxStatus`, a które są niezależnymi kategoriami. Mapowanie powinno należeć do przyszłego adaptera lub backendu analitycznego, nie do komponentów wykresów.

## 7. Oczekiwany workflow na ekranie czatów

Warstwa usług zawiera `claim`, `ignore`, `askCustomer`, `resolve`, `snooze` i `sendMessage`, ale aktualny ekran `/cases` nie podłącza pełnego zestawu operacji. W zweryfikowanym stanie część przycisków jest wyłączona, a dialogów ignorowania i pytania klienta brak.

Do decyzji: czy jest to celowy zakres makiety, czy brak względem zaakceptowanego produktu. Nie należy podłączać operacji bez potwierdzenia przejść, uprawnień i obsługi konfliktów.
