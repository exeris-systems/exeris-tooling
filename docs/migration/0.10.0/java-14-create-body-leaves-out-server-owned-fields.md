---
title: "0.10.0 migration step: a POST ignores the audit, version and soft-delete fields, and the entity schema lists createdAt and updatedAt only for an audited entity"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-10-08
---

### A POST ignores the audit, version and soft-delete fields, and the entity schema lists createdAt and updatedAt only for an audited entity

`Compatibility impact: breaking (ADR-090)`, for a client that sets an audit, version or soft-delete
field through `POST {base}`; additive otherwise.

A create is server-owned the way a `PUT` is
([ADR-090, Amendment 4](../../adr/ADR-090-reject-mismatched-tenant.md)):

- `<Entity>CreateDto` and the create route's validation leave out the audit fields (created and
  updated at and by), the version and the soft-delete fields (flag, time and actor), under the names
  the `systemFields` block gives them, besides the key, the owner, a UNIVERSE entity's shared scope,
  the read-only fields and the `inCreate = false` fields.
- `handleCreate` sets those properties back to the server's initial values before the service sees
  the entity: times and actors `null`, the version `0`, the soft-delete flag `false`. The repository
  stamps `createdAt` and `updatedAt` with the current time, as it did.
- The entity schema of the OpenAPI document lists `createdAt` and `updatedAt` for an audited entity,
  under their role names and `readOnly`; an entity that is not audited and declares no such field no
  longer has them.

**What to do:** set an author, or any other value the body can no longer carry, in your own service
(`MyNoteService extends NoteService`, overriding `save`) before it reaches the repository; the value
is kept. A client generated from the OpenAPI document loses `createdAt` and `updatedAt` on the entity
type of an entity that is not audited.
