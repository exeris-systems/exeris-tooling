---
title: "0.11.0 migration step: a List field is a TEXT column"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-11-07
---

### A `List` field is a `TEXT` column

A `List<X>` field is stored as one JSON text value. The create-table migration declared its column
`VARCHAR(255)`, so the JSON of a list could not exceed 255 characters. A `UUID` key takes 39 of
them with its quotes and separator, so a `List<UUID>` held at most six keys. A
regenerated `V{tier}{nnnnnn}__create_{table}.sql` declares it `TEXT`, with no declared width:

```sql
    related_ids TEXT
```

`NOT NULL`, `UNIQUE` and an index on the column are emitted as before. The repository writes and
reads the value as it did, as a string.

An index still bounds the column. The migration indexes a field that is `searchable`, `unique` or
`filterable`, which a field without `@Field` is by default, and PostgreSQL refuses a B-tree entry
larger than a third of a page: 2,704 bytes with the default 8 kB page. A `List<UUID>` reaches it at
its 70th key. To hold longer lists, annotate the field `@Field`, whose `searchable`, `filterable`
and `unique` default to `false`, so the column gets no index; the repository neither finds, filters
nor sorts on a `List` column.

**What breaks.** The create-table migration of an entity with a `List` field changes, so a database
that has already applied the earlier one fails Flyway's checksum validation. On such a database,
widen each list column with a forward migration of your own, such as
`ALTER TABLE products ALTER COLUMN related_ids TYPE TEXT;`, and run `flyway repair` to accept the
changed checksum. A database that has not applied it needs neither.

**Where it applies.** Only to entities that declare a `List` field. Every other generated file is
byte-identical to the previous release.
