---
title: "0.10.0 migration step: `exeris-codegen-ts`: a computed form field shows the loaded value, and has no compute stub"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-10-07
---

### `exeris-codegen-ts`: a computed form field shows the loaded value, and has no compute stub

`Compatibility impact: breaking (ADR-092)`, for an entity with a `@Field(computed = true)` field. TS
only: the Java side does not read `computed` or `computedFrom`.

The metadata names a computed field's dependencies (`computedFrom`) and no formula, so the emitted
`<Entity>FormComponent` computes nothing. It has no `compute<Name>()` method, the stub whose body
was a `TODO` and `return null`. Its `computed<Name>` signal reads the loaded entity,
`computed(() => this.current()?.<name> ?? null)`, so in edit mode the read-only field shows the
value the server returned, and on create it is empty. The input, its `data-testid`
(`field-<name>`), the `(Auto)` label and the "Computed from:" note are unchanged, and the field
stays out of the form model and the submitted DTO.

**What to do.** Regenerate (L1). A component that edited the stub to compute in the browser loses
the edit, since regeneration overwrites it; move the computation to the server, where the entity
sets the field. **If you ran `exeris:detach` (L2),** the component is yours and keeps its copy of
the stub and any edit to it.
