# Regression implementation evidence — 2026-11-10

Implementation delivered in stacked draft [PR #148](https://github.com/Spodyn/ApplicationSupport/pull/148); final documentation/traceability commit is awaiting its own exact-HEAD CI. Branch `codex/regression-ci-gates-2026-11-10`, sibling worktree `ApplicationSupport-regression`, baseline security PR #147 HEAD `1791527b16418dfc437142585c3000dd790c1699`. Main/security PR remain unmerged and unchanged.

- [x] Reconcile main, security PR, clean local work and active ruleset.
- [x] Red/green gate-policy regression; currently 71 gate/cleanup/workflow tests pass, including actual nonzero process exit.
- [x] Real Spring route mapping: 83 runtime operations, 77 anonymous denials, 57 ordinary-user denials; filter logout adds operation 84.
- [x] Reconcile OpenAPI to 84 operations/116 schemas; deterministic binary/dictionary generation tests pass; compatibility with exact baseline is additive-only.
- [x] Four real frontend/backend/PostgreSQL/RabbitMQ/MinIO/synthetic-provider HTTP E2E passed; browser reload/reconnect, logout/deactivation, quarantine and archived retry included.
- [x] Add red/green archived retry projection, quarantined download and controlled-clock Snooze replay regressions. Preserve repairable retry policy.
- [x] Scoped V8 coverage: lines 83.78%, statements 81.25%, functions 78.26%, branches 76.84%; measured floors enforced. Changed scoped executable lines covered.
- [x] Nine retry policy assertions pass; three compiling semantic mutations killed. No build error counted as mutation kill.
- [x] 56 configuration/security Python tests pass on Windows and Linux; zero-discovery success is now rejected; real Windows junction preserves alias-boundary assertion.
- [x] Existing browser suite: 18 passed with zero retries. Frontend unit suite: 80 passed; lint/typecheck/build passed with explicit synthetic public environment.
- [x] Frozen install and npm audit passed (no known vulnerabilities). OpenAPI lint/generator/client/adapters and YAML/actionlint passed.
- [x] Wire seven parallel CI jobs + always-running fail-closed validate aggregator; PR/merge_group triggers; separate repeated fake-provider/mutation workflow.
- [x] Strengthen and read back active main ruleset: six existing Actions-bound checks, strict base, no bypass, preserved owner review/squash policy. JSON snapshots retained. Redundant web/fullstack contexts staged until workflows land; autonomous merge remains disabled.
- [x] Final full backend `clean verify -Pcoverage`: 501 tests / 111 suites, zero failures/errors/skips; JAR built; architecture and safe query-parameter 400 regressions pass. Local observational JaCoCo: 6,735/9,079 lines and 2,435/4,658 branches; CI unit-only report has a different scope.
- [x] Final real-stack suite repeated three times: 12/12 passed in 1.5 minutes, zero retries; graceful shutdown removed all three disposable data services.
- [x] Full local `pnpm check` passed with explicit synthetic public environment; full-history secret scan, all config/generator/client/adapter, lint/typecheck/build/unit/UI E2E included.
- [x] Maven OSV scan: 247 packages, no findings.
- [x] Three grouped implementation commits pushed; PR #148 targets security baseline #147. CI run [42727434036](https://github.com/Spodyn/ApplicationSupport/actions/runs/42727434036) on implementation HEAD `78187ff58d5c1cdbd0d5fc2adbb475fcb1130db8`: all eight jobs succeeded; matching push CI succeeded too.
- [ ] Final report/traceability commit exact-HEAD CI verification. This line is an activation checklist, not an assertion that a future SHA was tested.

Known red evidence: initial archived-error UI and quarantine HTTP test failed (2/4); Snooze replay failed after deadline; malformed query parameters and limit=0 returned 500; architecture test rejected an initial root-to-storage exception dependency (moved translation to storage HTTP controller). Existing UI fixture now returns the same server retry availability as the backend after QUEUED. No assertions removed or retries raised.

A complete first backend pass ran all existing/new suites and found only the initial architecture cycle and a test schema-helper nullable-array handling error; these were corrected before final validation. Do not count that run as green. An initial pnpm check without required browser public environment stopped at configuration; explicit-environment build and browser checks pass separately. Final CI remains authoritative.

Remaining limits: not every per-route success/invalid/object-state permutation is covered by HTTP; read/denial coverage supplements feature suites. Full provider sandboxes, deployed ClamAV, load/DR/backup acceptance and physical-device PWA require separate authorized environments. Teams and parts of retention/Telegram outbound remain incomplete on this baseline; see matrix. No live provider test was run or message resent.

Existing owner services/data remain untouched in the original checkout. New real-stack services use disposable Testcontainers and a test-classpath-only HTTP provider. No developer .env/provider account is loaded. Jira connector availability was insufficient for live ticket lookup; current explicit user regression request and frozen repository contracts govern this work.

The additional inventory regression rejects removal of a required feature, an empty inventory and missing/out-of-repository test links; all four workflow/traceability tests also passed in an isolated Linux Node 22 container. Provider documentation distinguishes implemented Telegram inbound from absent outbound/Teams behavior.
