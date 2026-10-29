import test from "node:test";
import assert from "node:assert/strict";
import {checkEligibility, REQUIRED_JOBS, READY_LABEL} from "../ci_merge.mjs";

const sha = "a".repeat(40), base = "b".repeat(40);
const data = () => ({
  pull: {
    number: 42, state: "open", draft: false, mergeable: true,
    labels: [{name: READY_LABEL}],
    base: {ref: "main", sha: base, repo: {full_name: "Spodyn/ApplicationSupport"}},
    head: {sha, repo: {full_name: "Spodyn/ApplicationSupport"}}
  },
  run: {
    event: "pull_request", status: "completed", conclusion: "success",
    head_sha: sha, pull_requests: [{number: 42, head: {sha}, base: {sha: base}}]
  },
  jobs: REQUIRED_JOBS.map(name => ({name, status: "completed", conclusion: "success"})),
  reviews: [], threads: [], expectedBaseSha: base,
  ruleset: {enforcement: "active", rules: [
    {type: "pull_request", parameters: {required_approving_review_count: 0, allowed_merge_methods: ["squash"]}},
    {type: "required_status_checks", parameters: {required_status_checks: [{context: "validate"}]}}
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
