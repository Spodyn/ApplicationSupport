# Kontrakt grupowania spraw providerów

**Stan: 24 sierpnia 2026 - FROZEN.** Provider adapter uwierzytelnia/normalizuje event, a provider-neutral core rozstrzyga Channel, conversation identity oraz aktywną/terminalną generację Case.

## Reguły wspólne

1. Zweryfikuj provider callback i dedup identity.
2. Rozwiąż aktywną Integration i Channel.
3. Jeśli Integration jest `DISABLED` albo Channel jest `ignored`, nie twórz Case/Message/SLA; zachowaj odpowiedni techniczny inbound outcome.
4. Wylicz stabilny provider conversation/thread key zgodnie ze strategią.
5. Znajdź dokładnie jeden aktywny Case albo atomowo utwórz `NEW`.
6. Po terminalnym `RESOLVED`/`IGNORED` nie reopenować - pierwsza kolejna customer message tworzy jeden linked `NEW` Case.
7. Duplikaty i równoległe first events nie mogą tworzyć podwójnych Message/Case.

UI nigdy nie implementuje root/thread/topic grouping.

## Strategie v1

| Kontekst | Grupowanie |
|---|---|
| Slack public/private/Connect channel | Channel + root message/thread identity = jeden aktywny Case. |
| Teams standard channel | Standard channel context + root post = jeden aktywny Case z replies. |
| Teams group chat | Dokładnie jeden aktywny Case per group chat. |
| Telegram forum topic | Chat + topic/thread ID = jeden aktywny Case. |
| Telegram chat bez topics | Dokładnie jeden aktywny Case per chat. |

Teams private/shared channels są poza v1. Aktualne vendor SDK/scopes są technicznym wyborem, ale scope produktu pozostaje zamrożony.

## Ignored Channel

`channel.ignored=true` jest per-Channel i działa tylko dla przyszłych inbound events. Request nadal przechodzi auth/dedup, `inbound_events` zapisuje `IGNORED_BY_CHANNEL`, ale nie powstaje Case, Message, SLA ani read-state. Existing Case history nie jest przepisywana, a ignorowany inbound nie jest pokazywany jako zwykła wiadomość w inboxie.

## Terminal linking

Jeżeli ostatni Case danego grouping key jest terminalny, pierwsza kolejna customer message tworzy nową generację `NEW` z `related_case_id` wskazującym bezpośrednio poprzedni Case. Kolejne równoległe/seryjne wiadomości muszą trafić do tej samej nowej generacji. Terminalnego Case nie zmieniamy.

## Concurrency/idempotency

- Provider event identity jest stabilna i deduplikowana przed business effect.
- At-most-one active Case invariant dla grouping key jest egzekwowany transakcyjnie/constraintem/lockingiem odpowiednim dla query shape.
- Retry korzysta z trwałego inbound/idempotency state, nie z heurystyk tekstowych.
- Provider HTTP nie jest wykonywany w otwartej transakcji DB.

Dokładne constraint/index/locking implementation jest delegowaną decyzją techniczną, o ile powyższe invariants są testowane.
