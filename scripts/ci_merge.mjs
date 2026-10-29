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

export function checkEligibility({ pull, run, jobs, reviews = [], threads = [], ruleset, expectedBaseSha }) {
  const errors = [];
  if (!pull || pull.state !== "open" || pull.draft) errors.push("PR is not open and ready");
  if (pull?.base?.ref !== "main") errors.push("PR does not target main");
  if (!pull?.head?.sha || !pull?.head?.repo?.full_name || pull.head.repo.full_name !== pull.base.repo.full_name) errors.push("External or unknown source repository");
  if (pull?.mergeable !== true) errors.push("PR conflicts or mergeability is unknown");
  const labels = new Set((pull?.labels || []).map(x => x.name));
  if (!labels.has(READY_LABEL)) errors.push("No independent Jira/DoD/lease eligibility attestation");
  for (const name of ["do-not-merge", "blocked", "security-blocker"]) {
    if (labels.has(name)) errors.push("Blocked by label: " + name);
  }
  if (!run || run.event !== "pull_request" || run.status !== "completed" || run.conclusion !== "success") errors.push("Exact successful pull_request CI run missing");
  if (run?.head_sha !== pull?.head?.sha) errors.push("CI SHA != current PR HEAD");
  const belongs = (run?.pull_requests || []).some(x => x.number === pull?.number && x.head?.sha === pull.head.sha);
  if (!belongs) errors.push("CI run is not proven to belong to this PR and HEAD");
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
    if (!thread.isResolved && !thread.isOutdated) errors.push("Unresolved review thread");
  }
  const prRule = ruleset?.rules?.find(x => x.type === "pull_request")?.parameters;
  const ciRule = ruleset?.rules?.find(x => x.type === "required_status_checks")?.parameters;
  if (ruleset?.enforcement !== "active" || !prRule) errors.push("Branch protection not verified");
  if ((prRule?.required_approving_review_count ?? 1) !== 0) errors.push("An effective human approval is still required");
  if (!prRule?.allowed_merge_methods?.includes("squash")) errors.push("Squash is not permitted");
  if (!ciRule?.required_status_checks?.some(x => x.context === "validate")) errors.push("Required validate check missing");
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
  (run.pull_requests || []).some(p => p.number === pr.number && p.head?.sha === pr.head.sha)
);

export async function qualifyPR({ owner, repo, number, auth }) {
  const api = "https://api.github.com/repos/" + owner + "/" + repo;
  const pull = await asJson(api + "/pulls/" + number, auth);
  const allRuns = await asJson(api + "/actions/runs?event=pull_request&head_sha=" + encodeURIComponent(pull.head.sha) + "&per_page=100", auth);
  const run = pickCI(allRuns.workflow_runs || [], pull);
  const jobs = run ? (await asJson(api + "/actions/runs/" + run.id + "/jobs?per_page=100", auth)).jobs : [];
  const reviews = await asJson(api + "/pulls/" + number + "/reviews?per_page=100", auth);
  const ruleset = await asJson(api + "/rulesets/21055765", auth);
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
  if (jobs.length === 100) throw Error("CI job pagination incomplete");
  const errors = checkEligibility({
    pull, run, jobs, reviews, threads: threadConnection.nodes,
    ruleset, expectedBaseSha: pull.base.sha
  });
  return {errors, pull, run};
}

export async function main(env = process.env) {
  const auth = env.GH_TOKEN;
  const number = Number(env.USI_PR_NUMBER);
  const repository = env.GITHUB_REPOSITORY || "";
  if (!auth || !Number.isInteger(number) || number <= 0 || !/^[^/]+\/[^/]+$/.test(repository)) throw Error("Missing GitHub inputs");
  const [owner, repo] = repository.split("/");
  const check = await qualifyPR({owner, repo, number, auth});
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
  const latest = await qualifyPR({owner, repo, number, auth});
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
