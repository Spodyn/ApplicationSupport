# Kontrakt grupowania spraw providerów

Stan na 20 sierpnia 2026 r. Dokument zamraża reguły z Jira USI-34 w ramach epika USI-5. Opisuje przyszłe zachowanie provider-neutral inbound processing; nie implementuje adapterów Slacka, Microsoft Teams ani Telegrama, bazy danych, OpenAPI lub nowego UI.

## Granica odpowiedzialności

Inbound adapter uwierzytelnia i normalizuje event providera, a następnie przekazuje provider-neutral command do warstwy grupowania. Warstwa grupowania, przed utworzeniem lub odnalezieniem sprawy:

1. identyfikuje monitorowany kanał/rozmowę;
2. odczytuje `channels.grouping_strategy` oraz capability kontekstu;
3. wylicza `external_conversation_id` i opcjonalny `external_thread_key`;
4. odnajduje aktywną sprawę dla klucza albo atomowo tworzy nową;
5. przypisuje wiadomość do wybranej sprawy.

UI nie wylicza kluczy providera i nie podejmuje decyzji groupingowej. Otrzymuje gotową sprawę wraz z providerem, kanałem i metadanymi potrzebnymi do prezentacji.

## Strategie i dozwolone kombinacje

| Stabilny kod strategii | Provider i kontekst | Klucz grupowania |
| --- | --- | --- |
| `SLACK_ROOT_THREAD` | Slack, monitorowany kanał | Channel/conversation ID + timestamp/ID root message. Root oraz wszystkie odpowiedzi w jego threadzie należą do jednej aktywnej sprawy. |
| `TEAMS_ROOT_REPLIES` | Microsoft Teams, kontekst potwierdzony jako obsługujący root messages i replies | Conversation/channel context ID + ID root message. Root i wspierane replies należą do jednej aktywnej sprawy. |
| `TELEGRAM_TOPIC` | Telegram chat z topics | Chat ID + topic/thread ID. Wiadomości tego samego topicu należą do jednej aktywnej sprawy. |
| `TELEGRAM_CHAT_ACTIVE_CASE` | Telegram chat bez topics | Chat ID bez thread key. W danym momencie istnieje najwyżej jedna aktywna sprawa dla chatu. |

Strategia jest konfigurowana per rekord Channel, ale jej wartość musi być zgodna z providerem i capability konkretnego kontekstu. Backend odrzuca niedozwoloną kombinację; nie stosuje cichego fallbacku do strategii innego providera.

Dokładna macierz wspieranych kontekstów Teams powstaje w capability POC E18-T01/USI-179. USI-34 zamraża semantykę `TEAMS_ROOT_REPLIES` dla kontekstów uznanych tam za wspierane, ale nie klasyfikuje private/shared channels ani nie obiecuje ich obsługi.

## Root, reply i aktywna sprawa

- Pierwszy event nowego klucza grupowania tworzy dokładnie jedną sprawę `NEW` i pierwszą wiadomość.
- Kolejne wiadomości tego samego klucza trafiają do istniejącej aktywnej sprawy.
- Zmiana konfiguracji Channel działa dla przyszłego inbound processing. Nie przepisuje historycznych spraw ani wiadomości.
- Providerowe ID pozostają danymi adaptera/persistence. Nie są odtwarzane z tekstu, nazwy kanału lub kolejności wiadomości.

Jeżeli ostatnia sprawa dla klucza jest terminalna, nowa wiadomość nie otwiera jej ponownie. Powstaje nowa sprawa `NEW`, powiązana z poprzednią przez kanoniczne relation pole. Następne wiadomości tego klucza trafiają do nowej aktywnej sprawy. Szczegółowe statusy terminalne i maszyna stanów są zamrożone w `CASE_WORKFLOW.md`; pozostają kontraktem workflow, nie regułą providera.

## Idempotency i concurrency

- Znormalizowany event ma stabilną providerową tożsamość i jest deduplikowany przed skutkami biznesowymi.
- Ponowne dostarczenie tego samego eventu nie tworzy drugiej wiadomości ani drugiej sprawy.
- Równoległe pierwsze eventy tego samego klucza nie mogą utworzyć dwóch aktywnych spraw.
- Wybór/utworzenie sprawy oraz przypisanie pierwszej wiadomości są spójne transakcyjnie. Provider HTTP call nie utrzymuje otwartej transakcji DB.
- Retry po awarii wykorzystuje zapisany stan inbound/idempotency, a nie heurystykę po treści wiadomości.

Dokładne constraints i mechanizm blokowania zostaną dobrane w migracji backendowej do query/race conditions. Nie mogą osłabić invariantów: jeden skutek eventu i najwyżej jedna aktywna sprawa na klucz grupowania.

## Docelowa persistence

- `channels.grouping_strategy` przechowuje jeden z dozwolonych kodów zgodny z provider capability;
- `cases.external_conversation_id` przechowuje stabilny identyfikator channel/chat/conversation;
- `cases.external_thread_key` przechowuje root/thread/topic identity albo jest puste dla `TELEGRAM_CHAT_ACTIVE_CASE`;
- relation do poprzedniej sprawy wskazuje terminalną generację bez zmiany jej historii;
- providerowe event ID chroni idempotentne przyjęcie wiadomości.

Nazwy wyświetlane kanałów i klientów nie uczestniczą w kluczu unikalności. Rename u providera nie tworzy nowego Channel ani nowego kontekstu grupowania, jeżeli stabilne external ID pozostaje to samo.

## Macierz fixture’ów i testów przyszłej implementacji

| Scenariusz | Oczekiwany wynik |
| --- | --- |
| Slack root | Jedna nowa sprawa dla channel + root key. |
| Slack reply | Wiadomość w aktywnej sprawie root threadu. |
| Teams root/reply we wspieranym kontekście | Jedna aktywna sprawa dla conversation + root key. |
| Telegram pierwsza wiadomość topicu / kolejna wiadomość topicu | Nowa sprawa / ta sama aktywna sprawa dla chat + topic. |
| Telegram bez topics: pierwsza / kolejna wiadomość | Nowa sprawa / ta sama jedyna aktywna sprawa chatu. |
| Wiadomość po terminalnej sprawie | Nowa powiązana sprawa; poprzednia pozostaje bez zmian. |
| Duplikat provider eventu | Brak dodatkowej wiadomości i sprawy. |
| Równoległe pierwsze eventy | Dokładnie jedna aktywna sprawa dla klucza. |
| Niedozwolona strategia/provider/context | Walidacja odrzuca konfigurację; brak cichego fallbacku. |

## Poza zakresem USI-34

- rzeczywiste adaptery, webhooki i poświadczenia providerów;
- implementacja inbound queue, bazy danych, migracji i OpenAPI;
- capability POC oraz klasyfikacja private/shared contexts Teams;
- implementacja terminalnej maszyny stanów i relation w UI;
- zmiana zaakceptowanego układu lub aktywowanie workflow w `/cases`.
