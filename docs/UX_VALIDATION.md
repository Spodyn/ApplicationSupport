# UX implementation verification

Scope and accepted boundaries: [UX_BULK_I18N.md](UX_BULK_I18N.md).
Base: main `ee1e84ff9e56d106b40a6afa1d902489063cd523` (PR #148).
Isolated worktree: `ApplicationSupport-ux`, branch `codex/ux-i18n-bulk-cases`.
The original checkout and existing local services are unchanged. No development
environment file or real customer/provider credential was copied into tests.

## Local evidence (2026-11-10)

Local validation was rerun after the independent review corrections described below. The exact final PR HEAD, required remote CI and merge evidence are recorded in [PR #149](https://github.com/Spodyn/ApplicationSupport/pull/149). All commands below actually ran in the isolated worktree.

| Command / gate | Result |
| --- | --- |
| `pnpm install --frozen-lockfile` | PASS |
| `pnpm check` with explicit `/api/v1` and `/ws` browser environment | PASS: 9 developer-tool tests, 71 gate tests, 30 required feature mappings, 56 configuration tests, environment/secret/OpenAPI checks, ESLint, TypeScript, 91 web unit tests, production Next build and 31 Chromium E2E |
| Maven Wrapper `clean verify -Pcoverage` | PASS: 509 tests, 0 failures/errors/skips, production JAR and JaCoCo report |
| `pnpm test:fullstack` | PASS: 5 real-stack Chromium scenarios; synthetic provider, PostgreSQL/RabbitMQ/MinIO, actual audit/realtime and duplicate bulk replay |
| `pnpm test:coverage` | PASS: 20 suites / 91 tests; existing scoped lines 83.78%, statements 81.25%, functions 78.26%, branches 76.84% |
| `python -X utf8 scripts/testing/changed_coverage.py --base-ref ee1e84ff9e56d106b40a6afa1d902489063cd523` | PASS; scope remains the existing five security/transport modules, not total application coverage |
| `pnpm openapi:check` and compatibility against exact base | PASS: additive v1 expansion, 85 operations / 119 schemas, deterministic client generation |
| `pnpm audit --audit-level=high` | PASS: no known vulnerabilities |
| Maven dependency list + `scripts/security_dependencies.py` | PASS: 247 resolved Maven packages, no OSV findings |
| Decision-registry YAML / unique IDs | PASS: 26 decisions; source precedence reviewed |
| Diff whitespace / documentation links | PASS; local relative references reviewed |
| Remote CI / independent integration | Exact-HEAD results and final merge evidence are recorded on PR #149; previous-head green CI is not accepted for a later revision |

Known red runs were corrected rather than treated as passes. The new backend
fixture initially lacked the PostgreSQL driver override, and its inactive vote
fixture lacked deactivated_at; both were corrected without changing constraints.
The first UI run had five failures: a pre-i18n error-text expectation, an
ambiguous provider-filter label, and three raw-URL tests expecting unsupported
automatic linkification beyond the existing safe Markdown limits. The label is
now explicit; tests assert localized errors and proper long safe Markdown links
as well as unbroken words/raw URLs, with existing parser security preserved.
The first combined gate found the translation label “Jednorazowy token” matching
the existing secret-assignment heuristic. Its catalog key is now a semantic
identifier; the secret scanner and all its tests remain unchanged.

Remote CI and merge evidence are recorded on the PR at its exact HEAD. Missing live Jira search (403) is recorded; no Jira mutation is claimed. Production/provider sandbox/physical-device acceptance remains separate
from this UX implementation and is not represented as automated PASS.

## Independent review corrections

The independent integrator found that forwarding an external bulk key into a single-Case command could replay that earlier command after a later unassign. Bulk now owns its distinct per-item idempotency transaction and invokes the existing business implementation through package-local `MANDATORY` transaction entry points. Regressions exercise both individual Claim and Assign followed by unassign and a bulk command with the same key, plus rejection of direct nontransactional calls. Existing individual public idempotent entry points remain intact.

Visible selection previously sliced silently at 1000; it now disables with an explicit bound message and guards additional checkbox selection. Overlapping rows are deduplicated before display/count/selection. All-matching enumeration has a finite 21-page budget even for fresh cursors returning only duplicates/empty pages. Unit and 1001-row browser regressions verify these conditions.

PWA install metadata now has static public Polish/English variants, cached separately without request/user data. The legacy Polish manifest URL remains compatible through an explicit GET route. A new browser assertion caught Next's automatic manifest link duplicating the localized link; replacing the auto-discovered manifest file with that legacy route resolved it. Full-stack tests verify both locale variants are precached, the English manifest works offline, and API responses remain absent from cache.

After these corrections: full `pnpm check` PASS (91 web unit, 31 browser tests), full Maven verify PASS (509), scoped coverage PASS, and all 5 real-stack tests PASS. No test was skipped or relaxed to hide an implementation failure.

## Implementation by requested task

1. Polish/English: feature catalogs, request-local providers, persistent selector, safe problem/validation labels, domain projections and Intl formatting; content/names/IDs unchanged.
2. Bulk Cases: explicit keyboard multi-selection, visible/all-matching controls, pagination snapshot, confirmation, bounded sequential Assign/Reassign/Resolve/Ignore/Claim and per-item results/replay; backend rules, audit/outbox and realtime reused.
3. Resolved gray: list/header/shared badges and status chart use neutral gray.
4. Buttons: common native/shared hover, focus-visible, pressed/disabled and existing pending states, with reduced motion.
5. Composer: Enter/Shift+Enter, IME/229/repeat protection, synchronous duplicate lock, attachments/draft retention and existing send/retry rules.
6. SLA: remove only the shortcut chevron; preserve current SLA attention filter and calculations.
7. Filters: remove Customer/Channel inbox controls/state; preserve API/model compatibility, search/pagination/counts and unrelated screens.
8. Long messages: constrain flex sizing and bubble/attachment widths; anywhere wrapping with pre-wrap, safe usable links and no horizontal document overflow at 1440/820/390 px.

Backend/database: one additive protected REST operation and generated DTO/client expansion; no migration, data rewrite, new permission or CaseStatus.

Limits: language preference is browser-specific; Select all matching supports at most 1000 enumerated IDs and backend requests at most 100. Replay uses the existing 24-hour idempotency window. Jira lookup is unavailable (403). Live provider, deployed malware scanner, load/DR and physical-device acceptance remain outside this UX scope; none is called PASS.

## Changed files

```text
apps/api/openapi/v1/openapi.json
apps/api/src/main/java/com/unifiedsupportinbox/identity/internal/BootstrapSecurityConfiguration.java
apps/api/src/main/java/com/unifiedsupportinbox/workflow/CaseAdministrationService.java
apps/api/src/main/java/com/unifiedsupportinbox/workflow/CaseBulkController.java
apps/api/src/main/java/com/unifiedsupportinbox/workflow/CaseBulkService.java
apps/api/src/main/java/com/unifiedsupportinbox/workflow/CaseClaimService.java
apps/api/src/main/java/com/unifiedsupportinbox/workflow/CaseIgnoreService.java
apps/api/src/main/java/com/unifiedsupportinbox/workflow/CaseResolveService.java
apps/api/src/test/java/com/unifiedsupportinbox/workflow/CaseBulkServiceIntegrationTests.java
apps/api/src/test/java/com/unifiedsupportinbox/workflow/CaseBulkServiceTests.java
apps/web/app/activate/page.tsx
apps/web/app/globals.css
apps/web/app/layout.tsx
apps/web/app/login/page.tsx
apps/web/app/manifest.webmanifest/route.ts
apps/web/app/pwa/manifest/[locale]/route.ts
apps/web/app/statistics/page.tsx
apps/web/app/users/page.tsx
apps/web/components/auth/credential-form.tsx
apps/web/components/auth/login-form.tsx
apps/web/components/cases/bulk-case-toolbar.tsx
apps/web/components/cases/cases-page.tsx
apps/web/components/design-system/confirm-dialog.tsx
apps/web/components/design-system/data-states.tsx
apps/web/components/design-system/filter-bar.tsx
apps/web/components/design-system/inbox-status-badge.tsx
apps/web/components/design-system/page-skeletons.tsx
apps/web/components/design-system/sla-indicator.tsx
apps/web/components/design-system/user-avatar.tsx
apps/web/components/layout/app-rail.tsx
apps/web/components/layout/app-shell.tsx
apps/web/components/layout/connection-status-banner.tsx
apps/web/components/layout/mobile-navigation.tsx
apps/web/components/layout/notification-bell.tsx
apps/web/components/layout/page-header.tsx
apps/web/components/layout/theme-toggle.tsx
apps/web/components/layout/user-menu.tsx
apps/web/components/providers.tsx
apps/web/components/settings/administration-settings-page.tsx
apps/web/components/ui/dialog.tsx
apps/web/components/ui/input.tsx
apps/web/components/ui/sheet.tsx
apps/web/components/ui/sonner.tsx
apps/web/components/ui/spinner.tsx
apps/web/lib/domain/administration.ts
apps/web/lib/format.ts
apps/web/lib/i18n/catalog.ts
apps/web/lib/i18n/en.json
apps/web/lib/i18n/errors.ts
apps/web/lib/i18n/pl.json
apps/web/lib/i18n/provider.tsx
apps/web/lib/pwa-manifest.ts
apps/web/lib/services/administration.ts
apps/web/lib/services/api/administration-settings-adapter.ts
apps/web/lib/services/api/inbox-adapter.ts
apps/web/lib/services/bulk-selection.ts
apps/web/lib/services/current-user.ts
apps/web/lib/services/inbox.ts
apps/web/lib/services/queries.ts
apps/web/package.json
apps/web/public/sw.js
apps/web/tests/e2e/application.spec.ts
apps/web/tests/fullstack/workflows.spec.ts
apps/web/tests/unit/bulk-inbox-adapter.test.ts
apps/web/tests/unit/bulk-selection.test.ts
apps/web/tests/unit/i18n.test.tsx
config/regression-features.json
docs/ARCHITECTURE.md
docs/CURRENT_STATE.md
docs/DECISION_REGISTRY.md
docs/PRODUCT_CONTRACT.md
docs/PRODUCT_SPEC.md
docs/README.md
docs/REGRESSION_MATRIX.md
docs/REGRESSION_TESTING.md
docs/UX_BULK_I18N.md
docs/UX_VALIDATION.md
docs/WORKFLOW_MATRIX.md
docs/decision-registry.yaml
packages/api-client/src/generated/client.gen.ts
packages/api-client/src/generated/types.gen.ts
pnpm-lock.yaml
scripts/testing/check-inventory.mjs
scripts/tests/ci_workflow.test.mjs
```
