---
title: "0.10.0 migration step: a PUT no longer writes read-only fields, and the update schema lists the shared scope and the version"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-10-08
---

### A PUT no longer writes read-only fields, and the update schema lists the shared scope and the version

`Compatibility impact: breaking (ADR-090)`, for a client that sets a field marked
`@Field(readOnly = true)` through `PUT {base}/{id}`.

A read-only field is one the client does not set
([ADR-090, Amendment 2](../../adr/ADR-090-reject-mismatched-tenant.md)). The generated
`PUT {base}/{id}` keeps its stored value; an action whose entity method changes it still stores the
change. An entity with a read-only field gets a second update for that:

- `<Entity>Repository#updateFromRequest` and `<Entity>Service#updateFromRequest`, which the `PUT`
  handler calls. Its `UPDATE … SET` list leaves the read-only fields out, and it reads their stored
  value back, through a private `readStoredRequestColumns`, so the `PUT` response carries the stored
  value rather than the body's;
- `update`, which the action routes call, as before. It writes the read-only fields, and keeps the
  server-owned columns as every update does.

An entity without a read-only field has `update` alone. A field that also plays a system role keeps
that role's rule: a read-only version is still matched and incremented, and a read-only shared scope
is still written.

After regenerating:

- for an entity with a read-only `status`, the `PUT` writes
  `UPDATE orders SET order_number = ? WHERE id = ?` and an action writes
  `UPDATE orders SET order_number = ?, status = ? WHERE id = ?`;
- `handleUpdate` validates only the fields the update body carries. A `required` read-only field, or
  a required server-owned one, no longer answers `400` to a `PUT` that leaves it out; `handleCreate`
  still validates every field;
- the OpenAPI `<Entity>UpdateDto` now lists a UNIVERSE entity's shared-scope field, which the update
  writes from the body, and, on a versioned entity, the version field, `integer`/`int64`, under the
  name the `systemFields` block gives it, also when the entity declares no such field. It still
  leaves out read-only fields.

**What to do:** a client that set a read-only field through `PUT` must write it through an action or
a route of your own. Code of your own that applies a client's values to an entity with read-only
fields should call `updateFromRequest`; code that applies a domain change calls `update`. A client
generated from the OpenAPI document sends the shared scope and the expected version in the update
body.
