# ApplicationSupport - Repository Instructions for Codex

## Mission
This repository contains Unified Support Inbox (USI). Preserve the accepted frontend visual language and UX while implementing the frozen v1 product contract and production architecture through small, reviewable Jira-scoped changes.

The product/architecture decision pre-flight for E00-E25 is complete. Do not reopen already frozen decisions merely because an older mock, document, ticket description or historical Jira comment says something different.

## Mandatory source-of-truth order
Before implementing any ticket, resolve requirements in this order:

1. **Current Jira ticket**, including later `FINAL DECISION FREEZE`, `CONTRACT OVERRIDE` and `USER_DECISION_RESOLVED` comments.
2. Frozen E00 decisions and approved parent-epic pre-flight decisions for E01-E25.
3. `docs/PRODUCT_CONTRACT.md` and `docs/decision-registry.yaml`.
4. `docs/PRODUCT_SPEC.md`, `docs/WORKFLOW_MATRIX.md`, `docs/ARCHITECTURE.md`, `docs/INTEGRATIONS.md`, `docs/SECURITY.md`, `docs/OPERATIONS.md`.
5. Older focused documents such as `CASE_WORKFLOW.md`, `CASE_GROUPING.md`, `AUTHENTICATION.md`, `RETENTION.md`, `PERMISSION_MATRIX.md`, `CURRENT_STATE.md`, `DOMAIN_MODEL.md` and `TESTING.md`, but only where they do not conflict with a later source above.
6. Current code/mock behavior only where it does not conflict with the frozen contract.

`docs/OPEN_DECISIONS.md` is not a general ambiguity sink. As of the final decision freeze it contains no known unresolved product/architecture blocker. Create a new human blocker only when the hierarchy above cannot resolve a genuine contract change.

For autonomous lifecycle/dependency/review/merge behavior, `docs/ORCHESTRATOR_CONTRACT.md` and `docs/orchestrator-policy.yaml` are mandatory operational inputs. They do not override product semantics and do not grant production authority.

## Delegated autonomy
Codex may decide normal implementation details without asking the Product Owner, including naming, packages, internal data structures, indexes implied by query/race requirements, targeted locking, retry/backoff implementation, tests, local refactors required by a ticket, provider SDK details compatible with the current official API, and semantic Git-conflict resolution.

A human decision is required only for a real contract change, including: v1 scope expansion, fundamental workflow semantics, new roles/permissions, a new security boundary, destructive data semantics outside the retention contract, a paid third-party service/commercial commitment, production credentials/actions, pricing/customer commitments, or new legal/compliance obligations.

## Current frontend architecture
- Next.js 16
- React 19
- strict TypeScript
- Tailwind CSS
- existing shadcn/base-ui style components
- TanStack Query
- Recharts
- PWA/service worker

Data boundary:

`UI -> lib/services/queries.ts -> lib/services/registry.ts -> typed service interfaces -> mock/API implementations`

Keep this boundary. Components must not import directly from `mocks/`. Generated OpenAPI DTOs are transport types and must be mapped to stable frontend domain/view models.

## Visual contract
Treat the current UI as the accepted visual baseline unless the Jira ticket explicitly changes it.

Do not casually redesign layout, navigation, colors, typography, spacing/density, visible labels or component library. When replacing mock behavior with production behavior, preserve the established interaction model and add only states required by the ticket/contract.

## Domain contract
Canonical Case statuses are exactly:
- `new`
- `verification`
- `waiting_for_customer`
- `partially_ignored`
- `ignored`
- `resolved`

Frontend lower-snake values map to persistence/API upper-snake values. Do not add generic states such as `open`, `pending`, `on_hold`, `closed`, `snoozed` or `waiting_team` as CaseStatus. Snooze, unread and analytics dimensions are projections/personal state, not workflow states.

Backend/server projections are authoritative for action availability. The UI must not implement a second state machine.

## Backend baseline
Frozen production baseline:
- Java 25 LTS
- Spring Boot 4.1.x, latest compatible stable patch at implementation time
- Spring Modulith 2.1.x, latest compatible stable patch
- Maven Wrapper in repo
- PostgreSQL 18.x + Flyway
- RabbitMQ 4.3.x
- S3-compatible object storage / MinIO
- WebSocket/STOMP
- OpenTelemetry/Micrometer + Prometheus/Grafana
- modular monolith; no Kubernetes/microservice split without a later explicit contract change

OpenAPI is the frontend/backend transport contract. Generated files stay isolated and are not hand-edited.

## Persistence and concurrency rules
- PostgreSQL is source of truth.
- Default isolation `READ COMMITTED`; use targeted conditional updates/row locks.
- `FOR UPDATE SKIP LOCKED` for durable schedulers/workers where appropriate.
- `SERIALIZABLE` only for a specifically justified invariant with tests.
- Provider HTTP calls never run inside an open DB transaction.
- Retryable business commands are idempotent.
- Transactional outbox/inbox patterns protect async effects.
- Flyway migrations are append-only and use expand/contract with N-1 application compatibility where rollback requires it.

## Security
Never:
- commit `.env` files, credentials or provider secrets;
- expose signing secrets/bot tokens/client secrets in browser bundles;
- use production Slack/Teams/Telegram credentials in tests;
- connect Codex/CI/tests to production DB or object storage;
- cache authenticated API responses in the PWA service worker;
- disable security checks to make CI pass;
- perform production deployment, PITR, destructive recovery, secret rotation or DNS/provider cutover without explicit protected human approval.

Use fake/sandbox providers for automated testing. Actionable Critical/High security findings block merge unless a documented time-bounded suppression exists.

## Provider scope v1
- Slack: public/private/Slack Connect channels available to the app; no DM/group DM.
- Microsoft Teams: standard channels and group chats; no private/shared channels in v1.
- Telegram: private chats, groups/supergroups and forum topics; no broadcast channels.
- E-mail support channel and AI features are outside v1.

Provider-specific API versions/scopes/SDK details may be updated by the agent from current official vendor documentation as long as product scope and security boundaries are preserved.

## Validation
For every change run the relevant checks. Before completion of a code-bearing ticket, the expected full gate is:
1. install from committed lockfile/wrappers,
2. lint/static analysis,
3. typecheck/compile,
4. unit/component tests,
5. backend/integration tests as relevant,
6. production build,
7. Playwright E2E when affected,
8. security checks required by the ticket/CI.

Do not report completion while a required gate is failing.

Documentation-only changes still require diff review, link/source-precedence review, machine-readable YAML validation where applicable and repository CI configured for the PR.

## Change discipline
- One coherent Jira task per branch/PR unless the ticket explicitly groups work.
- No unrelated redesign or broad dependency upgrades.
- Search references before deleting or renaming public/domain symbols.
- Prefer small reviewable diffs.
- Preserve auditability and stable problem codes.
- At completion report changed files, commands/tests, results and remaining risks.

## Documentation discipline
When behavior changes under an explicitly approved contract change, update the canonical docs and decision registry in the same PR. Normal implementation detail does not require a new product decision entry.

Keep synchronized where relevant:
- `docs/PRODUCT_CONTRACT.md`
- `docs/PRODUCT_SPEC.md`
- `docs/WORKFLOW_MATRIX.md`
- `docs/ARCHITECTURE.md`
- `docs/INTEGRATIONS.md`
- `docs/SECURITY.md`
- `docs/OPERATIONS.md`
- `docs/DECISION_REGISTRY.md`
- `docs/decision-registry.yaml`
- `docs/ORCHESTRATOR_CONTRACT.md`
- `docs/orchestrator-policy.yaml`
- focused legacy/current-state docs affected by the ticket

## Autonomous lifecycle discipline
Developer workers do not merge and do not mark Jira `Gotowe`. Reviewer decisions are bound to the exact reviewed PR HEAD SHA. A changed SHA requires revalidation and re-review. Jira becomes `Gotowe` only after the reviewed change is actually merged to `main`.

On restart, reconcile Jira/GitHub/worktree reality before creating duplicate branches, PRs, comments or transitions. Dependency scheduling, leases, conflict recovery and exact merge gates are defined in `docs/ORCHESTRATOR_CONTRACT.md`.

## Stop conditions
Stop and create a clear blocker only if work would require an actual contract change under the human-decision boundary, production credentials/actions, an unresolved legal/compliance choice, or an irreconcilable conflict between equal/higher-precedence sources. Do not stop for ordinary implementation choices already delegated above.

<!-- BEGIN:nextjs-agent-rules -->

# This is NOT the Next.js you know

This version has breaking changes - APIs, conventions, and file structure may all differ from your training data. Read the relevant guide in `node_modules/next/dist/docs/` (resolved from this file's directory; in monorepos the `next` package may not be visible from the repo root) before writing any code. Heed deprecation notices.

This block is written and re-added by `next dev` - verify at `node_modules/next/dist/server/lib/generate-agent-files.js`. Removing it from a diff only re-creates the uncommitted change; committing it with your work keeps the tree clean.

<!-- END:nextjs-agent-rules -->