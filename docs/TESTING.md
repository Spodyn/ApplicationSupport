# Testowanie i CI

**Stan:** current frontend gate + frozen v1 testing contract, 24 sierpnia 2026.

## Obecny frontend gate

Aktualne repo używa Node.js 22.23.2, pnpm 11.18.0, Vitest i Playwright Chromium. Instalacja:

```bash
pnpm install --frozen-lockfile
```

Dostępne komendy:

- `pnpm lint`
- `pnpm typecheck`
- `pnpm test:unit`
- `pnpm build`
- `pnpm test:e2e`
- `pnpm check`

Kontrakt konfiguracji i secret hygiene mają niezależny gate bez zależności Node:

```bash
python3 -m unittest discover -s scripts/tests -p 'test_*.py'
python3 scripts/validate_environment.py api --env-file .env.example
python3 scripts/validate_environment.py web --env-file apps/web/.env.example
python3 scripts/secret_scan.py
```

Testy chronią profile `local/test/staging/production`, brakujące i niepoprawne
wartości, wymagane pliki config tree, redakcję błędów, allowlistę
`NEXT_PUBLIC_*`, reguły `.gitignore` oraz brak sekretów w drzewie/historii.

Obecne E2E korzystają z lokalnego Next.js/mock data, blokują zewnętrzne HTTP i wyłączają service worker dla deterministyczności.

## Zasada nadrzędna

Test historycznego mocka nie może utrwalić zachowania sprzecznego z finalnym freeze. Gdy ticket implementuje realny backend/API flow, test musi zostać zaktualizowany do kanonicznego kontraktu.

Przykład: obecny mock może usuwać badge unread przy samym otwarciu rozmowy. Frozen v1 wymaga jednak, aby samo otwarcie **nie oznaczało read**; read-position przesuwa się dopiero po actual render/seen i acknowledgement. Przy wdrożeniu E10/realnego `/cases` test ma chronić frozen behavior, nie fixture.

## Frontend contract tests

Testy powinny chronić co najmniej:

- dokładnie sześć CaseStatus,
- dokładnie trzy support channels v1,
- role `USER`/`ADMIN` i dziewięć permissions,
- DTO -> stable domain/view mapper,
- server-calculated workflow action availability,
- personal read/snooze isolation,
- loading/empty/error/401/403/409/provider-failure states,
- zaakceptowany visual baseline bez niezamierzonego redesignu.

## Backend gate po uruchomieniu Spring Boot

Docelowy CI rozszerza frontend gate o:

1. Java 25 / Maven Wrapper compile + tests,
2. Spring Modulith `ApplicationModules.verify()`,
3. backend unit/module tests,
4. PostgreSQL/RabbitMQ/object-storage integration tests przez Testcontainers,
5. Flyway clean migration + upgrade validation,
6. OpenAPI lint/generation/compatibility i TypeScript compile,
7. concurrency/idempotency tests,
8. required security checks.

Testy nigdy nie korzystają z production DB, storage ani provider credentials.

## Workflow i concurrency

Wymagane są deterministyczne testy m.in. dla:

- pełnej transition matrix i owner invariants,
- 20 równoległych Claim -> dokładnie jeden winner,
- Ignore weight snapshot/idempotent duplicate/reset/lifetime voter restriction,
- Ask Customer pozostaje `VERIFICATION` do provider `SENT`,
- permanent Ask delivery failure nie przełącza do WAITING,
- normal Reply/Resolve tylko current owner,
- reassign/unassign/force-resolve permissions,
- terminal Case nigdy nie reopen; nowa customer message tworzy dokładnie jeden linked Case,
- retry/idempotency bez duplicate business effects.

## Read/unread i Snooze

Pokryć:

- sparse per-user read state + new-user baseline,
- customer new/edit -> unread dla eligible users,
- samo otwarcie bez read acknowledgement -> nadal unread,
- read acknowledgement po actual render/seen,
- privacy stanów innych users,
- Snooze per-user, bez zmiany status/owner/SLA,
- wake/clear przez customer message, terminal state i Claim.

## Provider integrations

Każdy adapter ma używać fake/sandbox providera i testować:

- auth/signature/secret verification,
- durable ingest przed async processing,
- dedup provider retries,
- grouping i post-terminal linking,
- ignored/unmapped/disabled contexts bez niedozwolonych business records,
- edit/delete semantics,
- transient/permanent outbound failures i Retry-After,
- recovery/resync bez pełnego history importu.

Aktualne vendor API paths/scopes/SDK details są weryfikowane względem oficjalnej dokumentacji przy implementacji; product semantics pozostają frozen.

## Attachments/security

Pokryć limity 25 MiB/file, 10 plików, 50 MiB/message, provider lower limit, declared+detected MIME, malware states, archive limits, path traversal, unauthorized cross-case download, orphan cleanup oraz brak publicznego/trwałego credential-bearing URL.

## SLA/business hours/notifications

Testy muszą używać kontrolowanego czasu, bez flaky sleeps, i pokryć:

- first response/unclaimed/in-progress business-time clocks,
- pause/resume z zachowaniem elapsed,
- policy/schedule/timezone snapshot,
- DST, weekly intervals, date exceptions i `NO_FUTURE_OPENING`,
- OOO once per Case/closure,
- monotonic warning/breach,
- notification dedup/retry/DLQ/suppression/recheck-enabled.

## Auth/security

Pokryć kontrakt z `AUTHENTICATION.md` i `SECURITY.md`: cookie/session, CSRF, rate limits/backoff, invite/reset, bootstrap, permissions, content sanitizer, SSRF, forwarded headers, secret redaction i security headers.

Actionable Critical/High security finding blokuje merge zgodnie z frozen security policy.

## Retention, DR i performance

Testy implementacyjne obejmują:

- wszystkie retention defaults/ranges i purge semantics,
- legal hold oraz purge po restore,
- backup restore evidence,
- race/resilience failures,
- acceptance load 200 users/WS, 5k Cases/day, burst 100 inbound/s przez 60 s,
- 24h soak przed production acceptance.

## Completion rule

Ticket nie jest gotowy, jeśli wymagany gate jest czerwony. Reviewer ocenia dokładny HEAD SHA. Zmiana HEAD po review wymaga ponownej walidacji/review zgodnie z agent/orchestrator contract.
