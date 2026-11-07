---
title: "0.11.0 migration step: exeris-codegen-ts carries a system-field marker without its flag in the request types"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-11-07
---

### `exeris-codegen-ts`: a system-field marker without its flag is in the request types

The generated TypeScript client follows the kernel application on a field in a system role whose
`@ExerisDomain` flag is off: a field the entity's `systemFields` names for the version, an audit
time or actor, a soft-delete field or the owning tenant, on an entity that is not `versioned`,
`audited`, `softDelete` or tenant-partitioned respectively. Such a field is an ordinary field of the
API, so for every one of them:

1. `<Entity>Create` and `<Entity>Update`, and their Zod schemas, carry it instead of omitting it.
2. The list query offers it as a sort key when it is sortable and as a filter when it is
   filterable: `<Entity>SortField` and `<Entity>Filter` gain it.

An entity whose flag is on is unchanged, and so is an entity without such a field. The generated
forms and views are unchanged too: they still render no control for a field a `systemFields` block
names.

**What breaks.** A `<Entity>Create` or `<Entity>Update` built without the field no longer compiles
when the field is `required`; an optional one may stay absent, and the server then stores `null`.
Code that switched exhaustively over `<Entity>SortField` gains a case. Where the field was meant to
be server-owned, set the entity's flag for its role and regenerate both sides.
