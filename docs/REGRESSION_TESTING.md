# Regression testing

The inventory in [REGRESSION_MATRIX.md](REGRESSION_MATRIX.md) distinguishes shipped behavior from unimplemented contracts. Its machine-readable companion is `config/regression-features.json`; `pnpm test:inventory` verifies unique feature IDs and existing test sources. A linked test is traceability, not proof that every scenario passes. Actual run evidence and outstanding acceptance are in [REGRESSION_VALIDATION.md](REGRESSION_VALIDATION.md).

## Run locally

Use a clean worktree, Node from `.nvmrc`, committed pnpm/Maven wrappers, Java 25 and Docker. Do not copy the development `.env`. On Windows invoke Maven with Git Bash; commands below are Bash syntax. PowerShell can use `& 'C:/Program Files/Git/bin/bash.exe' apps/api/mvnw -f apps/api/pom.xml ...` from the repository root.

```bash
pnpm install --frozen-lockfile
export NEXT_PUBLIC_API_BASE_URL=/api/v1 NEXT_PUBLIC_WS_BASE_URL=/ws
# Frontend lint, typecheck, unit, production build, isolated UI E2E + config/security/contract checks
pnpm check
pnpm test:coverage
python3 scripts/testing/changed_coverage.py --base-ref <exact-base-sha>
# Backend compilation, unit/module/architecture checks and observational JaCoCo report
cd apps/api && ./mvnw --batch-mode clean verify -Pcoverage -DexcludedGroups=integration && cd ../..
# Build only the pinned synthetic object-store image, then real disposable integrations
bash infra/minio/build-source-images.sh server
cd apps/api && ./mvnw --batch-mode test -Dgroups=integration && cd ../..
# Real frontend/backend, PostgreSQL, RabbitMQ, MinIO, loopback synthetic Slack HTTP
pnpm --filter @usi/web exec playwright install chromium
pnpm test:fullstack
# Longer checks; never overlap Maven clean/build with a running full-stack server
pnpm test:fullstack --repeat-each=3
python3 scripts/testing/mutation_smoke.py
pnpm audit --audit-level=high
cd apps/api && ./mvnw --batch-mode dependency:list -DoutputFile=target/security-dependencies.txt && cd ../..
python3 scripts/security_dependencies.py --maven-list apps/api/target/security-dependencies.txt
pnpm openapi:compat -- --base-ref <exact-base-sha>
git diff --check <exact-base-sha> HEAD
```

Python executable names vary on Windows; use `python` if `python3` is the Windows store alias. Run `python -m unittest discover -s scripts/tests -p 'test_*.py' -v` and verify a nonzero discovered test count. Linux CI uses `python3`. Docker Compose lifecycle checks run separately via `infra/tests/verify-compose-lifecycle.sh`, using its own randomized project/volumes; they must not operate on the developer's local project.

For a feature, run `pnpm --dir apps/web exec vitest run tests/unit/inbox-api-adapter.test.ts`, `./mvnw test -Dtest=MessagePersistenceIntegrationTests,MessageRetryPolicyTests` from `apps/api`, or `pnpm test:fullstack --grep archived`. A targeted check supplements the full pre-merge gates.

## Isolation and test boundaries

The full-stack launcher whitelists process environment variables, never loads `.env`, fails on occupied ports 3190/3191, starts fresh containers, and creates a separate random user/channel for each scenario. Browser cookies belong to fresh contexts. Production Next runs behind a loopback gateway with actual HTTP/WebSocket forwarding. Backend fixtures are on the test classpath only and never shipped in the application JAR. Shutdown hooks/Testcontainers Ryuk own cleanup. The Java launcher uses temporary dummy integration secrets, never customer credentials.

The multi-session full-stack server uses the production maximum of 20 datasource
connections; ordinary unit/integration tests retain their small four-connection
pool. Three browser sessions, realtime invalidations and durable workers must not
inherit unit-test resource sizing. The Snooze/SLA scenario also verifies 16 concurrent
attention reads, requiring HTTP 200 and the same SLA exclusion predicate for every
response. This is a bounded regression, not a production throughput certification;
connection timeouts and zero test retries remain unchanged.

Ordinary provider tests use synthetic payloads and HTTP servers. Full-stack tests exercise the real Slack HTTP client, signed intake, durable workers/outbox, RabbitMQ, persistence, actual STOMP frames and real MinIO bytes. Service-worker assertions use fetches from an actively controlled browser page and verify offline API refusal while static assets remain available. The malware scanner itself is a deterministic injected boundary; this does not validate a deployed ClamAV daemon. Existing scanner/SSRF/authentication regression tests remain required. `tests/e2e` still tests isolated UI interaction with API stubs; it is not counted as full-stack coverage. Both suites have zero retries and refuse reuse of developer servers. Poll observable results; do not synchronize with arbitrary sleeps.

## Gates and coverage

CI runs seven jobs in parallel: `configuration`, `backend`, `integration`, `infrastructure`, `openapi`, `web`, `fullstack`. `validate` uses `if: always()` and requires **all seven success results**. Its tests deliberately inject missing, skipped, cancelled and failing jobs and verify a nonzero process exit. PR and merge-group events run the same checks. No `continue-on-error`, path-skipped mandatory jobs or fake passing statuses are used. Formatting is checked for changed-line whitespace, not an unsolicited repository reformat.

OpenAPI mapping tests compare all Spring REST mappings in both directions (logout is a Security filter), exercise anonymous/ordinary-user denials, and read collection/settings response shapes. Existing feature suites and full-stack tests provide command success, state transitions, object authorization, pagination and idempotency coverage. This does **not** establish every possible success/error permutation for every route; gaps remain in the matrix. The additive contract expansion is generated deterministically and compatibility-checked against the exact PR base. Do not hand-edit generated clients.

V8 coverage thresholds apply only to five explicit security/configuration/auth/HTTP transport modules: lines 83%, statements 81%, functions 78%, branches 76%. The measured starting values are 83.78/81.25/78.26/76.84%. Changed executable lines within those LCOV modules must be covered; unrelated UI lines are not represented as measured. JaCoCo is observational until a meaningful backend baseline is reviewed. Mutation smoke compiles and runs real retry-policy tests in temporary source copies; three semantic mutants must fail assertions, while compilation/infrastructure errors do not count as killed mutations. These narrow measurements are not whole-application coverage claims.

Scheduled/manual `regression-extended.yml` repeats full-stack workflows without retries and runs mutation smoke. No live provider secrets are configured. Provider sandbox acceptance, load, disaster recovery and deployment acceptance remain separate release work; see [provider-sandbox.md](testing/provider-sandbox.md).

## Main protection and staged activation

On 2026-11-10, ruleset 21055765 was read, strengthened and read back: active, no bypass actors, pull requests required, squash only, no force pushes/deletion, six Actions-app-bound mandatory existing checks and strict up-to-date base. Before/after JSON is retained under `docs/testing/`. Owner review requirements were preserved and no draft PR was merged. The new mandatory `validate` aggregator covers `web`/`fullstack` after this workflow lands.

Do not add unknown `web`/`fullstack` required contexts to the current main ruleset before the stacked baseline can emit them. After both security PR #147 and this stacked change land, add these two contexts (GitHub Actions app 15368) under Settings → Rules → Rulesets → Protect main → Require status checks; keep strict mode and no bypass. Verify readback with `gh api repos/Spodyn/ApplicationSupport/rulesets/21055765`. The independent merge guard already requires all eight contexts plus strict mode, so autonomous integration remains fail-closed until activation is complete. `USI_AUTO_MERGE_ENABLED` is not enabled by this work.

## Owner UX regression additions (2026-11-10)

The language/bulk/interaction scope and retry contract are in [UX_BULK_I18N.md](UX_BULK_I18N.md); fresh command and CI evidence is maintained in [UX_VALIDATION.md](UX_VALIDATION.md). Run the existing pnpm check, Maven verify, full-stack and coverage/security gates. The added browser cases are part of the same mandatory suite, with no retries/skips or CI gate changes. The feature inventory now requires all 31 entries, including I18N, BULK and DIRECT_MESSAGES.

## Permanent audit traceability (2026-11-28)

The 37-feature inventory now uses schema version 2. Every feature links canonical/source components, stable test-suite identifiers, testing layers and mandatory CI jobs, positive/negative/boundary expectations, and verification evidence tied to an exact revision. Suite identifiers identify source suites, not a claim that every scenario is covered. Scenario definitions and live acceptance remain distinct from a green CI run. Update dated verification only when fresh evidence exists; do not replace NOT TESTED/BLOCKED with an assumption. The detailed live limitations are in [the functional audit](FUNCTIONAL_AUDIT_2026_11_28.md).

`node scripts/testing/check-inventory.mjs --base-ref <exact SHA>` validates source paths, suite or individual test anchors, unique scenario IDs and evidence provenance. Mandatory configuration CI compares against the authoritative push/PR/merge-group base. Removing a previously mapped test requires a `coverageChanges` entry with feature, removed path, reason and mapped replacement paths; an unexplained reduction fails closed. Assertions and security requirements must remain intact regardless of that bookkeeping.

Every new feature or defect fix must include appropriate automated regression tests in the same PR, update the feature inventory/matrix and pass all mandatory gates before independent integration. Reproduce confirmed defects with failing behavioral tests before correction.

Extended regression runs nightly at 03:23 UTC and remains manually dispatchable. Three independent full-stack repetitions and selective mutation tests run without retries. Ordinary push/PR CI keeps all eight jobs; critical tests have not moved to the extended workflow. Browser and full-stack evidence is uploaded on success and failure, including JUnit counts, HTML reports, exact tested Git revision, inventory snapshot and scoped web coverage. Synthetic runtime logs remain limited to the controlled test environment; no live credentials or authenticated staging payloads enter CI artifacts.
