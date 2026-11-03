---
title: "0.11.0 migration step: @Blob is extracted into domain metadata"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-11-03
---

### `@Blob` is extracted into domain metadata

The processor extracts `@Blob` (`eu.exeris.sdk.annotation.Blob`) on entity fields into
`FieldMetadata.blob()` (`BlobMetadata`), matching the SDK reader (ADR-042). A `@Blob` field is
admitted into metadata whether or not it carries `@Field`, recording its optional `container`
(blank normalised to `null`) and declared `contentTypes` in declaration order. Dedicated routes
for `@Blob` fields arrive in the same release (ADR-108).

Two invalid declarations are now refused at build time:

- `EXT-PROC-1031`: `@Blob` on a field of an entity whose `dataScope` is `GLOBAL` (SDK ADR-072
  obligation 30). Global scope leaves `StorageContext.isolationKey` empty, and the kernel blob
  store terminally denies blob operations without an isolation key. To fix, declare
  `dataScope = TENANT` or `UNIVERSE` on the entity, or remove `@Blob` from the field.
- `EXT-PROC-1032`: `@Blob` on a field whose type is not `eu.exeris.kernel.spi.storage.blob.BlobRef`.
  The generated application serves `@Blob` fields on dedicated routes backed by the kernel blob
  store. To fix, declare `private BlobRef <fieldName>;` on the field, or remove `@Blob`.

Under `-Aexeris.strict`, `@Blob` no longer reports as an inert or unread annotation.
