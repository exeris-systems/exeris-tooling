---
title: "0.11.0 migration step: an entity's static and transient fields are not entity fields"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-10-30
---

### An entity's static and transient fields are not entity fields

The processor skips an `@ExerisDomain` class's `static` and `transient` fields, so a constant such
as `public static final String CHARGING = "CHARGING";` reaches neither the metadata nor the migration,
DTOs, OpenAPI or TypeScript model. Before, it became a column, and the generated repository called
accessors that did not exist, so the application did not compile. `@Field` on such a field is
reported as warning `EXT-PROC-1108` and has no effect.

After regenerating, a migration, DTO or TypeScript model that carried such a field no longer does. A
field meant to be persisted is declared as an instance field without `transient`.
