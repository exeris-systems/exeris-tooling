---
title: "0.11.0 migration step: metadata gains navMenu and tabs"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-11-01
---

### The metadata carries `navMenu` and `tabs`

The processor reads `@NavMenu` (on an entity class) into `DomainMetadata.navMenu` and `@Tab` (on
entity fields) into `uiMetadata.tabs`, matching the SDK 0.13 reader. Both leave the unread pass of
`-Aexeris.strict`; unconsumed attributes are reported as having no effect.

After regenerating, `target/classes/exeris-metadata/<Entity>.json` carries the two facets. No Java
output changes.
