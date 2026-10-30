---
title: "0.11.0 migration step: exeris-codegen-ts: CUSTOM block props fields keep their literal types"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-10-30
---

### `exeris-codegen-ts`: CUSTOM block props keep their literal types

A CUSTOM block's props field is emitted with a const assertion,
`protected readonly blockProps<N> = <JSON literal> as const;`, so its strings, numbers and booleans
keep their literal types, and a custom component whose `props` input declares a literal union
(`variant: 'primary' | 'secondary'`) type-checks under strict templates. After regenerating, every
`@View` page with CUSTOM block props carries `as const` on those fields.

An array in the props is readonly under the assertion. A custom component whose `props` input
declares an array member types it `readonly string[]` (or `ReadonlyArray<T>`); a mutable `string[]`
no longer accepts it. A view without CUSTOM blocks, or a block without props, is unchanged.
