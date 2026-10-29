/**
 * Delete a merged source branch only if it still points to the merged PR HEAD
 * and no other open PR uses it as a source or as the base of a stacked PR.
 * Runs only with trusted default-branch code. Never touches the default branch.
 */
export function safeToDelete({pull, refSha, openPulls, defaultBranch = "main"}) {
  if (!pull?.merged || pull.state !== "closed" || !pull.head?.ref || !pull.head?.sha) return false;
  if (pull.head.repo?.full_name !== pull.base.repo?.full_name) return false;
  if (pull.head.ref === defaultBranch || !/^(agent\/USI-|codex\/|usi-|ops\/USI-)/.test(pull.head.ref)) return false;
  if (refSha !== pull.head.sha) return false;
  if (!Array.isArray(openPulls)) return false;
  if (openPulls.some(x =>
    x.state === "open" && (x.head?.ref === pull.head.ref || x.base?.ref === pull.head.ref)
  )) return false;
  return true;
}
async function gh(path, auth, options = {}) {
  const response = await fetch("https://api.github.com" + path, {
    ...options,
    headers: {
      Authorization: "Bearer " + auth,
      Accept: "application/vnd.github+json",
      "X-GitHub-Api-Version": "2022-11-28",
      ...options.headers
    }
  });
  if (response.status === 404 && options.allow404) return null;
  if (!response.ok) throw Error("GitHub HTTP " + response.status + " " + path);
  return response.status === 204 ? null : response.json();
}
export async function cleanup(env = process.env) {
  const auth = env.GH_TOKEN, repo = env.GITHUB_REPOSITORY;
  const n = Number(env.USI_PR_NUMBER);
  if (!auth || !/^[^/]+\/[^/]+$/.test(repo || "") || !Number.isInteger(n) || n <= 0) throw Error("Invalid cleanup inputs");
  const prefix = "/repos/" + repo;
  const pull = await gh(prefix + "/pulls/" + n, auth);
  if (!pull.merged || pull.state !== "closed") return;
  const openPulls = await gh(prefix + "/pulls?state=open&per_page=100", auth);
  if (openPulls.length === 100) {
    console.log("Branch retained: open PR list may be incomplete");
    return;
  }
  const branch = pull.head.ref;
  if (!branch || branch === "main") return;
  const ref = await gh(prefix + "/git/ref/heads/" + branch.split("/").map(encodeURIComponent).join("/"), auth, {allow404: true});
  if (!ref) return;
  const repoData = await gh(prefix, auth);
  if (!safeToDelete({pull, refSha: ref.object?.sha, openPulls, defaultBranch: repoData.default_branch})) {
    console.log("Branch retained because source/stack/commit safety gate failed:", branch);
    return;
  }
  // Re-read ref and PR just before deletion; any post-merge new commit blocks.
  const currentRef = await gh(prefix + "/git/ref/heads/" + branch.split("/").map(encodeURIComponent).join("/"), auth, {allow404: true});
  if (!currentRef || currentRef.object?.sha !== pull.head.sha) return;
  await gh(prefix + "/git/refs/heads/" + branch.split("/").map(encodeURIComponent).join("/"), auth, {method:"DELETE"});
  console.log("Deleted merged, unreferenced branch:", branch);
}
if (process.argv[1]?.endsWith("/delete_merged_branch.mjs")) {
  cleanup().catch(err => {console.error(err); process.exitCode = 1;});
}
