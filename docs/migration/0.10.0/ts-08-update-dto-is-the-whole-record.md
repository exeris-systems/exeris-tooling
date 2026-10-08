---
title: "0.10.0 migration step: `exeris-codegen-ts`: `<Entity>Update` is the whole record, not a partial create DTO"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-10-08
---

### `exeris-codegen-ts`: `<Entity>Update` is the whole record, not a partial create DTO

`Compatibility impact: breaking (ADR-092)`, for code that calls a generated service's or store's
`update` with an object it builds itself.

The generated server's update is a full replacement. Its `PUT {base}/{id}` handler decodes the body
into the whole entity and checks it with the same rules as a create, and the repository's `UPDATE`
writes every domain column but the server-owned and the read-only ones, which keep their stored
value. A domain property the body leaves out is stored as null. 0.9.0 typed the body as
`Partial<<Entity>Create>`, so a call such as `update(id, { title })` compiled and nulled every other
column.

`<Entity>Update` is now the entity record without its key (the path carries it) and without the
fields the update does not take from the body: the server-owned fields, among them the owner of a
TENANT or UNIVERSE entity, and the `@Field(readOnly = true)` fields (the step "`<Entity>Update`
omits every field the server owns" lists them). A versioned
entity's update also requires the version, as in 0.9.0:

```ts
// a GLOBAL, unversioned entity
export type OrderUpdate = Omit<Order, 'id'>;
// a TENANT entity, versioned, that declares `version` as a Long
export type TicketUpdate = Omit<Ticket, 'id' | 'tenantId'> & { version: number | null };
```

`<Entity>UpdateSchema` follows it: `<Entity>Schema.omit({ id: true, … })`, with `.extend({ version })`
on a versioned entity, in place of `<Entity>CreateSchema.partial()`. Required fields are required,
and so is a UNIVERSE entity's shared scope; a read-only field is not part of the body. Peer DTOs (`peers/<peer>/…`)
follow the same rule.

The emitted edit form already sent the loaded record with the form's values over it, so the values
it sends are unchanged.

**What to do:** build an update body from the record you loaded, then change what you mean to
change: `service.update(id, { ...loaded, title })`. A call that passes only some fields fails to
compile when the entity has a required field it leaves out; where every field is optional it still
compiles, and still nulls what it leaves out, so check those calls by hand.
`<Entity>UpdateSchema.parse` now refuses a body without a required field.
