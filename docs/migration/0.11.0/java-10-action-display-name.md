---
title: "0.11.0 migration step: the metadata carries each action's label as its displayName"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-10-31
---

### The metadata carries each action's label as its `displayName`

The processor writes `@Action.label` into the action's `displayName`, as the SDK's source-model reader
reads it. After regenerating, every action in `target/classes/exeris-metadata/<Entity>.json` carries
`displayName`. No generated Java or TypeScript file changes: the emitted action controls are labelled
from the action's name.
