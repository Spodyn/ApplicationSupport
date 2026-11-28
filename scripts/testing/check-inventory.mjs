import fs from 'node:fs'
import path from 'node:path'
import {fileURLToPath, pathToFileURL} from 'node:url'
import {execFileSync} from 'node:child_process'

const root = fileURLToPath(new URL('../../', import.meta.url))
const required = 'AUTH USERS CUSTOMERS INTEGRATIONS CHANNELS SLACK_IN SLACK_OUT CASE CLAIM REPLY RESOLVE IGNORE SNOOZE SLA SCHEDULE NOTIFICATIONS READ ANALYTICS HISTORY CASE_NOTES REALTIME ATTACHMENTS RETRY PERSISTENCE AUDIT BROWSER API CI PROVIDERS I18N BULK DIRECT_MESSAGES STAGING HISTORY_RECOVERY PROVIDER_ONBOARDING INBOX_PREFERENCES MESSAGE_MUTATIONS'.split(' ')

function checkedPath(source, file = true) {
  const resolved = path.resolve(root, source)
  const relative = path.relative(root, resolved)
  if (relative.startsWith('..') || path.isAbsolute(relative) || !fs.existsSync(resolved) || (file && !fs.statSync(resolved).isFile())) throw Error(`Missing or out-of-repository test/source: ${source}`)
  return resolved
}

export function checkInventory(inventory, baseline) {
  if (!Array.isArray(inventory?.features)) throw Error('Missing feature inventory')
  const seen = new Set()
  for (const feature of inventory.features) {
    if (seen.has(feature.id) || !feature.expected || !feature.gap || !feature.tests?.length) throw Error(`Incomplete feature traceability: ${feature.id}`)
    if (!['IMPLEMENTED', 'PARTIALLY_IMPLEMENTED', 'SPECIFICATION_ONLY'].includes(feature.status)) throw Error(`Unknown implementation status: ${feature.id}`)
    seen.add(feature.id)
    for (const test of feature.tests) {
      checkedPath(test)
    }
  }
  const missing = required.filter(id => !seen.has(id))
  if (missing.length) throw Error(`Required feature mappings missing: ${missing.join(', ')}`)
  if (inventory.schemaVersion !== 2) throw Error('Expected inventory schemaVersion 2')
  const scenarioIds = new Set()
  for (const feature of inventory.features) {
    if (!feature.sources?.length || !feature.testIdentifiers?.length) throw Error(`Missing source/test identifiers: ${feature.id}`)
    for (const source of feature.sources) checkedPath(source, false)
    for (const kind of ['positive', 'negative', 'boundary']) {
      const scenario = feature.scenarios?.[kind]
      if (!scenario?.id || !scenario.expected?.trim() || scenarioIds.has(scenario.id)) throw Error(`Missing/duplicate ${kind} scenario: ${feature.id}`)
      scenarioIds.add(scenario.id)
    }
    for (const test of feature.testIdentifiers) {
      if (!feature.tests.includes(test.source) || !test.identifier?.trim()
        || !['backend', 'integration', 'frontend', 'browser', 'fullstack', 'configuration', 'infrastructure', 'openapi'].includes(test.layer)
        || !['configuration', 'backend', 'integration', 'infrastructure', 'openapi', 'web', 'fullstack'].includes(test.ci)) throw Error(`Invalid test identifier/layer/CI: ${feature.id}`)
      if (test.kind === 'suite') {
        if (test.identifier !== test.source) throw Error(`Stale suite identifier: ${feature.id}/${test.identifier}`)
      } else if (!fs.readFileSync(checkedPath(test.source), 'utf8').includes(test.identifier)) throw Error(`Stale test identifier: ${feature.id}/${test.identifier}`)
    }
    if (!['PASS', 'FAIL', 'NOT RUN', 'BLOCKED'].includes(feature.verification?.automated)
      || !['PASS', 'FAIL', 'FIXED', 'PARTIAL', 'NOT TESTED', 'BLOCKED', 'NOT IMPLEMENTED'].includes(feature.verification?.live)
      || !/^[0-9a-f]{40}$/.test(feature.verification?.revision ?? '')
      || !feature.verification?.evidence || !feature.verification?.scope || !feature.verification?.note) throw Error(`Missing verification provenance: ${feature.id}`)
  }
  if (baseline) {
    for (const previous of baseline.features) {
      const current = inventory.features.find(feature => feature.id === previous.id)
      const removed = previous.tests.filter(source => !current?.tests.includes(source))
      for (const source of removed) {
        const explanation = inventory.coverageChanges?.find(change => change.feature === previous.id && change.removed === source)
        if (!explanation?.reason?.trim() || !explanation.replacements?.length
          || !explanation.replacements.every(replacement => current?.tests.includes(replacement))) throw Error(`Unexplained coverage reduction: ${previous.id}/${source}`)
      }
    }
  }
  return seen.size
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const inventory = JSON.parse(fs.readFileSync(path.join(root, 'config/regression-features.json'), 'utf8'))
  let baseline
  if (process.argv.includes('--base-ref')) {
    const base = process.argv[process.argv.indexOf('--base-ref') + 1]
    if (!/^[0-9a-f]{40}$/.test(base ?? '')) throw Error('Expected exact --base-ref SHA')
    baseline = JSON.parse(execFileSync('git', ['show', `${base}:config/regression-features.json`], {cwd: root, encoding: 'utf8'}))
  }
  console.log(`Verified ${checkInventory(inventory, baseline)} feature mappings, scenario definitions, source anchors and verification provenance.`)
}
