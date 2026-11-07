---
title: "0.11.0 migration step: exeris-codegen-ts: generated route guard and bearer interceptor specs under generateTests"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-11-07
---

### `exeris-codegen-ts`: generated route guard and bearer interceptor specs under `generateTests`

When test generation is on (`generateTests: true` or `--tests`) and some route demands an
identity, so that `core/route-guards.ts` and `core/auth.interceptor.ts` are emitted, the generator
also emits `core/route-guards.spec.ts` and `core/auth.interceptor.spec.ts` beside them. The guards
spec runs `requireAuthenticated` and `requireAnyPermission(...)` against an `AuthService` double
and asserts each guard's answer and the permission names it asks about. The interceptor spec sends
requests through Angular's testing HTTP backend and asserts the `Authorization: Bearer` header on
a request under `apiBasePath` while a token is held, and its absence with no token, for another
host and for a protocol-relative URL.

Both specs provide their own `AuthService` double, so a completed `core/auth.service.ts` seed does
not change their outcome. Apps with `generateTests` off (the default), and apps with no route that
demands an identity, are unaffected and their generated output stays byte-identical. Apps with
`generateTests` on and such a route receive the two spec files and execute them through `npm test`.
