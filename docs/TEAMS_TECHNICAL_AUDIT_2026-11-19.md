# Audyt Microsoft Teams — 2026-11-19

Wersja publikacyjna: identyfikatory lokalnych spraw, ścieżki dowodów i host witryny sandboxa zostały zanonimizowane. `contoso.sharepoint.com` jest przykładem, nie rzeczywistą konfiguracją. Pełne dowody i oryginał raportu pozostają poza repozytorium. Nazwy A1/B1/C1 oznaczają scenariusze testowe.

**Aktualizacja po naprawach:** w standardowych kanałach potwierdzono LIVE odbiór
poprawnych obrazów, PDF i DOCX, wysyłkę kompletu PNG/PDF/DOCX z inboxa do tego samego
wątku oraz wyszarzony marker usunięcia z zachowanymi informacjami i obrazem.
Naprawiono też fałszywy błąd wysyłki po HTTP 202. Szczegóły i wyniki zmienionego
kodu są w [sekcji 11](#11-poprawki-wykonane-po-audycie). Nadal blokuje regularne
użycie brak recovery i pełnego onboarding/lifecycle oraz niewykonany odbiór group chat.

## Wniosek i badana wersja

**Stan przed poprawkami: integracja nie jest gotowa do regularnej obsługi zgłoszeń z plikami ani do pracy wymagającej odzyskania wiadomości po awarii.** Tekst w standardowych kanałach działa na prawdziwym Teams. Nie jest to odbiór całego E18 ani równoważność funkcjonalna ze Slackiem.

- Repozytorium: `Spodyn/ApplicationSupport`, lokalnie `<external-local-evidence>`.
- Branch audytowanego kodu: `codex/USI-181-teams-sandbox`.
- SHA: `514a51518eef25554ad91815db0c349501023bc7`; checkout był czysty na początku audytu.
- Porównano 642 klasy uruchomionego zewnętrznego JAR-a z aktualnym buildem: **0 różnic**, w tym 16 klas Teams. SHA-256 runtime JAR: `249ffd2725963c97429e86d4027d5f34c353a588faec29ff908fc8dc6ce8cd12`.
- [PR #46](https://github.com/Spodyn/ApplicationSupport/pull/46): OPEN, DRAFT, niepołączony, `mergeable=false`, HEAD `fa8eda29948fbafdc6e6c3556fa013600ed9970c`. To starszy setup, nie bieżący runtime. Nie zmieniano PR ani statusów Jira.
- Ponownie przeczytano Jira USI-180–184 i późniejszy freeze E18/USI-24. Obecne polecenie użytkownika upoważnia do audytu sandboxa; private/shared/1:1 pozostają poza v1.

Oznaczenia dowodów: **LIVE** — prawdziwy Teams + lokalny USI; **TEST** — test syntetyczny/mock dostawcy, często z prawdziwym disposable PostgreSQL/RabbitMQ/MinIO; **KOD** — analiza; **BLOCKED/NOT RUN** — niewykonany wymagany scenariusz. Zielony test mockowy nie oznacza potwierdzenia platformy Microsoft.

## 1. ZIP, tożsamość i instalacja

| Kontrola | Oryginał `usi-teams-sandbox.zip` | Poprawiony `usi-teams-sandbox-0.1.1.zip` |
| --- | --- | --- |
| Wersja aplikacji / schematu | 0.1.0 / 1.21 | 0.1.1 / 1.21 |
| Pełny oficjalny JSON Schema Draft4 + URI format checker | 0 błędów | 0 błędów |
| Pliki w głównym katalogu | `manifest.json`, `color.png`, `outline.png` | te same trzy pliki |
| ZIP CRC / PNG | poprawne; 192×192 i 32×32 | poprawne; 192×192 i 32×32 |
| Bot scopes | `team`, `groupChat` | `team`, `groupChat` |
| RSC Application | `ChannelMessage.Read.Group`, `ChatMessage.Read.Chat` | dokładnie te same dwa |
| `webApplicationInfo.id` / `botId` | ten sam zarejestrowany client ID | ten sam client ID |
| Dodatkowe `staticTabs` | `conversations` i `about`, scope `personal` | brak |
| Instalacja LIVE | użytkownik zgłosił `Unknown` | skuteczna instalacja i zwykłe wiadomości w dwóch kanałach |

SHA-256 oryginału: `b9ed35042e36cbba58abbe5c1ee362e2886eb6db785e5bca68ff1d6aa692d9da`; poprawionego ZIP-a: `04d1f0c63b0297049d6d42d1a6f79b3aadb13bfd5d4d629bfccea7f8c854ded1`.

**Przyczyna `Unknown` nie została jednoznacznie udowodniona.** Oryginał przechodzi pełny schemat, więc nie jest prawdziwe stwierdzenie „błąd JSON Schema”. Zidentyfikowano niepożądane osobiste zakładki z eksportu CLI. Usunięcie ich, podniesienie wersji i instalacja 0.1.1 rozwiązały problem praktycznie. Brakuje identyfikatora błędu/diagnostyki usługi Microsoft z pierwotnej próby, aby przypisać wyłączną przyczynę zakładkom. Nie odinstalowywano działającej aplikacji w celu ponownego wywołania błędu.

Dokładny krok naprawczy: użyć gotowego `<external-local-evidence>`; Teams → Aplikacje → Zarządzaj aplikacjami → Przekaż aplikację → Przekaż aplikację niestandardową → wskazać ZIP → Dodaj do zespołu → **USI Sandbox** → zaakceptować RSC. Ten krok został już wykonany. Nie trzeba ponawiać instalacji dla każdego standardowego kanału tego zespołu. [Instrukcja Microsoft](https://learn.microsoft.com/en-us/microsoftteams/platform/concepts/deploy-and-publish/apps-upload).

Token z istniejącego zewnętrznego magazynu sekretów ponownie uzyskał HTTP 200; jego `tid` odpowiada wskazanej przez użytkownika dzierżawie, a `appid` odpowiada `webApplicationInfo.id` i `botId`. Wartości identyfikatorów pozostają w paczce i lokalnym pliku `identifiers.json`; sekret/token nie występuje w raporcie. Nie dodawano organizacyjnego odczytu wiadomości. `supportsFiles=false` w manifeście nie jest samodzielną przyczyną braków transportu kanałowych plików; samo przełączenie na `true` nie implementuje dostępu do SharePoint/OneDrive.

Polityka uploadu jest **efektywnie dozwolona dla tej sesji/zespołu**, czego dowodzi działająca instalacja. Nie wykonano eksportu wszystkich polityk administracyjnych tenanta. Przy blokadzie administrator sprawdza Teams admin center → Teams apps → Setup policies → Upload custom apps, dostępność aplikacji i ustawienia organizacyjne; właściciel sprawdza ustawienia aplikacji zespołu. To odrębne kontrole od walidacji manifestu. [Polityki Microsoft](https://learn.microsoft.com/en-us/microsoftteams/teams-custom-app-policies-and-settings).

### Osobne bramki uruchomienia

| Etap | Wynik |
| --- | --- |
| ZIP i rejestracja Entra/bota | LIVE + walidacja lokalna: PASS |
| Instalacja w docelowym zespole | LIVE: PASS; wcześniejsza instalacja w innym zespole nie wystarczała |
| Zgoda na RSC tego zespołu | LIVE: skuteczny odbiór bez wzmianki; deklaracja obu RSC nie dowodzi instalacji w czacie |
| Automatyczne wykrycie kanałów Teams | KOD: brak implementacji |
| Utworzenie rekordu kanału i `teamsContextType` | wykonane lokalnym SQL, nie kreatorem USI |
| Przypisanie klienta / monitoring istniejącego rekordu | LIVE: panel Settings → Channels obsługuje istniejące rekordy |
| Odbiór po mapowaniu | LIVE: PASS dla `support-test` i `support-audit-2` |

## 2. Macierz obsługiwanych kontekstów

Poniższa tabela opisuje **obecny USI**, a nie samą teoretyczną możliwość Microsoft Graph.

| Kontekst | Wykrycie / podłączenie w USI | Nowe zwykłe wiadomości | Odpowiedzi klienta | Odpowiedź z USI | Dowód |
| --- | --- | --- | --- | --- | --- |
| Standardowy kanał | bez autodiscovery; instalacja w zespole + ręczny rekord/mapowanie | tak, bez wzmianki | tak, ten sam root | bot, właściwy wątek | LIVE w dwóch kanałach |
| Prywatny kanał | niedostępny w v1 | niedostępne w v1 | niedostępne w v1 | niedostępne w v1 | KOD + freeze |
| Udostępniony, jedna organizacja | niedostępny w v1 | niedostępne w v1 | niedostępne w v1 | niedostępne w v1 | KOD + freeze |
| Udostępniony między organizacjami | niedostępny w v1; dodatkowo granice członkostwa/host tenanta i osobny SharePoint | niedostępne | niedostępne | niedostępne | KOD + freeze; LIVE NOT RUN |
| Czat grupowy | instalacja i zgoda w konkretnym czacie + ręczny rekord | zaimplementowane | jeden aktywny Case, null thread | ścieżka Connector zaimplementowana | TEST + KOD; LIVE BLOCKED — jedno konto |
| Czat 1:1/personal | manifest i parser go nie obsługują | nie | nie | nie | TEST + KOD; poza v1 |

Microsoft rozwija aplikacje w private/shared, lecz te konteksty wymagają odrębnej instalacji, członkostwa i granic plików. Nie rozszerza to zaakceptowanego v1 USI. [Dokumentacja kontekstów](https://learn.microsoft.com/en-us/microsoftteams/platform/build-apps-for-shared-private-channels). `ChannelMessage.Read.Group` obejmuje zwykłe kanały zainstalowanego zespołu, a `ChatMessage.Read.Chat` konkretny czat; oba wymagają rzeczywistej instalacji/zgody w odpowiednim zasobie. [Odbiór bez wzmianki](https://learn.microsoft.com/en-us/microsoftteams/platform/bots/how-to/conversations/channel-messages-for-bots-and-agents), [RSC i zgoda](https://learn.microsoft.com/en-us/microsoftteams/platform/graph-api/rsc/resource-specific-consent).

### Kolejny kanał w USI Sandbox — procedura potwierdzona LIVE

1. Właściciel lub uprawniony członek: Teams → menu zespołu USI Sandbox → Dodaj kanał → Standardowy. W audycie utworzono `support-audit-2`.
2. Bot i team-level RSC są już zainstalowane; nie tworzyć kolejnego bota/sekretu/ZIP-a dla tego kanału.
3. Operator uzyskuje rzeczywisty channel ID oraz potwierdza standardowy typ. Brakuje w USI odczytu listy kanałów Teams. Istniejące dwa RSC nie są uprawnieniem do Graph list channels; Microsoft wskazuje m.in. dodatkowe resource-scoped `ChannelSettings.Read.Group`, jeżeli przyszła implementacja wybierze Graph discovery. Nie dodawano go w audycie. [List channels](https://learn.microsoft.com/en-us/graph/api/channel-list?view=graph-rest-1.0).
4. Operator lokalnego sandboxa tworzy rekord przez [local-teams-sandbox-seed.sql](../scripts/sql/local-teams-sandbox-seed.sql): integration/tenant istniejący, nowy context ID i nazwa, `teams_context_type=standardChannel`, istniejący secret_ref. Helper jest wyłącznie sandboxowy; domyślnie przypisuje syntetycznego klienta.
5. Administrator USI z `manage_integrations`: Settings → Channels → wiersz nowego kanału → wybrać właściwego klienta i włączyć monitoring. Panel zmienia istniejący rekord; nie tworzy go ani nie ustawia `teamsContextType`.
6. Nowy post bez wzmianki → jedna sprawa → Claim → Reply → sprawdzić ten kanał i root. Powtórzyć odpowiedź klienta i kontrolę SLA.

W audycie B1 przed mapowaniem był trwale zapisany jako FAILED / `TEAMS_CONTEXT_CONFIGURATION_PENDING`, bez Message/Case. Po mapowaniu scheduler przetworzył go do sandbox-case-B1; bot odpowiedział w `support-audit-2`. Nie trafił do sandbox-case-A1 z `support-test`. Zwykła odpowiedź klienta pozostała w 37; po Resolve następna wiadomość utworzyła sandbox-case-B2 z `related_case=37`.

### Kanał w innym zespole tej samej dzierżawy

1. Właściciel nowego zespołu instaluje **tę samą** aplikację 0.1.1 w nowym zespole i akceptuje jego RSC. Sam katalog aplikacji użytkownika ani instalacja w USI Sandbox nie wystarczają.
2. Gdy polityka blokuje instalację/zgodę, potrzebny jest administrator Teams/Entra. Nie jest wymagany nowy sekret tylko dlatego, że zmienia się zespół.
3. Dla każdego wybranego standardowego kanału powtórzyć identyfikację ID, rekord/typ, mapowanie klienta i monitoring jak powyżej. Wybrać istniejącą integrację tej dzierżawy; callback wymaga dokładnie jednego włączonego dopasowania do tenant ID.
4. Powtórzyć odbiór i reply w docelowym root. Ta instalacja nie została wykonana ponownie LIVE w audycie; procedura wynika z KOD + dokumentacji Microsoft.

### Kanał w innej dzierżawie

Obecny runtime ma globalne `client-id` i `tenant-id`, a zarejestrowany bot jest single-tenant/myOrg. Inny tenant nie jest kolejnym wierszem kanału — autentykator go odrzuci. **W tym samym uruchomieniu nie da się obecnie dołączyć dowolnej drugiej dzierżawy.**

Dla osobnego wdrożenia klienta/sandboxa: administrator nowej organizacji dopuszcza custom apps; operator rejestruje w niej bota/Entra, zachowuje sekret w jej zewnętrznym magazynie, ustawia jej IDs i HTTPS callback, renderuje dedykowany ZIP, właściciel instaluje i zgadza się w zespole, następnie operator i admin USI mapują kanały. Zamiana globalnych IDs w aktualnym wdrożeniu odłączyłaby pierwszy tenant. Wielodzierżawowy bot i per-integration tożsamości wymagałyby odrębnej implementacji i walidacji bezpieczeństwa. Nie wykonywano tego LIVE.

## 3. Przepływ i funkcje spraw

Przepływ: HTTPS → JWT Microsoft (issuer/audience/czas/service URL/tenant/recipient) → trwały `inbound_events` + outbox → RabbitMQ → worker → filtr kontekstu/mapowania → wspólny Case/Message/unread/SLA → komenda Reply → outbox → worker dostawy → client credentials → Bot Connector → checkpoint provider ID → SENT → workflow/SLA/realtime.

**Mechanizm odpowiedzi:** bot z client credentials, scope tokenu `https://api.botframework.com/.default`; normalny REST `/v3/conversations/{conversation}/activities/{root}` dla kanału. Nie jest to Graph delegated, impersonacja zalogowanego agenta ani API migracyjne. Teams pokazuje autora **USI Sandbox**, podczas gdy USI zapisuje rzeczywistego operatora **Test User**. Zwykły wątek działa — potwierdzone LIVE. Nie używać `Teamwork.Migrate.All` do odpowiedzi supportu. [Graph channel-post permissions](https://learn.microsoft.com/en-us/graph/api/channel-post-messages?view=graph-rest-1.0).

| Funkcja | Wynik / granica | Dowód |
| --- | --- | --- |
| Nowy root / odpowiedzi bez wzmianki | sandbox-case-A1, jeden root i kolejne wiadomości; odrębny root w drugim kanale ma odrębną sprawę | LIVE + TEST |
| Claim / Reply | wymagany właściciel; normalny reply SENT przechodzi do waiting i zachowuje właściciela | LIVE + TEST |
| Ask Customer | rzeczywiście wysłany botem; dopiero SENT daje waiting, null owner i deadline 1h w próbie | LIVE + TEST |
| Odpowiedź klienta po Ask | wraca NEW bez właściciela; po zwykłym reply wraca VERIFICATION z właścicielem | LIVE + TEST |
| Resolve | kończy aktywny cykl, neutralny SLA; kategoria opcjonalna | LIVE + TEST |
| Undo resolution | sandbox-case-A1 wróciła NEW; oryginalne FRT zachowane | LIVE + TEST |
| Nowa aktywność po terminalnym Case | sandbox-case-B2 powiązana z 37, ten sam root | LIVE + TEST |
| Ignore | 1/2 → PARTIALLY_IGNORED; nowa wiadomość resetuje głos w tej samej 38; głosujący nadal nie może Claim | LIVE + TEST |
| Terminalny Ignore przy 2 punktach | wspólna maszyna stanów; nie wykonano dwóch rzeczywistych głosów w tym audycie | TEST, LIVE NOT RUN |
| Snooze | prywatny, nie zmienił stanu ani NRT; nowa wiadomość klienta go zakończyła | LIVE + TEST |
| Read/unread | nowa sprawa była Unread; po wyrenderowaniu/ack znacznik zniknął; niezależność wielu użytkowników w testach | LIVE + TEST |
| Activity | Claim, waiting, Ask, customer reply, edit/delete, Resolve i Reopen obecne w audycie; Snooze jako osobista historia | LIVE + TEST |
| Edit | zmiana body i edited_at, bez nowej sprawy i bez resetowania SLA | LIVE + TEST |
| Delete | deleted_at + body wyzerowane; UI błędnie mówi „deleted in Slack” | LIVE + KOD — problem |
| Własne wiadomości bota | brak powrotnego CUSTOMER/Case; parser odrzuca autorów `28:` | LIVE + TEST |
| Duplikaty/race | event identity + lock + dedup Message również po zamknięciu generacji | TEST; nie fałszowano Microsoft JWT do testu LIVE |
| Poza kolejnością | root identity stabilne nawet gdy reply pierwszy; starszy edit nie nadpisuje nowszego; mutacja przed create retry/pending | KOD + częściowe wspólne TEST; pełna macierz Teams NOT RUN |
| Ignorowany kanał | B-IGNORED: PROCESSED/IGNORED_BY_CHANNEL, 0 Message/Case | LIVE + TEST |
| Nieaktywny kanał | B-INACTIVE: PROCESSED/INACTIVE_CHANNEL, 0 Message/Case; active zmieniono kontrolowanie lokalnym SQL i przywrócono | LIVE + KOD |
| Wyłączona integracja | odrzucenie callbacku / brak skutków workera; wspólny outbound guard | TEST + KOD; LIVE nie wyłączano całego połączenia |
| Brak kanału/mapowania klienta | retry konfiguracji, po wyczerpaniu DLQ; nie ma odpowiednika Slackowego inboxa bez klienta | LIVE dla brakującego rekordu, KOD dla null customer |

## 4. Załączniki — stan przed naprawami

| Plik/scenariusz | Teams → USI | USI → Teams | Dowód |
| --- | --- | --- | --- |
| Obraz wklejony do treści | tekst dociera, obraz pominięty | lokalny PNG CLEAN, preview działa, wysyłka FAILED | LIVE |
| Obraz wybrany jako plik | widoczny w Teams; brak obiektu załącznika USI | wszystkie niepuste listy plików odrzuca ten sam guard | LIVE inbound + KOD outbound |
| PDF | dwa dokumenty w jednej wiadomości, oba pominięte | guard odrzuca każdy typ | LIVE inbound + KOD outbound |
| DOCX | plik wysłany w wątku B1, sandbox-case-B2 zawiera podpis i 0 plików | guard odrzuca każdy typ | LIVE inbound + KOD outbound |
| Wiele plików | Teams pokazuje 3, USI pokazuje sam podpis i 0 inbound attachments | brak implementacji transportu | LIVE + KOD |
| Uprawniony download / podgląd przychodzącego dokumentu | niemożliwe: brak zarejestrowanego pliku w USI | lokalny pipeline ma autoryzację/scan; odbiorca Teams nie dostaje pliku | LIVE + TEST wspólnego storage |

W sandbox-case-A1 odczyt bazy wykazał **0 załączników CUSTOMER**. Jedyny załącznik SUPPORT to `clipboard.png`, 142 B, `CLEAN`; próba dostawy 1 zakończyła się `PERMANENT / TEAMS_ATTACHMENTS_UNSUPPORTED`. Nie wysłano ani tekstu tej konkretnej odpowiedzi, ani obrazu. UI pokazało ogólne „Could not deliver the message”; brak naprawialnego retry. Nie pobierano treści prywatnych dokumentów wybranych przez użytkownika do testu.

### Co konkretnie jest niezaimplementowane

- `TeamsActivity` ignoruje `attachments`; worker nie uruchamia transferu plików. Botowy payload dokumentów może zawierać tylko HTML; rzeczywiste referencje dokumentów są w Graph `chatMessage.attachments`.
- Minimalny odczyt Graph wiadomości z istniejącym RSC zwrócił **200 i trzy referencje do plików SharePoint**. Próby rozpoznania tych plików przez Graph `/shares/.../driveItem` zwróciły **403 accessDenied** dla wszystkich trzech. To dowód brakującego dostępu do storage, odrębnego od RSC wiadomości.
- Inline image należy pobierać z kanonicznego attachment `image/*` przez uwierzytelniony klient. Graph `hostedContents` jest innym źródłem: dla kanałów tabela API wymienia `ChannelMessage.Read.Group`; tabela app-only czatów nie wymienia `ChatMessage.Read.Chat`, więc nie należy automatycznie zakładać równoważności. [Hosted content](https://learn.microsoft.com/en-us/graph/api/chatmessagehostedcontent-get?view=graph-rest-1.0).
- Pliki kanałowe należą do SharePoint zespołu, pliki czatów zwykle do OneDrive użytkownika. Token Bot Framework nie zastępuje uprawnienia do tych magazynów. [Files API](https://learn.microsoft.com/en-us/microsoftteams/platform/bots/how-to/bots-filesv4).
- Przyszły dostęp należy ograniczyć do wymaganych witryn/plików, np. odpowiednio wdrożonym `Sites.Selected` / SelectedOperations, z oddzielnym nadaniem uprawnienia do zasobu. Sam admin consent Selected nie daje dostępu do żadnej witryny. Nie rozszerzono aplikacji o `Files.Read.All` ani `Sites.ReadWrite.All`. [Selected permissions](https://learn.microsoft.com/en-us/graph/permissions-selected-overview).
- Publiczna odpowiedź powinna wskazywać plik dostępny dla członków właściwego kanału/czatu; URL localhost, endpoint USI wymagający USI_SESSION albo przypadkowy link „organizacyjny” nie są poprawnym przekazaniem odbiorcy Teams.
- Dla inline bot Microsoft podaje ograniczenia PNG/JPEG/GIF, do 1 MB i 1024×1024; większe/inne formaty muszą mieć poprawną alternatywę plikową, nie ciche zniknięcie. [Inline images](https://learn.microsoft.com/en-us/microsoftteams/platform/bots/how-to/agents-inline-images).

Wspólny kontrakt USI: 25 MiB/plik, 10 plików, 50 MiB/wiadomość; ostrzejszy limit dostawcy wygrywa. Wymagane walidacja rzeczywistego MIME, streaming, quarantine i wyłącznie CLEAN, autoryzacja pobrania/preview oraz naprawialny błąd ze wskazaniem wymaganej konfiguracji. Slack posiada ten pipeline i przechodzi syntetyczne pełne przepływy PNG/PDF/DOCX i wielu innych formatów; obecny Teams go nie wywołuje. Zmiana flagi manifestu nie zastępuje implementacji.

## 5. SLA — dokładne znaczniki czasu i osie czasu

FRT zaczyna się **raz od `provider_created_at` pierwszej zaakceptowanej wiadomości klienta**. Jeśli pierwsza przetworzona wiadomość jest opóźnioną odpowiedzią w root, ona inicjuje sprawę; późniejsze starsze zdarzenie nie cofa automatycznie startu. Case `created_at` pochodzi z PostgreSQL. NRT zaczyna się od `messages.created_at`, czyli trwałej, serializowanej akceptacji pierwszej nowej wiadomości po potwierdzonej odpowiedzi człowieka, a nie od zegara klienta Teams. Edity, kolejne wiadomości w serii i duplikaty nie otwierają drugiego cyklu.

FRT/NRT kończy **udana odpowiedź operatora potwierdzona przez provider**, nie kliknięcie Send ani kolejka. Techniczny `acceptedAt` to PostgreSQL `clock_timestamp()` podczas lokalnej finalizacji po sukcesie HTTP; jest również `delivery_attempts.finished_at`. Nie jest to odczytanie wiadomości przez odbiorcę ani dokładny timestamp serwera Teams. Opóźnienie pomiędzy sukcesem dostawcy a finalizacją może więc powiększyć zmierzony czas. OOO/system/internal note nie zaliczają pierwszej odpowiedzi.

TTR nie ma odrębnego zaimplementowanego progu „resolution due”. Raportuje gross wall od `cases.created_at` do `resolved_at` oraz business working time pomniejszony o zaakceptowane waiting i okresy zamknięcia po Undo. Nie należy nazywać FRT albo in-progress deadline terminem rozwiązania sprawy.

LIVE używa snapshotu **UTC, pon.–pt. 09:00–17:00**. Interfejs w Warszawie pokazuje te godziny jako 10:00–18:00 zimą. Próby odbyły się po zamknięciu, więc 0 minut roboczych jest oczekiwanym wynikiem, nie dowodem, że SLA nie działa.

| Oś czasu | Oczekiwane | Uzyskane / dowód |
| --- | --- | --- |
| A1 19.11 17:23:39.381836Z provider → 17:23:40.575594Z Case | FRT od provider; 15 minut w następnym otwarciu | start dokładnie provider; due 20.11 09:15Z; LIVE PASS |
| Claim 17:24:55Z → reply HTTP → finalizacja 17:25:02.475593Z | kolejka nie kończy FRT; sukces kończy i zachowuje owner w waiting | completed równy finished_at udanej próby; SENT, waiting; LIVE PASS |
| Obraz klienta zaakceptowany 17:25:52.343363Z | wznowienie, jeden NRT 30 minut | NRT due 20.11 09:30Z; LIVE PASS |
| Snooze przed Ask | brak pauzy SLA, prywatny stan | NRT pozostał otwarty; po nowej wiadomości Snooze zniknął; LIVE PASS |
| Ask SENT 17:27:21.806884Z | koniec NRT, waiting i brak owner | RESPONDED, waiting, null owner, deadline 1h; LIVE PASS |
| Obraz klienta 17:29:22Z i nieudany PNG outbound 17:29:49Z | nowy NRT; FAILED nie zamyka cyklu ani nie włącza waiting | NRT pozostał aktywny, Case VERIFICATION; LIVE PASS |
| Resolve 17:34:18.261995Z → Undo 17:34:43Z | neutralny po Resolve; zachować FRT/history | wall 637 s, working 0 s; Undo NEW z wcześniejszym completed FRT; LIVE PASS |
| Warszawa, pt. 27.03.2026 16:53 → pon. 30.03 09:00 (zmiana DST) | 7 min w piątek, 8 min w poniedziałek dla FRT | test `keepsFirstReplyAndTwoNextReplyCyclesOnTheOriginalBusinessSchedule`: due pon. 09:08, warning 09:04:15; TEST PASS |
| NRT od pon. 09:12; kilka kolejnych wiadomości; odpowiedź 09:43 | jedna due 09:42, odpowiedź spóźniona; kolejny cykl niezależny | zachowany jeden deadline i historical breach; TEST PASS |

Zmiany harmonogramu/polityki nie przepisują snapshotu FRT/NRT istniejącej sprawy. Testy pokrywają DST, wyjątki i brak przyszłego otwarcia. Przełączenie ustawień produkcyjnych/sandboxowych tylko dla testu nie było potrzebne. Te same wspólne komponenty są używane przez Slack; różnica Teams dotyczy transportu oraz momentu dotarcia do tego wspólnego kodu.

## 6. Odporność i różnice względem Slacka

| Obszar | Teams | Slack | Dowód |
| --- | --- | --- | --- |
| Odnowienie Graph subscription | brak subscription w tym modelu; odbiór to Bot Connector/RSC | Events API, nie Graph | KOD — nie przedstawiać braku renewera jako wygasłej subskrypcji |
| Trwałe zdarzenia już odebrane | PostgreSQL + outbox, lease/redispatch; dedup biznesowy | analogicznie, dojrzalsze testy/workery | TEST + KOD |
| Wiadomości niedostarczone do callbacku w czasie przerwy | brak resync/history catch-up | istnieją `SlackResyncWorker/Repository/Controller` i stronicowanie historii/replies | KOD + próba LIVE opisana poniżej |
| Inbound retry konfiguracji | max 12 prób, bounded backoff do 300 s, potem DLQ | osobny reconcile/discovery i mechanizmy resync | TEST + KOD |
| Outbound 429 / 5xx | transient, wspólne max 8 prób/24h, backoff+jitter; numeryczny Retry-After dla Connector | classifier + wspólny worker | TEST, bez sztucznego wymuszania błędów Microsoft LIVE |
| Token endpoint 429/5xx | transient, lecz Retry-After nie jest przenoszony | inne API/obsługa | KOD — luka |
| Akceptacja już zapisana | `acceptedBodyRef` chroni przed ponownym wysłaniem | trwały checkpoint również dla częściowego uploadu | TEST |
| Timeout / niejednoznaczny wynik wysyłki | `TEAMS_DELIVERY_UNCONFIRMED`, bez automatycznego replay | Slack używa client message id/checkpointów | TEST + KOD |
| Crash po akceptacji przed zapisaniem provider ID | brak gwarancji exactly-once po stronie Connector; możliwy duplikat po lease recovery | lepszy provider idempotency path, nadal wymaga ostrożnej finalizacji | KOD; destrukcyjnego fault injection nie wykonywano LIVE |
| Cofnięcie zgody / uninstall | 401/403/404 klasyfikowane na wiadomości, brak pełnego installation lifecycle i proaktywnej detekcji | własny health reporter/probe i klasyfikacja revoke | KOD; LIVE nie cofano działającej zgody |
| Health w adminie | wejściowe zdarzenie może ustawić HEALTHY; brak TeamsConnectionTester i raportowania outbound failures | connection tester, probe, failure/recovered, silence detection | KOD + LIVE widok panelu |
| Ręczny retry po naprawie auth/permissions | obecna polityka dopuszcza TRANSIENT i INTEGRATION_DISABLED, nie większość TEAMS_* permanent | podobna wspólna polityka, więcej komunikatów dostawcy | KOD — brak naprawialnej ścieżki po ponownej zgodzie |

Restart LIVE: zatrzymano tylko lokalne procesy API; potwierdzono brak nasłuchu, następnie uruchomiono tę samą wersję. Drugi kontrolowany stop nastąpił 18:41:20 czasu Warszawy, start procesu 18:41:32, gotowość 18:41:40. Teams potwierdził wysłanie `B-RESTART` podczas przerwy. Kolejny `B-AFTER-RESTART` dotarł i utworzył sandbox-case-restart o 18:41:59; odpowiedź z inboxa ma SENT. Ponowne odczyty podczas dalszej pracy nadal wykazują **0 callbacków i 0 Messages dla B-RESTART**. To potwierdzona luka odbioru w tym oknie testu; brak implementacji catch-up nie pozwala jej odzyskać z USI. Test niedostarczenia przed durable intake jest odrębny od wznowienia zdarzenia już zapisanego w bazie. Nie zakładać nieograniczonego retry dostawcy.

## 7. Wyniki uruchomionych testów na audytowanym SHA

Logi bez wyświetlania sekretów: `<external-local-evidence>`.

| Komenda / zestaw | Zaliczono | Nie zaliczono | Pominięto | Granica |
| --- | ---: | ---: | ---: | --- |
| `bash apps/api/mvnw -f apps/api/pom.xml --batch-mode verify -DexcludedGroups=integration` | 298 | 0 | 0 | unit/compile/package, synthetic provider HTTP |
| `bash apps/api/mvnw -f apps/api/pom.xml --batch-mode test -Dgroups=integration` | 276 | 0 | 0 | disposable PostgreSQL i pozostałe fixture boundaries |
| `pnpm test:fullstack` | 20 | 0 | 0 | real lokalny frontend/backend/DB/Rabbit/MinIO; **syntetyczny Slack**, nie Teams LIVE |
| `pnpm check`: dev-tools | 9 | 0 | 0 | node test |
| `pnpm check`: gates | 71 | 0 | 0 | fail-closed konfiguracja CI/merge |
| `pnpm check`: config | 68 | 0 | 0 | obejmuje manifest/ZIP contract tests |
| `pnpm check`: OpenAPI | 14 | 0 | 0 | 97 operacji/136 schematów; generator i client compile |
| Adapter smoke w `pnpm check` | 3 | 0 | 0 | częściowo pokrywa się z web unit — nie sumować jako unikatowe testy |
| Web unit w `pnpm check` | 104 | 0 | 0 | 21 plików |
| UI Playwright w `pnpm check` | 34 | 0 | 0 | frontend z API stubs |
| `pnpm check`: lint/typecheck/build/config/env/secret scan | PASS | 0 | 0 | repo i frontend |
| `pnpm audit --audit-level=high` | PASS | 0 findings | 0 | aktualna baza zależności |
| `dependency:list` + `python scripts/security_dependencies.py --maven-list apps/api/target/security-dependencies.txt` | 250 packages | 0 findings | 0 | OSV |

Na Windows użyto `C:/Program Files/Git/bin/bash.exe` i literalnego argumentu `'-DoutputFile=target/security-dependencies.txt'`. Pierwsza próba tego ostatniego polecenia bez cytowania została błędnie rozbita przez PowerShell (`Unknown lifecycle phase .txt`); poprawione polecenie przeszło. To błąd uruchomienia narzędzia, nie niezaliczony test aplikacji.

Pakiety testów Teams obejmują 14 testów jednostkowych i 9 integracyjnych, zawartych w powyższych liczbach. Nie ma kompletnego Teams fullstack ani LIVE group chat. Testy zwykłego CI nie odczytywały lokalnych sekretów i nie kontaktowały się z prawdziwym Teams. Osobne ręczne próby LIVE używały wyłącznie upoważnionego sandboxa. Nie uruchamiano CI na opublikowanym nowym PR HEAD; nie wykonywano merge. Compose lifecycle PASS pochodzi z wcześniejszej walidacji tego samego kodu i nie jest liczony jako nowy test audytu.

## 8. Priorytety napraw

| Priorytet | Problem | Dokładne działanie / kryterium odbioru |
| --- | --- | --- |
| P1 | Inbound pomija wszystkie obrazy i dokumenty | normalizować stabilne referencje; pobierać asynchronicznie poza transakcją, walidować/scanować, pokazywać jawny błąd i retry; LIVE PNG paste/file + PDF + DOCX + multi |
| P1 | Outbound odrzuca każdą listę plików | przesłać CLEAN bytes do właściwego magazynu, zapewnić dostęp członkom kanału/czatu, wysłać raz do właściwego root; LIVE otworzyć plik kontem odbiorcy |
| P1 | Brak recovery przed callbackiem i pełnego install/revoke lifecycle | utrwalić granice monitorowania, wykonać bounded catch-up 24h/max7d zgodnie z kontraktem, sygnalizować luki i consent failure |
| P1 | Ręczny onboarding i brak wiarygodnego health | Teams-specific discovery/tester/health, typ kontekstu z zaufanego źródła, kontrolowane mapowanie w panelu, naprawialny retry auth/permissions |
| P1 | Brak LIVE group chat i Teams fullstack | drugi użytkownik, chat install/RSC, test tekstu/plików/grouping/reply; CI z syntetycznym Graph/Connector |
| P2 | Granica monitorowania zależy od `integrations.created_at`, nie kanału | jawne per-context monitoring_started_at i test opóźnionego onboarding/recovery; nie przedstawiać później odblokowanych zdarzeń jako importu historii |
| P2 | Retry-After tokena i HTTP-date | przenosić Retry-After przy token acquisition, obsługiwać oba formaty w Connector; syntetyczne 429/503 |
| P2 | Ogólne błędy i etykieta Slack po delete | provider-neutral marker i czytelne TEAMS_* wraz z backendowym retryAllowed |
| P2 | Publiczny limit body dopiero po alokacji `byte[]` | limit rzeczywistego odczytu przed deserializacją/dużą alokacją; podobny wzorzec istnieje przy Slack — wspólne hardening |
| P2 | Tymczasowy quick tunnel i ręczny runtime | stabilny HTTPS/monitorowany proces, konfiguracja bez ręcznego seedowania; poza produkcją nie wymaga zmiany DNS w tym audycie |

### Istotne pliki i linie audytowanego kodu

- [TeamsWebhookController.java](../apps/api/src/main/java/com/unifiedsupportinbox/provider/teams/internal/TeamsWebhookController.java#L26): limit body/JWT/durable identity.
- [TeamsJwtAuthenticator.java](../apps/api/src/main/java/com/unifiedsupportinbox/provider/teams/internal/TeamsJwtAuthenticator.java#L34): RS256, issuer/audience/tenant/service binding; bounded JWKS od linii 101.
- [TeamsActivity.java](../apps/api/src/main/java/com/unifiedsupportinbox/provider/teams/internal/TeamsActivity.java#L11): filtr kontekstów/botów, root identity, text/edit/delete; brak attachments.
- [TeamsInboundWorker.java](../apps/api/src/main/java/com/unifiedsupportinbox/provider/teams/internal/TeamsInboundWorker.java#L42): policy, mapping, 12 prób.
- [TeamsRouteStore.java](../apps/api/src/main/java/com/unifiedsupportinbox/provider/teams/internal/TeamsRouteStore.java#L12): zatwierdzenie metadata i monitoring od integracji.
- [TeamsMessageDeliveryProvider.java](../apps/api/src/main/java/com/unifiedsupportinbox/provider/teams/internal/TeamsMessageDeliveryProvider.java#L31): jawny rejection plików.
- [TeamsConnectorClient.java](../apps/api/src/main/java/com/unifiedsupportinbox/provider/teams/internal/TeamsConnectorClient.java#L24): token i normalne posting; klasyfikacja błędów od 61.
- [InboundMessageMutationService.java](../apps/api/src/main/java/com/unifiedsupportinbox/messaging/internal/InboundMessageMutationService.java#L59): kasowanie body na delete.
- [MessageDeliveryService.java](../apps/api/src/main/java/com/unifiedsupportinbox/messaging/internal/MessageDeliveryService.java#L96): finalizacja dostawy/workflow/SLA.
- [CaseSlaService.java](../apps/api/src/main/java/com/unifiedsupportinbox/sla/internal/CaseSlaService.java#L52): FRT, od 90 NRT, od 143 TTR/terminal.
- [MessageRetryPolicy.java](../apps/api/src/main/java/com/unifiedsupportinbox/messaging/internal/MessageRetryPolicy.java#L6): ograniczenie retry permanent.
- [IntegrationService.java](../apps/api/src/main/java/com/unifiedsupportinbox/integration/internal/IntegrationService.java#L131): fallback testera UNAVAILABLE.
- [IntegrationHealthCoordinator.java](../apps/api/src/main/java/com/unifiedsupportinbox/integration/internal/IntegrationHealthCoordinator.java#L88): silence check tylko Slack.
- [administration-settings-page.tsx](../apps/web/components/settings/administration-settings-page.tsx#L416): Add Slack; discovery tylko Slack od 465; edycja istniejących kanałów od 560.
- [inbox-adapter.ts](../apps/web/lib/services/api/inbox-adapter.ts#L161): błędny tekst delete Slack.
- [delivery-presentation.ts](../apps/web/lib/i18n/delivery-presentation.ts#L47): brak TEAMS_*.
- [SlackInboundAttachmentService.java](../apps/api/src/main/java/com/unifiedsupportinbox/provider/slack/internal/SlackInboundAttachmentService.java#L63), [SlackMessageDeliveryProvider.java](../apps/api/src/main/java/com/unifiedsupportinbox/provider/slack/internal/SlackMessageDeliveryProvider.java#L99), [SlackResyncWorker.java](../apps/api/src/main/java/com/unifiedsupportinbox/provider/slack/internal/SlackResyncWorker.java#L59): porównywany działający pipeline.

## 9. Co pozostaje po stronie użytkownika

Instalacja 0.1.1 w USI Sandbox jest zakończona. `support-test` i nowy `support-audit-2` są podłączone; nie trzeba ponownie wgrywać ZIP-a do tych standardowych kanałów. Mapowanie już istniejącego kanału: USI → Settings → Channels → klient + monitoring. Dodanie nowego rekordu Teams nadal wymaga operatora lokalnego backendu, dopóki nie powstanie discovery.

Do LIVE group chat potrzebny jest drugi uczestnik; następnie Teams → aplikacja USI Sandbox → Dodaj do czatu → wybrany czat → zgoda `ChatMessage.Read.Chat`, a operator mapuje rzeczywiste chat ID. Jedno konto i czat z samym sobą nie zastępują grupowego scenariusza v1.

Załączników nie odblokuje ponowna instalacja niezmienionego ZIP-a. Potrzebna jest implementacja i, dla dokumentów SharePoint/OneDrive, precyzyjnie zatwierdzony dostęp do ich magazynu. Dokładne kroki zgody będą przygotowane po wyborze i przetestowaniu minimalnego dostępu; nie ma powodu obecnie nadawać szerokich uprawnień do wszystkich plików dzierżawy.

## 10. Nowa dyspozycja użytkownika w trakcie audytu

Użytkownik polecił dodatkowo **naprawić przekazywanie wszystkich wspieranych załączników** oraz wyświetlać usunięte wiadomości **wyszarzone, ze znacznikiem usunięcia i zachowaną treścią/metadanymi**. Powyższy raport utrwala stan wyjściowy audytu. Wdrożenie tych zmian jest osobnym etapem tej samej pracy; wymaga aktualizacji testów i kanonicznego kontraktu zachowania po delete. Nie oznaczać ich jako zakończonych na podstawie zielonych wcześniejszych testów.

## 11. Poprawki wykonane po audycie

Punkty 1–9 opisują bazę `514a51518eef25554ad91815db0c349501023bc7`.
Poniższe wyniki dotyczą nowej implementacji na tej samej gałęzi. Nie zastępują
nieprzeprowadzonych testów czatu grupowego, recovery i pełnego lifecycle instalacji.

### Zmiany i granice bezpieczeństwa

- Inbound: trwałe zadanie per Message, strumieniowe pobieranie poza transakcją DB,
  rozpoznawanie `hostedContents`, referencji z Graph i jawnego dysku SharePoint,
  istniejący MinIO/policy/ClamAV. Limit 10 plików / 25 MiB na plik / 50 MiB na wiadomość.
  Każdy błąd ma metadane; błąd jednego pliku nie ukrywa poprawnych pozostałych.
- Outbound: małe PNG/JPEG/GIF przez natywne załączniki Connector; dokumenty i większe
  obrazy przez zatwierdzony folder SharePoint oraz linki w Adaptive Card w tym samym
  wątku. Microsoft może rozbić jedno żądanie Connector na tekst, obraz i kartę, ale USI
  przechowuje jedną logiczną wiadomość. Nie są to anonimowe linki ani adresy localhost.
- Wznowienie uploadu używa stałej ścieżki i checkpointu drive/item. Przyjęte żądanie
  nie jest wysyłane ponownie, również po odzyskaniu lease. Niejednoznaczny timeout
  pozostaje bez automatycznego replay — Connector nie gwarantuje klucza idempotencji.
- LIVE ujawnił `202` bez treści dla obrazów/kompletu plików. Poprzedni kod błędnie
  oznaczał to jako `TEAMS_DELIVERY_UNCONFIRMED`. Nowa tabela
  `teams_outbound_acceptance` zapisuje prawdziwy status HTTP i czas, bez wymyślania
  Microsoft message ID; wspólna finalizacja ustawia SENT i rozlicza SLA. Awaria zapisu
  checkpointu nadal blokuje replay. [Microsoft opisuje 202 jako przyjęcie do przetwarzania](https://learn.microsoft.com/en-us/azure/bot-service/rest-api/bot-framework-rest-connector-api-reference?view=azure-bot-service-4.0).
- Delete zachowuje ostatnią zaakceptowaną treść, autora, czas i CLEAN attachments;
  UI stosuje grayscale, przygaszony tekst i jawny marker. Stare, już wymazane body
  nie jest rekonstruowane. Expiry/erasure i uprawnienia do Case pozostają obowiązkowe.
- Użytkownik jawnie zatwierdził Graph Application `Sites.Selected` i `write` tylko
  do `https://contoso.sharepoint.com/sites/USISandbox`. Zgodę Entra oraz
  grant witryny wykonano i potwierdzono. Tymczasowa sesja administracyjna używała
  delegowanego `Sites.FullControl.All` i została wylogowana; bot tego scope nie dostał.
  RSC w ZIP pozostaje dokładnie `ChannelMessage.Read.Group` i `ChatMessage.Read.Chat`.
- Foldery obu kanałów skonfigurowano lokalnie. Nowe witryny wymagają osobnego grantu;
  pliki OneDrive czatu poza zatwierdzonym magazynem nie uzyskają dostępu automatycznie.
  Sekret nadal pochodzi z istniejącego zewnętrznego `secret_ref`.
- Naprawiono transfer już przyjętych wiadomości audytu, korzystając z ich zachowanych
  raw callbacks. Nie importowano starszej historii Teams ani nie tworzono nowych spraw.

### Odbiór po poprawkach

| Funkcja | Poziom dowodu | Wynik |
| --- | --- | --- |
| PNG wklejony do treści | LIVE | CASE-40: obraz 142 B, CLEAN, podgląd 32×24; dwa wcześniejsze obrazy CASE-36 również CLEAN |
| Obraz przesłany jako plik | LIVE | CASE-36: PNG 45 521 B, CLEAN, zachowane metadane |
| PDF | LIVE | CASE-36: poprawny PDF 109 310 B, CLEAN |
| DOCX | LIVE | CASE-38: Word 46 569 B, CLEAN, ta sama sprawa i wiadomość |
| Kilka plików w jednym poście | LIVE | dwa pliki poprawnie CLEAN, trzeci widoczny jako odrzucony przez policy |
| Nieprawidłowy „PDF” | LIVE + TEST | plik 142 B miał rzeczywistą sygnaturę PNG; `POLICY_MIME_MISMATCH`, bez linku pobrania i bez obejścia przez retry |
| USI → Teams: PNG + PDF + DOCX | LIVE | trzy pliki widoczne pod root C1 jako bot USI Sandbox; PDF otwarty w przeglądarce, DOCX w Word Web na koncie członka zespołu |
| Status nowej wysyłki PNG po poprawce 202 | LIVE | SENT, rzeczywisty HTTP 202 w durable receipt; brak sztucznego provider ID |
| Końcowa wysyłka PNG + PDF + DOCX po poprawce 202 | LIVE | CASE-40: jedna logiczna wiadomość SENT, wszystkie pliki widoczne w tym samym root C1 jako USI Sandbox; jedno zamknięcie NRT |
| Usunięcie wiadomości zawierającej obraz | LIVE | C1: `deleted_at=2026-11-19T20:09:37.649625Z`; tekst/autorstwo/czas/obraz zachowane, marker i wyszarzenie, podgląd nadal działa |
| Retry po naprawie uprawnień, bez drugiej sprawy/wiadomości/pliku | TEST | rzeczywisty disposable PostgreSQL + mock Graph/storage/scanner, częściowy błąd i skuteczna naprawa |
| Retry w panelu administratora | LIVE USI + TEST | przycisk działa przez normalną sesję/CSRF, zapisuje `TEAMS_ATTACHMENT_RETRY` z aktorem ADMIN; policy failure nie jest ponawiany |
| Brak replay po 202 i brak podwójnego FRT | TEST | trwały receipt z walidacją integracji/kanału/wątku, worker pomija przyjętą wysyłkę; wspólny test SLA bez provider ID |
| Członek inny niż zalogowane konto testowe | BLOCKED | w dzierżawie jest tylko jedno konto; nie tworzono płatnych kont/licencji |
| Załączniki w czacie grupowym / OneDrive innego użytkownika | BLOCKED | brak drugiego uczestnika oraz osobno zatwierdzonego magazynu; kod obsługuje chat Graph path, nie potwierdzono LIVE |

Pierwsze dwie wysyłki plików C1 z 19:37 i 19:41 czasu Warszawy dotarły do Teams,
lecz zostały zapisane jako FAILED przez wcześniejszy błąd 202. Pozostają dowodem
błędu sprzed naprawy; nie wykonano ich ponownie i nie fałszowano historycznych wyników.
Nowa wysyłka po poprawce jest osobnym, jawnie oznaczonym testem.

### Oś czasu SLA po poprawce 202 (UTC)

| Zdarzenie | Oczekiwane | Uzyskane |
| --- | --- | --- |
| C1 zakolejkowane; wcześniejsze wysyłki fałszywie FAILED | FRT pozostaje nieukończone do poprawnej finalizacji | LIVE potwierdzono |
| Connector przyjmuje nowy PNG: `20:10:10.377831` | zapisać 202, bez ponownego POST i bez pozornego Microsoft ID | durable receipt zgodny |
| Finalizacja SENT: `20:10:10.385523` | zaliczyć pierwszą odpowiedź raz, przejść zgodnie ze wspólnym workflow do waiting | `first_response_completed_at` dokładnie równy `delivery_attempts.finished_at` |
| Nowy klient `20:12:26.340429` | ten sam Case, nowy NRT, bez resetowania FRT | NRT due `2026-11-20T09:30:00Z` na zapisanym kalendarzu UTC 09–17 |
| Końcowy komplet plików SENT `20:16:40.412048` | zakończyć ten NRT raz, bez zmiany FRT | `ended_at=20:16:40.412048`, `outcome=RESPONDED`; FRT pozostaje `20:10:10.385523` |

SENT oznacza przyjęcie przez dostawcę, nie przeczytanie przez odbiorcę. Dodatkowo
potwierdzono widoczność nowych postów w prawdziwym Teams. Nie przyspieszano zegarów
LIVE ani nie deklarowano testu upływu całego dnia SLA.

### Walidacja zmienionego kodu

Końcowe lokalne wyniki (nie sumować nakładającego się adapter smoke z web unit):

| Polecenie / grupa | PASS | FAIL | SKIP | Dowód |
| --- | --- | --- | --- | --- |
| Maven `verify -Pcoverage -DexcludedGroups=integration` | 318 | 0 | 0 | `unit-release.log`; unit/module/architecture oraz testy bez tagu integration |
| Maven `test -Dgroups=integration` | 281 | 0 | 0 | `integration-complete.log`; disposable PostgreSQL/Rabbit/MinIO, V51–V53 i regresje Slack/Teams |
| `pnpm check`: dev-tools / gates / config | 9 / 71 / 68 | 0 | 0 | `check-final.log`; config powtórzony po aktualizacji runbooków: `config-release.log` |
| `pnpm check`: OpenAPI / adapter smoke | 14 / 3 | 0 | 0 | generator zgodny, 98 operacji i 136 schematów |
| `pnpm check`: web unit / UI E2E | 105 / 36 | 0 | 0 | lint, typecheck i production build również PASS |
| `pnpm test:fullstack` po korekcie zasobów harnessu | 20 | 0 | 0 | `fullstack-verified.log`; realny lokalny stack, **syntetyczny Slack**, 16 równoczesnych odczytów SLA |
| Lint/typecheck po zmianie harnessu | PASS | 0 | 0 | `pnpm lint`, `pnpm typecheck` |
| Secret scan: worktree + dostępna historia / końcowy worktree | PASS | 0 | 0 | `secrets-complete.log` oraz `python scripts/secret_scan.py --no-history` |
| YAML / diff / lokalne linki raportu | PASS | 0 | 0 | `yaml.safe_load`, `git diff --check`, 29 plikowych linków i granice wskazanych linii |

Na Windows komendy Maven uruchomiono z repo przez
`& 'C:/Program Files/Git/bin/bash.exe' apps/api/mvnw -f apps/api/pom.xml --batch-mode`
z argumentami podanymi w tabeli. `clean verify` wykonano także przed końcową
walidacją; ostatni `verify` uwzględnia dodatkowe asercje nieprawidłowego receipt i
awarii checkpointu. Nie zmieniano zależności; wyniki npm/OSV z sekcji 7 nadal
dotyczą tego samego zestawu wersji. Nie wykonywano zdalnego CI ani merge/zmian Jira.

Jeden pośredni pełny przebieg miał **19 PASS / 1 FAIL / 0 SKIP**
(`fullstack-release.log`): trzy sesje i workerzy wyczerpali cztery połączenia profilu
unit, a `GET /cases?slaAttention=true` zwrócił 500. Runtime wskazywał
`total=4, active=4, idle=0, waiting=21`, timeout 5000 ms. Nie ukryto tego ponowieniami:
harness E2E otrzymał produkcyjny limit 20, a test został wzmocniony o jawne HTTP 200
i 16 współbieżnych odczytów. Timeoutów, bezpieczeństwa i `retries: 0` nie zmieniono;
zwykłe testy integracyjne nadal używają puli 4. Zapisane zanonimizowane pola dowodu:
`fullstack-pool4-failure.json`. To nie jest certyfikacja przepustowości produkcyjnej;
dobór puli i limity równoczesnych klientów wymagają osobnego testu obciążenia.

Logi bez sekretów są poza repo w `<external-local-evidence>`.
Red tests odtworzyły utratę body po delete, odrzucenie plików, pomijanie hosted
images, błędną klasyfikację 202, blokujący FK transferu, rozszerzenie AVIF oraz
nieczytelny błąd MIME, zanim je poprawiono. Pierwszy UI run wykazał też niejednoznaczny
selektor odpowiedzi (wiadomość i textarea); asercję zawężono do opublikowanego akapitu,
bez usuwania sprawdzanego zachowania i bez test retry.

Ostatni lokalny runtime zawiera V53; SHA-256 JAR:
`6a7b093f49b39b679c0ae24e1ba8334ca39f570661985122ac8e44bb7d729abc`.
Po restarcie health=200, publiczny HTTPS callback bez podpisu=401, końcowy komplet
pozostał jedną wiadomością SENT, a FRT zachowało `20:10:10.385523Z`. JAR nie zawiera
`FullStackTestServer` ani testowych endpointów. Oryginalny post B-RESTART nadal ma
0 callbacks i 0 wiadomości w USI — test recovery pozostaje negatywny.

Dowody obrazowe w tym samym katalogu: `usi-files-sent-final.jpg`,
`teams-files-final.jpg`, `usi-deleted-retained-final.jpg`,
`usi-deleted-image-preview.jpg`, `recipient-pdf-open.jpg`,
`recipient-docx-open.jpg`, `selected-permission-granted.jpg`.

Nowe istotne pliki: `TeamsFilesHttpClient.java` (źródła/limity/redirects),
`TeamsInboundAttachments.java` (durable transfer), `TeamsOutboundAttachments.java`
(upload/checkpoint), `TeamsOutboundAcceptance.java` (realne 202),
`TeamsFilesAdminController.java` (autoryzowany retry), migracje V51/V52/V53 i regresje
Teams/Slack, adaptera oraz UI. Kanoniczne docs i decision registry uwzględniają
dyspozycję zachowania treści po delete oraz precyzyjnie zatwierdzony dostęp do plików.

- [TeamsFilesHttpClient.java:49](../apps/api/src/main/java/com/unifiedsupportinbox/provider/teams/internal/TeamsFilesHttpClient.java#L49): discovery; download 97, upload 137, stream/redirects 177.
- [TeamsInboundAttachments.java:47](../apps/api/src/main/java/com/unifiedsupportinbox/provider/teams/internal/TeamsInboundAttachments.java#L47): lease/worker; retry 166.
- [TeamsOutboundAttachments.java:25](../apps/api/src/main/java/com/unifiedsupportinbox/provider/teams/internal/TeamsOutboundAttachments.java#L25): przygotowanie CLEAN plików i stabilne checkpointy.
- [TeamsOutboundAcceptance.java:12](../apps/api/src/main/java/com/unifiedsupportinbox/provider/teams/internal/TeamsOutboundAcceptance.java#L12): scoped trwały receipt; zapis 22.
- [TeamsConnectorClient.java:105](../apps/api/src/main/java/com/unifiedsupportinbox/provider/teams/internal/TeamsConnectorClient.java#L105): obsługa pustego 202 i blokada replay po awarii checkpointu; od 81 także bezpieczna obsługa nieprawidłowego receipt po sukcesie HTTP.
- [TeamsFilesAdminController.java:20](../apps/api/src/main/java/com/unifiedsupportinbox/provider/teams/internal/TeamsFilesAdminController.java#L20): role/capability, integration scope i audit.
- [InboundMessageMutationService.java:62](../apps/api/src/main/java/com/unifiedsupportinbox/messaging/internal/InboundMessageMutationService.java#L62): zachowany body po delete.
- [cases-page.tsx:777](../apps/web/components/cases/cases-page.tsx#L777): marker, grayscale i zachowana treść; [application.spec.ts:573](../apps/web/tests/e2e/application.spec.ts#L573): regresja UI.

Sprzątanie per-message transfer state po legalnym fizycznym purge nie blokuje
usunięcia wiadomości: V53 dodaje cascade dopiero po istniejącym sprzątnięciu
attachments/object storage. Test odtwarzał blokujący FK przed poprawką. Format
obrazu otrzymanego z hostedContents zachowuje prawidłowe rozszerzenie, również
przy braku typu w metadanych Graph (syntetyczny transport PNG/JPEG/AVIF).

**Nadal nie jest to pełna gotowość do regularnego użycia:** po naprawie plików i
delete najwyższe pozostałe priorytety to recovery po przerwie w callbackach,
onboarding/discovery i wiarygodny health/lifecycle oraz odbiór prawdziwego czatu
grupowego. Nie należy traktować braku wiadomości wysłanej podczas przerwy jako PASS.
