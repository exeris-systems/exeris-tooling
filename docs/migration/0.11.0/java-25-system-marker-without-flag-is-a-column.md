---
title: "0.11.0 migration step: a system-field marker without its flag is an ordinary column"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-11-04
---

### A system-field marker without its flag is an ordinary column

A system role is switched on by its `@ExerisDomain` flag, never by a field: the owning tenant by a
`TENANT` or `UNIVERSE` data scope, the audit times and actors by `audited = true`, the soft-delete
flag, time and actor by `softDelete = true`, the version by `versioned = true`. A field marked
`@Version`, `@AuditCreatedAt`, `@SoftDelete` or another system marker, or named after a role
(`version`, `createdAt`, `deleted`, `tenantId`, …), on an entity whose flag for that role is off is
an ordinary domain column. The repository already read and wrote it as one. The create-table
migration left it out, so the repository's first query named a column the table did not have.

A regenerated `V{tier}{nnnnnn}__create_{table}.sql` now declares that column from the field's type,
like any other field: `NOT NULL` when it is `required`, an index when it is `searchable`, `unique`
or `filterable`, a `CHECK` for its bounds. It carries no default, and the repository neither stamps
nor locks on it. For example, an entity with neither `versioned` nor `audited` that declares

```java
@Version private Long version;
@AuditCreatedAt private Instant createdAt;
```

gains, in its create-table migration:

```sql
    version BIGINT,
    created_at TIMESTAMPTZ
```

An entity whose flag is on is unchanged: the system column is declared once, as before.

**What breaks.** The create-table migration of an affected entity changes, so a database that has
already applied the earlier one fails Flyway's checksum validation. That database also lacks the
column, which the generated repository reads and writes. First decide what the marker meant:

- the role: set the flag with the marker (`versioned = true`, `audited = true`, …). The
  create-table migration then declares the system column, with its default, and the repository
  stamps or locks on it;
- an ordinary field: leave the entity as it is.

Then, on a database that applied the earlier migration, add the column with a forward migration of
your own, such as `ALTER TABLE customers ADD COLUMN version BIGINT;`, and run `flyway repair` to
accept the changed checksum. A database that has not applied it needs neither.

**Where it applies.** Only to entities that declare a field in a system role whose flag is off.
Every other generated file is byte-identical to the previous release.
