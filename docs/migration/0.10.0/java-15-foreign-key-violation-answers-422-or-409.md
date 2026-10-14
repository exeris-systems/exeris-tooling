---
title: "0.10.0 migration step: a write naming a missing row through a relationship answers 422, and a delete of a still-referenced row answers 409"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-10-14
---

### A write naming a missing row through a relationship answers 422, and a delete of a still-referenced row answers 409

`Compatibility impact: breaking (ADR-076)`, for a client that branches on the `500` a foreign-key
violation used to answer; additive otherwise.

A foreign-key violation is the caller's to correct, so it no longer falls into the handler's `500`
([ADR-076, Amendment 1](../../adr/ADR-076-write-rejection-status.md)):

- `POST`, `PUT` and a respond-once action whose `MANY_TO_ONE` field names a row that does not exist
  answer `422` with a problem object (`type`, `title`, `status`, `detail`) whose `field` member names
  the relationship. A streaming action reports it as a `stream-error` frame with status `422`.
- `DELETE` of a row other rows still reference answers `409`. A soft-deleted entity's delete keeps
  the row, so it is never refused this way.
- The generated repository raises `<Entity>ReferenceNotFoundException` (carrying `relation()`) and
  `<Entity>StillReferencedException` (carrying `id()`), in the repository package beside
  `<Entity>NotFoundException`. They are emitted only where a foreign key of the build can produce
  them: the first for an entity that references an entity of the build, the second for an entity
  some entity of the build references and whose delete removes the row.
- The OpenAPI document declares `422` on those writes and `409` on that delete.

**What to do:** a client that retried or reported a `500` on these requests reads the new statuses
instead; a `409` on `DELETE` means "remove or repoint the referencing rows first", not a version
conflict. Code of your own that caught `RuntimeException` around `save`, `update` or `deleteById` to
detect a constraint violation can catch the typed exceptions. A foreign key you declared yourself,
outside the generated migration, is not attributed to a relationship: a write violating it still
answers `500`.
