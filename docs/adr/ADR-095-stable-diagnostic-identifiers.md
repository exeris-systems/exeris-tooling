---
title: "ADR-095 — Every diagnostic exeris-tooling prints carries a stable EXT- identifier"
type: adr
visibility: public
owning-repo: exeris-tooling
status: active
slug: adr/ADR-095
---

# ADR-095 — Every diagnostic exeris-tooling prints carries a stable EXT- identifier

- **Status:** ACCEPTED (2026-10-02) · amended 2026-10-06 (Amendment 1 — Maven coordinates move to the eu.exeris group)
- **Deciders:** the founder (scope, a dedicated artefact); `exeris-tooling` (registry layout)
- **Repo:** `exeris-tooling`
- **Scope:** tooling / build — cross-repo: `exeris-ai-bridge` consumes the identifiers
- **Visibility:** public
- **Milestone:** 0.9.0 (`ROADMAP.md`, D4)
- **Relates to:** ADR-025 (the consumer), ADR-085 (registry and stub rules), ADR-060 (logging)
- **Supersedes / superseded by:** —

## Context and Problem Statement

exeris-tooling prints diagnostics from three places, and before this decision none of them carried
an identifier:

| Where | How it prints | What a message carried |
|---|---|---|
| The annotation processor (`exeris-processor`) | `javac`'s `Messager`, as a note, warning or error | A `[Exeris] ` prefix and free text |
| The Maven plugin (`exeris-codegen-maven-plugin`) | `MojoFailureException`, `MojoExecutionException` and the Maven log | Free text; several were a wrapped exception's `getMessage()` |
| The code-generation pipeline (`exeris-codegen-java`: `CodegenPipeline`, `CodegenMain`) | `java.lang.System.Logger`, and `stderr` for command-line usage | Free text |

`exeris-ai-bridge` (ADR-025) exposes a `build-explain_diagnostic` tool that decodes a diagnostic a
developer pastes. With no identifier to key on, it matches message text against anchors in its own
catalogue and keeps a table of real upstream messages that fails its build when an anchor stops
matching. Any rewording here breaks a match there, and the bridge cannot tell whether its catalogue
covers every diagnostic or only the ones it has sampled.

The kernel solved the same problem for its own errors: `KernelErrorCodes` in `exeris-kernel-spi` is a
single registry of `EX-<DOMAIN>-<NNNN>` string constants (a letter domain tag such as `MEM`, `HTTP`,
`PERS`, then a four-digit number) that log scrapers match against.

A registry shared by the three printing sites needs a home all three can depend on. The processor
cannot depend on the code-generation modules: its dependency set is closed by the build-time-only
policy (`javax.lang.model`, the SDK source model, the JDK, and Jackson for the metadata write-out).
The plugin depends on `exeris-codegen-java`, which does not depend on the processor.

**The question this ADR answers:** what identifier does each tooling diagnostic carry, where does it
appear in the printed line, what keeps an identifier stable, and where does the registry live?

## 🏁 The Decision

**Every diagnostic exeris-tooling prints carries an identifier `EXT-<AREA>-<NNNN>` from one registry,
`eu.exeris.tooling.diagnostics.DiagnosticId`, which is the whole of a new artefact
`eu.exeris.tooling:exeris-diagnostics` that depends on nothing.**

**Concrete obligations:**

1. **The prefix is `EXT`, and it cannot collide with the kernel's `EX`.** Every kernel code has a
   hyphen directly after `EX`; every tooling code has `T` there. No kernel code is therefore a prefix
   or a substring match of a tooling code, or the reverse, and a tool routes a code to its owner by
   prefix alone. Tooling allocates nothing in the `EX-` namespace and reads none of the kernel's
   domain tags.
2. **Area tokens.** `PROC` is the annotation processor, printed by `javac`. `PLUG` is the
   `exeris-codegen-maven-plugin` goals, printed by Maven. `GEN` is the code-generation pipeline in
   `exeris-codegen-java`, printed through `System.Logger` or the `CodegenMain` command line. `TS` is
   reserved for the TypeScript emitter (`exeris-codegen-ts`), whose command line prints its errors
   and warnings without an identifier today. No `TS` identifier is allocated; allocating the first
   one is an amendment to this ADR, because it needs a registry on the npm side kept in step with
   this one.
3. **Numbering.** The thousands digit is the area: `1` `PROC`, `2` `PLUG`, `3` `GEN`, `4` reserved
   for `TS`. The hundreds digit is a group, defined per area:
   - `PROC` and `GEN` group by kind: `x0xx` errors and refusals, `x1xx` warnings on an ordinary
     build, `x2xx` the `-Aexeris.strict` audit, `x9xx` the `-Aexeris.verbose` notes.
   - `PLUG` groups by goal: `20xx` `exeris:generate`, `21xx` `exeris:detach`, `22xx`
     `exeris:verify-capabilities`, `23xx` `exeris:verify-runtime`.

   The last two digits count up from `01` within a group. A new group takes an unused hundreds
   digit. The area and the group record where an identifier was allocated, not a property it keeps.
4. **Stability.**
   - An identifier names one meaning for as long as it exists.
   - It is never renumbered and never reused for a different meaning.
   - One that stops being printed is retired: its row moves to the *Retired identifiers* section of
     `docs/diagnostics.md`, and its number is never allocated again.
   - A diagnostic whose severity changes keeps its identifier.
   - The message text after the identifier is not part of the contract and may be reworded in any
     release. The registry's one-line meaning may be clarified, never changed to a different meaning.
5. **One event, one identifier.** An event that two layers report carries the one identifier
   allocated for it, whichever layer prints it: `EXT-PLUG-2206` (the cap-tier Wall scanned no class)
   is printed by `exeris:verify-capabilities` and by the `CodegenPipeline` method that goal calls. A
   continuation line that explains the line above it carries no identifier of its own.
6. **Printed shape.** Every diagnostic is formatted by `DiagnosticId.format` as
   `[Exeris] <identifier>: <message>`. The `[Exeris] ` prefix is not necessarily first on the
   printed line: `javac` puts `warning: ` or a source position ahead of it, Maven prints a goal
   failure as `[ERROR] Failed to execute goal …: [Exeris] EXT-PLUG-…: …`, and the JDK logger adds its
   own record header. A consumer finds an identifier anywhere in a line with
   `\[Exeris\] (EXT-[A-Z]+-\d{4}): `. A plugin failure that wraps a user-side exception keeps that
   exception's message after the identifier; an I/O failure ends with the cause's `toString()`, so
   the cause is visible without `mvn -e`.
7. **Where the registry lives.** `DiagnosticId` is the only type in `exeris-diagnostics`. The
   artefact uses nothing beyond `java.lang`, declares no dependencies and registers no service, so
   it can put neither a runtime type nor a second processor on the `javac` path. It is a reactor
   module, managed in `exeris-tooling-bom` and published to Maven Central with the other modules.
   `exeris-processor`, `exeris-codegen-java` and `exeris-codegen-maven-plugin` depend on it, and the
   processor's permitted dependency set names it. `docs/diagnostics.md` is the canonical human
   table, giving each identifier's meaning and what to do about it. `DiagnosticIdTest` fails the
   build when the table and the registry disagree, when a registered identifier is listed as
   retired, or when a pinned value changes.
8. **The processor cannot print without an identifier.** Its `note`, `warning`, `error` and
   `reportProcessingFailure` helpers take a required `DiagnosticId`, and only one method calls
   `Messager.printMessage`; a test fails when another call appears.
9. **The consumer keys on the identifier.** `exeris-ai-bridge`'s `build-explain_diagnostic` looks a
   pasted diagnostic up by identifier first and keeps its text anchors only for output from tooling
   releases before 0.9.0. The bridge owns its catalogue and its slugs; each slug maps to one
   identifier, and several slugs may share one. The mapping for its catalogue as of this decision:

   | Bridge slug | Identifier |
   |---|---|
   | `processor/wrong-element-kind` | `EXT-PROC-1001` |
   | `processor/processing-failure` | `EXT-PROC-1002` |
   | `processor/datascope-contradiction` | `EXT-PROC-1003` |
   | `processor/repeated-graph-edge` | `EXT-PROC-1013` |
   | `processor/tenant-scoped-deprecated` | `EXT-PROC-1101` |
   | `processor/validation-attribute-deprecated` | `EXT-PROC-1102` |
   | `processor/validate-on-unrecognised` | `EXT-PROC-1103` |
   | `processor/inert-attribute` | `EXT-PROC-1201`, `EXT-PROC-1202` |
   | `processor/unread-annotation` | `EXT-PROC-1203` |
   | `codegen/empty-metadata-refusal` | `EXT-PLUG-2001` |
   | `codegen/detach-conflicts` | `EXT-PLUG-2102` |
   | `caps/unsatisfied-requires`, `caps/dependency-cycle`, `caps/graph-unresolved` | `EXT-PLUG-2201` |
   | `caps/wall-violated` | `EXT-PLUG-2203` |
   | `caps/wall-scanned-nothing` | `EXT-PLUG-2206` |
   | `runtime/no-kernel-driver` | `EXT-PLUG-2302` |

   `processor/universe-tier-reserved` has no identifier: the processor no longer prints that
   message, and the `DataScope.UNIVERSE` refusals it now prints are `EXT-PROC-1006` to `1010`.

## Consequences

### ✅ Positive Outcomes

- **[+] A lookup instead of a text match.** The bridge, a CI step or a test matches an identifier
  that does not move when a message is reworded.
- **[+] Coverage is checkable.** The registry is a closed list, so a consumer can state which
  identifiers its catalogue covers instead of sampling messages.
- **[+] Owner by prefix.** `EX-` goes to the kernel, `EXT-` to tooling, with no table to consult.
- **[+] The processor's dependency set is unchanged in kind.** The registry adds an artefact with
  no dependencies and no service file; nothing it contains can run at build time.
- **[+] I/O failures name their cause in the default Maven output.**

### ⚠️ Trade-offs

- **[-] Every printed line changes.** A check anchored at the start of a plugin message no longer
  matches; `docs/MIGRATION-0.x-to-1.0.md` says how to move to the identifier.
- **[-] One more published artefact** to release, sign and keep in `exeris-tooling-bom`.
- **[-] Only the processor is structurally guarded.** A new print site in the plugin or the pipeline
  that skips `DiagnosticId.format` compiles; review and the per-site tests catch it, nothing else
  does.
- **[-] Group digits drift from severity.** An identifier that changes severity keeps a group digit
  that names its old severity.

### 📋 What is NOT in scope

- **Identifiers for the TypeScript emitter.** `TS` is reserved, not allocated (obligation 2).
- **The kernel's codes.** `KernelErrorCodes` is unchanged and is not merged with this registry.
- **A machine-readable diagnostic channel** (SARIF, JSON lines). Diagnostics stay text with an
  identifier in it.
- **Localised message text.**
- **Diagnostics inside generated code.** What the emitted application logs at run time is governed
  by ADR-060, which has generated code log through `System.Logger` with no logging facade. The `GEN`
  warnings are the pipeline's own logging at build time, not emitted code.

### 🚫 Non-Goals

- **Stable message text.** The text is for a person and may be rewritten in any release.
- **An ecosystem-wide registry shared with the kernel.** The two namespaces are disjoint by prefix
  so that neither repository has to coordinate allocation with the other.

### ⚠️ Risks and Assumptions

- **Assumes:** consumers read diagnostics as text lines, so an identifier inside the line is enough.
- **Assumes:** four digits per area are enough; each area has room for ten groups of 99.
- **Risk:** a consumer anchors on `[Exeris] ` at the start of a line and misses Maven and `javac`
  output. Obligation 6 and `docs/diagnostics.md` state the regex that does not.
- **Reversed by:** a machine-readable diagnostic channel that consumers read instead of text, or a
  decision to allocate tooling and kernel codes from one ecosystem registry. Either would supersede
  obligations 1, 6 and 7.

## Cross-references

- **ADR-025** — `exeris-ai-bridge`, the MCP server whose `build-explain_diagnostic` tool is the
  first consumer of these identifiers.
- **ADR-085** — the federated documentation standards: the registry row is reserved before content,
  and a cross-repo ADR leaves a `.link.md` stub in each affected repository.
- **ADR-060** — generated code logs through `System.Logger`; it governs emitted code, not the
  pipeline's own warnings.
- **ADR-055** and **ADR-024** — the cap-tier Wall and the capability graph whose failures are
  `EXT-PLUG-2201` to `2206`.
- **ADR-078** — the runtime-driver gate, `EXT-PLUG-2301` and `2302`.
- **ADR-091** — the application starter; `exeris-app-parent` puts `exeris-processor` on
  `annotationProcessorPaths` by coordinate, which resolves `exeris-diagnostics` with it.
- `docs/diagnostics.md` — every identifier, its meaning and what to do.
- `.agents/policies/processor-build-time-only.md` — the processor's permitted dependency set.
- `ROADMAP.md`, D4.

## Engineering Protocol

1. **`exeris-diagnostics` lands as a reactor module** in 0.9.0, ahead of `exeris-processor` in the
   root `<modules>`, parented by `exeris-tooling-parent` and managed in `exeris-tooling-bom`.
   `tools/release-readiness` walks `<modules>`, so the artefact's jar, sources and javadoc are
   required at release without a change to the script.
2. **`DiagnosticIdTest`** (in `exeris-diagnostics`) enforces obligations 3, 4 and 7;
   `ExerisDomainProcessorDiagnosticIdTest` enforces obligation 8; the plugin, pipeline and command-line
   tests assert the identifier at the print sites they exercise.
3. **The registry row for ADR-095** in `exeris-docs/adr-index.md` names `exeris-tooling` as owner
   and the scope as cross-repo, with `exeris-ai-bridge` as the stub holder.
4. **`exeris-ai-bridge` adds `docs/adr/ADR-095.link.md`** and moves `build-explain_diagnostic` to
   identifier-first lookup (obligation 9).
5. **`docs/MIGRATION-0.x-to-1.0.md`** carries the consumer-facing change under D4.
6. Migration owner: `exeris-tooling`, target 0.9.0.

## Amendment 1 — Maven coordinates move to the eu.exeris group (2026-10-06)

- **Status:** ACCEPTED (2026-10-06).
- **Amends:** the artefact coordinate in the Decision statement. The registry class, its package,
  the identifiers and every obligation are unchanged.
- **Deciders:** the founder.

From 0.10.0 every `exeris-tooling` module is published under the groupId `eu.exeris`, the group of
the kernel and SDK artefacts, so the registry's artefact is `eu.exeris:exeris-diagnostics`. Releases
up to and including 0.9.0 publish it as `eu.exeris.tooling:exeris-diagnostics`, and no relocation POM
is published under the old group. The move changes Maven coordinates, not Java packages: the
registry class `DiagnosticId` keeps its package.
