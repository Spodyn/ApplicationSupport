# Model domenowy frontendu

Stan na 9 sierpnia 2026 r. Dokument opisuje aktualny kontrakt frontendu. Nie jest schematem OpenAPI ani specyfikacją przyszłego backendu.

## Kanoniczny workflow sprawy

Jedynym źródłem prawdy dla cyklu życia sprawy jest `lib/domain/inbox.ts`:

```text
InboxCase.status: InboxStatus
```

Dozwolone wartości oraz ich kanoniczne odpowiedniki persistence/API:

| Frontend | Persistence/API | Znaczenie |
| --- | --- | --- |
| `new` | `NEW` | Nowa, nieprzejęta sprawa. |
| `verification` | `VERIFICATION` | Sprawa przejęta do weryfikacji przez dokładnie jednego ownera. |
| `waiting_for_customer` | `WAITING_FOR_CUSTOMER` | Wysłano pytanie i sprawa oczekuje na klienta bez ownera. |
| `partially_ignored` | `PARTIALLY_IGNORED` | Oddano część wymaganych głosów ignorowania; sprawa nie ma ownera. |
| `ignored` | `IGNORED` | Osiągnięto próg ignorowania; stan terminalny bez ownera. |
| `resolved` | `RESOLVED` | Sprawa rozwiązana; stan terminalny zachowujący ostatniego ownera, jeżeli istniał. |

USI-35 zamraża macierz przejść, invariants ownership, dedykowane commandy i brak reopen stanów terminalnych. `lib/domain/inbox.ts` utrwala ten kontrakt w danych frontendu, a pełne warunki poszczególnych commandów opisuje `CASE_WORKFLOW.md`. Backend pozostaje authoritative i nie może udostępnić generic aktualizacji statusu.

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

## Providerowe grupowanie spraw

USI-34 rozdziela tożsamość providerowej konwersacji od kanonicznego workflow Case. Przyszły inbound adapter wylicza `external_conversation_id` i opcjonalny `external_thread_key` według strategii Channel, a UI otrzymuje już wybraną sprawę. Root/thread/topic nie są statusami ani logiką komponentu.

Slack grupuje root z threadem, Teams root z replies tylko w kontekstach potwierdzonych przez capability matrix, Telegram według topicu, a bez topics utrzymuje jedną aktywną sprawę na chat. Wiadomość po terminalnej sprawie tworzy nową powiązaną sprawę bez reopen poprzedniej. Regułę groupingową opisuje `CASE_GROUPING.md`, a terminal semantics — `CASE_WORKFLOW.md`.

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

### Tożsamość i uwierzytelnianie

USI-33 rozdziela kanoniczną tożsamość użytkownika od sposobu uwierzytelnienia. Lokalne hasło oraz przyszłe powiązanie OIDC są danymi serwerowymi służącymi potwierdzeniu tej samej tożsamości; nie tworzą wariantów modelu `User`. Rola, permissions, aktywność i ważność konta pozostają właściwościami lokalnego użytkownika, a nie claims z mechanizmu logowania. Szczegóły zawiera `AUTHENTICATION.md`.

### Analityka

`lib/domain/analytics.ts` definiuje projekcję raportową. `AnalyticsStatusDimension` jest wymiarem danych analitycznych i może zawierać klucze inne niż `InboxStatus`. Nie jest maszyną stanów workflow. Sposób mapowania statusów domenowych na wymiary raportowe pozostaje do uzgodnienia przed OpenAPI.

### Retencja przyszłego backendu

USI-40 klasyfikuje przyszłe dane persistence jako `messages`, `inbound_events`, `audit_events` i `attachments` wraz z binary objects. Klasyfikacja nie dodaje drugiego frontendowego modelu wiadomości ani sprawy. Po expiry wiadomość lub attachment może pozostawić wyłącznie minimalny, sanityzowany tombstone potrzebny dla integralności Case/history; inbound i audit events podlegają kontrolowanemu hard delete zgodnie z `RETENTION.md`.

Runtime v1 pozostaje single-tenant i nie dodaje `tenant_id` do modeli domenowych. Granica klienta jest granicą deploymentu, bazy, object storage, sekretów i konfiguracji, a nie polem wybieranym przez UI lub API.

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

Otwarte kwestie są opisane w `docs/OPEN_DECISIONS.md`, zamknięty kontrakt kanałów w `docs/V1_SCOPE.md`, maszyna stanów w `docs/CASE_WORKFLOW.md`, a kontrakt retencji i izolacji w `docs/RETENTION.md`. Nie powinny być rozstrzygane przez przypadkowe rozszerzanie typów.
