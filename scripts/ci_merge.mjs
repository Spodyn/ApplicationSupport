/**
 * USI exact-SHA CI merge guard.
 * The merge-ready label is an attestation by the *separate* orchestrator that
 * Jira dependencies, DoD, external verification and the exclusive lease were
 * checked. CI alone cannot establish those external facts.
 *
 * This script runs from the trusted default branch. NEVER checkout PR code.
 */
export const REQUIRED_JOBS = Object.freeze(["configuration", "backend", "integration", "infrastructure", "openapi", "validate"]);
export const READY_LABEL = "usi/merge-ready";
const EXPECTED_MAIN_RULESET_ID = 21055765;
// Bind eligibility to the actual repository CI workflow, not an arbitrary PR workflow.
const EXPECTED_CI_WORKFLOW_ID = 330478347;
const EXPECTED_CI_WORKFLOW_PATH = ".github/workflows/ci.yml";

/**
 * A PR label is only a routing hint, not evidence. The independent attestor
 * must publish an immutable, short-lived issue comment under a dedicated
 * trusted GitHub App bot identity. This runner never issues attestations.
 */
export const ATTESTATION_MARKER = "<!-- usi-pr-eligibility-v1 -->";
const ATTESTATION_CHECKS = Object.freeze([
  "jira_dependencies", "jira_dod", "external_verification", "security", "worker_lease"
]);
const MAX_ATTESTATION_MS = 10 * 60 * 1000;
const MAX_EVIDENCE_AGE_MS = 5 * 60 * 1000;

export function validateIndependentAttestation({pull, expectedBaseSha, comments, trustedAttestorId, now = Date.now()}) {
  const trustedId = Number(trustedAttestorId);
  if (!Number.isSafeInteger(trustedId) || trustedId <= 0) {
    return ["Trusted independent attestor identity is not configured"];
  }
  if (!Array.isArray(comments) || comments.length >= 100) {
    return ["Independent attestation comment history incomplete"];
  }
  const events = comments.filter(comment =>
    comment?.user?.type === "Bot" &&
    Number(comment.user.id) === trustedId &&
    typeof comment.body === "string" &&
    comment.body.trimStart().startsWith(ATTESTATION_MARKER)
  ).sort((a, b) => Date.parse(a.created_at) - Date.parse(b.created_at));
  if (!events.length) return ["No trusted bound Jira/DoD/lease attestation"];
  const latest = events.at(-1);
  const posted = Date.parse(latest.created_at);
  if (!Number.isFinite(posted)) return ["Attestation creation time invalid"];
  if (events.length > 1 && Date.parse(events.at(-2).created_at) === posted) {
    return ["Ambiguous trusted attestation events"];
  }
  // Edits are not accepted: publish a new trusted comment to revoke or renew.
  if (latest.updated_at !== latest.created_at) return ["Attestation comment was edited"];
  let proof;
  try {
    proof = JSON.parse(latest.body.trimStart().slice(ATTESTATION_MARKER.length).trim());
  } catch {
    return ["Malformed trusted attestation"];
  }
  const errors = [];
  if (proof?.schema !== "usi-pr-eligibility-v1" || proof?.status !== "ready") {
    errors.push("Trusted attestation is revoked or has an invalid schema/status");
  }
  if (proof?.pr_number !== pull?.number || proof?.head_sha !== pull?.head?.sha) {
    errors.push("Attestation PR/HEAD mismatch");
  }
  if (proof?.base_sha !== expectedBaseSha || proof?.base_sha !== pull?.base?.sha) {
    errors.push("Attestation base SHA mismatch");
  }
  if (!/^USI-[1-9]\d*$/.test(proof?.jira_key || "") ||
      !pull?.title?.includes(proof?.jira_key)) {
    errors.push("Attestation Jira key not bound to PR title");
  }
  const issued = Date.parse(proof?.issued_at);
  const expires = Date.parse(proof?.expires_at);
  const current = typeof now === "number" ? now : Date.parse(now);
  if (![issued, expires, current].every(Number.isFinite) ||
      Math.abs(posted - issued) > 120000 ||
      issued > current + 30000 || expires <= current ||
      expires <= issued || expires - issued > MAX_ATTESTATION_MS) {
    errors.push("Attestation expired, replayed, future-dated or not freshly issued");
  }
  for (const name of ATTESTATION_CHECKS) {
    const evidence = proof?.evidence?.[name];
    const checked = Date.parse(evidence?.checked_at);
    if (evidence?.result !== "PASS" ||
        typeof evidence?.reference !== "string" || evidence.reference.trim().length < 12 ||
        !Number.isFinite(checked) || !Number.isFinite(issued) ||
        checked < current - MAX_EVIDENCE_AGE_MS || checked > current + 30000) {
      errors.push("Missing or stale independent evidence: " + name);
    }
  }
  return errors;
}


export function checkEligibility({ pull, run, jobs, reviews = [], threads = [], ruleset, effectiveRulesets, expectedBaseSha, comments = [], trustedAttestorId, changedFiles, now }) {
  const errors = [];
  if (!pull || pull.state !== "open" || pull.draft) errors.push("PR is not open and ready");
  if (pull?.base?.ref !== "main") errors.push("PR does not target main");
  if (!pull?.head?.sha || !pull?.head?.repo?.full_name || pull.head.repo.full_name !== pull.base.repo.full_name) errors.push("External or unknown source repository");
  if (pull?.mergeable !== true) errors.push("PR conflicts or mergeability is unknown");
  const labels = new Set((pull?.labels || []).map(x => x.name));
  if (!labels.has(READY_LABEL)) errors.push("No independent Jira/DoD/lease eligibility attestation");
  errors.push(...validateIndependentAttestation({pull, expectedBaseSha, comments, trustedAttestorId, now}));
  for (const name of ["do-not-merge", "blocked", "security-blocker"]) {
    if (labels.has(name)) errors.push("Blocked by label: " + name);
  }
  if (!run || run.event !== "pull_request" || run.status !== "completed" || run.conclusion !== "success") errors.push("Exact successful pull_request CI run missing");
  if (run?.workflow_id !== EXPECTED_CI_WORKFLOW_ID || run?.path !== EXPECTED_CI_WORKFLOW_PATH || run?.name !== "CI") {
    errors.push("CI run is not from the trusted repository workflow");
  }
  // A PR can edit its own pull_request workflow to make six no-op jobs pass.
  // Never let such a workflow attest to its own security; require separate review.
  if (!Array.isArray(changedFiles) || changedFiles.length >= 100 ||
      changedFiles.some(path => typeof path !== "string" || path.startsWith(".github/workflows/"))) {
    errors.push("PR workflow changes or incomplete file inventory require independent review");
  }
  if (run?.head_sha !== pull?.head?.sha) errors.push("CI SHA != current PR HEAD");
  const belongs = (run?.pull_requests || []).some(x => x.number === pull?.number && x.head?.sha === pull.head.sha);
  if (!belongs) errors.push("CI run is not proven to belong to this PR and HEAD");
  const currentBaseProven = (run?.pull_requests || []).some(x =>
    x.number === pull?.number && x.head?.sha === pull?.head?.sha && x.base?.sha === expectedBaseSha
  );
  if (!currentBaseProven) errors.push("CI run is not proven against current main base SHA");
  if (run?.pull_requests?.some(x => x.number === pull?.number && x.base?.sha && x.base.sha !== expectedBaseSha)) errors.push("CI was tested against an outdated base");
  if (!expectedBaseSha || expectedBaseSha !== pull?.base?.sha) errors.push("Main base changed during qualification");
  for (const job of REQUIRED_JOBS) {
    const hits = (jobs || []).filter(x => x.name === job);
    if (hits.length !== 1 || hits[0].status !== "completed" || hits[0].conclusion !== "success") errors.push("Required job not green: " + job);
  }
  for (const job of jobs || []) {
    if (job.status !== "completed" || job.conclusion !== "success") errors.push("Non-green CI job: " + job.name);
  }
  for (const review of reviews) {
    if (review.state === "CHANGES_REQUESTED" && !review.dismissedAt) errors.push("Unresolved requested changes");
  }
  for (const thread of threads) {
    // Even outdated threads may contain unresolved security findings.
    if (!thread.isResolved) errors.push("Unresolved review thread");
  }
  // Inspect the inherited repository/organization ruleset inventory, not only
  // a single hard-coded ruleset. Fail closed if another active branch policy
  // appears until an independent integrator has reconciled its requirements.
  if (!Array.isArray(effectiveRulesets) || !effectiveRulesets.length || effectiveRulesets.length >= 100) {
    errors.push("Effective branch ruleset inventory unavailable or incomplete");
  } else {
    const activeBranchRulesets = effectiveRulesets.filter(x =>
      x?.target === "branch" && x?.enforcement === "active");
    if (activeBranchRulesets.length !== 1 ||
        activeBranchRulesets[0].id !== EXPECTED_MAIN_RULESET_ID) {
      errors.push("Effective active branch rulesets changed; independent policy reconciliation required");
    }
  }
  const prRule = ruleset?.rules?.find(x => x.type === "pull_request")?.parameters;
  const ciRule = ruleset?.rules?.find(x => x.type === "required_status_checks")?.parameters;
  if (ruleset?.enforcement !== "active" || !prRule) errors.push("Branch protection not verified");
  if ((prRule?.required_approving_review_count ?? 1) !== 0) errors.push("An effective human approval is still required");
  if (!prRule?.allowed_merge_methods?.includes("squash")) errors.push("Squash is not permitted");
  if (!ciRule?.required_status_checks?.some(x => x.context === "validate")) errors.push("Required validate check missing");
  // The merge API atomically checks HEAD but not main SHA. Strict GitHub
  // protection must reject a base race between our final read and PUT merge.
  if (ciRule?.strict_required_status_checks_policy !== true) errors.push("Strict up-to-date CI branch protection required for unattended merge");
  return errors;
}

const asJson = async (url, auth, opts = {}) => {
  const response = await fetch(url, {
    ...opts,
    headers: {
      Accept: "application/vnd.github+json",
      Authorization: "Bearer " + auth,
      "X-GitHub-Api-Version": "2022-11-28",
      ...opts.headers
    }
  });
  if (!response.ok) throw Error("GitHub HTTP " + response.status + " for " + url);
  return await response.json();
};

const pickCI = (runs, pr) => runs.find(run =>
  run.event === "pull_request" && run.head_sha === pr.head.sha &&
  run.workflow_id === EXPECTED_CI_WORKFLOW_ID && run.path === EXPECTED_CI_WORKFLOW_PATH && run.name === "CI" &&
  (run.pull_requests || []).some(p => p.number === pr.number && p.head?.sha === pr.head.sha)
);

export async function qualifyPR({ owner, repo, number, auth, trustedAttestorId }) {
  const api = "https://api.github.com/repos/" + owner + "/" + repo;
  const pull = await asJson(api + "/pulls/" + number, auth);
  // The PR base field can lag the real main ref. Read the authoritative ref.
  const mainRef = await asJson(api + "/git/ref/heads/main", auth);
  const currentMainSha = mainRef?.object?.sha;
  if (!/^[0-9a-f]{40}$/.test(currentMainSha || "")) throw Error("Current main ref could not be verified");
  const allRuns = await asJson(api + "/actions/runs?event=pull_request&head_sha=" + encodeURIComponent(pull.head.sha) + "&per_page=100", auth);
  const run = pickCI(allRuns.workflow_runs || [], pull);
  const jobs = run ? (await asJson(api + "/actions/runs/" + run.id + "/jobs?per_page=100", auth)).jobs : [];
  const reviews = await asJson(api + "/pulls/" + number + "/reviews?per_page=100", auth);
  const comments = await asJson(api + "/issues/" + number + "/comments?per_page=100", auth);
  const files = await asJson(api + "/pulls/" + number + "/files?per_page=100", auth);
  const effectiveRulesets = await asJson(api + "/rulesets?includes_parents=true&per_page=100", auth);
  const ruleset = await asJson(api + "/rulesets/" + EXPECTED_MAIN_RULESET_ID, auth);
  const graphql = await asJson("https://api.github.com/graphql", auth, {
    method: "POST",
    headers: {"Content-Type": "application/json"},
    body: JSON.stringify({
      query: "query($owner:String!,$repo:String!,$n:Int!){repository(owner:$owner,name:$repo){pullRequest(number:$n){reviewThreads(first:100){pageInfo{hasNextPage}nodes{isResolved isOutdated}}}}}",
      variables: {owner, repo, n: Number(number)}
    })
  });
  if (graphql.errors?.length) throw Error("Cannot verify review threads");
  const threadConnection = graphql.data?.repository?.pullRequest?.reviewThreads;
  if (!threadConnection || threadConnection.pageInfo?.hasNextPage) throw Error("Review-thread inspection incomplete");
  if (reviews.length === 100) throw Error("Review pagination incomplete");
  if (comments.length === 100) throw Error("Attestation comment pagination incomplete");
  if (jobs.length === 100) throw Error("CI job pagination incomplete");
  if (!Array.isArray(files) || files.length >= 100) throw Error("PR file inventory incomplete");
  const errors = checkEligibility({
    pull, run, jobs, reviews, threads: threadConnection.nodes,
    ruleset, effectiveRulesets, expectedBaseSha: currentMainSha, comments, trustedAttestorId,
    changedFiles: files.map(file => file.filename)
  });
  return {errors, pull, run};
}

export async function main(env = process.env) {
  const auth = env.GH_TOKEN;
  const number = Number(env.USI_PR_NUMBER);
  const repository = env.GITHUB_REPOSITORY || "";
  if (!auth || !Number.isInteger(number) || number <= 0 || !/^[^/]+\/[^/]+$/.test(repository)) throw Error("Missing GitHub inputs");
  const [owner, repo] = repository.split("/");
  const trustedAttestorId = Number(env.USI_ATTESTOR_USER_ID);
  const check = await qualifyPR({owner, repo, number, auth, trustedAttestorId});
  if (check.errors.length) {
    console.log("BLOCKED PR #" + number + ": " + check.errors.join("; "));
    return; // Fail closed: never auto-merge this PR
  }
  // Deliberate staged rollout: enable only after independently tested merge/Jira flow.
  if (env.USI_AUTO_MERGE_ENABLED !== "true") {
    console.log("QUALIFIED DRY RUN PR #" + number + " " + check.pull.head.sha);
    return;
  }
  // Requalify immediately before mutation to prevent stale HEAD/base/rule races.
  const latest = await qualifyPR({owner, repo, number, auth, trustedAttestorId});
  if (latest.errors.length || latest.pull.head.sha !== check.pull.head.sha || latest.pull.base.sha !== check.pull.base.sha) {
    throw Error("PR changed after gate evaluation; merge refused");
  }
  const api = "https://api.github.com/repos/" + owner + "/" + repo;
  const result = await asJson(api + "/pulls/" + number + "/merge", auth, {
    method: "PUT",
    headers: {"Content-Type": "application/json"},
    body: JSON.stringify({merge_method: "squash", sha: latest.pull.head.sha})
  });
  if (!result.merged || !result.sha) throw Error("GitHub did not confirm merge");
  console.log("MERGED PR #" + number + " " + result.sha + "; Jira update is a separate orchestrator responsibility");
}

if (process.argv[1]?.endsWith("/ci_merge.mjs")) {
  main().catch(error => { console.error(error); process.exitCode = 1; });
}
