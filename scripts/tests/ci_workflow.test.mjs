import {readFileSync} from 'node:fs'
import assert from 'node:assert/strict'
import test from 'node:test'
import {spawnSync} from 'node:child_process'
import {fileURLToPath} from 'node:url'
import {REGRESSION_JOBS} from '../testing/ci-gate.mjs'
import {checkInventory} from '../testing/check-inventory.mjs'

const workflow = readFileSync(new URL('../../.github/workflows/ci.yml', import.meta.url), 'utf8')
test('mandatory CI definitions and fail-closed aggregator cannot silently diverge', () => {
  const names = [...workflow.matchAll(/^  ([a-z][a-z_]*):\r?$/gm)].map(match => match[1])
  // Restrict to the jobs section: event names have the same YAML indentation.
  const jobs = workflow.slice(workflow.indexOf('\njobs:'))
  const jobNames = [...jobs.matchAll(/^  ([a-z][a-z_]*):\r?$/gm)].map(match => match[1])
  assert.deepEqual(jobNames.sort(), [...REGRESSION_JOBS, 'validate'].sort())
  assert(names.includes('pull_request') && names.includes('merge_group'))
  const gate = jobs.slice(jobs.indexOf('\n  validate:'))
  assert.match(gate, /if: always\(\)/)
  const needs = gate.match(/needs: \[([^\]]+)\]/)?.[1].split(',').map(value => value.trim())
  assert.deepEqual(needs?.sort(), [...REGRESSION_JOBS].sort())
  assert.match(gate, /USI_JOB_RESULTS: \$\{\{ toJSON\(needs\) }}/)
  assert.match(gate, /node scripts\/testing\/ci-gate\.mjs/)
  assert.doesNotMatch(workflow, /continue-on-error:|paths-ignore:|paths:/)
})

test('ordinary workflows pin actions, never configure provider credentials, and run the real stack', () => {
  for (const match of workflow.matchAll(/uses: (\S+)/g)) assert.match(match[1], /@[0-9a-f]{40}$/)
  assert.match(workflow, /run: pnpm test:fullstack/)
  assert.match(workflow, /python3 scripts\/testing\/run_config_tests\.py/)
  assert.doesNotMatch(workflow, /USI_SANDBOX_SLACK_BOT_TOKEN|pull_request_target|secrets\./)
})

test('validation base handles a first branch push but refuses an invalid authoritative base', () => {
  const script = fileURLToPath(new URL('../testing/base-ref.mjs', import.meta.url))
  const expected = spawnSync('git', ['rev-parse', 'HEAD^'], {encoding: 'utf8'}).stdout.trim()
  const first = spawnSync(process.execPath, [script], {encoding: 'utf8', env: {...process.env, BASE_SHA: '0'.repeat(40)}})
  assert.equal(first.status, 0)
  assert.equal(first.stdout.trim(), expected)
  const bad = spawnSync(process.execPath, [script], {encoding: 'utf8', env: {...process.env, BASE_SHA: 'invalid-commit-test-only'}})
  assert.notEqual(bad.status, 0)
})

test('traceability rejects erased features, broken test links and out-of-repository paths', () => {
  const inventory = JSON.parse(readFileSync(new URL('../../config/regression-features.json', import.meta.url), 'utf8'))
  assert.equal(checkInventory(inventory), 30)
  assert.throws(() => checkInventory({features: []}), /Required feature mappings missing/)
  const missing = structuredClone(inventory)
  missing.features = missing.features.filter(feature => feature.id !== 'AUTH')
  assert.throws(() => checkInventory(missing), /AUTH/)
  for (const path of ['missing-test-source.java', '../../AGENTS.md']) {
    const invalid = structuredClone(inventory)
    invalid.features[0].tests = [path]
    assert.throws(() => checkInventory(invalid), /Missing or out-of-repository test/)
  }
})
