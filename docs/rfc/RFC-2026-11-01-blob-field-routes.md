---
title: "RFC-2026-11-01: How the generated kernel application serves a @Blob field"
type: rfc
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-11-01
---

# RFC-2026-11-01: How the generated kernel application serves a `@Blob` field

| Field            | Value |
|:-----------------|:------|
| **Status**       | **ACCEPTED** |
| **Author(s)**    | exeris-tooling |
| **Date Opened**  | 2026-11-01 |
| **Date Closed**  | 2026-11-01 |
| **Target ADR(s)**| ADR-108 |
| **Affected Repos**| exeris-tooling |
| **Reviewers**    | the founder |

## Question

What does the generated application emit for an entity field that carries `@Blob`: which routes upload,
download and delete its bytes, how the field appears in the entity's DTO and TypeScript model, what key
and container the bytes are stored under, and what the application does when the deployment binds no
blob store?

## Context

`@Blob` (SDK 0.11, frozen for 1.0 by SDK ADR-072 obligation 29) marks a field whose bytes live in the
kernel's blob store rather than in a column. SDK 0.13 reads it into `FieldMetadata.blob`
(`BlobMetadata(container, contentTypes)`), and the kernel's `spi.storage.blob` is stable since 0.11
(kernel ADR-056). The processor still lists `@Blob` as inert: the field is emitted as if the
annotation were absent. 0.11.0 rows J3 (extraction and Java emission) and S2 (the TS file control and
service calls) wait on this decision, and so does the TCK facet `BLOBS`, the last of the seven facets
the processor's TCK binding declares unsupported. The release plan's wave 0 asks for an RFC, then an
ADR.

## Investigation

### Constraints

**From the SDK (decided, not open):**
- `@Blob` carries `container` and `contentTypes` only. Size is not its concern; a bound is
  `@Validation`'s (SDK ADR-072 obligation 2, `Blob.java`).
- `contentTypes` lists what the generated upload surface accepts; empty means any. It is not a
  validation rule (`Blob.java`).
- A blank `container` means tooling derives it from the owning domain; the container is
  tenant-relative, never a bucket or path (`Blob.java`, `BlobMetadata`).
- `@Blob` on a `dataScope = GLOBAL` entity is refused at build time by tooling, with a diagnostic
  (SDK ADR-072 obligation 30; kernel ADR-056 puts GLOBAL blob storage out of scope).

**From the kernel 0.13.1 SPI:**
- `BlobStore.beginUpload(BlobRef, contentLength, contentType)` needs the length up front; transfers are
  `MemorySegment` buffers through upload and download handles, no streams (ADR-056 obligation 3).
- `BlobRef(container, key)` is tenant-relative; the store resolves the tenant from the ambient storage
  context and refuses with `EX-BLOB-8002` when none is bound.
- `signedUrl` is optional per store: the filesystem driver has none.
- An HTTP request and a respond-once response are each one buffer. `HttpConfig.maxRequestBodyBytes`
  defaults to 10 MiB. There is no multipart parser and no streaming response body except SSE.
- `storage.blob.provider` selects the driver; unset means no store is bound, and an unknown id fails the
  boot with `EX-BLOB-8008`.

## Options and recommendation

Each subsection lists the options and the one this RFC recommends.

### 1. Routes

- **(a) Per field, raw body (recommended).** `PUT {base}/{id}/blobs/{field}` with the bytes as the body
  and the media type in `Content-Type`; `GET {base}/{id}/blobs/{field}` answers the bytes with the
  stored type; `DELETE {base}/{id}/blobs/{field}`. One route set per `@Blob` field, registered like the
  action routes. The body is one buffer, so its length is known for `beginUpload`.
- (b) `multipart/form-data` on the entity's create and update routes. Needs a multipart parser the
  kernel does not have, and couples a byte transfer to the row write.
- (c) Signed-URL handshake only (`POST …/upload-url` returns a URL the client PUTs to). Unavailable on the
  filesystem driver, so it cannot be the only path.

### 2. The field in the DTO and the TypeScript model

- **(a) A read-only descriptor (recommended).** The field's type is the kernel's `BlobRef`; the entity
  JSON carries `{ contentType, sizeBytes }` from `BlobStore.stat`, or `null` when nothing is stored, and
  the field is server-owned: left out of the create and update bodies (as ADR-090 does for other
  server-owned fields). The TS model types it `BlobDescriptor | null`.
- (b) The raw `BlobRef` (`container`, `key`). Exposes storage addressing the client cannot use.
- (c) Omit the field from the DTO. The client cannot tell whether anything is stored without a request.

### 3. Key and container

- **(a) Deterministic key `<id>/<field>` in a derived container (recommended).** A blank `container`
  derives to the entity's table name; the key is the row id and the field name. A second upload
  replaces the first; a delete is idempotent; nothing is left behind by a re-upload.
- (b) A random key per upload, stored in a column. Keeps old versions until cleaned, and needs a column
  the SDK does not model.

### 4. Download

- **(a) Proxy the bytes (recommended).** The download route reads through `openDownload` into one
  response buffer. Works on every driver.
- (b) `302` to `signedUrl` when the store offers one, else proxy. Two behaviours per deployment; can be
  added later behind the same route without changing it.

### 5. `contentTypes`, size and errors

- **Recommended.** The server refuses a `Content-Type` outside a non-empty `contentTypes` with `415`
  before it writes; the TS file control sets `accept` from the same list. Size is bounded by the
  kernel's `maxRequestBodyBytes` (`413` from the dispatcher) and by any `@Validation` bound on the field.
  A missing row answers `404`, a download of an empty field `404`, a store failure `500`.

### 6. No store bound

- **(a) Required at boot (recommended).** An application with a `@Blob` field lists `storage` among its
  required subsystems, so a deployment without `storage.blob.provider` fails at boot instead of on the
  first upload.
- (b) The routes answer `503` while no store is bound. Fails late, at the first request.

### 7. The row's lifecycle

- **Recommended.** Deleting the row deletes its blobs after the row delete commits; a failed blob delete
  is logged and does not fail the request (the row is gone; an orphaned object is a cleanup concern). A
  soft delete keeps them.

### 8. Field types and authorization

- **Recommended.** A `@Blob` field must be typed `BlobRef`; any other type is refused with a diagnostic,
  as is `@Blob` on a GLOBAL entity. Upload and delete follow the entity's update route requirement, and
  download its get-by-id requirement, in the generated route policy (ADR-105).

## Consequences of the recommendation

- J3: extraction into `FieldMetadata.blob`, the two refusals, the route set, `storage` in the required
  subsystems, the stat-backed descriptor, blob deletion with the row; the `BLOBS` facet comes off the
  TCK's unsupported list.
- S2: a file control in the generated form (upload after the row is saved), download and delete calls on
  the entity service, the descriptor in the detail view.
- Not in 0.11: signed-URL download (4b) and uploads above `maxRequestBodyBytes`; a later additive change.

## Outcome

Accepted on 2026-11-01 by the founder, with every recommendation above: per-field routes with a raw
body (1a), a read-only descriptor (2a), the deterministic key in a derived container (3a), proxied
downloads (4a), the storage subsystem required at boot (6a), and the recommendations of 5, 7 and 8.
ADR-108 records the decision.
