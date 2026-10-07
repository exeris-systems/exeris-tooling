---
title: "0.10.0 migration step: `EXT-PROC-1104` is retired"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-10-07
---

### `EXT-PROC-1104` is retired

The processor prints no warning for an entity whose derived default table differs from the
snake-cased class name plus "s" (0.9.0's `EXT-PROC-1104`, T6). Nothing changes in generated code or
in the table an entity gets: `effectiveTableName()` names it, and `@ExerisDomain(tableName = …)`
overrides it, as in 0.9.0. A `tableName` set only to silence the warning can stay. The identifier is
listed under *Retired identifiers* in [`diagnostics.md`](../../diagnostics.md) and is never reused.
