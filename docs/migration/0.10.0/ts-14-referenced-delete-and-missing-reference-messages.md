---
title: "0.10.0 migration step: `exeris-codegen-ts`: the error message names a still-referenced delete and a missing related record"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-10-14
---

### `exeris-codegen-ts`: the error message names a still-referenced delete and a missing related record

`Compatibility impact: additive`, for an app that shows the generated `httpErrorMessage` text.

The generated server answers a delete of a row other rows still reference with `409`, and a write
whose relationship names a row that does not exist with `422`
([ADR-076, Amendment 1](../../adr/ADR-076-write-rejection-status.md)). `core/http-error.ts` now says
so: a `409` on a delete reads "… is still referenced by other records. Remove or change them first."
instead of the version-conflict text, and a `422` reads "… refers to a related record that does not
exist." in place of the generic "(status 422)". The helper still reads no response body.

**What to do:** nothing; regenerate to pick up the wording. A test of your own that pinned the old
text for these statuses needs the new one.
