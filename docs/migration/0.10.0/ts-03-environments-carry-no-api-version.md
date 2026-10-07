---
title: "0.10.0 migration step: `exeris-codegen-ts`: the environments carry no `apiVersion` (T38)"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-10-07
---

### `exeris-codegen-ts`: the environments carry no `apiVersion` (T38)

The emitted `environment.development.ts` no longer carries `apiVersion`, and a newly written
`environment.ts` does not either. No emitted service, store or client reads it, and no generated
route has a version segment. `ClientConfig`, the shape the KERNEL strategy's `getClientConfig()`
returns, loses the field with it.

**What to do:** remove any read of `environment.apiVersion` from your own code. `environment.ts` is
written only when it is absent, so an existing app keeps the key there until you delete it; the key
is harmless, but nothing reads it.
