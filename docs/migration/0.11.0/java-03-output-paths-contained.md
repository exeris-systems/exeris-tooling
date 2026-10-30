---
title: "0.11.0 migration step: the Java writer refuses an output path outside the output directory"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-10-30
---

### The Java writer refuses an output path outside the output directory

`exeris-codegen-core`'s `OutputWriter` resolves every file it writes, claims or prunes against the
output directory and refuses one that lands outside it. A package name with an empty, `.` or `..`
segment or a path separator, and a class name with a path separator, are refused before anything is
written; the build fails with an `IOException` naming the path. An empty package name is the
default package and writes at the output root, as before.

Metadata written by the annotation processor holds Java identifiers and is unaffected. Metadata
authored by hand or by another tool uses Java identifiers for entity and package names.
