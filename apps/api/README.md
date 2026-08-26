# USI API

This directory is the home of the production Spring Boot modular monolith.

USI-41 established the repository boundary. USI-43 adds Spring-compatible
`local`, `test`, `staging`, and `production` configuration profiles under
`src/main/resources`; the executable Spring Boot application, Maven Wrapper,
modules, and Flyway migrations remain owned by their dedicated backend tickets.
No database schema or executable backend is introduced here.

The profile resources contain non-secret typed-property mappings. Local accepts
only disposable development credentials. Staging/production import core
credentials from a required config tree and keep provider credentials behind
an integration `secret_ref` resolver in a separate, non-overlapping secret
tree. The machine-readable contract, startup preflight, variables, and mount
layout are documented in
[`config/README.md`](../../config/README.md).

The backend owns authorization, workflow invariants, persistence, provider
adapters, and the OpenAPI transport contract. API contract artifacts and their
generation configuration belong in [`openapi/`](openapi/).
