---
title: "0.11.0 migration step: exeris-codegen-ts: generated store and form specs under generateTests"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-10-30
---

### `exeris-codegen-ts`: generated store and form specs under `generateTests`

When test generation is on (`generateTests: true` or `--tests`), the generator also emits
`stores/{entity}.store.spec.ts` for each entity when stores and services are generated, and
`components/{entity}-form.component.spec.ts` for each entity with a form page when forms and
services are generated. The store
spec exercises state transitions across CRUD operations and HTTP error status mappings (409 on
update vs delete, 422). The form spec exercises Signal Forms control inclusion and disabled state
across create and edit modes (`inCreate` / `inUpdate`), exclusion of server-owned and read-only
fields, and a required field that starts blank blocking form submission.

Apps with `generateTests` off (the default) are unaffected and their generated output stays byte-identical.
Apps with `generateTests` on receive the new spec files and execute them through `npm test`.
