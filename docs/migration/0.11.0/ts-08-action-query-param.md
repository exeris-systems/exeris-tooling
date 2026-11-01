---
title: "0.11.0 migration step: service sends query parameters for an action via HttpParams"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-10-30
---

### `exeris-codegen-ts`: the generated service method sends `@QueryParam` action parameters via `HttpParams`

The generated Angular service method for an entity action now sends query parameters (`@QueryParam`)
via `HttpParams` under their declared query key. Parameters bound from the request body (`@ActionParam`)
continue to be sent in the JSON request body.

When an action declares only query parameters, the service sends an empty body object `{}` alongside
the query parameters.
