# Backend module boundaries

The Java backend is a Spring Modulith modular monolith. Direct subpackages of
`com.unifiedsupportinbox` listed below are the application-module roots.
`ApplicationModules.verify()` is part of Maven verification and rejects module
cycles and references into another module's internal packages.

## Modules

- `identity` — authentication, sessions and identity-facing security.
- `administration` — administrative application use cases.
- `customer` — customer/company domain and projections.
- `integration` — provider installation/configuration model and health.
- `channel` — provider-neutral monitored channel/conversation discovery, mapping and operational state.
- `realtime` — authenticated WebSocket/STOMP transport and realtime delivery boundary.
- `inbox` — inbox queries, user-specific read/snooze projections and views.
- `messaging` — messages, conversation history and delivery lifecycle.
- `workflow` — case commands and workflow state transitions.
- `sla` — SLA clocks, deadlines and breach projections.
- `notification` — notification decisions and delivery orchestration.
- `analytics` — statistics and reporting projections.
- `audit` — immutable audit-event model and queries.
- `storage` — attachment/object-storage abstraction.
- `provider` — Slack, Teams and Telegram adapters behind provider-neutral contracts.

## Dependency direction

A module exposes types from its root package as its default public API. Concrete
implementations, repositories, configuration and provider-specific details live
below `internal` subpackages and must not be imported by other modules.

Cross-module collaboration must use a module's public application/domain
interfaces or published domain events. A module must never reach into another
module's repository or `internal` package. Dependencies must remain acyclic;
when concrete dependencies are introduced, `@ApplicationModule` allowed
dependencies and named interfaces should be narrowed to the actual contract.

Provider-specific code belongs below the `provider` boundary and calls the rest
of the system only through provider-neutral public contracts. Business modules
must not depend on Slack, Teams or Telegram SDK types.

Database table ownership is introduced by the domain persistence tickets; the
`channel` module owns provider-neutral discovered Channel persistence while
provider adapters supply only normalized discovery data through its public API.
Realtime keeps connection state in memory only; PostgreSQL remains the source
of truth and later E11 tickets publish minimal post-commit signals through this boundary.
