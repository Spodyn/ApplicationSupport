import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const SCRIPT_DIR = path.dirname(fileURLToPath(import.meta.url))
export const REPO_ROOT = path.resolve(SCRIPT_DIR, '../..')
export const SPEC_PATH = path.join(REPO_ROOT, 'apps/api/openapi/v1/openapi.json')
export const GENERATED_DIR = path.join(REPO_ROOT, 'packages/api-client/src/generated')

export function readContract(file = SPEC_PATH) {
  return JSON.parse(fs.readFileSync(file, 'utf8'))
}

export function operationEntries(contract) {
  const methods = new Set(['get', 'post', 'put', 'patch', 'delete'])
  const entries = []
  for (const [route, pathItem] of Object.entries(contract.paths ?? {})) {
    for (const [method, operation] of Object.entries(pathItem ?? {})) {
      if (methods.has(method.toLowerCase())) {
        entries.push({ route, method: method.toUpperCase(), operation, pathItem })
      }
    }
  }
  return entries.sort((left, right) =>
    `${left.route}:${left.method}`.localeCompare(`${right.route}:${right.method}`),
  )
}

export function resolveSchema(contract, schema) {
  if (!schema?.$ref) return schema
  const prefix = '#/components/schemas/'
  if (!schema.$ref.startsWith(prefix)) {
    throw new Error(`Only local component schema refs are supported: ${schema.$ref}`)
  }
  const name = schema.$ref.slice(prefix.length)
  const resolved = contract.components?.schemas?.[name]
  if (!resolved) throw new Error(`Unresolved schema ref: ${schema.$ref}`)
  return resolved
}

export function schemaRefName(schema) {
  const prefix = '#/components/schemas/'
  return schema?.$ref?.startsWith(prefix) ? schema.$ref.slice(prefix.length) : null
}
