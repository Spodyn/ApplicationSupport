# USI generated API client

This workspace isolates the TypeScript client generated from the backend-owned
OpenAPI contract.

- Generator output belongs only in `src/generated/` and is never hand-edited.
- The package intentionally has no public exports until the generation pipeline
  creates a real client.
- Transport DTOs are not frontend domain/view models.
- `apps/web/app` and `apps/web/components` must not import this package directly.
  A future API implementation maps transport DTOs to stable frontend models
  behind `apps/web/lib/services/registry.ts`.
