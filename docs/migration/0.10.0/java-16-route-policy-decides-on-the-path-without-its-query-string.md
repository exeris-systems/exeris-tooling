---
title: "0.10.0 migration step: the route policy decides on the path without its query string, and on HEAD as GET"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-10-14
---

### The route policy decides on the path without its query string, and on HEAD as GET

`Compatibility impact: additive` (ADR-105): the emitted `Application.routePolicy()` now matches its
documented contract, and a rule that never matched a request with a query string now does.

`Application.routePolicy()` folds `applicationPolicy()`, `GeneratedRoutePolicy` and
`unmatchedRoutes()` as before, then hands each of them the request path with any query string removed
and a `HEAD` request as `GET`. The router serves a `HEAD` request from the `GET` route when none is
registered for `HEAD`, so a `GET` rule now governs both. A new private `pathWithoutQuery(String)`
helper is emitted beside it.

- An exact-path rule such as `path.equals("/reports")` in `applicationPolicy()` refuses
  `GET /reports?x=1` and `HEAD /reports` as it refuses `GET /reports`.
- A rule written for `HEAD` alone is not consulted: `HEAD` is asked as `GET`.

**What to do:** regenerate; `Application.java` changes. An application that overrides
`routePolicy()` answers for the request as the kernel hands it and normalises the path and method
itself.
