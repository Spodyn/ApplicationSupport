import assert from 'node:assert/strict'
import { test } from 'node:test'
import { generatedFiles } from './generate.mjs'
import { findBreakingChanges } from './compat.mjs'
import { lintContract } from './lint.mjs'

const base = {
  openapi: '3.1.1',
  info: { title: 'Example', version: '1.0.0' },
  paths: {
    '/api/v1/examples': {
      get: {
        operationId: 'listExamples',
        parameters: [
          { in: 'query', name: 'limit', required: false, schema: { type: 'integer' } },
        ],
        responses: {
          200: { content: { 'application/json': { schema: { $ref: '#/components/schemas/Example' } } } },
        },
      },
    },
  },
  components: {
    schemas: {
      Example: {
        type: 'object',
        additionalProperties: false,
        required: ['id'],
        properties: {
          id: { type: 'string', format: 'uuid' },
          state: { type: 'string', enum: ['A', 'B'] },
        },
      },
    },
  },
}

test('generation is deterministic', () => {
  assert.deepEqual([...generatedFiles(base)], [...generatedFiles(structuredClone(base))])
})

test('additive optional fields are compatible', () => {
  const next = structuredClone(base)
  next.components.schemas.Example.properties.note = { type: 'string' }
  assert.deepEqual(findBreakingChanges(base, next), [])
})

test('removed enum values and newly required fields are breaking', () => {
  const next = structuredClone(base)
  next.components.schemas.Example.properties.state.enum = ['A']
  next.components.schemas.Example.required.push('state')
  const changes = findBreakingChanges(base, next).join('\n')
  assert.match(changes, /enum value removed: B/)
  assert.match(changes, /new required property: state/)
})

test('removed operations are breaking', () => {
  const next = structuredClone(base)
  delete next.paths['/api/v1/examples'].get
  assert.match(findBreakingChanges(base, next).join('\n'), /operation removed/)
})

test('generated client carries operation inputs and response types', () => {
  const client = generatedFiles(base).get('client.gen.ts')
  assert.match(client, /export interface ListExamplesInput/)
  assert.match(client, /"limit"\?: number/)
  assert.match(client, /listExamples: \(input: ListExamplesInput\)/)
  assert.match(client, /request<Example>/)
})

test('generated client recursively imports component refs from container schemas', () => {
  const next = structuredClone(base)
  next.paths['/api/v1/examples'].get.responses[200].content['application/json'].schema = {
    type: 'array',
    items: { $ref: '#/components/schemas/Example' },
  }
  const client = generatedFiles(next).get('client.gen.ts')
  assert.match(client, /import type \{ Example \} from '\.\/types\.gen'/)
  assert.match(client, /request<Array<Example>>/)
})

test('lint accepts the supported deterministic subset', () => {
  assert.deepEqual(lintContract(base), [])
})

test('lint fails closed for generator-unsupported contract features', () => {
  const next = structuredClone(base)
  next.paths['/api/v1/examples'].get.operationId = 'list-examples'
  next.paths['/api/v1/examples'].get.parameters.push({ in: 'header', name: 'X-Test', schema: { type: 'string' } })
  next.paths['/api/v1/examples'].post = {
    operationId: 'createExample',
    requestBody: { content: { 'application/xml': { schema: { $ref: '#/components/schemas/Example' } } } },
    responses: { 204: { description: 'No content' } },
  }
  const errors = lintContract(next).join('\n')
  assert.match(errors, /operationId must be a valid TypeScript identifier/)
  assert.match(errors, /parameter location header is not supported/)
  assert.match(errors, /media type application\/xml is not supported/)
})
