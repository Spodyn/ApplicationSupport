# USI API

This directory is the home of the production Spring Boot modular monolith.

USI-41 establishes the repository boundary only. The Spring Boot application,
Maven Wrapper, modules, Flyway migrations, and runtime configuration are added
by their dedicated backend tickets. No database schema or executable backend is
introduced here.

The backend owns authorization, workflow invariants, persistence, provider
adapters, and the OpenAPI transport contract. API contract artifacts and their
generation configuration belong in [`openapi/`](openapi/).

