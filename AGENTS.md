# ApplicationSupport — Repository Instructions for Codex

## Mission
This repository contains the accepted frontend/mock implementation of Unified Support Inbox.
Preserve the current visual language and UX while making the codebase production-ready for a future Java/Spring Boot backend.

Current stage: repository hardening complete. Backend/API work begins only when explicitly requested; do not implement the production backend or real Slack/Teams/Telegram integrations speculatively.

## Current architecture
Frontend:
- Next.js 16
- React 19
- strict TypeScript
- Tailwind CSS
- existing shadcn/base-ui style components
- TanStack Query
- Recharts
- PWA/service worker

Data boundary:
UI -> `lib/services/queries.ts` -> `lib/services/registry.ts` -> typed service interfaces -> mock implementations.

This boundary is intentional. Future OpenAPI adapters must replace service implementations without coupling UI components to transport DTOs.

## Visual contract
Treat the current UI as an accepted visual baseline.

Unless explicitly asked:
- do not redesign layouts,
- do not change navigation placement,
- do not change colors, typography, spacing or density,
- do not rename visible labels,
- do not remove working states/interactions,
- do not introduce a second UI library.

Before hardening/refactors, capture representative screenshots of `/cases`, `/statistics`, `/users`, `/settings` at a consistent desktop viewport and preserve them for comparison.

## Domain contract
For support workflow, `lib/domain/inbox.ts` is the current canonical frontend workflow model.

Canonical statuses:
- `new`
- `verification`
- `waiting_for_customer`
- `partially_ignored`
- `ignored`
- `resolved`

Do not silently replace them with generic ticket states such as `open`, `pending`, `on_hold` or `closed`.

The older generic `Case` model and its unused repository/mock chain were removed after a usage audit. Do not reintroduce generic ticket states or treat archived/older code as the backend contract.

Do not re-add removed product features solely because an older document mentions them. Record ambiguity in `docs/OPEN_DECISIONS.md`.

## Data/service boundaries
- Components must not import from `mocks/` directly.
- Data access must pass through service interfaces and TanStack Query.
- Keep `lib/services/registry.ts` as the composition boundary.
- Generated OpenAPI types are transport types, not UI domain types.
- Future API adapters map DTOs to stable frontend domain types.
- Never expose secrets in client bundles.

## Security
Never:
- commit `.env` files or credentials,
- use production Slack/Teams/Telegram credentials in tests,
- connect tests to a production database,
- cache authenticated API responses in the PWA service worker,
- disable security checks merely to make tests pass.

Use environment variables and `.env.example` placeholders only.

## Dependencies
- Prefer existing dependencies.
- Add only justified dependencies.
- Keep `package.json` and `pnpm-lock.yaml` synchronized.
- Do not perform broad dependency upgrades in unrelated tasks.
- Use the package manager declared in `package.json`.

## Code quality
- Keep TypeScript strict.
- Avoid `any`.
- Preserve accessibility.
- Keep user-visible copy in Polish unless explicitly requested otherwise.
- Avoid speculative abstractions and large unrelated refactors.

## Validation
After changes, run the relevant checks. Before completing hardening, the full gate is:
1. install from committed lockfile,
2. lint,
3. typecheck,
4. unit/component tests,
5. production build,
6. Playwright E2E when available.

Do not report completion while required checks fail.

## Testing priorities
Cover currently exposed critical interactions:
- route smoke tests,
- opening/selecting a case,
- the available reply action,
- user-management dialogs,
- settings forms.

When claim, ignore, ask-customer, resolve or snooze interactions become available in the accepted UI, add deterministic tests for them without inventing workflow rules.

Tests must not call real providers.

## Change discipline
- One coherent task at a time.
- Do not mix UI redesign with infrastructure hardening.
- Search references before deleting files/types.
- Prefer small reviewable diffs.
- At the end report changed files, commands run, results and remaining risks.
- If product behavior is ambiguous, document it instead of guessing.

## Documentation
Keep synchronized:
- `README.md`
- `docs/CURRENT_STATE.md`
- `docs/ARCHITECTURE.md`
- `docs/DOMAIN_MODEL.md`
- `docs/OPEN_DECISIONS.md`
- `docs/TESTING.md`

Clearly distinguish current behavior from planned behavior.

## Backend boundary
Planned production backend: Java/Spring Boot + PostgreSQL + RabbitMQ.
Do not build it during repository hardening.

When backend work begins:
- OpenAPI is the frontend/backend contract,
- generated clients stay isolated,
- generated files are not hand-edited,
- provider integrations are adapters,
- backend workflows preserve concurrency/idempotency guarantees.

## Stop conditions
Stop and report instead of guessing if a change:
- conflicts with product documentation,
- would reintroduce a recently removed feature,
- requires production credentials,
- requires broad visual redesign,
- changes canonical workflow semantics without an explicit spec.

<!-- BEGIN:nextjs-agent-rules -->

# This is NOT the Next.js you know

This version has breaking changes — APIs, conventions, and file structure may all differ from your training data. Read the relevant guide in `node_modules/next/dist/docs/` (resolved from this file's directory; in monorepos the `next` package may not be visible from the repo root) before writing any code. Heed deprecation notices.

This block is written and re-added by `next dev` — verify at `node_modules/next/dist/server/lib/generate-agent-files.js`. Removing it from a diff only re-creates the uncommitted change; committing it with your work keeps the tree clean.

<!-- END:nextjs-agent-rules -->
