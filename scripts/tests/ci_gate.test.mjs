import test from 'node:test'
import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { REGRESSION_JOBS, regressionGate } from '../testing/ci-gate.mjs'

const green = () => Object.fromEntries(REGRESSION_JOBS.map(name => [name, {result: 'success'}]))
test('complete successful evidence passes', () => assert.deepEqual(regressionGate(green()), []))
for (const name of REGRESSION_JOBS) {
  for (const result of ['failure', 'cancelled', 'skipped', 'pending', 'neutral', undefined]) {
    test(`${name} ${result ?? 'missing'} cannot pass`, () => {
      const evidence = green()
      if (result === undefined) delete evidence[name]
      else evidence[name] = {result}
      assert.ok(regressionGate(evidence).some(message => message.startsWith(`${name}:`)))
    })
  }
}
test('malformed and unknown evidence fails closed', () => {
  for (const value of [null, [], 'success', {...green(), unknown: {result: 'success'}}]) {
    assert.notDeepEqual(regressionGate(value), [])
  }
})
test('the executable gate really returns a failing process exit code', () => {
  const run = results => spawnSync(process.execPath, ['scripts/testing/ci-gate.mjs'], {
    env: {...process.env, USI_JOB_RESULTS: JSON.stringify(results)}, encoding: 'utf8',
  })
  assert.equal(run(green()).status, 0)
  const broken = green(); broken.fullstack.result = 'failure'
  assert.equal(run(broken).status, 1)
})
