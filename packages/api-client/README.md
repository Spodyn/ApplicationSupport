# USI generated API client

This workspace isolates TypeScript transport artifacts generated from the
backend-owned `apps/api/openapi/v1/openapi.json` contract.

- Generator output belongs only in `src/generated/` and is never hand-edited.
- Regenerate from the repository root with `pnpm openapi:generate`.
- Verify deterministic checked-in output with `pnpm openapi:generated:check`.
- Compile the generated package with `pnpm api-client:typecheck`.
- Transport DTOs are not frontend domain/view models.
- `apps/web/app` and `apps/web/components` must not import generated DTOs.
  Service-layer adapters may use generated types and map them to stable
  `apps/web/lib/domain/*` models behind the existing service boundary.

The first smoke adapter is `apps/web/lib/services/api/channel-adapter.ts`. It
maps the generated API enum (`SLACK`, `TEAMS`, `TELEGRAM`) to the existing
frontend `Channel` domain values without leaking transport casing into UI code.
