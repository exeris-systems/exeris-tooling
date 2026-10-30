---
title: "0.11.0 migration step: field-level @PrimaryKey names the entity's key"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-10-30
---

### Field-level `@PrimaryKey` names the entity's primary key

The annotation processor reads the field-level `@PrimaryKey` marker into
`SystemFieldsMetadata.primaryKeyField`, matching the SDK's source-model reader. Until 0.11.0 the
marker had no effect on emitted code and `-Aexeris.strict` reported it as unread.

#### Refused strategies

The marker accepts `strategy = Strategy.UUID` (the default) and `Strategy.UUID_DATABASE`. The
strategies `SEQUENCE`, `IDENTITY` and `NONE` are refused at build time with the compile error
`EXT-PROC-1021`, naming the entity, the field and the strategy. All primary keys in the Exeris
ecosystem are UUIDs ([ADR-104](../../adr/ADR-104-declared-primary-key-uuid-only.md)).

#### Unintended key renames

A project that annotated a field with `@PrimaryKey` before 0.11.0 while relying on an `id` field, or
on a field other than the intended key, now has that annotated field resolved as the entity's
primary key. Check the generated metadata (`target/classes/exeris-metadata/<Entity>.json`) or the
`CREATE TABLE` migration to confirm the effective key matches the intended column. If an entity
declares both a marked key and a field `id`, the processor refuses the declaration with `EXT-PROC-1019`.
