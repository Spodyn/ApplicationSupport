import { spawnSync } from 'node:child_process'
import { readContract, REPO_ROOT } from './model.mjs'

const SPEC_REPO_PATH = 'apps/api/openapi/v1/openapi.json'
const METHODS = ['get', 'post', 'put', 'patch', 'delete']

function sameJson(left, right) {
  return JSON.stringify(left) === JSON.stringify(right)
}

function compareSchema(base, current, location, breaking) {
  if (!base || !current) return
  if (base.$ref && base.$ref !== current.$ref) breaking.push(`${location}: schema reference changed`)
  if (base.type && !sameJson(base.type, current.type)) breaking.push(`${location}: type changed`)
  if (base.format && base.format !== current.format) breaking.push(`${location}: format changed`)

  if (base.enum) {
    const next = new Set(current.enum ?? [])
    for (const value of base.enum) {
      if (!next.has(value)) breaking.push(`${location}: enum value removed: ${value}`)
    }
  }

  const baseRequired = new Set(base.required ?? [])
  for (const required of current.required ?? []) {
    if (!baseRequired.has(required)) breaking.push(`${location}: new required property: ${required}`)
  }

  for (const [name, property] of Object.entries(base.properties ?? {})) {
    const next = current.properties?.[name]
    if (!next) breaking.push(`${location}: property removed: ${name}`)
    else compareSchema(property, next, `${location}.${name}`, breaking)
  }

  if (base.items && current.items) compareSchema(base.items, current.items, `${location}[]`, breaking)
  if (base.additionalProperties !== false && current.additionalProperties === false) {
    breaking.push(`${location}: additional properties are no longer accepted`)
  }

  const numericTightening = [
    ['minimum', (oldValue, newValue) => newValue > oldValue],
    ['maximum', (oldValue, newValue) => newValue < oldValue],
    ['minLength', (oldValue, newValue) => newValue > oldValue],
    ['maxLength', (oldValue, newValue) => newValue < oldValue],
  ]
  for (const [key, isBreaking] of numericTightening) {
    if (base[key] !== undefined && current[key] !== undefined && isBreaking(base[key], current[key])) {
      breaking.push(`${location}: ${key} became more restrictive`)
    }
  }
}

function parameterKey(parameter) {
  return `${parameter.in}:${parameter.name}`
}

function compareParameters(baseParameters = [], currentParameters = [], location, breaking) {
  const currentByKey = new Map(currentParameters.map((parameter) => [parameterKey(parameter), parameter]))
  const baseByKey = new Map(baseParameters.map((parameter) => [parameterKey(parameter), parameter]))
  for (const [key, parameter] of baseByKey) {
    const next = currentByKey.get(key)
    if (!next) breaking.push(`${location}: parameter removed: ${key}`)
    else compareSchema(parameter.schema, next.schema, `${location} parameter ${key}`, breaking)
  }
  for (const [key, parameter] of currentByKey) {
    if (parameter.required && !baseByKey.get(key)?.required) breaking.push(`${location}: new required parameter: ${key}`)
  }
}

export function findBreakingChanges(base, current) {
  const breaking = []

  for (const [name, schema] of Object.entries(base.components?.schemas ?? {})) {
    const next = current.components?.schemas?.[name]
    if (!next) breaking.push(`components.schemas.${name}: schema removed`)
    else compareSchema(schema, next, `components.schemas.${name}`, breaking)
  }

  for (const [route, basePath] of Object.entries(base.paths ?? {})) {
    const currentPath = current.paths?.[route]
    if (!currentPath) {
      breaking.push(`${route}: path removed`)
      continue
    }
    compareParameters(basePath.parameters, currentPath.parameters, route, breaking)
    for (const method of METHODS) {
      const operation = basePath[method]
      if (!operation) continue
      const next = currentPath[method]
      const location = `${method.toUpperCase()} ${route}`
      if (!next) {
        breaking.push(`${location}: operation removed`)
        continue
      }
      if (operation.operationId !== next.operationId) breaking.push(`${location}: operationId changed`)
      compareParameters(operation.parameters, next.parameters, location, breaking)

      if (!operation.requestBody?.required && next.requestBody?.required) {
        breaking.push(`${location}: request body became required`)
      }
      const baseRequest = operation.requestBody?.content?.['application/json']?.schema
      const nextRequest = next.requestBody?.content?.['application/json']?.schema
      if (baseRequest && !nextRequest) breaking.push(`${location}: JSON request body removed`)
      else if (baseRequest && nextRequest) compareSchema(baseRequest, nextRequest, `${location} request`, breaking)

      for (const [status, response] of Object.entries(operation.responses ?? {})) {
        const nextResponse = next.responses?.[status]
        if (!nextResponse) {
          breaking.push(`${location}: response removed: ${status}`)
          continue
        }
        for (const [mediaType, content] of Object.entries(response.content ?? {})) {
          const nextSchema = nextResponse.content?.[mediaType]?.schema
          if (!nextSchema) breaking.push(`${location} ${status}: response media type removed: ${mediaType}`)
          else compareSchema(content.schema, nextSchema, `${location} ${status} ${mediaType}`, breaking)
        }
      }
    }
  }
  return [...new Set(breaking)].sort()
}

function argument(name) {
  const index = process.argv.indexOf(name)
  return index >= 0 ? process.argv[index + 1] : null
}

function contractAt(ref) {
  const commitCheck = spawnSync('git', ['cat-file', '-e', `${ref}^{commit}`], { cwd: REPO_ROOT, encoding: 'utf8' })
  if (commitCheck.status !== 0) throw new Error(`Compatibility base is not an available commit: ${ref}`)

  const pathCheck = spawnSync('git', ['cat-file', '-e', `${ref}:${SPEC_REPO_PATH}`], { cwd: REPO_ROOT, encoding: 'utf8' })
  if (pathCheck.status !== 0) return null

  const show = spawnSync('git', ['show', `${ref}:${SPEC_REPO_PATH}`], { cwd: REPO_ROOT, encoding: 'utf8' })
  if (show.status !== 0) throw new Error(show.stderr || `Could not read OpenAPI contract from ${ref}`)
  return JSON.parse(show.stdout)
}

if (process.argv[1]?.endsWith('compat.mjs')) {
  const baseRef = argument('--base-ref')
  if (!baseRef) {
    console.error('Usage: node scripts/openapi/compat.mjs --base-ref <git-ref>')
    process.exit(2)
  }
  try {
    const base = contractAt(baseRef)
    if (!base) {
      console.log(`OpenAPI compatibility: no contract at ${baseRef}; current v1 contract establishes the baseline.`)
      process.exit(0)
    }
    const breaking = findBreakingChanges(base, readContract())
    if (breaking.length) {
      console.error('Breaking OpenAPI changes are forbidden within v1:')
      for (const item of breaking) console.error(`- ${item}`)
      process.exit(1)
    }
    console.log('OpenAPI compatibility passed (additive-only v1 policy).')
  } catch (error) {
    console.error(`OpenAPI compatibility failed closed: ${error.message}`)
    process.exit(1)
  }
}
