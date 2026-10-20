# Database Migration Standard

[中文](DATABASE_MIGRATION_STANDARD.md) | [English](DATABASE_MIGRATION_STANDARD.en.md)

> English summary; the Chinese document is normative.

## Scope

Flyway SQL scripts under each service's `src/main/resources/db/migration/`.

## Rules

- Use `V{version}__{description}.sql`; versions increase monotonically and are never reused within a service.
- UTF-8, uppercase SQL keywords, backtick identifiers, two-space indentation.
- Executed migrations are immutable; add a new version for structural changes.
- No `IF NOT EXISTS`, `DROP TABLE` or implicit idempotency that hides environment drift.
- Explicit InnoDB/utf8mb4 collation; comments on tables, columns, indexes and constraints.
- `datetime(3)` for time, `decimal` for money; cross-service references store IDs only.
- No secrets or production personal data in migrations.

## Related

- [Standards index](README.en.md)
- [Persistence stack](21_PERSISTENCE_STACK.en.md)
