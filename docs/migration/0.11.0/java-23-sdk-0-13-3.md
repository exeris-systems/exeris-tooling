---
title: "0.11.0 migration step: exeris-app-bom pins SDK 0.13.3"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-11-03
---

### `exeris-app-bom` pins SDK 0.13.3

`exeris.sdk.version` in `exeris-app-bom` is `0.13.3`; `exeris.kernel.version` stays `0.13.1`. SDK 0.13.3
changes no annotation, no SPI type and no metadata shape, so regenerating changes no generated file.

Its changes are in artefacts an application does not depend on. The `exeris-sdk-tck` corpus types its
`@Blob` fields `BlobRef`. The `exeris-sdk-examples` entities sit in `<scenario>.domain` packages and
declare JavaBean accessors, the layout EXT-PROC-1033 and EXT-PROC-1034 require of an entity a generated
application is built from, and reference another entity by its `UUID` key, which EXT-GEN-3003 requires.
The parity suite's `actionsAgree` compares each action's `displayName` as well; the processor writes it
from `@Action.label`, as the reader does, so the processor's binding of the kit passes unchanged.
