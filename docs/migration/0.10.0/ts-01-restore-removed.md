---
title: "0.10.0 migration step: `exeris-codegen-ts`: `restore()` is removed from a soft-delete entity's service and store (T58)"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-10-07
---

### `exeris-codegen-ts`: `restore()` is removed from a soft-delete entity's service and store (T58)

`<Entity>Service` and `<Entity>Store` of a `@SoftDelete` entity no longer have `restore(id)`. In
0.9.0 it was deprecated and sent no request: the service's Observable errored and the store set its
error and rejected, because nothing on the generated server un-sets the soft-delete flag. The service
also drops the `throwError` import that only `restore` used. `softDelete(id)` on the service and
`archive(id)` on the store are unchanged: they call `DELETE {base}/{id}`, which on a `@SoftDelete`
entity is the archive.

**What to do:** remove every call to `restore(...)`; the TypeScript compiler names each one. There is
no generated replacement. An app that needs to restore an archived row writes the route and the
repository statement by hand.
