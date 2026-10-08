---
title: "0.10.0 migration step: `exeris:generate` names a dependency the generated code imports and the build does not declare (`EXT-PLUG-2004`)"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-10-07
---

### `exeris:generate` names a dependency the generated code imports and the build does not declare (`EXT-PLUG-2004`)

After it writes the generated tree, `exeris:generate` checks this module's dependencies for every
artefact the generated code imports, and logs the `EXT-PLUG-2004` warning naming each one that is
missing by `groupId:artifactId` ([diagnostics](../../diagnostics.md)):

| Artefact | Imported when | Classpath |
|---|---|---|
| `eu.exeris:exeris-kernel-spi`, `eu.exeris:exeris-kernel-core` | always | compile |
| `eu.exeris:exeris-sdk-composition-runtime` | the build has a `@CapabilityModule` | compile |
| `tools.jackson.core:jackson-databind` | an entity has a `List<X>` field | compile |
| `org.junit.jupiter:junit-jupiter-api`, `org.assertj:assertj-core` | `exeris.tests=true` | test |

A missing compile artefact still fails the build at `compile`, as before; the warning printed ahead of
the `javac` error names the jar where `javac` names a package. A build on `eu.exeris:exeris-app-starter`
declares every compile artefact in the table and prints nothing. A dependency at runtime scope does not
count for the compile classpath, so Jackson 3 reached only through the runtime-scoped Community driver
is reported.

The goal now asks Maven to collect this module's dependency graph (coordinates only, nothing is
downloaded), so a build that cannot resolve its own dependency POMs fails at `generate-sources` instead
of at `compile`. The check does not run with `exeris.codegen.skip=true` or
`exeris.addCompileSourceRoot=false`, where another module compiles the generated tree.

**What to do:** nothing for a build that compiles. For a build that does not, add each artefact the
warning lists, the compile ones at compile scope (or depend on `exeris-app-starter`, type `pom`) and
JUnit 5 and AssertJ at test scope.
