---
title: Codegen CLI
type: module
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-11-03
---

# Codegen CLI

**Module:** `eu.exeris:exeris-codegen-cli`

`exeris-codegen-cli` is the Java generator as one self-contained jar: `exeris-codegen-java` and its runtime dependencies, shaded, with `CodegenMain` as `Main-Class`. `java -jar` over a metadata directory writes what `exeris:generate` writes, for a tool that generates outside a Maven build.

The module has no sources of its own. `CodegenMain` and `CliArgs` live in `exeris-codegen-java`, beside the `EXT-GEN` print sites; this module packages them. It is a tool, not an application dependency.

## Coordinates and dependencies

| Item | Value |
|---|---|
| Group and artifact | `eu.exeris:exeris-codegen-cli` |
| Published | Yes, to Maven Central with sources and javadoc jars; managed in `exeris-tooling-bom` and in none of `exeris-app-bom`, `exeris-app-parent` or `exeris-app-starter` |
| Bundled | `exeris-codegen-java` and its runtime graph, plus `slf4j-nop` at runtime scope, so `swagger-parser`'s logging finds a provider and stays silent |
| Manifest | `Main-Class: eu.exeris.tooling.codegen.java.CodegenMain`, `Implementation-Version: <project version>` |
| JDK | 25 or newer, with no upper bound |

The shade configuration follows `exeris-kernel-diagnostics-cli`: service files merged, license, notice and `DEPENDENCIES` files appended or merged rather than first-wins, signature files and `module-info.class` dropped, no dependency-reduced POM.

## Contract

```text
java -jar exeris-codegen-cli-<version>.jar --metadata-dir=<path> --output-dir=<path> [options]
```

| Switch | Meaning |
|---|---|
| `--metadata-dir=<path>` | The processor's `exeris-metadata` directory. Required unless `--print-catalogue` |
| `--output-dir=<path>` | The root the main sources are written to. Required unless `--print-catalogue` |
| `--base-package=<pkg>` | The package of `Application` and its siblings; default the first domain's package by qualified name, without `.domain` |
| `--tests` | Also write the generated tests; requires `--test-output-dir` |
| `--test-output-dir=<path>` | The root the generated tests are written to; read only with `--tests` |
| `--allow-empty` | Permit the teardown that prunes a previously generated main tree when no `@ExerisDomain` is found |
| `--print-catalogue` | Write the bundled generator catalogue to standard output and exit; needs no other switch |

Every argument is a `--switch` or `--switch=value`. A value switch without its value, a flag given one, an unknown switch and a positional argument are argument errors, so a flag a release does not know fails instead of being ignored. The command line validates the capability graph fail-fast; it has no equivalent of `exeris:generate`'s deferral.

| Exit status | Meaning |
|---|---|
| `0` | The run succeeded, including a run that found no metadata and owned no tree |
| `1` | Generation was refused or failed |
| `2` | The arguments were invalid |

The version is logged at start from the jar's `Implementation-Version`, or reported unknown off the jar. It never appears in a generated file.

`CodegenCliJarIT` runs the shaded jar as a separate process and `CodegenPipeline` in-process over the same processor-written metadata and base package, and fails unless the two trees, main and test roots with their manifests, are byte-identical.

## Hot path

None: build-time. One process per generation run.

## Failure modes

Every non-zero exit prints a line carrying an identifier to standard error.

| Situation | Exit | Identifier |
|---|---|---|
| Missing, malformed or unknown arguments | `2` | `EXT-GEN-3002`, followed by the usage text |
| Empty metadata over a previously generated tree, without `--allow-empty` | `1` | `EXT-PLUG-2001`, the event it shares with `exeris:generate` |
| The capability graph does not resolve | `1` | `EXT-PLUG-2201` |
| A field type the repository cannot store, or an entity outside a `.domain` package | `1` | The refusal's own identifier, `EXT-GEN-3003` or `EXT-PROC-1033` |
| An I/O failure, or any other failure of the pipeline | `1` | `EXT-GEN-3001`, with the cause; an unexpected exception also prints its stack trace |
| `--print-catalogue` finds no catalogue on the classpath | `1` | `EXT-GEN-3004` |

The meaning of each is in the [diagnostics reference](../diagnostics.md).

Trade-off: a shaded jar is one file to download and run, and it carries its own copies of JavaPoet, swagger and Jackson, so a security fix in one of them reaches a user only through a new tooling release.

## Owning ADRs

| ADR | Decision |
|---|---|
| [ADR-097](../adr/ADR-097-generator-catalogue-and-launchable-generator.md) | The launchable generator: module, arguments, exit codes, version, same output as the pipeline, JDK, distribution (obligations 10 to 16) |
| [ADR-095](../adr/ADR-095-stable-diagnostic-identifiers.md) | The identifiers it prints |

## Where this does not apply

- **An application's own build.** An application builds through the [Maven plugin](06-codegen-maven-plugin.md); the jar is not on its classpath.
- **The frontend.** The TypeScript generator is `exeris-gen` from [codegen-ts](05-codegen-ts.md).
- **Writing a new project.** That is [initializr](08-initializr.md), a separate jar, not a mode of this one.
