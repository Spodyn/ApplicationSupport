import { operationEntries, readContract, resolveSchema } from './model.mjs'

const IDENTIFIER = /^[A-Za-z_$][A-Za-z0-9_$]*$/
const SUPPORTED_PARAMETER_LOCATIONS = new Set(['path', 'query'])
const SUPPORTED_MEDIA_TYPES = new Set(['application/json', 'application/problem+json'])
const SUPPORTED_SCHEMA_TYPES = new Set(['string', 'integer', 'number', 'boolean', 'array', 'object', 'null'])
const UNSUPPORTED_HTTP_METHODS = new Set(['head', 'options', 'trace'])

function validateSchema(contract, schema, location, push) {
  if (!schema || typeof schema !== 'object' || Array.isArray(schema)) {
    push(`${location}: schema must be an object`)
    return
  }
  if (schema.$ref) {
    try {
      resolveSchema(contract, schema)
    } catch (error) {
      push(`${location}: ${error.message}`)
    }
    return
  }
  for (const keyword of ['allOf', 'anyOf', 'not']) {
    if (schema[keyword]) push(`${location}: ${keyword} is not supported by the deterministic TypeScript generator`)
  }
  if (schema.oneOf) {
    if (!Array.isArray(schema.oneOf) || schema.oneOf.length < 2) push(`${location}: oneOf must contain at least two schemas`)
    else schema.oneOf.forEach((item, index) => validateSchema(contract, item, `${location}.oneOf[${index}]`, push))
  }
  const types = Array.isArray(schema.type) ? schema.type : schema.type ? [schema.type] : []
  for (const type of types) {
    if (!SUPPORTED_SCHEMA_TYPES.has(type)) push(`${location}: unsupported schema type ${type}`)
  }
  if (schema.type === 'array') {
    if (!schema.items) push(`${location}: array items schema is required`)
    else validateSchema(contract, schema.items, `${location}[]`, push)
  }
  if (schema.type === 'object' || schema.properties) {
    if (schema.additionalProperties !== false) {
      push(`${location}: object schemas must set additionalProperties=false until map generation is supported`)
    }
    const properties = schema.properties ?? {}
    for (const required of schema.required ?? []) {
      if (!(required in properties)) push(`${location}: required property ${required} is not defined`)
    }
    for (const [name, property] of Object.entries(properties)) {
      validateSchema(contract, property, `${location}.${name}`, push)
    }
  }
  if (schema.enum) {
    if (!Array.isArray(schema.enum) || schema.enum.length === 0) push(`${location}: enum must not be empty`)
    else if (new Set(schema.enum).size !== schema.enum.length) push(`${location}: enum values must be unique`)
  }
}

function validateParameter(contract, parameter, location, push) {
  if (parameter?.$ref) {
    push(`${location}: parameter $ref is not supported by the deterministic TypeScript generator`)
    return
  }
  if (!parameter?.name || !parameter?.in) {
    push(`${location}: parameter name and in are required`)
    return
  }
  if (!SUPPORTED_PARAMETER_LOCATIONS.has(parameter.in)) {
    push(`${location}: parameter location ${parameter.in} is not supported; only path and query are generated`)
  }
  if (parameter.in === 'path' && parameter.required !== true) push(`${location}: path parameters must be required`)
  validateSchema(contract, parameter.schema, `${location} schema`, push)
}

function validateContent(contract, content, location, push) {
  if (!content) return
  for (const [mediaType, media] of Object.entries(content)) {
    if (!SUPPORTED_MEDIA_TYPES.has(mediaType)) {
      push(`${location}: media type ${mediaType} is not supported by the deterministic TypeScript generator`)
      continue
    }
    if (!media?.schema) push(`${location} ${mediaType}: schema is required`)
    else validateSchema(contract, media.schema, `${location} ${mediaType}`, push)
  }
}

export function lintContract(contract) {
  const errors = []
  const push = (message) => errors.push(message)

  if (contract.openapi !== '3.1.1') push('openapi must be exactly 3.1.1')
  if (contract.info?.version !== '1.0.0') push('info.version must be 1.0.0 for the frozen v1 contract')
  if (!contract.info?.title) push('info.title is required')
  if (!contract.paths || typeof contract.paths !== 'object' || Array.isArray(contract.paths)) push('paths must be an object')
  if (!contract.components?.schemas || typeof contract.components.schemas !== 'object') push('components.schemas is required')

  for (const [name, schema] of Object.entries(contract.components?.schemas ?? {})) {
    if (!IDENTIFIER.test(name)) push(`schema ${name}: component name must be a valid TypeScript identifier`)
    validateSchema(contract, schema, `schema ${name}`, push)
  }

  for (const [route, pathItem] of Object.entries(contract.paths ?? {})) {
    if (!route.startsWith('/api/v1/')) push(`${route}: public REST paths must start with /api/v1/`)
    if (pathItem?.$ref) push(`${route}: path item $ref is not supported by the deterministic TypeScript generator`)
    for (const method of UNSUPPORTED_HTTP_METHODS) {
      if (pathItem?.[method]) push(`${method.toUpperCase()} ${route}: HTTP method is not supported by the deterministic TypeScript generator`)
    }
    for (const [index, parameter] of (pathItem?.parameters ?? []).entries()) {
      validateParameter(contract, parameter, `${route} path parameter[${index}]`, push)
    }
  }

  const operationIds = new Set()
  for (const { route, method, operation, pathItem } of operationEntries(contract)) {
    const location = `${method} ${route}`
    if (!operation.operationId) push(`${location}: operationId is required`)
    else if (!IDENTIFIER.test(operation.operationId)) push(`${location}: operationId must be a valid TypeScript identifier`)
    else if (operationIds.has(operation.operationId)) push(`${location}: duplicate operationId ${operation.operationId}`)
    else operationIds.add(operation.operationId)

    const parameterKeys = new Set()
    for (const [index, parameter] of [...(pathItem.parameters ?? []), ...(operation.parameters ?? [])].entries()) {
      validateParameter(contract, parameter, `${location} parameter[${index}]`, push)
      if (parameter?.in && parameter?.name) {
        const key = `${parameter.in}:${parameter.name}`
        if (parameterKeys.has(key) && index >= (pathItem.parameters ?? []).length) {
          // Operation-level parameters intentionally override path-level definitions.
        } else parameterKeys.add(key)
      }
    }

    if (operation.requestBody?.$ref) push(`${location}: requestBody $ref is not supported by the deterministic TypeScript generator`)
    validateContent(contract, operation.requestBody?.content, `${location} request`, push)

    if (!operation.responses || typeof operation.responses !== 'object') push(`${location}: responses are required`)
    for (const [status, response] of Object.entries(operation.responses ?? {})) {
      if (response?.$ref) push(`${location} ${status}: response $ref is not supported by the deterministic TypeScript generator`)
      validateContent(contract, response?.content, `${location} response ${status}`, push)
      if (/^[45]\d\d$/.test(status) && !response?.content?.['application/problem+json']?.schema) {
        push(`${location} ${status}: errors must use application/problem+json`)
      }
    }
  }

  return [...new Set(errors)].sort()
}

if (process.argv[1]?.endsWith('lint.mjs')) {
  const errors = lintContract(readContract())
  if (errors.length) {
    for (const error of errors) console.error(`OpenAPI lint: ${error}`)
    process.exit(1)
  }
  const contract = readContract()
  console.log(`OpenAPI lint passed (${operationEntries(contract).length} operations, ${Object.keys(contract.components.schemas).length} schemas).`)
}
