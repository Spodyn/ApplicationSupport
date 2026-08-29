# Database migration safety policy

**Status:** Frozen v1 implementation policy

Flyway owns the application schema. Migrations in
`apps/api/src/main/resources/db/migration` are append-only: once a migration is
applied anywhere outside a disposable local database, its filename and contents
must never change. A correction is a new migration, never an edit, delete,
rename, or baseline rewrite of an existing migration.

## Expand-contract lifecycle

Potentially incompatible schema changes use these independently deployable
stages:

1. **Expand:** add the new table, column, index, constraint, or representation
   without removing the old one. The migration must be safe for the current
   application and, where rollback is expected, the immediately previous (N-1)
   application version.
2. **Backfill or migrate:** move existing data in bounded, observable work. Do
   not combine an unbounded production backfill with a request transaction.
3. **Switch:** deploy code that reads and writes the new representation while
   preserving the old contract needed by N-1 code until rollback is no longer a
   possibility.
4. **Contract:** remove the obsolete representation only in a later release,
   after evidence shows no supported application version or operational tool
   depends on it.

`DROP`, destructive type narrowing, and same-release `RENAME` are therefore
not normal migration techniques. A rename is implemented as add, copy/backfill,
switch, and later cleanup. Production database rollback never attempts to undo
Flyway migrations; deployment rollback selects a compatible application image.

## Author and review requirements

- Use a new monotonically ordered `V<version>__description.sql` migration.
- Keep schema, index, constraint, and data-transition changes attributable to
  the owning Jira task.
- State N-1 compatibility and the future cleanup condition in the PR whenever
  a migration changes a public persistence contract.
- Do not run provider HTTP calls or long external work inside migration or
  application transactions.
- Treat Flyway validation/checksum failure as a release blocker. Never repair
  it by altering `flyway_schema_history` outside a controlled, human-approved
  incident procedure.

## Required validation

Each schema-bearing change must prove both paths against isolated PostgreSQL:

1. a clean database applies the complete migration chain and boots with Hibernate
   in `validate` mode; and
2. a database migrated from the immediately preceding committed migration
   snapshot upgrades with the current chain, validates successfully, and leaves
   no pending migrations.

The repository's Flyway lifecycle integration test enforces these paths. CI
also runs it as part of the Testcontainers integration gate. No test, CI, or
local command may target a production database.
