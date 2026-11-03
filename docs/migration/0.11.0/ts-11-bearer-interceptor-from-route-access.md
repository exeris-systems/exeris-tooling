---
title: "0.11.0 migration step: the frontend sends the bearer token where a route demands an identity"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-11-01
---

### `exeris-codegen-ts`: the bearer interceptor follows declared route access

`exeris-codegen-ts` derives each route's requirement from the same `@RouteAccess` and
`permissions` the generated route policy compiles (ADR-105), held to
`contract/route-policy.json`. When some route demands an identity, the generated app registers
the bearer `HttpInterceptorFn`, which adds `Authorization: Bearer <AuthService.token()>` to its
requests. An app whose routes demand no identity regenerates byte-identically.
