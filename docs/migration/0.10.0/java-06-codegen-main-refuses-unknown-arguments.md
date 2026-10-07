---
title: "0.10.0 migration step: The `CodegenMain` command line refuses arguments it does not know"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-10-07
---

### The `CodegenMain` command line refuses arguments it does not know

This affects a script that runs `eu.exeris.tooling.codegen.java.CodegenMain` directly; a build that
generates through `exeris:generate` is unaffected, and so is generated code. From 0.10.0 the same
class is the `Main-Class` of the new `eu.exeris:exeris-codegen-cli` jar (ADR-097).

| | 0.9.0 and earlier | 0.10.0 and later |
|---|---|---|
| An unknown `--switch`, a positional argument | ignored | argument error, nothing generated |
| `--metadata-dir` / `--output-dir` / `--base-package` without `=<value>` | ignored | argument error |
| Exit status for invalid arguments | `1` | `2` |
| Exit status for a refused or failed run | `1` | `1` |
| A failed or refused run other than `EXT-GEN-3003` | `EXT-GEN-3001`, through the JDK logger | the event's identifier, printed on `stderr` |
| Version in the start-up log line | always `v0.1.0` | the jar's `Implementation-Version`, or `unknown` |

From 0.10.0 the empty-metadata refusal prints `EXT-PLUG-2001` and an unresolved capability graph
`EXT-PLUG-2201`, the identifiers `exeris:generate` prints for the same events; an I/O failure keeps
`EXT-GEN-3001` ([diagnostics](../../diagnostics.md)).

**What to do:** drop any argument the command line does not list (run it with no arguments to see the
list), pass every value as `--switch=value`, and treat exit status `2` as a usage error where a script
checked for `1`. The new switches are `--tests` with `--test-output-dir=<path>` (ADR-058's test root),
`--allow-empty` and `--print-catalogue`.
