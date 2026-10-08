---
title: "0.10.0 migration step: the generated `Application` binds a route policy and gains three hooks"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-10-08
---

### The generated `Application` binds a route policy and gains three hooks

The generated `Application` binds `HttpKernelProviders.HTTP_ROUTE_POLICY` beside the server handler,
and a new generated file, `GeneratedRoutePolicy`, sits in the base package next to it. The policy
declines every route, so each route answers as it did before: it is public, and its handler runs
without a principal.

`Application` gains three `protected` methods:

- `routePolicy()` returns `HttpRoutePolicy.firstDeclared(List.of(applicationPolicy(),
  GeneratedRoutePolicy.INSTANCE), unmatchedRoutes())`.
- `applicationPolicy()` is your own route rules. It is asked first and abstains on every route by
  default.
- `unmatchedRoutes()` is the answer for a route no policy describes. It returns `permitAll()` by
  default, so an undeclared route is public.

A subclass that overrides a requirement-bearing `applicationPolicy()` must also put `security` in
`subsystems()`, or the route answers `401`.

If you edited the generated `Application` to bind an `HTTP_ROUTE_POLICY` of your own, regeneration
rewrites that file and drops the binding. Move the policy into an `applicationPolicy()` override in
a subclass of `Application` and start the subclass from its own `main`. An `Application` you
detached (`exeris:detach`) is yours and is not rewritten; add the generated binding by hand, or move
your policy into the same override.
