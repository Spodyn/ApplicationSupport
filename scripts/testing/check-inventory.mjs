import fs from 'node:fs'
import path from 'node:path'
import {fileURLToPath} from 'node:url'

const root = fileURLToPath(new URL('../../', import.meta.url))
const inventory = JSON.parse(fs.readFileSync(path.join(root, 'config/regression-features.json'), 'utf8'))
const seen = new Set()
for (const feature of inventory.features) {
  if (seen.has(feature.id) || !feature.expected || !feature.gap || !feature.tests?.length) throw Error(`Incomplete feature traceability: ${feature.id}`)
  seen.add(feature.id)
  for (const test of feature.tests) {
    const resolved = path.resolve(root, test)
    if (!resolved.startsWith(root) || !fs.existsSync(resolved)) throw Error(`Missing or out-of-repository test: ${test}`)
  }
}
console.log(`Verified ${seen.size} feature mappings and their test source paths.`)
