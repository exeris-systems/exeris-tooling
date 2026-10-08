---
title: "0.10.0 migration step: `exeris-codegen-ts`: `<Entity>Update` omits every field the server owns"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-10-08
---

### `exeris-codegen-ts`: `<Entity>Update` omits every field the server owns

`Compatibility impact: breaking (ADR-090)`, for code that calls a generated service's or
store's `update` with an object it builds itself and sets one of the omitted properties.

The generated server's `PUT {base}/{id}` never writes these columns from the body, and its response
carries the stored values (ADR-090 Amendment 1): the key, the owner of a TENANT or UNIVERSE entity,
`createdAt`, `createdBy`, `updatedBy`, the soft-delete flag, `deletedAt` and `deletedBy`, and every
field a `systemFields` role names, under the name the block gives it. The server sets `updatedAt` and
the version itself. `<Entity>Update` and `<Entity>UpdateSchema` now leave all of them out, the
properties the Java `<Entity>UpdateDto` leaves out. A versioned entity's update still requires the
version, the value the edit was loaded at, and a UNIVERSE entity's shared scope stays, because the
update writes it from the body.

```ts
// an audited, soft-delete, versioned TENANT entity
export type TicketUpdate = Omit<Ticket, 'id' | 'tenantId' | 'createdAt' | 'createdBy' | 'deleted' | 'deletedAt' | 'deletedBy' | 'updatedAt' | 'updatedBy'> & { version: number | null };
```

`<Entity>UpdateSchema` omits the same keys. The emitted edit form builds its request from the loaded
record with those properties taken out, so the request no longer carries them either.

A soft-delete entity's `PUT` to a row that is soft-deleted answers `404`, or `409` when the entity is
versioned, as a read of that row does.

**What to do:** where you build an update body from a loaded record, take the omitted properties out
of it before the call. TypeScript refuses a literal that names one, but an object with those
properties arriving through a spread still compiles, and the server ignores them:
`const { id, createdAt, ...rest } = loaded; service.update(id, { ...rest, title })`. A call that
sets one of them in a literal fails to compile; remove the property.
