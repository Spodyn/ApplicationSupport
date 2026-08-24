# Unified Support Inbox

Unified Support Inbox (USI) to produkcyjnie projektowana wspólna skrzynka wsparcia dla Slacka, Microsoft Teams i Telegrama. Obecny kod zawiera zaakceptowany frontend/mock baseline; docelowy system jest rozwijany jako full-stack modularny monolit zgodnie z zamrożonym kontraktem v1.

## Dokumentacja kanoniczna

Punktem wejścia jest [`docs/README.md`](docs/README.md). Najważniejsze źródła prawdy:

- [`docs/PRODUCT_CONTRACT.md`](docs/PRODUCT_CONTRACT.md) — normatywny kontrakt produktu/architektury,
- [`docs/PRODUCT_SPEC.md`](docs/PRODUCT_SPEC.md) — pełna specyfikacja funkcjonalna,
- [`docs/WORKFLOW_MATRIX.md`](docs/WORKFLOW_MATRIX.md) — workflow i action guards,
- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md), [`docs/INTEGRATIONS.md`](docs/INTEGRATIONS.md), [`docs/SECURITY.md`](docs/SECURITY.md), [`docs/OPERATIONS.md`](docs/OPERATIONS.md),
- [`docs/decision-registry.yaml`](docs/decision-registry.yaml) — machine-readable frozen decisions,
- [`docs/ORCHESTRATOR_CONTRACT.md`](docs/ORCHESTRATOR_CONTRACT.md) i [`docs/orchestrator-policy.yaml`](docs/orchestrator-policy.yaml) — autonomous development/review/merge policy.

Starsze dokumenty focused/current-state pozostają pomocnicze i nie mogą nadpisywać późniejszych finalnych decyzji.

## Scope v1

Kanały supportowe v1 to dokładnie:

- Slack,
- Microsoft Teams,
- Telegram.

E-mail jako kanał wsparcia i funkcje AI są poza v1. Adres e-mail użytkownika pozostaje daną identity.

## Uruchomienie obecnego frontendu

Wymagany jest Node.js 22.23.2 LTS (`.nvmrc`) i pnpm 11.18.0.

```bash
pnpm install --frozen-lockfile
pnpm dev
```

Frontend jest dostępny na `http://localhost:3000`.

## Kontrola jakości frontendu

```bash
pnpm exec playwright install chromium
pnpm check
```

`pnpm check` obejmuje lint, typecheck, testy jednostkowe, production build i Playwright E2E. Docelowy full-stack gate zostanie rozszerzony o Java/Maven, Testcontainers, Spring Modulith, Flyway, OpenAPI i security checks zgodnie z [`docs/TESTING.md`](docs/TESTING.md).

## Trasy obecnego frontendu

| Trasa | Przeznaczenie |
| --- | --- |
| `/` | Redirect do `/cases`. |
| `/cases` | Skrzynka spraw i rozmowa. |
| `/statistics` | Statystyki; docelowo również ADMIN-only sekcja **Aktualna praca**. |
| `/users` | Administracja użytkownikami. |
| `/settings` | Ustawienia systemu. |

Osobna top-level trasa `/current-cases` **nie należy do frozen v1**. Admin current-work jest rozstrzygnięty: UI `/statistics` -> **Aktualna praca**, backend `GET /api/v1/admin/current-cases`.

## Granica danych frontendu

```text
UI
 -> lib/services/queries.ts
 -> lib/services/registry.ts
 -> typed service interfaces
 -> mock lub production API adapter
 -> generated transport client (za adapterem)
```

Generated OpenAPI DTO nie są frontend domain modelami. Obowiązuje jeden jawny DTO -> stable domain/view mapper. Komponenty nie importują bezpośrednio z `mocks/`.

## Backend baseline

Frozen production baseline:

- Java 25 LTS,
- Spring Boot 4.1.x,
- Spring Modulith 2.1.x,
- PostgreSQL 18.x + Flyway,
- RabbitMQ 4.3.x,
- S3-compatible object storage / MinIO,
- WebSocket/STOMP,
- OpenTelemetry/Micrometer + Prometheus/Grafana,
- modular monolith,
- Docker/Compose + Terraform/Ansible; bez Kubernetes w v1.

## PWA i bezpieczeństwo

Service worker nie może cache'ować authenticated API responses. Produkcyjne secrets/provider tokens/password material nie trafiają do browser bundle, repo, logów ani audit. Codex/CI/testy nie mają dostępu do production DB, object storage ani provider credentials.

## Stan projektu

Decision freeze E00-E25 jest zakończony: nie ma obecnie znanego product/architecture decision gap blokującego implementację. Obecny brak backendu, realnych provider adapters i części workflow UI to **implementation backlog**, nie otwarte decyzje produktowe.

Autonomiczni agenci korzystają z frozen contract i dependency graph; produkcyjne wdrożenia oraz inne high-risk production actions nadal wymagają explicit human approval.