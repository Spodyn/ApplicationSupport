# Architektura

Stan na 9 sierpnia 2026 r. Dokument rozdziela bieżącą architekturę frontendu od planowanej integracji backendowej.

## Stan obecny

Repozytorium zawiera frontend Next.js korzystający wyłącznie z lokalnych usług mock. Nie zawiera backendu, bazy danych ani prawdziwych adapterów Slacka, Microsoft Teams lub Telegrama.

Przepływ danych:

```text
komponent lub trasa
        ↓
hook TanStack Query — lib/services/queries.ts
        ↓
rejestr implementacji — lib/services/registry.ts
        ↓
obszarowy interfejs usługi — lib/services/*.ts
        ↓
lokalna implementacja mock
        ↓
dane początkowe — mocks/*.ts
```

Zasady bieżącej granicy:

- komponenty nie importują z `mocks/`,
- `lib/services/registry.ts` jest jedynym miejscem kompozycji implementacji,
- TanStack Query odpowiada za odczyt, cache, mutacje i unieważnianie,
- `lib/domain/inbox.ts` jest jedynym modelem workflow sprawy,
- `lib/domain/shared.ts` zawiera współdzielone typy frontendu,
- analityka i administracja pozostają osobnymi obszarami domenowymi.

Aktywne kontrakty usług są definiowane obszarowo:

- `lib/services/inbox.ts`,
- `lib/services/analytics.ts`,
- `lib/services/administration.ts`,
- `lib/services/current-user.ts`.

Nie istnieje wspólny, ogólny kontrakt `CaseRepository`. Został usunięty wraz z nieużywanym modelem legacy, aby nie został przypadkowo potraktowany jako projekt backendu.

Kontrakt v1/GA zamraża listę providerów do Slacka, Microsoft Teams i Telegrama. E-mail jako kanał wsparcia oraz funkcje AI są poza v1. Pełną granicę wydania i przegląd powierzchni frontendu opisuje `docs/V1_SCOPE.md`.

USI-32 zamraża kanoniczne role `USER` i `ADMIN` oraz dziewięć granularnych permissions. Funkcja administracyjna wymaga jednocześnie roli `ADMIN` i przypisanego permission; wyjątkiem są ustawienia Ogólne, wymagające samej roli `ADMIN`. Pełna matryca widoków, akcji i rodzin endpointów znajduje się w `docs/PERMISSION_MATRIX.md`.

USI-33 zamraża v1 jako lokalny e-mail i hasło z sesją serwerową przekazywaną wyłącznie przez bezpieczne cookie `HttpOnly`. Przeglądarkowe mutacje wymagają CSRF, a identyfikator sesji nie może trafić do web storage ani cache PWA. Przyszłe OIDC ustanawia tę samą sesję i mapuje do kanonicznego użytkownika bez zaufania do claims roli/permissions. Pełny kontrakt znajduje się w `docs/AUTHENTICATION.md`.

USI-34 zamraża provider-neutral grouping wykonywany przez inbound adapters przed utworzeniem lub odnalezieniem sprawy. Slack używa root+thread, Teams root+replies w kontekstach potwierdzonych capability matrix, Telegram topic albo jednej aktywnej sprawy na chat bez topics. Pełny kontrakt kluczy, terminal linking i idempotency znajduje się w `docs/CASE_GROUPING.md`.

## Planowana granica backendu

Planowany kierunek, bez implementowania go w tym repozytorium:

Docelowa infrastruktura wskazana dla następnej fazy to Spring Boot, PostgreSQL i RabbitMQ. Żaden z tych elementów nie jest obecnie częścią repozytorium.

```text
Spring Boot REST/OpenAPI
        ↓
wygenerowany klient transportowy TypeScript
        ↓
adapter frontendu
        ↓
obszarowy interfejs usługi
        ↓
stabilne typy domenowe frontendu
        ↓
TanStack Query i UI
```

Odpowiedzialności warstw:

1. Spring Boot publikuje uzgodniony kontrakt REST/OpenAPI.
2. Generator tworzy DTO i kod transportowy w izolowanym katalogu, np. `lib/api/generated/`.
3. Adapter wywołuje klienta, mapuje DTO na typy `lib/domain/` i normalizuje błędy.
4. `lib/services/registry.ts` wybiera adapter zamiast implementacji mock.
5. Hooki i komponenty pozostają zależne od stabilnych interfejsów usług, nie od DTO.

Adaptery providerów planowane dla v1 mogą dotyczyć wyłącznie Slacka, Microsoft Teams i Telegrama. E-mail nie może zostać dodany do `Channel`, OpenAPI ani warstwy adapterów w ramach v1. USI-6 nie implementuje żadnego z tych adapterów.

Adapter normalizuje providerowe ID do `external_conversation_id` i opcjonalnego `external_thread_key`; komponenty frontendu nigdy nie implementują logiki root/thread/topic. Strategia jest konfigurowana per Channel i walidowana względem capability providera.

Wygenerowane pliki nie powinny być ręcznie edytowane. Szczegóły autoryzacji, konfliktów, idempotencji, paginacji i reguł workflow wymagają osobnego uzgodnienia kontraktu; ten etap ich nie implementuje.

Przyszły backend jest jedyną granicą bezpieczeństwa: każda chroniona operacja powtarza kontrolę aktywnej sesji, literalnej roli i effective permissions niezależnie od widoczności elementu UI. Frontend mapuje `401`/`403` na stany aplikacji, ale nie jest źródłem decyzji autoryzacyjnej.

Spring Session JDBC przechowuje docelowy stan sesji w PostgreSQL. Login, logout i `auth/me` należą do kontraktu API, ale ich implementacja oraz trasa `/login` pozostają poza obecnym repozytorium.

## Granice bezpieczeństwa

- Sekrety i poświadczenia dostawców nie mogą trafić do bundla klienta.
- Testy nie wywołują prawdziwych komunikatorów ani produkcyjnej bazy.
- Service worker pomija `/api`, żądania autoryzowane i metody inne niż GET.
- Typy transportowe nie są typami domenowymi i nie powinny przeciekać do komponentów.

## Weryfikacja zmian

Pełną bramką repozytorium jest `pnpm check`: lint, typecheck, testy jednostkowe/komponentowe, produkcyjny build i Playwright E2E. Szczegóły znajdują się w `docs/TESTING.md`.
