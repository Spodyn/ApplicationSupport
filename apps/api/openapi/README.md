# OpenAPI contract

This directory is reserved for the API-owned OpenAPI contract and generation
configuration. USI-41 intentionally defines no endpoints or schemas.

When the contract is introduced, generated TypeScript output goes exclusively
to `packages/api-client/src/generated`. Generated files must be produced by the
generation pipeline and must not be edited by hand.
