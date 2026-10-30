---
title: "0.11.0 migration step: exeris-codegen-ts: render ssg under scaffold false emits view.routes.server.ts"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-10-30
---

### `exeris-codegen-ts`: `render: 'ssg'` under `scaffold: false` emits `view.routes.server.ts`

Under `render: 'ssg'` with `scaffold: false`, the generator emits `view.routes.server.ts` next to
`view.routes.ts`. It exports `viewServerRoutes: ServerRoute[]`, listing each param-less `@View` route
that reads no entity data with `renderMode: RenderMode.Prerender`. The server entry, server config
and builder options stay owned by the application.

An existing application that configured `render: 'ssg'` without the scaffold and maintained a
hand-written prerender list can spread `...viewServerRoutes` into its `app.routes.server.ts` to keep
its prerendered routes in step with `@View` pages. An application under `render: 'csr'` (the default),
or one that generates the scaffold, is unaffected.
