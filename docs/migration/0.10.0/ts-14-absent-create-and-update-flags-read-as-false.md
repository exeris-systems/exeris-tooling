---
title: "0.10.0 migration step: `exeris-codegen-ts`: an absent `inCreate` or `inUpdate` reads as false"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-10-14
---

### `exeris-codegen-ts`: an absent `inCreate` or `inUpdate` reads as false

`Compatibility impact: breaking (ADR-090)`, for code that builds a `<Entity>Create` that names a
`@Field(inCreate = false)` property, or a `<Entity>Update` that names a `@Field(inUpdate = false)`
property, of an entity read from processor metadata.

The processor's metadata omits a `false` flag, so a field marked `inCreate = false` or
`inUpdate = false` reached the TypeScript generators as a field without the key, which they read as
`true` (ADR-090 Amendment 5). The generated `<Entity>Create`, `<Entity>CreateSchema` and the create
form offered the `inCreate = false` field, and `<Entity>Update`, `<Entity>UpdateSchema` and the edit
form's request offered the `inUpdate = false` one, although the Java bodies leave them out. They now
leave them out. The Java output is unchanged.

A hand-written metadata file, for the generators or for `exeris:generate`, reads a field without the
keys as left out of both bodies in the Java and the TypeScript emitters alike: name
`"inCreate": true, "inUpdate": true` for a field the bodies offer.

**What to do:** remove an `inCreate = false` property from a create body you build, and an
`inUpdate = false` property from an update body. Where a metadata file is written by hand, add
`"inCreate": true, "inUpdate": true` to each field the create and update bodies offer.
