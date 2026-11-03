---
title: "0.11.0 migration step: @Blob fields are served on dedicated routes and leave the entity types"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-11-03
---

### `exeris-codegen-ts`: @Blob fields are served on dedicated routes

A `@Blob` field is server-owned: the kernel application never fills it in the entity's JSON and
serves its bytes on the field's own routes (ADR-108). An entity without `@Blob` fields generates
exactly as before. For one with them, the generated TypeScript client:

1. Leaves `@Blob` fields out of the emitted entity interface, `<Entity>Create`, `<Entity>Update`,
   Zod schemas, form models, and list queries.
2. Emits `export interface BlobDescriptor { readonly contentType: string; readonly sizeBytes: number; }`
   once in the shared `types/index.ts` barrel.
3. Emits dedicated service methods per blob field: `upload<Field>(id, file)`, `download<Field>(id)`,
   `delete<Field>(id)`, and `stat<Field>(id)` (HEAD returning `BlobDescriptor | null`).
4. Generates a file input with `accept` attributes on the form, uploading selected files
   after a successful create or update before navigating.
5. In the detail view, fetches the descriptor with `stat<Field>` to display content type and
   size, and provides a download button saving the blob via an object URL.
6. Registers route requirements for `PUT`, `GET`, `DELETE {base}/{id}/blobs/{field}` matching the
   entity's access requirement.

**What breaks.** Code that read a `@Blob` field from an entity, or wrote it into a create or update
payload, no longer compiles, because the field is gone from those types. The server never stored a
value sent that way. Read the object with `stat<Field>` or `download<Field>`, and send it with
`upload<Field>` after the row exists.
