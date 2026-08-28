# USI API

This directory is the home of the production Spring Boot modular monolith.

USI-48 adds the executable Java 25 / Spring Boot 4.1 modular-monolith
foundation, Maven Wrapper, Spring MVC, Validation, JPA, Actuator, Flyway, and
a deny-by-default Spring Security placeholder. No business API or domain
schema is introduced here; Flyway migrations remain owned by E03.

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

## REST v1 conventions

REST endpoints live under `/api/v1`. Technical identifiers use UUID in canonical
lower-case text form. Absolute timestamps are emitted and accepted as RFC3339
UTC (`Z`) instants. Public enum names are transport contract values and must not
be renamed casually after publication.

Collection endpoints use stable query parameter names: `cursor` for the opaque
continuation token, `limit` for page size, and `sort` for an endpoint-defined
stable sort enum. Endpoint-specific filters use explicit stable query names;
adding or renaming a public filter/sort value is an API-contract change.

Long feeds use signed, opaque, versioned cursor pagination: default page size
50, maximum 100, cursor TTL 24 hours. `CursorPage<T>` contains `items` and
`nextCursor`; a null `nextCursor` means the last page. Repository ordering uses
an indexed stable sort field plus UUID `id` as the deterministic tie-breaker;
no offset pagination is used for long message/case history.

`CursorCodec` uses HMAC-SHA256 and requires signing key material to be supplied
by protected runtime configuration when a paginated endpoint is wired. Cursor
payloads bind to a caller-provided canonical scope string. Endpoint adapters
must include endpoint identity, normalized filters, and sort in that scope so a
cursor cannot be reused with a different query. Clients treat the resulting
cursor as an opaque string and never parse or construct it.

Malformed, tampered, expired, unsupported-version, or query-mismatched cursors
fail closed and map to the stable `INVALID_CURSOR` problem code.

## REST error contract

API failures use `application/problem+json`. Every problem response contains a
stable machine-readable `code`, `title`, numeric `status`, safe user-facing
`detail`, and `correlationId`; validation failures additionally expose
`fieldErrors` without rejected values. Frontend/adapters must branch on `code`,
never parse `detail` text for application logic.

Unexpected failures return the stable `INTERNAL_ERROR` contract and never copy
exception messages, stack traces, provider payloads, tokens, or secrets into the
response. Controlled provider/application failures likewise expose only
explicit safe details. Until USI-54 adds request-wide correlation propagation,
the error foundation generates a response correlation ID and reuses an existing
validated request attribute when one is supplied by server-side infrastructure.

## Local validation

The Maven Wrapper requires Java 25 and downloads its pinned Maven distribution
on first use:

```bash
./mvnw clean verify
```

`GET /actuator/health` is the only public route in this bootstrap. All other
routes are denied until the authenticated API is implemented; denied requests
use the same problem+json contract as controller failures.
