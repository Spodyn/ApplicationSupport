# CI-gated integration (USI-247)

The **integrator**, not the developer worker, owns the final `main` merge and Jira transition. The user has approved replacing routine human reviewer assignment with complete automated checks, without weakening the frozen product/security/release contract.

## Trust and activation

- Trigger: completed `CI` workflow for a PR, `usi/merge-ready` label application, ready-for-review, or a manual workflow dispatch.
- Trusted code: `.github/workflows/usi-integration.yml` checks out **main**, not the feature branch, and executes `scripts/ci_merge.mjs`. It never runs PR code with write credentials.
- GitHub job checks six exact-HEAD CI jobs: `configuration`, `backend`, `integration`, `infrastructure`, `openapi`, `validate`; all must finish successfully. Any extra failing/pending job blocks.
- The PR must be open, non-draft, mergeable, same-repository, target `main`, with CI bound to its current SHA and current base, and without unresolved review/security findings. GitHub's effective ruleset must permit squash and no approval requirements may remain.
- An independent trusted orchestrator must set `usi/merge-ready` **only after checking live Jira dependencies, any required external/manual acceptance, DoD, security findings and an exclusive worker lease.** This label is not a reviewer approval. **Do not apply it on the basis of green CI alone.** PR authors should not self-attest. Remove the label when any of these facts changes.

### Bound independent eligibility evidence (security hold)

The label `usi/merge-ready` is **not sufficient**. Before the trusted-main runner may qualify a PR, an independent attestor must publish a new, **unedited** top-level PR issue comment using a **dedicated GitHub App bot account**. The runner checks that comment's GitHub-supplied `user.id` and `user.type=Bot` against the repository Actions variable `USI_ATTESTOR_USER_ID`. An unset/invalid ID, unknown issuer, edited comment, missing history, duplicate newest timestamp, malformed JSON or stale evidence blocks merging. Do **not** configure a generic developer account or shared `github-actions[bot]` as the trusted issuer.

The comment starts with the exact marker `<!-- usi-pr-eligibility-v1 -->` followed by one JSON object. Its schema is:

```json
{
  "schema": "usi-pr-eligibility-v1",
  "status": "ready",
  "pr_number": 42,
  "jira_key": "USI-247",
  "head_sha": "<exact 40-hex PR head>",
  "base_sha": "<exact 40-hex main base>",
  "issued_at": "<UTC ISO timestamp>",
  "expires_at": "<UTC ISO timestamp, at most 10 minutes later>",
  "evidence": {
    "jira_dependencies": {"result": "PASS", "reference": "<durable audit reference>", "checked_at": "<UTC ISO timestamp>"},
    "jira_dod": {"result": "PASS", "reference": "<durable audit reference>", "checked_at": "<UTC ISO timestamp>"},
    "external_verification": {"result": "PASS", "reference": "<durable audit reference>", "checked_at": "<UTC ISO timestamp>"},
    "security": {"result": "PASS", "reference": "<durable audit reference>", "checked_at": "<UTC ISO timestamp>"},
    "worker_lease": {"result": "PASS", "reference": "<authoritative lease-store audit reference>", "checked_at": "<UTC ISO timestamp>"}
  }
}
```

This is **schema documentation, not a real attestation**. The independent attestor must actually read current Jira dependency/DoD and external acceptance evidence, security findings, and authoritative active worker leases before issuing any PASS. Each evidence check must be no more than five minutes older than issuance. An explicit non-applicable acceptance condition may be recorded as a verified PASS **only when the Jira contract truly has no such mandatory condition**, with the reason in its audit reference. Unknown, unavailable, NOT_RUN or BLOCKED verification must never be converted to PASS.

A new trusted comment with `status: "revoked"` revokes previous readiness; the most recent trusted comment controls. Publish a new comment after every HEAD/base change or relevant Jira/security/lease change; never edit an old comment. The workflow re-reads the comment and PR immediately before merge. The runner validates the trusted GitHub issuer, SHA binding, expiry and evidence shape, **not** the underlying external facts; those remain the responsibility of a separately implemented and audited attestor.

**Additional current protection blocker (2026-10-29):** GitHub ruleset #21055765 `Protect main` has `strict_required_status_checks_policy: false` (confirmed via the live ruleset API). GitHub's merge REST API atomically checks the PR HEAD but not the main base SHA; a concurrent main update after the final check could otherwise race with merge. The trusted runner now requires `strict_required_status_checks_policy: true` and verifies the authoritative `refs/heads/main` SHA plus the CI run's exact base provenance. Only an authorized GitHub administrator may change the effective ruleset, after assessing CI impact; this PR does **not** change production branch protection. Classic branch-protection read remains 403 under the connected GitHub App, so its absence cannot be inferred.

**Activation remains blocked:** no dedicated verified attestor identity, authoritative Jira/lease evidence publisher, positive non-production merge/Jira handoff or authorized `USI_AUTO_MERGE_ENABLED=true` activation has been proven. This hardening does not enable unattended merging or branch deletion.

- The runner never declares Jira `Gotowe` itself. The orchestrator must verify the actual merged SHA in `main` and then update Jira only if all required DoD and verification are complete.
- Serialize all integration workflows through `usi-main-integration`. A changed SHA/base requalifies immediately before the merge API call.

The workflow is initially in **dry-run mode**: missing or non-`true` repository variable `USI_AUTO_MERGE_ENABLED` does not mutate anything, even when all gates pass. This is intentional until a dry run and an authorized non-production merge acceptance prove the complete GitHub/Jira workflow. With a repository administrator's authorization, set repository Actions variable `USI_AUTO_MERGE_ENABLED=true` to activate only after the above conditions are demonstrably met. If permissions or Actions variables are unavailable, keep dry-run mode and continue other independent Jira tickets.

The repository-level GitHub `allow_auto_merge` setting governs GitHub's separate built-in auto-merge feature. This workflow uses the normal protected-branch PR merge endpoint, and does not depend on that setting.

## Operational checks

1. Read current `main`, the PR head/base SHA, active rulesets and complete GitHub CI results. Reconcile Jira tickets, explicit dependencies, latest comments/overrides, unresolved reviews and required verification.
2. Confirm the applicable feature branch has no active lease/heartbeat and that integrating it will not collide with a second worker. No hidden `PASS` for missing tests.
3. Only the independent integrator adds `usi/merge-ready` when the **entire** gate is satisfied. Existing green but blocked PRs must remain unlabeled.
4. Check the workflow dry run and exact-head decision. Verify a non-production trial has the same permissions and normal squash path before enabling live merges.
5. After merge, verify `main` SHA and Jira state. Record the source PR HEAD, merged SHA and test outcomes; clear/recreate downstream branches as appropriate.
6. Never delete an active or unmerged branch without verifying its unique commits and the owning worker. Deletion requires a confirmed merge or explicit disposition and GitHub deletion privileges.

## Test contract

- `node --test scripts/tests/ci_merge.test.mjs` runs under the required `configuration` CI job.
- `python3 -m unittest discover -s scripts/tests -p 'test_*.py' -v` verifies machine-readable policy invariants.
- The GitHub Actions workflow checks the trusted merger tests before any potential write.
- The integration path and Jira handoff remain **NOT VERIFIED** until an actual end-to-end dry-run and non-production acceptance are recorded; unit tests alone cannot mark the operational ticket `Gotowe`.

## Limitations

GitHub REST/GraphQL verification is deliberately fail-closed for missing/paginated CI jobs, unknown review threads, old base SHAs, active review requests requiring resolution or missing rights. The `usi/merge-ready` label is an external evidence gate, not a magic proof of Jira readiness. Pending tests, invalid proofs or blocked verification should not stop independent development tickets.

## Automatic cleanup of merged source branches

`.github/workflows/usi-branch-cleanup.yml` listens for a successfully merged PR. It runs **only trusted `main` code** and deletes the feature branch only if `scripts/delete_merged_branch.mjs` confirms:

- the PR is actually closed **and merged** and its source repository is this repository;
- the branch belongs to a recognized USI feature-branch family, not `main` or another protected baseline;
- the existing branch ref still points to the **exact merged PR HEAD SHA**, proving no newer commits were added;
- no other open PR still uses the branch as its **source or base**, preserving stacked PRs;
- the complete open PR set is available; on pagination ambiguity the script fails closed.

**Safety hold (2026-10-29, USI-247):** GitHub PR/ref checks are insufficient to prove that an independent developer worker does not still hold a durable lease/heartbeat. Both the merged-source and historical cleanup paths now **fail closed** without explicit independently verified no-active-lease evidence. No authoritative lease checker is currently wired, so the trusted workflows retain branches even if the PR/ref/age gates pass. Do not substitute CI success, a human-readable comment, or an Actions variable for lease evidence. A later Jira-scoped change must implement a fresh branch/ref-bound lease lookup, negative tests, and a verified end-to-end cleanup before deletion can resume.

The cleanup is designed for newly merged PR branches, **not blind deletion of old agent/feature branches**. Old branches without provable merged PR provenance, or with active leases/work in progress, must be retained until reconciled. The native GitHub `delete_branch_on_merge` setting was observed to be disabled; this workflow provides a guarded alternative.

### Historical merged branches

`.github/workflows/usi-branch-prune.yml` runs after updates to `main` and on a daily schedule. It does **not** blindly delete all `agent/*` branches. Its implementation `scripts/prune_merged_branches.mjs` fetches the complete open and closed PR lists and the branch list, and only removes a branch if all are true:

- it matches the approved `agent/USI-*`, `codex/*`, `usi-*` or `ops/USI-*` feature patterns, is not protected and is not the default branch;
- the matching PR was merged **at least 7 days ago** and the branch still points to **exactly the recorded merged head SHA**;
- no open PR uses the branch as source or as the base of a stacked PR;
- pagination is complete and the branch ref is re-checked just before the deletion;
- no new commits have appeared. A branch that has diverged since merging is retained.

When verified lease evidence is unavailable, the sweep reports `workerLeaseVerification: NOT_RUN` and deletes 0 branches. Once a trustworthy worker-lease check is implemented and tested, the sweep may delete at most 40 branches per run. `workflow_dispatch` defaults to dry run, while post-main-push/scheduled sweeps can request live mode but remain blocked by the mandatory worker-lease safety hold. A separate unit suite `scripts/tests/prune_merged_branches.test.mjs` asserts negative, stale, protected, fork and stacked-PR cases. Failed REST requests do not get interpreted as deletions.

At the 29 October 2026 audit, 70 feature branches met the initial provenance/age/consumer criteria. A branch can have **more than one historical merged PR**: for example, `agent/USI-79` no longer matched merged PR #36, but did match the later merged PR #37. The sweep examines the complete merged PR history and only removes refs whose current HEAD matches a verified merged PR, with no open PR consumer. It never assumes every branch is garbage.
