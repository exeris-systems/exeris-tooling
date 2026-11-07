---
title: "0.11.0 migration step: exeris-codegen-ts treats a system-field marker without its flag as an ordinary field"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-11-07
---

### `exeris-codegen-ts`: a system-field marker without its flag is an ordinary field of the request types and the views

The generated TypeScript client follows the kernel application on a field in a system role whose
`@ExerisDomain` flag is off: a field the entity's `systemFields` names for the version, an audit
time or actor, a soft-delete field or the owning tenant, on an entity that is not `versioned`,
`audited`, `softDelete` or tenant-partitioned respectively. Such a field is an ordinary field of the
API, so for every one of them:

1. `<Entity>Create` and `<Entity>Update`, and their Zod schemas, carry it instead of omitting it.
2. The list query offers it as a sort key when it is sortable and as a filter when it is
   filterable: `<Entity>SortField` and `<Entity>Filter` gain it.
3. The form renders a control for it, the detail view lists it in its field table, and the list
   may pick it as a default column. The detail view's system panel no longer shows a declared
   `createdAt` or `updatedAt` of an entity that is not `audited`; the field table shows it instead.

A `tenantId` field of a `GLOBAL` entity, and a field named `createdAt`, `updatedAt`, `version` or
`deleted` on an entity whose flag for that role is off, count the same way: they were left out of
the form and the views by name, and are now ordinary fields.

The other way round, a `softDelete` entity's `deletedAt` and `deletedBy` and an `audited` entity's
`createdBy` and `updatedBy` are system fields under their declared or canonical names whether or
not a `systemFields` block names them. Where the form or the views showed such a field, they now
leave it out, as the request types already did.

An entity whose flag is on is otherwise unchanged, and so is an entity without such a field.

**What breaks.** A `<Entity>Create` or `<Entity>Update` built without the field no longer compiles
when the field is `required`; an optional one may stay absent, and the server then stores `null`.
Code that switched exhaustively over `<Entity>SortField` gains a case. A test that relied on the form
offering no `field-<name>` control for such a field, or on the field's row in the system panel,
follows the new layout. Where the field was meant to be server-owned, set the entity's flag for its
role and regenerate both sides.
