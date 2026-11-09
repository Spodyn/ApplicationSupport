import test from "node:test";
import assert from "node:assert/strict";
import {safeToDelete} from "../delete_merged_branch.mjs";

const fixture = () => ({
  pull: {
    state:"closed",merged:true,
    head:{ref:"codex/USI-190-telegram-topic-grouping",sha:"a".repeat(40),repo:{full_name:"Spodyn/ApplicationSupport"}},
    base:{ref:"main",repo:{full_name:"Spodyn/ApplicationSupport"}}
  },
  refSha:"a".repeat(40),openPulls:[],defaultBranch:"main",verifiedNoActiveWorkerLease:true
});
test("deletes exactly a merged unreferenced branch still at PR HEAD",()=>{
  assert.equal(safeToDelete(fixture()),true);
});
test("preserves source branch when there is an open child PR using it as base",()=>{
  const d=fixture();d.openPulls=[{state:"open",head:{ref:"child"},base:{ref:d.pull.head.ref}}];
  assert.equal(safeToDelete(d),false);
});
test("preserves branch still used by an open source PR",()=>{
  const d=fixture();d.openPulls=[{state:"open",head:{ref:d.pull.head.ref},base:{ref:"main"}}];
  assert.equal(safeToDelete(d),false);
});
test("never removes main, external forks, or unapproved branch naming families",()=>{
  for(const name of ["main","develop","temp-do-not-use","feature/unmerged"]){
    const d=fixture();d.pull.head.ref=name;assert.equal(safeToDelete(d),false);
  }
  const d=fixture();d.pull.head.repo.full_name="external/fork";
  assert.equal(safeToDelete(d),false);
});
test("does not delete changed/unknown refs, unmerged or still-open PRs",()=>{
  for(const change of [d=>{d.refSha="b".repeat(40)},d=>{d.refSha=null},d=>{d.pull.merged=false},d=>{d.pull.state="open"},d=>{d.openPulls=null}]){
    const d=fixture();change(d);assert.equal(safeToDelete(d),false);
  }
});

test("retains merged branch if the worker lease is unknown, missing or active",()=>{
  for(const evidence of [undefined, false, null, "true"]){
    const d=fixture();
    if(evidence===undefined)delete d.verifiedNoActiveWorkerLease;
    else d.verifiedNoActiveWorkerLease=evidence;
    assert.equal(safeToDelete(d),false);
  }
});
