/**
 * Historical feature branch cleanup. Delete only stale (>7 days) branches
 * demonstrably unchanged since an already merged PR, with no open PR consumer.
 * Never delete refs with unmerged work or uncertain pagination.
 */
import {safeToDelete} from "./delete_merged_branch.mjs";

export function eligibleStaleBranch({branch, mergedPRs, openPRs, now, minAgeMs = 7*24*60*60*1000, verifiedNoActiveWorkerLease = false}) {
  // Merged provenance is not evidence that a live worker/lease is absent.
  if (verifiedNoActiveWorkerLease !== true) return false;
  if (!branch || branch.protected || !branch.commit?.sha) return false;
  if (!Array.isArray(mergedPRs) || !Array.isArray(openPRs)) return false;
  for (const p of mergedPRs) {
    if (p?.head?.ref !== branch.name || p?.head?.sha !== branch.commit.sha) continue;
    if (!p.merged_at || !Number.isFinite(Date.parse(p.merged_at))) continue;
    if (now - Date.parse(p.merged_at) < minAgeMs) continue;
    if (safeToDelete({
      pull: {...p, state:"closed", merged:true},
      refSha:branch.commit.sha, openPulls:openPRs, defaultBranch:"main", verifiedNoActiveWorkerLease
    })) return true;
  }
  return false;
}

const get = async (url, auth, options = {}) => {
  const response=await fetch(url,{...options,headers:{
    Authorization:"Bearer "+auth,
    Accept:"application/vnd.github+json",
    "X-GitHub-Api-Version":"2022-11-28",
    ...options.headers
  }});
  if(response.status===404 && options.allow404)return null;
  if(!response.ok)throw Error("GitHub HTTP "+response.status+" "+url);
  return response.status===204?null:await response.json();
};
async function allPages(api, path, auth){
  const output=[];
  for(let page=1;page<=15;page++){
    const a=await get(api+path+(path.includes("?")?"&":"?")+"per_page=100&page="+page,auth);
    if(!Array.isArray(a))throw Error("Invalid GitHub page "+path);
    output.push(...a);
    if(a.length<100)return output;
  }
  throw Error("Pagination incomplete, refusing cleanup");
}
export async function sweep(env=process.env){
  const auth=env.GH_TOKEN,repository=env.GITHUB_REPOSITORY;
  if(!auth||!/^[^/]+\/[^/]+$/.test(repository||""))throw Error("Missing repository/auth");
  const api="https://api.github.com/repos/"+repository;
  const [branches,open,closed]=await Promise.all([
    allPages(api,"/branches",auth),
    allPages(api,"/pulls?state=open",auth),
    allPages(api,"/pulls?state=closed",auth)
  ]);
  const merged=closed.filter(x=>x.merged_at && x.state==="closed");
  // No authoritative lease registry/heartbeat lookup exists in this runner.
  // Fail closed. A configured Actions variable alone is not valid evidence.
  const verifiedNoActiveWorkerLease = false;
  const now=Date.now();
  let deleted=0,eligible=0;
  for(const branch of branches){
    if(!eligibleStaleBranch({branch,mergedPRs:merged,openPRs:open,now,verifiedNoActiveWorkerLease}))continue;
    eligible++;
    if(deleted>=40)break;
    // The SHA is fetched again immediately before deletion; any new commit
    // after the merged PR makes it ineligible. Stacked PR references were
    // already inspected across the COMPLETE open PR list above.
    const refPath="/git/ref/heads/"+branch.name.split("/").map(encodeURIComponent).join("/");
    const latest=await get(api+refPath,auth,{allow404:true});
    if(!latest||latest.object?.sha!==branch.commit.sha)continue;
    const dryRun=env.USI_BRANCH_SWEEP_DRY_RUN==="true";
    if(dryRun) {
      console.log("DRY RUN stale merged branch:",branch.name);
      continue;
    }
    await get(api+"/git/refs/heads/"+branch.name.split("/").map(encodeURIComponent).join("/"),auth,{method:"DELETE"});
    deleted++;
    console.log("Deleted safely merged stale branch:",branch.name);
  }
  if (!verifiedNoActiveWorkerLease) console.log("BLOCKED: no authoritative worker lease/heartbeat inactivity evidence; all branches retained");
  console.log(JSON.stringify({eligible,deleted,dryRun:env.USI_BRANCH_SWEEP_DRY_RUN==="true",workerLeaseVerification:"NOT_RUN"}));
}
if(process.argv[1]?.endsWith("/prune_merged_branches.mjs")){
  sweep().catch(error=>{console.error(error);process.exitCode=1;});
}
