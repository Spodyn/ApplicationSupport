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

## Local validation

The Maven Wrapper requires Java 25 and downloads its pinned Maven distribution
on first use:

```bash
./mvnw clean verify
```

`GET /actuator/health` is the only public route in this bootstrap. All other
routes are denied until the authenticated API is implemented.
