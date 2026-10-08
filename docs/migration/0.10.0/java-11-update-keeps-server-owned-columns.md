---
title: "0.10.0 migration step: an update no longer writes the audit and soft-delete columns from the request body"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-10-08
---

### An update no longer writes the audit and soft-delete columns from the request body

`Compatibility impact: breaking (ADR-090)`, for a client that sets a creation stamp, an author or a
soft-delete flag through `PUT {base}/{id}`.

The generated `PUT {base}/{id}` still replaces every domain field with the body's value. The
columns the server owns now keep their stored value, whatever the body says
([ADR-090, Amendment 1](../../adr/ADR-090-reject-mismatched-tenant.md)):

- the key and a tenant-partitioned entity's owner, as before;
- on an `audited` entity, the creation stamp and the fields marked `@AuditCreatedBy` and
  `@AuditUpdatedBy`;
- on a `softDelete` entity, the soft-delete flag and the fields marked `@SoftDeleteTimestamp` and
  `@SoftDeletedBy`;
- any field a `systemFields` role names for one of those roles.

The update stamp and the version are still set by the server: `updatedAt` is `Instant.now()`, and
a versioned entity's version is the expected version from the body plus one. A UNIVERSE entity's
shared-scope field is still written from the body, as ADR-090 decides.

After regenerating:

- the repository's `UPDATE` lists fewer columns. For an audited, soft-deleted, versioned entity
  `UPDATE orders SET order_number = ?, created_at = ?, updated_at = ?, deleted = ?, version = ? WHERE id = ? AND version = ?`
  becomes `UPDATE orders SET order_number = ?, updated_at = ?, version = ? WHERE id = ? AND version = ? AND deleted = false`;
- `update` reads the kept columns back in the same transaction, through a new private
  `readStoredColumns` method, so the entity it returns, and the `PUT` response, carry the stored
  creation stamp, author, owner and soft-delete flag rather than the body's;
- a `PUT` against a soft-deleted row answers `404`, or `409` on a versioned entity, like a `PUT`
  against a missing row; it no longer writes the row or clears its soft-delete flag;
- the OpenAPI `<Entity>UpdateDto` leaves out the audit and soft-delete fields. It keeps the version;
- the generated `RecordingPersistence` gains `writeBinds`, the binds of the last write, and the
  generated `<Entity>RepositoryTest` reads the update's binds from it.

**What to do:** a client that corrected a creation stamp or an author through `PUT`, or restored a
soft-deleted row by sending `deleted: false`, can no longer do so through the generated route. Write
such a change through your own route or repository method. The generated TypeScript `<Entity>Update`
type still carries these fields; the server ignores them.
