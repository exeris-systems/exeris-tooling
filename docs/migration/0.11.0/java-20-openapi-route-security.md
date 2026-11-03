---
title: "0.11.0 migration step: the OpenAPI document declares bearer security on the routes that demand an identity"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-11-03
---

### The OpenAPI document declares bearer security on the routes that demand an identity

The emitted `openapi/<entity>-api.yaml` states the requirement the generated `GeneratedRoutePolicy`
binds for each route (ADR-079 Amendment 2, ADR-105). An operation whose route demands an identity
carries its own `security`, and its response set gains the statuses the kernel answers at the edge,
before the handler runs:

| The route requires | `security` on the operation | Added responses |
| --- | --- | --- |
| an identity (`@RouteAccess(AUTHENTICATED)`) | `[{ bearerAuth: [] }]` | `401` |
| any one of `permissions` `s1`, …, `sn` | `[{ bearerAuth: [s1] }, …, { bearerAuth: [sn] }]`, in declaration order | `401`, `403` |
| nothing (`@RouteAccess(PUBLIC)`), or no declaration | none | none |

An action route, streaming or not, takes its action's declaration when it has one, and its
entity's otherwise; the collection, item, `@Blob` field, live-view and spectate routes take the
entity's. Neither added response has a
body. A document with such an operation declares
`components.securitySchemes.bearerAuth: { type: http, scheme: bearer }`; no document sets a
root-level `security`.

This applies only to an application that declares access. One that declares none regenerates a
byte-identical document, and every operation stays unauthenticated.

For a consumer of the document, a client generator fed it sends an `Authorization: Bearer`
header on the secured operations and on no other, and a scoped operation's alternatives read as
"any one of these scopes", which is what the policy enforces. A tool that treated the document as
having no security, or that reads only a root-level `security`, sees the requirements on the
operations instead.
