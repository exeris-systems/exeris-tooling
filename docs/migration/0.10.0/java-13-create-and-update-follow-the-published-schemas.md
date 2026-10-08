---
title: "0.10.0 migration step: a POST validates the fields its schema lists, a PUT leaves inUpdate = false fields as stored, and the entity schema lists the version"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-10-08
---

### A POST validates the fields its schema lists, a PUT leaves inUpdate = false fields as stored, and the entity schema lists the version

`Compatibility impact: breaking (ADR-090)`, for a client that sets a field marked
`@Field(inUpdate = false)` through `PUT {base}/{id}`, and for code that creates a row with a
`required` read-only field left null, which now fails in the repository; additive for a `POST` that omits a `required` field its
schema does not list.

The create and update routes each validate the fields their published body lists
([ADR-090, Amendment 3](../../adr/ADR-090-reject-mismatched-tenant.md)):

- `handleCreate` no longer answers `400` for a `required` field that `<Entity>CreateDto` leaves out:
  a read-only field, a field marked `@Field(inCreate = false)`, the key, the owner and a UNIVERSE
  entity's shared scope. The insert still writes the entity it is handed; which values of it a
  `POST` body can set is [java-14](java-14-create-body-leaves-out-server-owned-fields.md).
- A field marked `@Field(inUpdate = false)` is treated as a read-only field on `PUT`: it is out of
  `<Entity>UpdateDto`, out of `updateFromRequest`'s `UPDATE ... SET` list, read back so the response
  carries the stored value, and not validated. An action whose entity method changes it still stores
  the change, through `update`. An entity with such a field gets `updateFromRequest` on its
  repository and service, as an entity with a read-only field does.
- A field marked `@Field(inCreate = false)` is out of `<Entity>CreateDto` and still in
  `<Entity>UpdateDto`.
- The entity schema of a versioned entity that declares no version field lists the version,
  `integer`/`int64` and `readOnly`, under the name the `systemFields` block gives it.

- A plain domain field of a reference type that is both `required` and `readOnly` is one the server
  sets before the row is written. The repository's `save` throws an `IllegalStateException`
  (`Cannot create <Entity>: field '<field>' is required and read-only, ...`) when it is still null,
  which the handler logs and answers with `500`, instead of the database's `NOT NULL` violation. A
  system-role field and a primitive are not checked. The generated repository test gains a
  `save<Field>LeftNull` case per such field.
- The metadata loader reads a field without `inCreate` / `inUpdate` keys as `true`, the `@Field`
  default; hand-written metadata JSON no longer drops those fields from the create and update bodies.

**What to do:** set a `required` read-only field in your own service (`MyTicketService extends
TicketService`, overriding `save`) before it reaches the repository. A client that set an `inUpdate = false` field through `PUT` must write it
through an action or a route of your own. Code of your own that applies a client's values to such an entity
should call `updateFromRequest`; code that applies a domain change calls `update`. A client generated
from the OpenAPI document gains the `version` property on the entity type.
