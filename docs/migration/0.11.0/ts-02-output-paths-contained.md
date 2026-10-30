---
title: "0.11.0 migration step: exeris-codegen-ts: an output path outside the output directory is refused"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-10-30
---

### `exeris-codegen-ts`: an output path outside the output directory is refused

The generator resolves every file it writes against the output directory and refuses one that lands
outside it, under `--overwrite` too, before it creates a directory or writes a byte. The metadata
schema holds `entityName` to a Java identifier and `packageName` to dotted Java identifiers or the
empty default package, letters of any script included, so a metadata file whose names carry `/`,
`\` or `..` is refused when it is read.

Metadata written by the annotation processor is unaffected. Metadata authored by hand or by another
tool uses Java identifiers for entity and package names.
