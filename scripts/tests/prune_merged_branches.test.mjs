import test from "node:test";
import assert from "node:assert/strict";
import {eligibleStaleBranch} from "../prune_merged_branches.mjs";

const NOW=Date.parse("2026-10-29T18:00:00Z");
const candidate=()=>({
  branch:{name:"agent/USI-41",protected:false,commit:{sha:"a".repeat(40)}},
  mergedPRs:[{
    number:10,state:"closed",merged_at:"2026-08-25T01:04:22Z",
    head:{ref:"agent/USI-41",sha:"a".repeat(40),repo:{full_name:"Spodyn/ApplicationSupport"}},
    base:{ref:"main",repo:{full_name:"Spodyn/ApplicationSupport"}}
  }],
  openPRs:[],now:NOW
});
test("qualifies a safely merged branch older than 7 days",()=>{
  assert.equal(eligibleStaleBranch(candidate()),true);
});
test("refuses branches with later unique commits or no proven merged PR",()=>{
  const d=candidate();d.branch.commit.sha="b".repeat(40);
  assert.equal(eligibleStaleBranch(d),false);
  d.branch.commit.sha="a".repeat(40);d.mergedPRs=[];
  assert.equal(eligibleStaleBranch(d),false);
});
test("refuses merged branches that are too recent or protected",()=>{
  const d=candidate();d.mergedPRs[0].merged_at="2026-10-29T00:00:00Z";
  assert.equal(eligibleStaleBranch(d),false);
  d.mergedPRs[0].merged_at="2026-08-25T01:04:22Z";d.branch.protected=true;
  assert.equal(eligibleStaleBranch(d),false);
});
test("keeps any active stacked PR base/head",()=>{
  const d=candidate();d.openPRs=[{state:"open",head:{ref:"child"},base:{ref:d.branch.name}}];
  assert.equal(eligibleStaleBranch(d),false);
  d.openPRs=[{state:"open",head:{ref:d.branch.name},base:{ref:"main"}}];
  assert.equal(eligibleStaleBranch(d),false);
});
test("does not select arbitrary or forked branch families",()=>{
  const d=candidate();d.branch.name="temp-do-not-use";d.mergedPRs[0].head.ref=d.branch.name;
  assert.equal(eligibleStaleBranch(d),false);
  d.branch.name="agent/USI-41";d.mergedPRs[0].head.ref=d.branch.name;d.mergedPRs[0].head.repo.full_name="a/fork";
  assert.equal(eligibleStaleBranch(d),false);
});


test("a later merged PR can prove eligibility even when the earlier PR SHA differs",()=>{
  const d=candidate();
  const earlier={...d.mergedPRs[0],number:9,head:{...d.mergedPRs[0].head,sha:"b".repeat(40)}};
  d.mergedPRs.unshift(earlier);
  assert.equal(eligibleStaleBranch(d),true);
});
