---
title: "0.11.0 migration step: pages are guarded and stream clients carry the bearer token where a route demands an identity"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-11-03
---

### `exeris-codegen-ts`: route guards and identity-requiring stream clients follow declared route access

This step applies to an entity or action that declares access: `@RouteAccess(AUTHENTICATED)` or a
non-empty `permissions`, which the generated route policy compiles (ADR-105). An app whose entities
and actions declare no access, or only `@RouteAccess(PUBLIC)`, regenerates byte-identically.

When some route demands an identity, the generated tree gains `core/route-guards.ts`, a generated
file with two functional guards over `AuthService`: `requireAuthenticated`, and
`requireAnyPermission(...permissions)`, which admits a user holding any one of the names. A refused
navigation returns `false` and stays on the current page; no sign-in route is generated.

Each entity page is guarded by the requirement of the backend route it calls first: the list page
by `GET {base}`, the detail page by `GET {base}/{id}`, the create page by `POST {base}` and the edit
page by `PUT {base}/{id}`. An `AUTHENTICATED` entity's pages take `[requireAuthenticated]`, and an
entity with `permissions` takes `[requireAnyPermission(...)]` over its names in declaration order.

`app.routes.ts` and `auth.service.ts` are seeds, so regenerating an existing app does not add
`canActivate` to its routes. A newly generated app has the guards in place. In an existing app, add
the import and a `canActivate` entry to each page route of such an entity, for example:

```ts
import { requireAnyPermission, requireAuthenticated } from './core/route-guards';

  {
    path: 'invoices',
    loadComponent: () => import('./components/invoice-list.component')
      .then(m => m.InvoiceListComponent),
    title: 'Invoices - My App',
    canActivate: [requireAnyPermission('invoice:read', 'invoice:write')]
  },
  {
    path: 'products/:id',
    loadComponent: () => import('./components/product-detail.component')
      .then(m => m.ProductDetailComponent),
    title: 'Product - My App',
    canActivate: [requireAuthenticated]
  },
```

The guards call `AuthService.isAuthenticated()` and `AuthService.hasAnyPermission(string[])`, both
present in the seed. A consumer who rewrote `auth.service.ts` keeps both members, or the guards do
not compile.

The live-view client `services/<entity>.stream.ts` of a `realTimeApi` entity that declares access
opens `GET {base}/stream` and `GET {base}/{id}/stream` with `fetch` and
`Authorization: Bearer <AuthService.token()>` instead of a native `EventSource`, which cannot send
that header. The file is generated, so regenerating applies it. Its class, `STREAM_EVENT_TYPES`,
`stream()` and `spectate(id)` keep their types, named-event handling and reconnect behaviour.

A per-action stream client `services/<entity>.action-streams.ts` whose action route demands an
identity, by the entity's declaration or the action's own, sends
`Authorization: Bearer <AuthService.token()>` on its `POST` while a token is held. Its class and
`stream(id)` are unchanged. A client on a public or undeclared action route is unchanged.

Both kinds of client inject `AuthService` where they send the token, so code that constructs one
with `new` outside an injection context gets it through `inject()` or `TestBed` instead.
