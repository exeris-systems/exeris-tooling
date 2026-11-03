---
title: "0.11.0 migration step: declared route access becomes the generated route policy"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-11-01
---

### Declared route access becomes the generated route policy

The processor writes `@RouteAccess` and `permissions`, on the entity and on each action, into the
metadata, and the generated `GeneratedRoutePolicy` compiles them into the kernel route policy
(ADR-105). Every route of an entity carries the entity's requirement; an action that declares its
own replaces it on that action's route:

| The entity or action declares | The route requires |
| --- | --- |
| `@RouteAccess(PUBLIC)` | nothing (`permitAll()`) |
| a non-empty `permissions` | an identity holding any one of them |
| `@RouteAccess(AUTHENTICATED)`, no `permissions` | an identity |
| nothing, and neither does its entity | whatever `Application.unmatchedRoutes()` answers, `permitAll()` unless overridden |

An application that declares no access anywhere regenerates byte-identically and every route stays
public. Once a route demands an identity, `Application.subsystems()` lists `security`, and a
request without a valid bearer token answers `401` on it (`403` when the token lacks the scopes).
Without a security provider on the classpath such a route answers `401` to everyone: add the kernel
security provider, `eu.exeris:exeris-kernel-community` in the open-core tree, and configure
`security.jwks.uri`, `security.jwt.issuer` and `security.jwt.audience`.

A route the application adds in `configureRoutes` and that a generated template also matches is
answered by the generated row unless `applicationPolicy()` answers for it first.

Under `-Aexeris.strict`, `@RouteAccess` and the `permissions` attributes no longer report as having
no effect.
