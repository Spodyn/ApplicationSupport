import fs from 'node:fs'
import path from 'node:path'
import {fileURLToPath, pathToFileURL} from 'node:url'

const root = fileURLToPath(new URL('../../', import.meta.url))
const required = 'AUTH USERS CUSTOMERS INTEGRATIONS CHANNELS SLACK_IN SLACK_OUT CASE CLAIM REPLY RESOLVE IGNORE SNOOZE SLA SCHEDULE NOTIFICATIONS READ ANALYTICS HISTORY REALTIME ATTACHMENTS RETRY PERSISTENCE AUDIT BROWSER API CI PROVIDERS'.split(' ')

export function checkInventory(inventory) {
  if (!Array.isArray(inventory?.features)) throw Error('Missing feature inventory')
  const seen = new Set()
  for (const feature of inventory.features) {
    if (seen.has(feature.id) || !feature.expected || !feature.gap || !feature.tests?.length) throw Error(`Incomplete feature traceability: ${feature.id}`)
    if (!['IMPLEMENTED', 'PARTIALLY_IMPLEMENTED', 'SPECIFICATION_ONLY'].includes(feature.status)) throw Error(`Unknown implementation status: ${feature.id}`)
    seen.add(feature.id)
    for (const test of feature.tests) {
      const resolved = path.resolve(root, test)
      const relative = path.relative(root, resolved)
      if (relative.startsWith('..') || path.isAbsolute(relative) || !fs.existsSync(resolved) || !fs.statSync(resolved).isFile()) throw Error(`Missing or out-of-repository test: ${test}`)
    }
  }
  const missing = required.filter(id => !seen.has(id))
  if (missing.length) throw Error(`Required feature mappings missing: ${missing.join(', ')}`)
  return seen.size
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const inventory = JSON.parse(fs.readFileSync(path.join(root, 'config/regression-features.json'), 'utf8'))
  console.log(`Verified ${checkInventory(inventory)} feature mappings and their test source paths.`)
}
