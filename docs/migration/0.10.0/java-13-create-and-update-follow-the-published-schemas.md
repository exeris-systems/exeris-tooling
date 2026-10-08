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
`@Field(inUpdate = false)` through `PUT {base}/{id}`, and additive for a `POST` that omits a
`required` field its schema does not list.

The create and update routes each validate the fields their published body lists
([ADR-090, Amendment 3](../../adr/ADR-090-reject-mismatched-tenant.md)):

- `handleCreate` no longer answers `400` for a `required` field that `<Entity>CreateDto` leaves out:
  a read-only field, a field marked `@Field(inCreate = false)`, the key, the owner and a UNIVERSE
  entity's shared scope. The insert is unchanged and still writes the entity it is handed.
- A field marked `@Field(inUpdate = false)` is treated as a read-only field on `PUT`: it is out of
  `<Entity>UpdateDto`, out of `updateFromRequest`'s `UPDATE ... SET` list, read back so the response
  carries the stored value, and not validated. An action whose entity method changes it still stores
  the change, through `update`. An entity with such a field gets `updateFromRequest` on its
  repository and service, as an entity with a read-only field does.
- A field marked `@Field(inCreate = false)` is out of `<Entity>CreateDto` and still in
  `<Entity>UpdateDto`.
- The entity schema of a versioned entity that declares no version field lists the version,
  `integer`/`int64` and `readOnly`, under the name the `systemFields` block gives it.

**What to do:** a client that set an `inUpdate = false` field through `PUT` must write it through an
action or a route of your own. Code of your own that applies a client's values to such an entity
should call `updateFromRequest`; code that applies a domain change calls `update`. A client generated
from the OpenAPI document gains the `version` property on the entity type.
