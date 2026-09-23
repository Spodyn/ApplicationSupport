# Database conventions

**Status:** Frozen v1 implementation policy (USI-57 / E03-T02)

This document defines the persistence conventions for new USI schema work. It complements `MIGRATION_POLICY.md`: historical Flyway migrations are immutable, so conventions are enforced prospectively rather than by rewriting already-applied migrations.

## Naming

Application-owned PostgreSQL identifiers use unquoted `snake_case` names.

- Tables and columns: descriptive `snake_case` (`external_thread_key`, `last_activity_at`).
- Primary-key constraints, when declared explicitly: `pk_<table>`.
- Foreign keys: `fk_<child_table>_<column_or_relation>`.
- Unique constraints: `uq_<table>_<columns_or_purpose>`.
- Check constraints: `ck_<table>_<purpose>`.
- Non-unique indexes: `idx_<table>_<columns_or_purpose>`.
- Sequences: `<domain>_<purpose>_seq`.

New foreign keys, unique constraints, check constraints and indexes must be explicitly named. Avoid anonymous inline `REFERENCES`, `UNIQUE` and `CHECK` clauses in new schema-bearing migrations. Existing migrations remain untouched under the append-only migration policy.

## Identifiers

New durable application entities use PostgreSQL `uuid` primary keys. PostgreSQL 18 `uuidv7()` is the preferred database default because it preserves opaque UUID identity while providing time-ordered values that behave better in indexes than random UUIDv4 values.

Use a UUID primary key for technical identity even when a domain object also needs a human-readable identifier. API UUIDs are serialized as canonical lower-case strings.

Exceptions must be deliberate and documented, for example a true singleton/configuration row, a standards-mandated external identifier, or a pure relation whose natural composite key is the identity.

## Absolute and local time

An instant on the global timeline is stored as `timestamptz`. This includes `created_at`, `updated_at`, provider occurrence times, claim/resolve timestamps, retry deadlines and audit timestamps.

- Never use `timestamp without time zone` for an absolute moment.
- Application/API boundaries normalize absolute time to UTC and expose RFC3339 timestamps with an offset; the v1 API canonical form is UTC (`Z`).
- PostgreSQL session timezone must not be relied upon to recover a lost offset.
- `date`, `time`, weekday numbers and IANA timezone identifiers are allowed only when they intentionally model local civil/business time, for example a weekly support schedule. The conversion from that local schedule to an absolute deadline happens with an explicit timezone.

## Optimistic locking

Mutable aggregates that can be changed concurrently use a `version` column when compare-and-swap/optimistic locking is part of their write contract:

```sql
version bigint NOT NULL DEFAULT 0
```

Updates that depend on the caller's observed state include the expected version in the predicate and increment it atomically. Do not add `version` mechanically to append-only event/audit tables that are not updated as aggregates.

## Case identity and reference

A Case has two independent identities:

- `id uuid PRIMARY KEY DEFAULT uuidv7()` is the technical identifier used by persistence and API relations.
- `reference` is an immutable, human-readable sequential value such as `CASE-00000001`.

The Case reference must not be derived from the UUID, row count, `MAX(reference)`, application-local counters or a read-then-increment transaction. Generation must use a PostgreSQL sequence (or an equivalently atomic database primitive), and the Case table must also enforce a named unique constraint on `reference`. PostgreSQL sequences may contain gaps after rollback; uniqueness and concurrency safety matter, gaplessness does not.

E07-T01 owns the actual Case table and sequence/default implementation. This ticket freezes the contract so that implementation does not have to redesign identity semantics.

## Migration example

`apps/api/src/test/resources/db/conventions/database_conventions_example.sql` is a non-executed reference migration. It demonstrates the required conventions without adding example-only objects to the production schema. It intentionally shows:

- `snake_case` names;
- UUIDv7 technical identity;
- a sequence-backed human reference independent of the UUID;
- `timestamptz` absolute timestamps;
- an explicitly named foreign key, unique constraint, check constraint and index;
- a `version` column for optimistic locking.

Real schema changes still follow the expand/contract and validation requirements in `MIGRATION_POLICY.md`.

## Review checklist for new schema work

Before merging a new schema-bearing migration, verify that identifiers follow `snake_case`; new durable entity IDs use UUID/UUIDv7 unless an exception is documented; absolute moments use `timestamptz`; local schedule fields are unmistakably local-time concepts; FKs/UNIQUE/CHECK/indexes are named; mutable concurrent aggregates have an explicit locking strategy; human-facing identifiers are independent from technical UUIDs; and Flyway clean/upgrade/validate tests pass.
