---
title: "0.11.0 migration step: a @Blob field is served on its own routes and is no longer a column"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-11-03
---

### A `@Blob` field is served on its own routes and is no longer a column

A field whose metadata carries the `@Blob` facet, declared as the kernel's `BlobRef`, keeps its
bytes in the kernel's blob store (ADR-108). For each such field `f` of an entity at `{base}` the
generated application serves:

| Route | Answers |
| --- | --- |
| `PUT {base}/{id}/blobs/f` | stores the raw body under its `Content-Type`: `204`; `400` for an empty body, `415` for a media type outside a non-empty `contentTypes`, `404` for an absent row |
| `GET {base}/{id}/blobs/f` | the stored bytes with their stored `Content-Type`; `404` for an absent row or object |
| `HEAD {base}/{id}/blobs/f` | no body, the stored object's `Content-Type` and `Content-Length`; `404` as for `GET` |
| `DELETE {base}/{id}/blobs/f` | `204` whether or not an object was stored; `404` for an absent row |

The object's key is `<id>/f` in the field's `@Blob.container`, or in the entity's table name when
none is declared, so a second upload replaces the first. A hard delete of the row deletes its
objects afterwards, logging a failed object delete at warning level without failing the request; a
soft delete keeps them. Every route carries the entity's route requirement.

This is breaking for an entity that already declared such a field:

- The field is no column. The generated migration, repository, finders, list filters and sort
  properties leave it out; a table created by an earlier migration keeps its column, which nothing
  reads or writes any more. Drop it with a migration of your own when you are ready.
- The field is out of the create and update bodies and their OpenAPI schemas. A value a body names
  is reset to `null` before the service sees it, and the entity's JSON always carries the field as
  `null`; read the descriptor with `HEAD` on the field's route.
- `<Entity>Handler` takes a `BlobStore` as its last constructor argument, and
  `RuntimeComponents.create<Entity>Handler()` passes `KernelProviders.BLOB_STORE.get()`, which
  `COMPOSITION_SCOPES` lists. An override of that factory passes a store too.
- `Application.subsystems()` lists `storage`, and `exeris:verify-runtime` requires a
  `BlobStorageProvider`. Configure `storage.blob.provider` (and `storage.blob.location`): without a
  provider the kernel binds no store and the composition fails at boot. The open-core drivers are in
  `eu.exeris:exeris-kernel-community`.

Uploads are bounded by the kernel's `maxRequestBodyBytes`; there is no multipart and no signed-URL
redirect. An application without a `@Blob` field regenerates byte-identically.
