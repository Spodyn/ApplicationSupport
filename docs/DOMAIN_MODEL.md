# Model domenowy frontendu

Stan na 9 sierpnia 2026 r. Dokument opisuje aktualny kontrakt frontendu. Nie jest schematem OpenAPI ani specyfikacją przyszłego backendu.

## Kanoniczny workflow sprawy

Jedynym źródłem prawdy dla cyklu życia sprawy jest `lib/domain/inbox.ts`:

```text
InboxCase.status: InboxStatus
```

Dozwolone wartości:

| Status | Znaczenie w aktualnym mocku frontendu |
| --- | --- |
| `new` | Nowa, nieprzejęta sprawa. |
| `verification` | Sprawa przejęta do weryfikacji. |
| `waiting_for_customer` | Wysłano pytanie i sprawa oczekuje na klienta. |
| `partially_ignored` | Oddano część wymaganych głosów ignorowania. |
| `ignored` | Osiągnięto wymagany próg ignorowania; mock traktuje status jako końcowy. |
| `resolved` | Sprawa rozwiązana; mock traktuje status jako końcowy. |

Tabela dokumentuje wyłącznie zachowanie obecnej implementacji mock. Nie rozstrzyga docelowych uprawnień, przejść ani warunków backendowych poza tym, co jest już zakodowane.

Nie istnieje drugi ogólny `CaseStatus`. Usunięty model ze stanami `open`, `pending`, `on_hold` i `closed` nie może zostać odtworzony jako kontrakt API bez jawnej decyzji produktowej.

## `InboxCase`

`InboxCase` jest stabilnym modelem obszaru skrzynki używanym przez usługę inbox. Obejmuje między innymi:

- identyfikator, referencję i temat,
- kanał platformy i kanał źródłowy,
- klienta i właściciela,
- kanoniczny `InboxStatus`,
- stan odczytania i odłożenia bieżącego użytkownika,
- bieżący stan SLA,
- metadane prezentacyjne, powiązaną sprawę i aktywność.

`InboxMessage` jest osobnym modelem wiadomości w rozmowie. Operacje i kontrakt repozytorium znajdują się w `lib/services/inbox.ts`.

## Typy współdzielone

`lib/domain/shared.ts` zawiera wyłącznie niekonfliktowe typy używane przez kilka obszarów:

- `Channel`,
- `SlaState`,
- `MessageDeliveryStatus`,
- `UserRole`,
- `UserPresence`,
- `User`.

Są to typy domenowe frontendu, nie wygenerowane typy transportowe. Przyszły adapter może je wypełniać danymi z API, ale DTO nie powinny być importowane bezpośrednio przez komponenty.

USI-6 zamraża `Channel` jako dokładny zestaw kanałów v1/GA: `slack`, `teams` i `telegram`. E-mail jako kanał wsparcia jest poza v1 i nie może zostać dodany do tego typu bez nowej, jawnej decyzji produktowej. Pola `email` w modelach użytkowników opisują tożsamość konta, nie kanał przyjmowania spraw.

## Pozostałe obszary

### Administracja

`lib/domain/administration.ts` definiuje konta administracyjne, ustawienia, integracje, ignorowane kanały, powiadomienia i uprawnienia. Kanoniczne role aplikacji to dokładnie `USER` i `ADMIN`. Katalog permissions zawiera dokładnie: `manage_users`, `manage_integrations`, `manage_sla`, `manage_schedule`, `manage_notifications`, `view_global_statistics`, `reassign_cases`, `force_resolve` i `view_audit`.

Permission administracyjny jest skuteczny tylko dla roli `ADMIN`; sam grant zapisany przy `USER` nie daje dostępu. Ogólne ustawienia wymagają `ADMIN` bez dodatkowego permission. Szczegółową matrycę akcji, widoków i przyszłych endpointów opisuje `PERMISSION_MATRIX.md`.

`AdministrationUser` nie jest drugim modelem sprawy. Jego relacja do współdzielonego `User` nadal wymaga realizacji przez przyszły kanoniczny model tożsamości i mapper, ale starsze wartości `agent/supervisor/admin` nie są docelową rolą aplikacji ani kontraktem API.

### Analityka

`lib/domain/analytics.ts` definiuje projekcję raportową. `AnalyticsStatusDimension` jest wymiarem danych analitycznych i może zawierać klucze inne niż `InboxStatus`. Nie jest maszyną stanów workflow. Sposób mapowania statusów domenowych na wymiary raportowe pozostaje do uzgodnienia przed OpenAPI.

## Świadomie usunięty model legacy

Po sprawdzeniu wszystkich referencji usunięto nieużywany, równoległy łańcuch:

- ogólne `Case`, `CaseStatus`, `CaseRepository` i `SupportStatistics`,
- stare hooki `useCases`, `useCase`, `useStatistics` i `useIntegrations`,
- osobne mocki spraw, statystyk i integracji,
- nieużywane odznaki oparte na starych statusach.

Aktywne ekrany korzystały już z `InboxCase`, `AnalyticsResult` oraz modeli administracji, dlatego usunięcie nie zmienia zachowania UI.

## Znane granice

- `components/cases/cases-page.tsx` nadal łączy dane usługi inbox z lokalnym modelem prezentacyjnym; kierunek konsolidacji pozostaje otwarty.
- Lista prezentacyjna pokazuje również e-mail. Zgodnie z USI-6 jest to fixture poza v1, a nie brakująca wartość `Channel`; fixture ma zostać usunięty przy zastąpieniu danych prezentacyjnych realnym API.
- Reguła ignorowanych kanałów jest konfigurowana w administracji, ale nie ma jeszcze procesu przyjmowania wiadomości, który ją egzekwuje.

Otwarte kwestie są opisane w `docs/OPEN_DECISIONS.md`, a zamknięty kontrakt kanałów w `docs/V1_SCOPE.md`. Nie powinny być rozstrzygane przez przypadkowe rozszerzanie typów.
