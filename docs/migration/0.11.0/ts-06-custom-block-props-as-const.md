---
title: "0.11.0 migration step: exeris-codegen-ts: CUSTOM block props fields keep their literal types"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-10-30
---

### `exeris-codegen-ts`: CUSTOM block props fields keep their literal types

A CUSTOM block's props JSON is emitted with a TypeScript const assertion (`as const`):
`protected readonly blockProps<N> = <JSON literal> as const;`. This preserves literal types on string,
number, and boolean values so that a custom component whose props input declares a literal union (e.g.
`variant: 'primary' | 'secondary'`) type-checks under Angular strict templates. After regenerating,
existing `@View` page components with CUSTOM block props gain `as const` on those fields. A custom
component declaring an array input that receives a JSON array literal should type it with a readonly
element type (e.g. `readonly string[]`) or declare the property as readonly. A view without CUSTOM
blocks, or a block with no props, is unchanged.
