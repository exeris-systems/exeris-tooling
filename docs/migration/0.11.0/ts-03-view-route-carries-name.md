---
title: "0.11.0 migration step: exeris-codegen-ts: view routes carry the view name in route data"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-10-30
---

### `exeris-codegen-ts`: a view route carries the view's name in its `data`

Each `@View` page's route (`pages/<kebab>.route.ts`) carries the view's declared name as
`data: { view: '<ViewName>' }`. After regenerating, every view route file gains that line; the path,
the title and the navigation are unchanged. Code that reads the active view from the path can read
`route.snapshot.data['view']` instead.
