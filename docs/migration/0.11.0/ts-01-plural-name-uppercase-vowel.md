---
title: "0.11.0 migration step: exeris-codegen-ts: an uppercase vowel before a trailing y counts as a vowel"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-10-29
---

### `exeris-codegen-ts`: an uppercase vowel before a trailing `y` counts as a vowel

The plural of an entity name ending in an uppercase vowel and `y` takes a plain `s`, as the SDK's
`pluralName()` does from 0.13.0: `Ay` becomes `Ays`, where it was `Aies`. For such an entity the
generated route path, the store and the list labels that use the plural change after regenerating,
and now match the backend's routes. No other name changes.
