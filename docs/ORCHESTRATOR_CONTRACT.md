# Autonomous Orchestrator Contract

**Status:** Owner-approved operational revision under USI-247 (2026-10-29); activation of CI-gated automatic merging remains separately gated. Frozen product semantics are unchanged.  
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

### Reviewer worker / automated quality gate

For standard development PRs, owner decision USI-247 (2026-10-29) replaces the target requirement to assign an independent human reviewer with **complete exact-HEAD automated quality gates**. No reviewer is assigned solely to unblock the queue; no fictional `APPROVE` event is created.

An independent reviewer may still be required by an *effective* GitHub ruleset, a protected security/production decision or an explicitly documented high-risk exception. If such a real approval is required and unavailable, keep the PR blocked and work on independent tickets. When a reviewer is actually used, its decision is bound to the exact reviewed HEAD and is one of `APPROVE`, `REQUEST_CHANGES` or `BLOCKED`. Neither a previous SHA approval nor a green status on another commit counts.

### Integrator / orchestrator worker

This worker is separate from the developer worker. It checks the entire active PR queue (including existing `Do przeglądu` PRs), confirms current branch ownership/lease, verifies every applicable check for the exact current SHA and merges only when the effective merge gate in §6 permits it. It records the merge SHA and only then updates Jira.

**Activation order:** (1) land the explicit authorized operational-policy change; (2) verify and where necessary update GitHub ruleset/branch protection and automation permissions; (3) validate an end-to-end non-production test PR of the same merge path; (4) enable unattended merging. Until all steps are proven, this document is the *target* process, not evidence that an unattended merge worker exists.

## 4. Jira lifecycle

Normal lifecycle:

`Do zrobienia -> W toku -> Do przeglądu -> Gotowe`

Rules:

- Move a ticket to `W toku` only when a worker actually starts it.
- Move to `Do przeglądu` only after a pushed PR exists and required available local validation has passed. A PR may remain there while awaiting mandatory external/acceptance verification.
- Move to `Gotowe` only after the exact checked change is merged to `main` **and** all applicable Definition of Done/mandatory verification is complete.
- Neither a decision-freeze comment nor green CI in isolation makes a ticket `Gotowe`. Never report `BLOCKED` or `NOT RUN` as `PASS`.

## 5. Branch and PR discipline

- one coherent Jira ticket per feature branch/PR unless the ticket explicitly groups work,
- branch name includes the real Jira key,
- work starts from current `origin/main`,
- unrelated redesign/refactor/dependency upgrades are excluded,
- generated files are changed only by their generation pipeline,
- no force-push/rebase as the default conflict strategy for active reviewed work.

## 6. Merge gate

**Target owner-approved mode (USI-247, 2026-10-29):** the integrator may squash-merge a standard development PR without manual reviewer assignment **only after** the authorized policy revision is effective, actual automation is verified and enabled, and all these conditions hold simultaneously:

1. The current PR HEAD SHA is precisely the SHA whose CI and security results were checked. Recheck after any push, change of base, or stale CI.
2. The complete required workflow is green at that SHA, **including configuration, backend, integration, infrastructure, OpenAPI and validate jobs where configured**; check runs are neither pending, skipped without justification, cancelled nor failed. The GitHub `validate` required status alone is not evidence the other required gates passed.
3. Required automated tests are meaningful (including appropriate negative/regression cases); no failing test was weakened or converted to skip to obtain green CI.
4. PR is mergeable, without conflicts or unresolved review/security findings, and all currently effective GitHub rulesets/branch protections are satisfied.
5. Required Jira dependencies are still `Gotowe`, and no new higher-precedence Jira/product contract invalidates the work.
6. No prohibited production operation, credential leak or Critical/High actionable security finding remains.
7. All **mandatory** verification and ticket DoD conditions are satisfied. Missing tenant/device/sandbox or real-provider acceptance evidence is `BLOCKED` / `NOT RUN`, not `PASS`; if it is mandatory for this PR's integration, keep this PR blocked while advancing independent tickets.
8. A required human approval under any still-effective policy, security exception or GitHub rule must actually exist on the exact HEAD. Do not self-approve, fabricate approval, or bypass protection.

No ordinary human review is mandated by the **target** policy once formally activated, but any review already issued with `REQUEST_CHANGES`, blocked thread or security concern must be resolved, not ignored. An existing `APPROVE` on a different SHA confers no authority.

If conditions are unmet, save the exact reason and checkpoint and move to another independent task. After merge, verify the actual commit in `main` and only then reconcile Jira. Do not confuse a successful workflow run with an actual merge.

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

- ticket/work item state, worker lease and heartbeat,
- branch/worktree identity, PR number/head SHA,
- exact-SHA CI/security results and any actual reviewed SHA/result,
- separately labelled `PASS`, `FAIL`, `NOT RUN` and `BLOCKED` checks,
- mandatory external/manual acceptance evidence and revisit conditions,
- remote mutation/idempotency markers and merge SHA.

After crash/restart reconcile GitHub/Jira/working-tree reality, checks and active leases before new mutations. Expired leases may be recovered only after verifying no running worker owns that worktree/ticket. A lease that cannot be verified is *not* evidence the worker stopped. Avoid duplicate PRs, tickets and comments.

## 9. Concurrency and scaling

Scale gradually based on throughput, conflict rate, CI quality and failed merge attempts:

1. One developer plus independent integrator with manual, exact-SHA gate audit; no unattended merges during dry run.
2. Verify the ruleset and automation identity/permissions; exercise one non-production test PR through the entire intended merge and Jira reconciliation flow.
3. Enable CI-gated automatic merges only after the test proves all guards; then add parallel developers with durable exclusive leases and heartbeat.

Two schedules may run concurrently; never share a ticket/branch/worktree without safe atomic ownership. An unverifiable active lease blocks *that* item only, not unrelated independent work.

## 10. Production boundary

Autonomous development/review/merge does **not** authorize production actions. Production deployment, PITR/destructive recovery, secret rotation, DNS/provider callback cutover and other high-risk actions require protected explicit human approval according to `OPERATIONS.md` and `SECURITY.md`.

Codex/reviewer workers never receive production credentials.

## 11. Provider technical freshness

Compatible vendor SDK/API version, scopes and endpoint details may be verified from current official vendor documentation during implementation without Product Owner escalation, provided the frozen provider scope, workflow and security boundary are unchanged.

## 12. Owner decision and deferred verification

The owner explicitly prefers continued independent implementation where a dependency is unavailable and CI-gated automatic processing of existing and new PRs without assigning a manual reviewer (29 October 2026, USI-247). This is an operational change only: frozen product behavior, security, Jira dependencies, real test failures and production protections remain mandatory. The active branch ruleset and automation implementation, not the statement of intent, determine whether unattended merge is truly enabled.

A blocked item records the missing verification, exact prerequisite to rerun it, and next independent ticket. Revisit deferred checks once the environment is available. Do not create false green acceptance or claim production readiness.

## 13. Completion evidence

For every completed ticket preserve:

- Jira key,
- feature branch and PR,
- merged commit/SHA,
- exact validated PR HEAD SHA and, if a real review was required, the reviewed SHA and result,
- required test/CI results,
- migration/API/event summary if applicable,
- remaining non-blocking risks.

This evidence is the basis for restart reconciliation and later audit.