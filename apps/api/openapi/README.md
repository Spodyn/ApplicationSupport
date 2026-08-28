# OpenAPI contract

The backend owns the versioned transport contract. The frozen v1 source is
`v1/openapi.json` and public REST paths added to it must live under `/api/v1/`.
Generated TypeScript output goes exclusively to
`packages/api-client/src/generated` and must never be edited by hand.

## Commands

From the repository root:

```bash
pnpm openapi:lint
pnpm openapi:generate
pnpm openapi:generated:check
pnpm openapi:check
```

`pnpm openapi:generate` is the only supported way to update generated files.
`pnpm openapi:generated:check` regenerates in memory and fails when checked-in
output is stale or contains unexpected generated files. `pnpm openapi:check`
also runs generator/compatibility unit tests, TypeScript compilation of the
client package, and the frontend adapter smoke test.

## v1 compatibility policy

Within v1 the public contract is additive-only. CI compares the current source
to the PR base commit (or the previous commit on a push) and fails on breaking
changes including removed paths/operations/schemas/properties/responses,
changed operation IDs or schema type/format, removed enum values, newly
required parameters/properties/request bodies, and tighter supported scalar
constraints. A new major API version requires an explicit later contract
change; the compatibility gate is not bypassed by an ignore flag.

The first commit that introduces `v1/openapi.json` establishes the compatibility
baseline because its base commit has no OpenAPI v1 source yet.

The initial source intentionally contains shared transport schemas only. It does
not invent a business endpoint before the endpoint-owning Jira tickets define
one. As real `/api/v1/*` operations are added, the deterministic generator will
add their typed client operations from the same source.
