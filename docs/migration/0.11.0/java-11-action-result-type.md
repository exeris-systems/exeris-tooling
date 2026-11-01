---
title: "0.11.0 migration step: the metadata carries each action's return type as resultType"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-11-01
---

### The metadata carries each action's return type as `resultType`

The processor extracts each `@Action` method's return type into `ActionMetadata.resultType`, matching
the shape the SDK's source-model reader extracts (fully qualified, nested types joined by `.`, generic
arguments joined by `,` with no space, primitives as keywords, `void` for void, type variables by
simple name, and type-use annotations stripped).

After regenerating, every action in `target/classes/exeris-metadata/<Entity>.json` carries `resultType`.
No generated Java or TypeScript file changes: the action route continues answering with the updated
aggregate on both sides.
