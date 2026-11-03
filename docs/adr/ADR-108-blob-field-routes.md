---
title: "ADR-108 — The generated application serves a @Blob field on its own routes and requires a blob store at boot"
type: adr
visibility: public
owning-repo: exeris-tooling
status: active
slug: adr/ADR-108
---

# ADR-108 — The generated application serves a `@Blob` field on its own routes and requires a blob store at boot

- **Status:** ACCEPTED (2026-11-01) · accepted-on-merge per the per-repo pattern (ADR-047 / ADR-058) ·
  amended 2026-11-03 (Amendment 1 — the descriptor is read with `HEAD` on the field's route)
- **Deciders:** the founder; `exeris-tooling` (processor, kernel handler and application emitters, TS
  emitters)
- **Repo:** `exeris-tooling`
- **Scope:** tooling / codegen — per-repo
- **Visibility:** public
- **Milestone:** 0.11.0 (`docs/0.11.0-release-plan.md`, rows J3 and S2)
- **Relates to:** SDK ADR-072 (the frozen `@Blob` surface and the GLOBAL refusal), kernel ADR-056
  (the blob storage SPI), ADR-042 (processor and SDK reader agree), ADR-090 (server-owned fields stay
  out of request bodies), ADR-105 (the generated route policy), ADR-015 and ADR-092 (output stability)
- **Supersedes / superseded by:** —
- **Decided in:** `docs/rfc/RFC-2026-11-01-blob-field-routes.md`

## Context and Problem Statement

`@Blob` marks a field whose bytes live in the kernel's blob store instead of a column. The SDK freezes
its surface for 1.0 (`container`, `contentTypes`; SDK ADR-072) and reads it into
`FieldMetadata.blob`; the kernel's `spi.storage.blob` is stable (kernel ADR-056). The processor lists
`@Blob` as inert, so a field carrying it is emitted as an ordinary column and nothing stores its bytes.

The kernel bounds the shape. An upload needs its length before `beginUpload`; a request and a
respond-once response are each one buffer, capped by `maxRequestBodyBytes` (10 MiB by default); there
is no multipart parser; `signedUrl` is optional per store and absent on the filesystem driver; and
`storage.blob.provider` unset means no store is bound.

**The question this ADR answers:** what the generated application emits for a `@Blob` field.

## 🏁 The Decision

**Each `@Blob` field is served on its own routes with a raw body, stored under a key derived from the
row and the field, shown in the entity as a read-only descriptor, and backed by a blob store the
application requires at boot.**

1. **Build-time refusals.** The processor refuses `@Blob` on a `dataScope = GLOBAL` entity (SDK ADR-072
   obligation 30) and on a field whose type is not the kernel's `BlobRef`, each with a stable
   diagnostic. Otherwise it writes `FieldMetadata.blob` in the SDK reader's shape (ADR-042).
2. **Routes.** For each `@Blob` field `f` of an entity at `{base}`:
   - `PUT {base}/{id}/blobs/{f}` stores the request body under the request's `Content-Type`; answers
     `204`. An empty body is refused with `400`.
   - `GET {base}/{id}/blobs/{f}` answers the stored bytes with their stored content type, read through
     `openDownload` into one response buffer; `404` when the row or the object is absent.
   - `DELETE {base}/{id}/blobs/{f}` deletes the object; `204` whether or not one was stored; `404`
     when the row is absent.
   No multipart, no signed-URL redirect. The path segment is the field's name as declared.
3. **Content type and size.** A non-empty `contentTypes` is enforced on upload: any other
   `Content-Type` answers `415` before anything is written. A `@Validation` size bound on the field is
   enforced the same way (`413`). The kernel's `maxRequestBodyBytes` bounds every upload.
4. **Key and container.** The object's `BlobRef` is `(container, "<id>/<f>")`. A blank
   `@Blob.container` derives to the entity's table name. A second upload replaces the first; the key
   is deterministic, so nothing is left behind.
5. **The field in the entity.** The entity's JSON carries the field as a read-only descriptor,
   `{ "contentType": …, "sizeBytes": … }` from `BlobStore.stat`, or `null` when nothing is stored. The
   field is server-owned: it is left out of the create and update bodies, schemas and TS types
   (ADR-090), and the TS model types it `BlobDescriptor | null`.
6. **Row lifecycle.** A hard delete of the row deletes its objects after the row delete commits; a
   failed object delete is logged at warning level and does not fail the request. A soft delete keeps
   them.
7. **A store is required.** An application with a `@Blob` field lists `storage` among the subsystems it
   requires, so a deployment without `storage.blob.provider` fails at boot rather than at the first
   upload.
8. **Authorization.** In the generated route policy (ADR-105), upload and delete take the entity's
   update requirement and download takes its get-by-id requirement.
9. **The frontend (S2).** The generated form shows a file control per `@Blob` field, with `accept`
   from `contentTypes`, that uploads after the row is saved; the service gains upload, download and
   delete calls on these routes; the detail view shows the descriptor and a download link.

## Consequences

### ✅ Positive Outcomes

- Works on every kernel blob driver: nothing depends on signed URLs or multipart.
- The object for a field is found from the row alone, and a re-upload leaves no orphan.
- A misconfigured deployment fails at boot, where the cause is named.
- The TCK facet `BLOBS` can come off the processor binding's unsupported list.

### ⚠️ Trade-offs

- Every get and list response costs one `stat` per `@Blob` field.
- Files are bounded by `maxRequestBodyBytes`; a larger upload needs a later, additive route.
- Every download passes through the application.

### Neutral

- A signed-URL download can be added later behind the same `GET` route without changing it.

### 🚫 Non-Goals

- `multipart/form-data`, on these routes or on the entity's create and update routes.
- An upload together with the row's create: the bytes go to the field's route after the row exists.
- A signed-URL upload or a redirect to a signed URL on download.
- Uploads above the kernel's `maxRequestBodyBytes`, and streaming transfers.
- Cleanup of a soft-deleted row's objects, and of objects orphaned by a failed delete.
- `@Blob` on a `GLOBAL` entity, and blob fields of any type other than `BlobRef`.

### ⚠️ Risks and Assumptions

- **Assumes:** kernel 0.13's blob SPI and HTTP layer as obligation 2 states them: a known length before
  `beginUpload`, buffer transfers, and one request and response buffer.
- **Assumes:** the deployment's `maxRequestBodyBytes` is large enough for the application's files; the
  generated code does not raise it.
- **Risk:** the descriptor costs a `stat` per `@Blob` field on every get and list. On a store where
  `stat` is a network round trip, a list of many rows multiplies it.
- **Risk:** an object whose delete fails after a hard delete stays in the store with no row pointing at
  it.
- **Reversed by:** a kernel HTTP layer with streaming request bodies or a multipart parser, which
  would let uploads exceed one buffer and travel with the row's create; a signed-URL capability on
  every driver, which would make the redirect the default download; or a measured `stat` cost on
  list responses large enough to move the descriptor to the download route.

## Cross-references

- `docs/rfc/RFC-2026-11-01-blob-field-routes.md` — the options and the founder's choices.
- SDK `Blob.java`, `BlobMetadata`, SDK ADR-072 obligations 2, 29, 30.
- Kernel ADR-056; `BlobStore`, `BlobRef`, `BlobStorageProvider`; `docs/subsystems/storage.md`.

## Engineering Protocol

- J3 adds the refusals (stable diagnostics, `docs/diagnostics.md`), the extraction, the routes, the
  descriptor, the lifecycle hook and `storage` in the required subsystems, with processor, generator
  and e2e compile tests, and removes `Facet.BLOBS` from the TCK binding's unsupported list and ceiling.
- S2 adds the TS half with specs and an `ng test` run of the generated sample app.
- Both carry `accepted-api-changes.json` entries and migration fragments: the field leaves the create
  and update bodies, which is breaking (ADR-015, ADR-092).

## Amendment 1 — the descriptor is read with `HEAD` on the field's route (2026-11-03)

**Status:** Accepted *(replaces obligation 5, the descriptor half of obligation 9 and the size
sentence of obligation 3; the routes, the key, the refusals and the required store are unchanged)*

The generated application emits no response type of its own: every route answers with the
`@ExerisDomain` class itself, encoded by the kernel's response encoder, and a `@Blob` field is
declared as a `BlobRef`. A `{ "contentType", "sizeBytes" }` member in the entity's JSON would need a
response type per entity, its page envelope and its OpenAPI schemas, and would cost one `stat` per
`@Blob` field on every row of every list, the risk the Decision already names. Obligation 5 is
replaced by:

5. **The field in the entity.** The generated application never fills a `@Blob` field: it is not a
   column, it is reset to `null` after a create or update body is decoded, and the entity's JSON
   carries it as `null`. It is server-owned and left out of the create and update bodies, schemas
   and TS types (ADR-090); the TS model leaves it out of the entity type. The descriptor is
   `HEAD {base}/{id}/blobs/{f}`: the kernel router answers `HEAD` with the field's `GET` route
   (RFC 9110 §9.3.2), and the handler, seeing `HEAD`, answers from `BlobStore.stat` alone, with no
   body and with `Content-Type` and `Content-Length` set to the stored object's; `404` when the row or
   the object is absent. Both headers are CORS-safelisted, so a browser client reads them.

In obligation 9 the detail view loads the descriptor with that `HEAD` request, and the service gains
a stat call beside upload, download and delete. In obligation 8 `HEAD` takes the download
requirement, as the route policy already answers `HEAD` from the `GET` rows.

In obligation 3 the sentence "A `@Validation` size bound on the field is enforced the same way
(`413`)" is withdrawn: no `@Validation` attribute in SDK 0.13 bounds a byte count (`maxLength` bounds
a string's length, `max` a number), and `@Blob` carries no size by design. The kernel's
`maxRequestBodyBytes` is the one size bound, and the kernel answers `413` for it before the handler
runs; neither the handler nor the OpenAPI operation declares `413`. A byte bound needs an SDK
attribute first, and is then added to the upload route.

The trade-off "every get and list response costs one `stat` per `@Blob` field", and the risk of the
same cost on a store where `stat` is a network round trip, no longer apply: a `stat` is paid only by
a client that asks for the descriptor. The reversal condition about a measured `stat` cost on list
responses is moot.

**Assumes:** the kernel's HTTP/1.1 response writer keeps an application `Content-Length` header and
supplies its own only when none is set (`CommunityHttpResponseHeaders.write`, kernel 0.13).

Verification: an e2e test drives the generated handler with `HEAD` and asserts the two headers and
an empty body, and with `GET` asserts the bytes; the generated entity's JSON after an upload carries
the field as `null`.
