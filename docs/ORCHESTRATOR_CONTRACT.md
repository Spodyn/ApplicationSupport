# Autonomous Orchestrator Contract

**Status:** FROZEN operational contract for autonomous development of USI v1.  
**Scope:** development/review/merge orchestration only; it does not grant production authority.

## 1. Source precedence

Every worker resolves requirements in this order:

1. current Jira ticket plus later `FINAL DECISION FREEZE`, `CONTRACT OVERRIDE` or `USER_DECISION_RESOLVED` comments,
2. E00 freeze and approved E01-E25 parent pre-flight decisions,
3. `PRODUCT_CONTRACT.md` + `decision-registry.yaml`,
4. canonical product/workflow/architecture/integrations/security/operations docs,
5. older non-conflicting docs and current implementation.

A historical `USER_DECISION_REQUIRED` is not a blocker after a later resolution.

## 2. Dependency graph

The orchestrator builds the graph from the current Jira snapshot rather than a static hard-coded ticket order.

Dependency evidence, in precedence order:

1. native Jira dependency/block links,
2. explicit `Dependencies` section in the ticket/parent contract,
3. reviewed manual override maintained by the orchestrator configuration.

Every dependency edge stores provenance: `native_link`, `description`, or `manual_override`.

A ticket is `READY` only when:

- its own Jira status is eligible for work,
- every required dependency is Jira `Gotowe`,
- no unresolved contract/security/production blocker exists,
- no other active lease owns the ticket,
- the ticket is not already represented by an active equivalent branch/PR.

The scheduler scans the whole DAG. It must not assume that epics are executed sequentially when independent work is ready.

Cycles are detected explicitly and block only the affected graph component until resolved.

## 3. Worker boundaries

### Developer worker

May:

- create/update the Jira-scoped feature branch/worktree,
- implement the smallest coherent ticket scope,
- run tests and validation,
- commit and push,
- create/update a PR,
- prepare a completion report.

Must not:

- merge its own PR,
- mark Jira `Gotowe`,
- alter frozen product semantics for convenience,
- use production credentials, production DB/storage or provider production secrets,
- perform production deployment/recovery/cutover.

Where practical, the developer worker should not receive Jira mutation or GitHub merge credentials at all.

### Decision worker

Resolves ordinary implementation choices autonomously using the source hierarchy. It escalates only a real contract change or protected production/legal/commercial decision as defined by `AGENTS.md` and `decision-registry.yaml`.

### Reviewer worker

Runs with fresh context and reviews the **exact current PR HEAD SHA**. It does not trust the developer's completion report as evidence.

Reviewer output is exactly one of:

- `APPROVE`
- `REQUEST_CHANGES`
- `BLOCKED`

The review covers ticket scope, frozen contract, diff, tests/CI, security, migration/API compatibility and unintended UI changes as applicable.

## 4. Jira lifecycle

Normal lifecycle:

`Do zrobienia -> W toku -> Do przeglądu -> Gotowe`

Rules:

- orchestrator moves a ticket to `W toku` only when a worker actually starts it,
- `Do przeglądu` only after a real pushed PR exists and required local validation has passed,
- `Gotowe` only after the exact reviewed change is actually merged to `main`,
- a decision freeze comment alone never makes an implementation ticket `Gotowe`.

Remote state changes are idempotent and reconciled after restart.

## 5. Branch and PR discipline

- one coherent Jira ticket per feature branch/PR unless the ticket explicitly groups work,
- branch name includes the real Jira key,
- work starts from current `origin/main`,
- unrelated redesign/refactor/dependency upgrades are excluded,
- generated files are changed only by their generation pipeline,
- no force-push/rebase as the default conflict strategy for active reviewed work.

## 6. Merge gate

A PR may be squash-merged only when all are true:

1. current PR HEAD SHA equals the SHA reviewed by the reviewer,
2. reviewer result is `APPROVE`,
3. all required validation/CI/security gates for that SHA are green,
4. PR is mergeable and has no unresolved review threads/blockers,
5. all graph dependencies are still Jira `Gotowe`,
6. no new higher-precedence Jira contract change invalidates the work,
7. branch contains no production secret or prohibited production operation.

After successful merge, the orchestrator verifies the merge result and only then moves Jira to `Gotowe`.

## 7. Conflict handling

Default recovery for an out-of-date/conflicting branch:

1. merge latest `main` into the feature branch,
2. resolve conflicts semantically against the frozen contract,
3. rerun required validation,
4. push the new HEAD,
5. require a full review of the new HEAD SHA.

Do not preserve an old approval after the reviewed SHA changes.

## 8. Failure and restart safety

Orchestrator state is durable (SQLite or equivalent) and includes at least:

- ticket/work item state,
- worker lease + heartbeat,
- branch/worktree identity,
- PR number/head SHA,
- last reviewed SHA/result,
- validation state,
- remote mutation/idempotency markers.

After crash/restart the orchestrator first reconciles GitHub/Jira/working-tree reality before creating any new branch, PR, comment or status transition. It must not duplicate business/workflow mutations merely because local state was lost.

Expired worker leases may be recovered only after verifying no still-running worker owns the same worktree/ticket.

## 9. Concurrency and scaling

Scale gradually based on measured throughput/conflicts/CI wait/review rejection and machine resource usage:

1. one DEV + reviewer, no automatic merge during dry run,
2. multiple DEV workers after lifecycle stability,
3. automatic merge only after exact-SHA review and merge-gate behavior has proven reliable.

More workers are not useful if they increase conflicts, flaky CI or review churn.

## 10. Production boundary

Autonomous development/review/merge does **not** authorize production actions. Production deployment, PITR/destructive recovery, secret rotation, DNS/provider callback cutover and other high-risk actions require protected explicit human approval according to `OPERATIONS.md` and `SECURITY.md`.

Codex/reviewer workers never receive production credentials.

## 11. Provider technical freshness

Compatible vendor SDK/API version, scopes and endpoint details may be verified from current official vendor documentation during implementation without Product Owner escalation, provided the frozen provider scope, workflow and security boundary are unchanged.

## 12. Completion evidence

For every completed ticket preserve:

- Jira key,
- feature branch and PR,
- merged commit/SHA,
- exact reviewed HEAD SHA,
- reviewer result,
- required test/CI results,
- migration/API/event summary if applicable,
- remaining non-blocking risks.

This evidence is the basis for restart reconciliation and later audit.