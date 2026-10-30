import test from "node:test";
import assert from "node:assert/strict";
import {checkEligibility, changedPathsFromFiles, REQUIRED_JOBS, READY_LABEL, ATTESTATION_MARKER} from "../ci_merge.mjs";

const sha = "a".repeat(40), base = "b".repeat(40);

const now = Date.parse("2026-10-29T21:00:00.000Z");
const attestorId = 123456789;
const issuedAt = "2026-10-29T20:59:00.000Z";
const checkedAt = "2026-10-29T20:58:00.000Z";
const attestation = () => ({
  schema: "usi-pr-eligibility-v1",
  status: "ready",
  pr_number: 42,
  jira_key: "USI-247",
  head_sha: sha,
  base_sha: base,
  issued_at: issuedAt,
  expires_at: "2026-10-29T21:04:00.000Z",
  evidence: Object.fromEntries([
    "jira_dependencies", "jira_dod", "external_verification", "security", "worker_lease"
  ].map(name => [name, {
    result: "PASS", reference: "jira://USI-247/" + name,
    checked_at: checkedAt
  }]))
});
const comment = (proof = attestation(), createdAt = "2026-10-29T20:59:01.000Z") => ({
  user: {id: attestorId, type: "Bot"},
  created_at: createdAt, updated_at: createdAt,
  body: ATTESTATION_MARKER + "\n" + JSON.stringify(proof)
});
const editProof = (d, mutate) => {
  const proof = JSON.parse(d.comments[0].body.slice(ATTESTATION_MARKER.length).trim());
  mutate(proof);
  d.comments[0].body = ATTESTATION_MARKER + "\n" + JSON.stringify(proof);
};

const data = () => ({
  pull: {
    number: 42, title: "USI-247: Secure merge gate", state: "open", draft: false, mergeable: true,
    labels: [{name: READY_LABEL}],
    base: {ref: "main", sha: base, repo: {full_name: "Spodyn/ApplicationSupport"}},
    head: {sha, repo: {full_name: "Spodyn/ApplicationSupport"}}
  },
  run: {
    event: "pull_request", status: "completed", conclusion: "success",
    name: "CI", workflow_id: 330478347, path: ".github/workflows/ci.yml",
    head_sha: sha, pull_requests: [{number: 42, head: {sha}, base: {sha: base}}]
  },
  jobs: REQUIRED_JOBS.map(name => ({name, status: "completed", conclusion: "success"})),
  reviews: [], threads: [], expectedBaseSha: base,
  comments: [comment()], trustedAttestorId: attestorId, changedFiles: [], now,
  effectiveRulesets: [{id: 21055765, target: "branch", enforcement: "active"}],
  ruleset: {enforcement: "active", rules: [
    {type: "pull_request", parameters: {required_approving_review_count: 0, allowed_merge_methods: ["squash"]}},
    {type: "required_status_checks", parameters: {strict_required_status_checks_policy: true, required_status_checks: [{context: "validate"}]}}
  ]}
});

test("eligible only with complete six-job exact SHA and readiness attestation", () => {
  assert.deepEqual(checkEligibility(data()), []);
});
test("fails closed for a draft, mismatch, and missing eligibility label", () => {
  const d = data();
  d.pull.draft = true;
  d.run.head_sha = "c".repeat(40);
  d.pull.labels = [];
  assert.ok(checkEligibility(d).length >= 3);
});
test("rejects green jobs from a lookalike workflow or PR-modified CI definition", () => {
  const spoofedId = data();
  spoofedId.run.workflow_id = 999999999;
  assert.match(checkEligibility(spoofedId).join(" "), /trusted repository workflow/);

  const spoofedPath = data();
  spoofedPath.run.path = ".github/workflows/fake-ci.yml";
  assert.match(checkEligibility(spoofedPath).join(" "), /trusted repository workflow/);

  const spoofedName = data();
  spoofedName.run.name = "Pretend CI";
  assert.match(checkEligibility(spoofedName).join(" "), /trusted repository workflow/);

  const modifiedWorkflow = data();
  modifiedWorkflow.changedFiles = [".github/workflows/ci.yml"];
  assert.match(checkEligibility(modifiedWorkflow).join(" "), /independent review/);

  const modifiedOtherWorkflow = data();
  modifiedOtherWorkflow.changedFiles = [".github/workflows/usi-integration.yml"];
  assert.match(checkEligibility(modifiedOtherWorkflow).join(" "), /independent review/);

  const missingInventory = data();
  delete missingInventory.changedFiles;
  assert.match(checkEligibility(missingInventory).join(" "), /file inventory/);

  const regularCodeChange = data();
  regularCodeChange.changedFiles = ["apps/api/src/main/java/Example.java"];
  assert.deepEqual(checkEligibility(regularCodeChange), []);
});

test("trusted merge guard, policy, contract and nested agent instructions require independent review", () => {
  for (const path of [
    "scripts/ci_merge.mjs",
    "scripts/tests/ci_merge.test.mjs",
    "scripts/tests/test_orchestrator_policy.py",
    "scripts/delete_merged_branch.mjs",
    "scripts/prune_merged_branches.mjs",
    "scripts/tests/delete_merged_branch.test.mjs",
    "scripts/tests/prune_merged_branches.test.mjs",
    "scripts/secret_scan.py",
    "scripts/validate_environment.py",
    "docs/orchestrator-policy.yaml",
    "docs/ORCHESTRATOR_CONTRACT.md",
    "docs/PRODUCT_CONTRACT.md",
    "docs/PRODUCT_SPEC.md",
    "docs/WORKFLOW_MATRIX.md",
    "docs/ARCHITECTURE.md",
    "docs/INTEGRATIONS.md",
    "docs/SECURITY.md",
    "docs/OPERATIONS.md",
    "docs/DECISION_REGISTRY.md",
    "docs/decision-registry.yaml",
    ".github/CODEOWNERS",
    ".github/actions/ci-helper/action.yml",
    "AGENTS.md",
    "apps/web/AGENTS.md"
  ]) {
    const d = data();
    d.changedFiles = [path];
    assert.match(checkEligibility(d).join(" "), /independent review/, path);
  }
  const renamed = data();
  renamed.changedFiles = changedPathsFromFiles([{
    filename: "docs/archived-merge-guard.mjs",
    previous_filename: "scripts/ci_merge.mjs",
    status: "renamed"
  }]);
  assert.match(checkEligibility(renamed).join(" "), /independent review/);
  const normal = data();
  normal.changedFiles = ["apps/web/components/cases/cases-page.tsx"];
  assert.deepEqual(checkEligibility(normal), []);
});

test("renamed trusted cleanup or secret-scanner scripts still require independent review", () => {
  for (const oldPath of [
    "scripts/delete_merged_branch.mjs",
    "scripts/prune_merged_branches.mjs",
    "scripts/secret_scan.py",
    "scripts/validate_environment.py"
  ]) {
    const d = data();
    d.changedFiles = changedPathsFromFiles([{
      filename: "docs/retired-" + oldPath.split("/").at(-1),
      previous_filename: oldPath,
      status: "renamed"
    }]);
    assert.match(checkEligibility(d).join(" "), /independent review/, oldPath);
  }
});

test("renaming a workflow out of the protected directory cannot evade review", () => {
  const files = [{filename: "docs/archived-ci.yml", previous_filename: ".github/workflows/ci.yml", status: "renamed"}];
  const d = data();
  d.changedFiles = changedPathsFromFiles(files);
  assert.deepEqual(d.changedFiles, ["docs/archived-ci.yml", ".github/workflows/ci.yml"]);
  assert.match(checkEligibility(d).join(" "), /independent review/);
});

test("all required jobs, not just validate, must pass", () => {
  const d = data();
  d.jobs.find(j => j.name === "integration").conclusion = "failure";
  assert.match(checkEligibility(d).join(" "), /integration/);
  d.jobs = d.jobs.filter(j => j.name !== "integration");
  assert.match(checkEligibility(d).join(" "), /integration/);
});
test("blocks pending and unrecognized additional failing checks", () => {
  const d = data();
  d.jobs.push({name: "security", status: "completed", conclusion: "failure"});
  assert.match(checkEligibility(d).join(" "), /security/);
});
test("refuses old base, wrong PR or head, and fork", () => {
  const d = data();
  d.run.pull_requests = [{number: 99, head: {sha}}];
  assert.match(checkEligibility(d).join(" "), /not proven/);
  d.run.pull_requests = [{number: 42, head: {sha}, base: {sha: "c".repeat(40)}}];
  assert.match(checkEligibility(d).join(" "), /outdated base/);
  d.pull.head.repo.full_name = "untrusted/fork";
  assert.match(checkEligibility(d).join(" "), /External/);
});
test("fails closed on conflicting or unknown mergeability", () => {
  for (const mergeable of [false, null]) {
    const d = data();
    d.pull.mergeable = mergeable;
    assert.match(checkEligibility(d).join(" "), /mergeability/);
  }
});
test("blocks security, change requests and unresolved discussions", () => {
  const d = data();
  d.pull.labels.push({name: "security-blocker"});
  d.reviews.push({state: "CHANGES_REQUESTED"});
  d.threads.push({isResolved: false, isOutdated: false});
  const all = checkEligibility(d).join(" ");
  assert.match(all, /security-blocker/);
  assert.match(all, /requested changes/);
  assert.match(all, /Unresolved review thread/);
});
test("does not bypass required approvals, missing rules or invalid squash policy", () => {
  const d = data();
  d.ruleset.rules[0].parameters.required_approving_review_count = 1;
  assert.match(checkEligibility(d).join(" "), /approval/);
  d.ruleset.rules[0].parameters.allowed_merge_methods = ["merge"];
  assert.match(checkEligibility(d).join(" "), /Squash/);
  d.ruleset.enforcement = "disabled";
  assert.match(checkEligibility(d).join(" "), /Branch protection/);
});


test("fails closed when attestation is absent, issuer is unknown or untrusted", () => {
  const missing = data();
  missing.comments = [];
  assert.match(checkEligibility(missing).join(" "), /No trusted bound/);
  const unconfigured = data();
  unconfigured.trustedAttestorId = undefined;
  assert.match(checkEligibility(unconfigured).join(" "), /not configured/);
  const untrusted = data();
  untrusted.comments[0].user.id = 999;
  assert.match(checkEligibility(untrusted).join(" "), /No trusted bound/);
  const human = data();
  human.comments[0].user.type = "User";
  assert.match(checkEligibility(human).join(" "), /No trusted bound/);
});

test("invalidates a previously valid attestation after head or base changes", () => {
  const head = data();
  head.pull.head.sha = "c".repeat(40);
  head.run.head_sha = head.pull.head.sha;
  head.run.pull_requests[0].head.sha = head.pull.head.sha;
  assert.match(checkEligibility(head).join(" "), /Attestation PR\/HEAD mismatch/);
  const baseChange = data();
  baseChange.pull.base.sha = "d".repeat(40);
  baseChange.expectedBaseSha = baseChange.pull.base.sha;
  baseChange.run.pull_requests[0].base.sha = baseChange.pull.base.sha;
  assert.match(checkEligibility(baseChange).join(" "), /Attestation base SHA mismatch/);
});

test("rejects expired, replayed, future or excessively long-lived attestations", () => {
  const expired = data();
  expired.now = Date.parse("2026-10-29T21:05:00.000Z");
  assert.match(checkEligibility(expired).join(" "), /expired, replayed/);
  const long = data();
  editProof(long, proof => {proof.expires_at = "2026-10-29T22:00:00.000Z";});
  assert.match(checkEligibility(long).join(" "), /expired, replayed/);
  const future = data();
  editProof(future, proof => {proof.issued_at = "2026-10-29T21:10:00.000Z";});
  assert.match(checkEligibility(future).join(" "), /expired, replayed/);
});

test("future-dated attestation comment is rejected even if proof timestamps are otherwise fresh", () => {
  const future = data();
  future.comments[0].created_at = "2026-10-29T21:01:00.000Z";
  future.comments[0].updated_at = future.comments[0].created_at;
  assert.match(checkEligibility(future).join(" "), /creation time invalid or future-dated/);

  const toleratedClockSkew = data();
  toleratedClockSkew.comments[0].created_at = "2026-10-29T21:00:20.000Z";
  toleratedClockSkew.comments[0].updated_at = toleratedClockSkew.comments[0].created_at;
  assert.deepEqual(checkEligibility(toleratedClockSkew), []);
});

test("latest trusted revocation wins; edits and ambiguous events fail closed", () => {
  const revoked = data();
  revoked.comments.push(comment({...attestation(), status: "revoked"}, "2026-10-29T21:00:00.000Z"));
  assert.match(checkEligibility(revoked).join(" "), /revoked/);
  const edited = data();
  edited.comments[0].updated_at = "2026-10-29T21:00:00.000Z";
  assert.match(checkEligibility(edited).join(" "), /was edited/);
  const ambiguous = data();
  ambiguous.comments.push(comment(attestation()));
  assert.match(checkEligibility(ambiguous).join(" "), /Ambiguous/);
});

test("new PR discussion invalidates trusted evidence until a fresh attestation", () => {
  const later = data();
  later.comments[0].id = 100;
  later.comments.push({
    id: 101, user: {id: 99, type: "User"},
    created_at: "2026-10-29T20:59:30.000Z",
    updated_at: "2026-10-29T20:59:30.000Z",
    body: "New security finding: investigate before merge"
  });
  assert.match(checkEligibility(later).join(" "), /discussion changed after independent attestation/);

  const sameSecond = data();
  sameSecond.comments[0].id = 100;
  sameSecond.comments.push({
    id: 101, user: {id: 99, type: "User"},
    created_at: sameSecond.comments[0].created_at,
    updated_at: sameSecond.comments[0].created_at,
    body: "Potential blocker posted in the same second"
  });
  assert.match(checkEligibility(sameSecond).join(" "), /discussion changed after independent attestation/);

  const editedOldDiscussion = data();
  editedOldDiscussion.comments[0].id = 100;
  editedOldDiscussion.comments.push({
    id: 99, user: {id: 99, type: "User"},
    created_at: "2026-10-29T20:58:00.000Z",
    updated_at: "2026-10-29T20:59:30.000Z",
    body: "Edited after the proof to disclose a security concern"
  });
  assert.match(checkEligibility(editedOldDiscussion).join(" "),
    /discussion changed after independent attestation/);

  const renewed = data();
  renewed.comments[0].id = 100;
  renewed.comments.push({
    id: 101, user: {id: 99, type: "User"},
    created_at: "2026-10-29T20:59:30.000Z",
    updated_at: "2026-10-29T20:59:30.000Z",
    body: "Security finding independently reviewed and resolved"
  });
  renewed.comments.push({...comment(attestation(), "2026-10-29T20:59:41.000Z"), id: 102});
  assert.deepEqual(checkEligibility(renewed), []);
});

test("rejects incomplete evidence and partial comment pagination", () => {
  const d = data();
  editProof(d, proof => {delete proof.evidence.worker_lease;});
  assert.match(checkEligibility(d).join(" "), /worker_lease/);
  const stale = data();
  editProof(stale, proof => {proof.evidence.security.checked_at = "2026-10-29T20:00:00.000Z";});
  assert.match(checkEligibility(stale).join(" "), /security/);
  const partial = data();
  partial.comments = Array.from({length: 100}, () => comment());
  assert.match(checkEligibility(partial).join(" "), /history incomplete/);
});

test("rejects evidence that aged out since issuance even when attestation is unexpired", () => {
  const d = data();
  d.now = Date.parse("2026-10-29T21:03:30.000Z");
  assert.match(checkEligibility(d).join(" "), /Missing or stale independent evidence: jira_dependencies/);
  // Evidence dated after an already posted proof is not a real renewal.
  const fabricatedRenewal = data();
  fabricatedRenewal.now = d.now;
  editProof(fabricatedRenewal, proof => {
    for (const evidence of Object.values(proof.evidence)) {
      evidence.checked_at = "2026-10-29T21:02:00.000Z";
    }
  });
  assert.match(checkEligibility(fabricatedRenewal).join(" "), /Missing or stale independent evidence/);

  // A genuinely new attestation with checks performed before issuance is valid.
  const renewed = data();
  renewed.now = d.now;
  editProof(renewed, proof => {
    proof.issued_at = "2026-10-29T21:02:30.000Z";
    proof.expires_at = "2026-10-29T21:06:00.000Z";
    for (const evidence of Object.values(proof.evidence)) {
      evidence.checked_at = "2026-10-29T21:02:20.000Z";
    }
  });
  renewed.comments[0].created_at = "2026-10-29T21:02:31.000Z";
  renewed.comments[0].updated_at = renewed.comments[0].created_at;
  assert.deepEqual(checkEligibility(renewed), []);
});

test("rejects postdated evidence even while it appears fresh at merge time", () => {
  const d = data();
  d.now = Date.parse("2026-10-29T21:00:00.000Z");
  editProof(d, proof => {
    for (const evidence of Object.values(proof.evidence)) {
      evidence.checked_at = "2026-10-29T21:00:20.000Z";
    }
  });
  // Evidence is only 20 seconds in the future relative to now, but 80 seconds
  // after the immutable comment's issuance; it cannot have been audited then.
  assert.match(checkEligibility(d).join(" "), /Missing or stale independent evidence/);
});

test("requires a matching Jira key and unmodified attestation body", () => {
  const d = data();
  editProof(d, proof => {proof.jira_key = "USI-999";});
  assert.match(checkEligibility(d).join(" "), /Jira key/);
  const prefixCollision = data();
  prefixCollision.pull.title = "USI-247: unrelated ticket";
  editProof(prefixCollision, proof => {proof.jira_key = "USI-24";});
  assert.match(checkEligibility(prefixCollision).join(" "), /Jira key/);
  const ambiguousTitle = data();
  ambiguousTitle.pull.title = "USI-247 and USI-248: combined work";
  assert.match(checkEligibility(ambiguousTitle).join(" "), /Jira key/);
  const malformed = data();
  malformed.comments[0].body = ATTESTATION_MARKER + "\nnot-json";
  assert.match(checkEligibility(malformed).join(" "), /Malformed/);
});


test("refuses a stale PR base when the authoritative main ref has moved", () => {
  const d = data();
  d.expectedBaseSha = "e".repeat(40);
  assert.match(checkEligibility(d).join(" "), /Main base changed during qualification/);
  assert.match(checkEligibility(d).join(" "), /Attestation base SHA mismatch/);
});


test("rejects missing current-base CI provenance and non-strict GitHub protection", () => {
  const d = data();
  delete d.run.pull_requests[0].base.sha;
  assert.match(checkEligibility(d).join(" "), /not proven against current main/);
  const nonStrict = data();
  nonStrict.ruleset.rules[1].parameters.strict_required_status_checks_policy = false;
  assert.match(checkEligibility(nonStrict).join(" "), /Strict up-to-date CI/);
});

test("fails closed when inherited branch ruleset inventory is missing or changes", () => {
  const missing = data();
  delete missing.effectiveRulesets;
  assert.match(checkEligibility(missing).join(" "), /inventory unavailable/);
  const extra = data();
  extra.effectiveRulesets.push({id: 987654, target: "branch", enforcement: "active"});
  assert.match(checkEligibility(extra).join(" "), /rulesets changed/);
  const incomplete = data();
  incomplete.effectiveRulesets = Array.from({length: 100}, (_, id) =>
    ({id, target: "branch", enforcement: "evaluate"}));
  assert.match(checkEligibility(incomplete).join(" "), /inventory unavailable/);
});

test("blocks unresolved outdated review threads until explicitly resolved", () => {
  const d = data();
  d.threads.push({isResolved: false, isOutdated: true});
  assert.match(checkEligibility(d).join(" "), /Unresolved review thread/);
});

test("CI command, build harness and test-runner edits cannot self-qualify on green jobs", () => {
  const protectedPaths = [
    "package.json", "apps/web/package.json", "packages/api-client/package.json",
    "pnpm-lock.yaml", "pnpm-workspace.yaml", ".npmrc", ".nvmrc",
    "apps/api/pom.xml", "apps/api/mvnw", "apps/api/mvnw.cmd",
    "apps/api/.mvn/maven.config", "apps/api/.mvn/wrapper/maven-wrapper.properties",
    "apps/web/playwright.config.ts", "apps/web/vitest.config.ts",
    "apps/web/eslint.config.mjs", "apps/web/next.config.ts",
    "apps/web/tsconfig.json", "packages/api-client/tsconfig.build.json",
    "scripts/generate-openapi.mjs", "scripts/tests/test_secret_scan.py",
    "infra/tests/verify-compose-lifecycle.sh",
    "infra/minio/build-source-images.sh", "infra/compose.yaml", "infra/.env.example",
    ".gitattributes", "apps/api/src/main/resources/application-test.properties",
    "apps/api/src/test/java/com/unifiedsupportinbox/testing/TestInfrastructure.java",
    "apps/web/tests/unit/setup.ts"
  ];
  for (const path of protectedPaths) {
    const d = data();
    d.changedFiles = [path];
    assert.match(checkEligibility(d).join(" "), /independent review/, path);
  }

  // A rename can hide the old path in previous_filename; inspect both.
  const renamed = data();
  renamed.changedFiles = changedPathsFromFiles([
    {filename: "apps/web/src/harmless.ts", previous_filename: "apps/web/package.json"}
  ]);
  assert.match(checkEligibility(renamed).join(" "), /independent review/);

  const ordinary = data();
  ordinary.changedFiles = ["apps/api/src/main/java/example/Handler.java", "apps/web/components/example.tsx"];
  assert.deepEqual(checkEligibility(ordinary), []);
});
