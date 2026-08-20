# Retencja danych, audyt i izolacja deploymentu

Stan na 20 sierpnia 2026 r. Dokument zamraża decyzję z Jira USI-40 w ramach epika USI-5. Jest kontraktem produktowym, architektonicznym i operacyjnym dla przyszłego backendu oraz infrastruktury; nie implementuje jobów retencji, konfiguracji runtime, migracji DB, object storage, backupów ani nowego UI. Późniejsze epiki security i operations muszą linkować do tego kontraktu i zachować jego inwarianty.

## Model single-tenant

W v1 jeden klient oznacza jeden niezależny deployment:

- osobną bazę PostgreSQL;
- osobny object storage;
- osobne sekrety i konfigurację;
- ten sam kod aplikacji, bez forków per klient.

Tabele runtime v1 nie mają `tenant_id`. Aplikacja nie udostępnia tenant selectora ani cross-tenant API, a inventory klientów/deploymentów pozostaje odpowiedzialnością warstwy deployment/ops. Żaden runtime deployment nie może otrzymać dostępu do bazy, object storage, sekretów lub konfiguracji innego klienta.

Środowiska deweloperskie, testowe i CI nie łączą się z produkcyjną bazą ani object storage i nie otrzymują produkcyjnych sekretów providerów. Admin API nie zwraca wartości sekretów. Konfiguracja retencji, legal hold i backupów należy do konkretnego deploymentu i nie tworzy różnic w kodzie aplikacji.

## Konfiguracja retencji

Retencja jest konfigurowana per deployment. Każda wartość jest liczbą dni z domyślną wartością bezpieczną i domkniętym dozwolonym zakresem:

| Klasa danych | Wartość domyślna | Dozwolony zakres |
| --- | ---: | ---: |
| `messages` | 730 dni | 30–3650 dni |
| `inbound_events` | 30 dni | 7–365 dni |
| `audit_events` | 2555 dni / 7 lat | 365–3650 dni |
| `attachments` | 730 dni | 30–3650 dni |
| Backupy | 35 dni | 7–90 dni |

Binary object w object storage ma zawsze taki sam retention jak odpowiadający mu attachment. Nie jest konfigurowany niezależnie.

Brak wartości używa wartości domyślnej. Jawna wartość spoza dozwolonego zakresu powoduje błąd walidacji i konfiguracja nie może zostać zastosowana; system nie może po cichu przyciąć, zastąpić ani zignorować takiej wartości. Bezterminowa retencja nie jest zwykłą opcją konfiguracji v1.

## Semantyka expiry i purge

Kontrolowany retention job stosuje politykę odpowiednią dla klasy danych. Implementacja persistence musi wskazać trwałe timestampy UTC używane do wyliczenia wieku rekordu i nie może zmieniać poniższej semantyki.

| Klasa danych | Zachowanie po upływie retencji |
| --- | --- |
| `inbound_events` | Hard delete rekordu po upływie retencji. |
| `audit_events` | Hard delete przez kontrolowany retention job. Append-only zabrania edycji eventu w czasie jego życia, ale nie zabrania kontrolowanego expiry/purge wynikającego z polityki retencji. |
| `messages` | Usunięcie lub nieodwracalne zredagowanie body oraz danych wrażliwych i zbędnego PII. Pozostaje wyłącznie minimalny tombstone i metadane referencyjne konieczne dla integralności Case/history. |
| `attachments` | Hard delete binary objectu z object storage. Rekord DB może pozostać jako sanityzowany tombstone tylko wtedy, gdy wymaga tego referencja lub historia; nie zachowuje pliku ani zbędnego PII. Po usunięciu nadrzędnej historii rekord może zostać całkowicie usunięty. |

Purge nie może polegać na przypadkowym `ON DELETE CASCADE` przez relacje przechowujące historię. Schemat i job muszą jawnie rozróżniać dane usuwane, redagowane oraz zachowywane jako tombstone, utrzymując referencyjną integralność Case/history bez pozostawiania treści lub PII ponad okres retencji.

## Legal hold

V1 wspiera legal hold wyłącznie per deployment:

- hold jest ustawiany przez deployment/ops configuration, poza zwykłym UI aplikacji;
- aktywny hold zatrzymuje retention purge dla całego deploymentu;
- włączenie i wyłączenie hold jest jawną, audytowalną operacją operacyjną;
- legal hold per Case i per user pozostaje poza v1.

Legal hold wstrzymuje purge, ale nie zmienia wartości polityki retencji ani nie zamienia backupu w archiwum. Po wyłączeniu hold job ponownie stosuje bieżącą politykę do danych, których okres retencji upłynął.

## Backup i restore

Backup jest zaszyfrowanym mechanizmem disaster recovery, nie archiwum służącym do obchodzenia runtime retention.

- Runtime purge nie wymaga selektywnego usuwania danych z istniejących starych backupów.
- Dane usunięte z runtime mogą pozostać w zaszyfrowanym backupie do naturalnego końca jego rotacji.
- Retencja backupów wynosi domyślnie 35 dni i jest konfigurowalna per deployment wyłącznie w zakresie 7–90 dni.
- Po restore retention/purge musi zostać ponownie zastosowany przed uznaniem środowiska za gotowe do normalnego ruchu. Środowisko nie może trwale przywrócić danych, których runtime retention już wygasł.

Procedura restore musi uwzględnić aktywny legal hold deploymentu: hold zatrzymuje purge także po restore, a jego stan i każda zmiana pozostają jawne oraz audytowalne operacyjnie.

## Wymagane testy przyszłej implementacji

- akceptacja wartości domyślnych i obu granic każdego zakresu konfiguracji;
- odrzucenie wartości poniżej i powyżej zakresu bez cichego fallbacku;
- hard delete `inbound_events` i kontrolowany hard delete `audit_events` po expiry;
- redakcja `messages` do minimalnego tombstone bez body i zbędnego PII;
- usunięcie binary objectu attachmentu oraz sanityzacja albo późniejsze usunięcie jego rekordu DB;
- brak przypadkowego cascade przez relacje zachowujące historię;
- zatrzymanie purge przez legal hold per deployment oraz wznowienie po jego wyłączeniu;
- rotacja backupów w dozwolonym zakresie i ponowne zastosowanie purge przed otwarciem ruchu po restore;
- izolacja baz, object storage, sekretów i konfiguracji między deploymentami;
- brak `tenant_id`, tenant selectora i cross-tenant API w runtime v1.

Testy implementacyjne korzystają z izolowanych zasobów testowych i nigdy nie łączą się z produkcyjną bazą, object storage ani sekretami providerów.

## Poza zakresem USI-40

- implementacja backendu, retention jobów, migracji DB, object storage i backup automation;
- wybór nazw kluczy konfiguracyjnych, harmonogramu jobów i fizycznych kolumn timestampów;
- bezterminowa retencja jako zwykła konfiguracja;
- legal hold per Case lub per user;
- tenant selector, cross-tenant API i `tenant_id` w tabelach runtime v1;
- zmiany istniejącego UX lub dodanie konfiguracji retencji/legal hold do UI.
