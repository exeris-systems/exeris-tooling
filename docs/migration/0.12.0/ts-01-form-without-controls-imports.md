---
title: "0.12.0 migration step: `exeris-codegen-ts`: a form without a control no longer imports FormField"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-11-22
---

### `exeris-codegen-ts`: a form without a control no longer imports `FormField`

An entity whose every field is server-owned (system, read-only, or outside the create and update bodies) produces a form with no control. Its component used to declare `imports: [FormField]` and import `FormField` from `@angular/forms/signals`, which `ng build` reports as NG8113 (unused standalone import). The generated form now leaves `FormField` out of the import line and omits the `imports` entry of the `@Component` decorator when it renders no control. A form with at least one control is unchanged.

Regenerate; no action is needed unless you edited a generated form of such an entity, in which case remove `FormField` from its imports.
