# Zakres v1, kanały wspierane i wyłączenia

Stan na 20 sierpnia 2026 r. Dokument utrwala decyzję produktową z Jira USI-6 w ramach epika USI-5 (E00 — Product Contract & Architecture Freeze). Nie opisuje jeszcze implementacji backendu, API ani rzeczywistych integracji.

## Granica wydania

W tym kontrakcie v1 jest zakresem GA. Pozycje poza v1 należą do obszaru po GA i wymagają osobnego ticketu oraz jawnego kontraktu przed implementacją.

| Obszar | GA / v1 | Po GA / poza v1 |
| --- | --- | --- |
| Kanały dostawców | Slack, Microsoft Teams, Telegram | E-mail jako kanał dostawcy |
| Funkcje AI | Brak | Wszystkie funkcje AI |

Klasyfikacja „po GA / poza v1” nie jest zobowiązaniem do realizacji ani terminu. Oznacza wyłącznie, że dana funkcja nie należy do kontraktu v1.

## Konsekwencje kontraktu

- Kanoniczny frontendowy `Channel` pozostaje ograniczony do `slack`, `teams` i `telegram`.
- E-mail widoczny w danych prezentacyjnych `/cases` jest istniejącym fixture’em mockupu. Nie jest kanałem domenowym v1 i ma zostać usunięty przy zastąpieniu fixture’ów realnym API, bez zmiany zaakceptowanego UX w USI-6.
- Pola adresu e-mail użytkownika są danymi konta i tożsamości. Nie oznaczają obsługi e-maila jako kanału wsparcia.
- Frontend pozostaje implementacją mock. USI-6 nie dodaje rzeczywistych adapterów Slacka, Microsoft Teams ani Telegrama.
- USI-6 nie dodaje funkcji AI, endpointów, eventów, migracji DB ani reguł workflow.

## Przegląd ekranów i funkcji

Poniższa tabela klasyfikuje zaakceptowane powierzchnie frontendu względem zakresu v1. „Kontrakt UX v1” oznacza zachowanie istniejącego wyglądu i stanów; nie oznacza implementacji produkcyjnego backendu w USI-6.

| Powierzchnia lub funkcja | Klasyfikacja | Ograniczenie USI-6 |
| --- | --- | --- |
| `/cases`: lista, wyszukiwanie, filtry, wybór i odczytanie rozmowy, rozpoczęcie odpowiedzi oraz przepływ mobilny | Kontrakt UX v1 | Dane domenowe dotyczą Slacka, Teams i Telegrama; wpisy e-mail są fixture’em poza v1. |
| `/cases`: przejęcie, ignorowanie, pytanie klienta, rozwiązanie i odłożenie | Kontrakt UX v1 w obecnych, zaakceptowanych stanach | USI-6 nie aktywuje kontrolek ani nie definiuje nowych przejść, uprawnień i konfliktów. |
| `/statistics`: KPI, wykresy, tabela i filtry | Kontrakt UX v1 | Wymiar kanału obejmuje Slack, Teams i Telegram; brak e-maila i AI. |
| `/users`: konta, role, ważność i uprawnienia | Kontrakt UX v1 | Adres e-mail użytkownika nie jest kanałem wsparcia. |
| `/settings`: SLA, godziny pracy, poza biurem, integracje, kanały, powiadomienia i uprawnienia | Kontrakt UX v1 | Konfiguracja providerów dotyczy Slacka, Teams i Telegrama; USI-6 nie tworzy realnych połączeń. |
| `/`, nawigacja, stany danych, dostępność i PWA | Kontrakt UX v1 | Bez zmian w USI-6. |
| `/current-cases` | Poza v1 | Trasa nie istnieje w zaakceptowanym frontendzie; ewentualny przyszły zakres wymaga osobnej specyfikacji. |
| E-mail jako źródło spraw i wiadomości | Poza v1 | Nie rozszerzać `Channel`, usług, adapterów ani przyszłego kontraktu API o e-mail w ramach v1. |
| Funkcje AI | Poza v1 | Nie dodawać elementów UI, usług, endpointów ani przetwarzania AI w ramach v1. |

## Granice implementacji USI-6

- Brak zmian UI i widocznych etykiet.
- Brak zmian API, OpenAPI i eventów.
- Brak zmian bazy danych i migracji.
- Brak rzeczywistych wywołań providerów i produkcyjnych poświadczeń.
- Szczegółowe reguły workflow pozostają poza USI-6 i nie mogą być wyprowadzane z fixture’ów.
