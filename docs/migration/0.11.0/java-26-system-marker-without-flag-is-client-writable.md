---
title: "0.11.0 migration step: a system-field marker without its flag is a client-writable field"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-11-07
---

### A system-field marker without its flag is a client-writable field

A field in a system role whose `@ExerisDomain` flag is off is an ordinary domain field in the
generated API as well as in the migration and the repository. That is a field marked `@Version`,
`@AuditCreatedAt`, `@SoftDelete` or another system marker, or named for a role in the entity's
`systemFields` (`version`, `createdAt`, `deleted`, `tenantId`, …), on an entity whose flag for that
role is off: `versioned`, `audited`, `softDelete`, or a `TENANT` or `UNIVERSE` data scope for the
owning tenant. The generated API left such a field out of the create and update request bodies,
never offered it as a sort key or a filter, and the repository's `UPDATE` never wrote it, so
nothing could set the column.

After regenerating, for every such field:

- the `<Entity>CreateDto` and `<Entity>UpdateDto` schemas of the OpenAPI document carry it, and the
  create and update handlers validate it by its `@Field` rules;
- the list route accepts it as a `sort` key when it is sortable and as a filter when it is
  filterable, like any other field of its type;
- the repository's `UPDATE` writes it from the entity, and the `updateFromRequest` statement keeps
  it only when it is `readOnly` or `inUpdate = false`.

For example, an entity with neither `versioned` nor `audited` that declares

```java
@Version private Long version;
@AuditCreatedAt private Instant createdAt;
```

now updates with `UPDATE customers SET name = ?, version = ?, created_at = ? WHERE id = ?`, and a
client may send `version` and `createdAt` in both bodies.

An entity whose flag is on is unchanged: the field stays server-owned, out of the bodies, the sort
keys and the filters.

**What breaks.** A client that relied on the server dropping such a field from a request body now
has the value it sends stored, and a `PUT` without it stores `null`. Decide what the marker meant:

- the role: set the flag with the marker (`versioned = true`, `audited = true`, …). The field is
  then server-owned again, and the repository stamps or locks on it;
- an ordinary field: send its value in the create and update bodies like any other field.

**Where it applies.** Only to entities that declare a field in a system role whose flag is off.
