---
title: "Diagnostic identifiers — what each EXT- code means and what to do"
type: reference
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-10-06
---

# Diagnostic identifiers

Every diagnostic exeris-tooling prints carries a stable identifier, written as
`[Exeris] <identifier>: <message>`:

```
[Exeris] EXT-PROC-1001: @ExerisDomain can only be applied to classes
[ERROR] Failed to execute goal …: [Exeris] EXT-PLUG-2001: Refusing to wipe the committed generated tree: …
```

That covers the annotation processor's `javac` notes, warnings and errors, the warnings the
code-generation pipeline logs, and every failure and warning the `exeris-codegen-maven-plugin` goals
raise. `[Exeris]` is not always first on the line: `javac`, Maven and the JDK logger each put their
own header ahead of it. Match on the identifier, not on the text after it: the text is written for a
person and may be reworded in any release, the identifier may not. A regular expression that finds
every one of them, anywhere in a line, is `\[Exeris\] (EXT-[A-Z]+-\d{4}): `.

[ADR-095](adr/ADR-095-stable-diagnostic-identifiers.md) is the contract. The registry is
`eu.exeris.tooling.diagnostics.DiagnosticId` in the `eu.exeris.tooling:exeris-diagnostics` artefact.
`DiagnosticIdTest` fails the build when this table and the registry disagree.

## Format

| Part | Meaning |
|---|---|
| `EXT` | exeris-tooling. Distinct from the kernel's `EX-<DOMAIN>-<NNNN>` codes (`KernelErrorCodes`), so a tool can route a code to its owner by prefix alone. |
| `PROC` / `PLUG` / `GEN` | The annotation processor (inside `javac`), the Maven plugin, or the code-generation pipeline. `TS` is reserved for the TypeScript emitter and has no identifiers yet. |
| `NNNN` | `PROC` uses 1xxx, `PLUG` 2xxx, `GEN` 3xxx; 4xxx is reserved for `TS`. The hundreds digit groups codes by what they were allocated for (below). |

**Stability.** An identifier names one meaning for as long as it exists. It is never renumbered and
never reused; one that stops being emitted is retired and its number stays unallocated. A diagnostic
whose severity changes keeps its identifier, so the group it was allocated in says where it came
from, not what severity it has now. An event that two layers report carries one identifier:
`EXT-PLUG-2206` is printed both by `exeris:verify-capabilities` and by the pipeline it calls.

## Annotation processor — `EXT-PROC`

Printed by `javac` while it compiles your sources. `-Aexeris.strict=true` enables the 12xx audit and
`-Aexeris.verbose=true` the 19xx trail; both are off by default.

### 10xx — declaration refusals (error)

| ID | Meaning | What to do |
|---|---|---|
| `EXT-PROC-1001` | A type-level annotation (`@ExerisDomain`, `@Saga`, `@CapabilityModule`, `@View`) is on an element that is not a class or type. | Move the annotation to a class (`@ExerisDomain`, `@Saga`) or a type (`@CapabilityModule`, `@View`). |
| `EXT-PROC-1002` | Extracting metadata for an element threw; no metadata was written for it. | Read the exception after the colon; rerun with `-Aexeris.verbose=true` for its stack trace. If your source is valid, report it as a processor defect. |
| `EXT-PROC-1003` | `@ExerisDomain` declares `dataScope` and the deprecated `tenantScoped` with values that contradict each other. | Declare the tier once with `dataScope` and drop `tenantScoped`. |
| `EXT-PROC-1004` | `@RouteAccess(PUBLIC)` is on a type or action method that also declares permissions. | Drop the permissions, or declare `@RouteAccess(AUTHENTICATED)`. |
| `EXT-PROC-1005` | An action method declares permissions but inherits `@RouteAccess(PUBLIC)` from its type. | Drop the method's permissions, or declare `@RouteAccess(AUTHENTICATED)` on the method. |
| `EXT-PROC-1006` | A `DataScope.UNIVERSE` entity declares no owning tenant field. | Add `private UUID tenantId;`, or mark the field that plays the role with `@TenantId`. |
| `EXT-PROC-1007` | A `DataScope.UNIVERSE` entity declares no `@SharedScope` field. | Mark the `UUID` or `String` field holding the shared-scope key with `@SharedScope`, or declare `dataScope = TENANT`. |
| `EXT-PROC-1008` | The `@SharedScope` field is neither `java.util.UUID` nor `java.lang.String`. | Change the field's type to `UUID` or `String`. |
| `EXT-PROC-1009` | The `@SharedScope` field is also the owning tenant field. | Put `@SharedScope` on a separate field. |
| `EXT-PROC-1010` | The `@SharedScope` field is declared required. | Drop `required = true`; a row with no shared scope is owner-private. |
| `EXT-PROC-1011` | A system-field role marker (`@TenantId`, `@Version`, `@SoftDelete…`, `@Audit…`) is on more than one field. | Keep the marker on exactly one field. |
| `EXT-PROC-1012` | A system-field role marker and the matching `@ExerisDomain` override name different fields. | Remove whichever of the two is wrong. |
| `EXT-PROC-1013` | `@GraphEdge` is declared more than once on one field. | Declare each edge on its own field. |
| `EXT-PROC-1014` | `@ExerisDomain(realTimeApi = true)` is on a `TENANT` or `UNIVERSE` entity. Kernel events carry no tenant, so the generated live view would send every tenant's events to every subscriber. | Declare `dataScope = DataScope.GLOBAL` if the rows are not tenant-owned; otherwise drop `realTimeApi` until stream events carry an isolation key. |

### 11xx — warnings on an ordinary build

| ID | Meaning | What to do |
|---|---|---|
| `EXT-PROC-1101` | `@ExerisDomain.tenantScoped` is deprecated for removal; it is read as a fallback. | Replace it with the `dataScope` the message names. |
| `EXT-PROC-1102` | A deprecated `@Validation` attribute is set; it is read as a fallback. | Move to the replacement the message names (see `MIGRATION.md` in exeris-sdk). |
| `EXT-PROC-1103` | `@Validation.validateOn` holds a value other than `CREATE` or `UPDATE`, which is dropped. | Use `@Field.inCreate` / `@Field.inUpdate` instead. |
| `EXT-PROC-1104` | An entity's derived default table name differs from the plain plural of its class name. | To keep an existing table, set the `tableName` the message names; otherwise migrate to the new name. |
| `EXT-PROC-1105` | `@SharedScope` is on an entity that is not `DataScope.UNIVERSE`, so it has no effect. | Declare `dataScope = DataScope.UNIVERSE`, or remove the marker. |
| `EXT-PROC-1106` | `@Bind` with `source = STATIC` or `NONE` carries attributes that are ignored. | Put authored content in `@Block(props)`, or bind with `source = ENTITY`, `PROJECTION` or `ACTION`. |
| `EXT-PROC-1107` | `@Action(streaming = true)`: the generated stream route does not run the action. | Expect keep-alives only on that route; the per-action stream driver is tracked in the ROADMAP (EV1-stream). |

### 12xx — the `-Aexeris.strict` audit (warning)

| ID | Meaning | What to do |
|---|---|---|
| `EXT-PROC-1201` | An annotation attribute is set that no code generator consumes. | Remove the attribute, or keep it knowing it changes no emitted output. The message says why it is inert. |
| `EXT-PROC-1202` | An annotation is set that the processor reads but no code generator consumes. | As 1201, for a whole annotation. |
| `EXT-PROC-1203` | An SDK annotation is set that the processor never reads. | As 1201; the annotation has no effect on emitted output. |

### 19xx — the `-Aexeris.verbose` trail (note)

| ID | Meaning | What to do |
|---|---|---|
| `EXT-PROC-1901` | Progress trace: an element is being processed, or its metadata was written. | Nothing; informational. |
| `EXT-PROC-1902` | An element's source could not be read for the ADR-042 digest, which is omitted. | Nothing for the build. That entity's metadata JSON is written without its `sourceDigest` field. |

## Maven plugin — `EXT-PLUG`

Printed by Maven as the goal's failure or warning. A failure that wraps a user-side exception (an
unresolved capability graph, a Wall violation, the empty-metadata refusal) keeps that exception's
text after the identifier; an I/O failure (2002, 2003, 2101, 2202, 2204, 2301) ends with the cause,
so it is visible without `mvn -e`.

### 20xx — `exeris:generate`

| ID | Meaning | What to do |
|---|---|---|
| `EXT-PLUG-2001` | No `@ExerisDomain` metadata was found, and generation refused to delete the committed generated tree. | Make the project compile (`mvn compile -Dexeris.codegen.skip=true`), then rerun `exeris:generate`. If you removed every entity on purpose, pass `-Dexeris.codegen.allowEmpty=true`. |
| `EXT-PLUG-2002` | Source generation failed reading metadata or writing the generated tree. | Check the cause for the path; check permissions and disk space on the metadata and output directories. |
| `EXT-PLUG-2003` | Test generation failed reading metadata or writing the generated test tree. | As 2002, for the generated-test output directory. |

### 21xx — `exeris:detach`

| ID | Meaning | What to do |
|---|---|---|
| `EXT-PLUG-2101` | Detach failed moving the generated sources or updating the ignore file. | Check the cause; the source tree may be partly moved, so check `git status` before rerunning. |
| `EXT-PLUG-2102` | Generated files already existed at the detach target and were left in place. | Reconcile the listed files by hand, or set `-Dexeris.failOnConflict=false` to accept them as a warning. |

### 22xx — `exeris:verify-capabilities`

`EXT-PLUG-2201` is also raised by `exeris:generate` when no later `verify-capabilities` execution
is bound to take the verdict.

| ID | Meaning | What to do |
|---|---|---|
| `EXT-PLUG-2201` | The capability graph has an unsatisfied `@Requires`, a version mismatch or a dependency cycle. | Each problem is listed after the identifier: add the missing `@Provides`, widen the version range, or break the cycle. |
| `EXT-PLUG-2202` | Capability metadata could not be read. | Check the cause; rebuild so the processor rewrites `target/classes/exeris-metadata/`. |
| `EXT-PLUG-2203` | A capability module's compiled classes reference a type the cap-tier Wall forbids. | Remove the listed references from the capability module (ADR-024 predicate 4, ADR-055). |
| `EXT-PLUG-2204` | The cap-tier Wall scan could not read a compiled class. | Check the cause; a clean rebuild usually replaces a corrupt class file. |
| `EXT-PLUG-2205` | The cap-tier Wall guard is switched off. | Remove `exeris.wall.skip=true` unless the exemption is intended. |
| `EXT-PLUG-2206` | The build declares capability modules but the Wall found no compiled classes to scan. | Point `exeris.classesDir` at the compiled classes; until then the Wall is unverified, not satisfied. |

### 23xx — `exeris:verify-runtime`

| ID | Meaning | What to do |
|---|---|---|
| `EXT-PLUG-2301` | Domain metadata could not be read while checking for runtime drivers. | Check the cause; rebuild so the processor rewrites the metadata. |
| `EXT-PLUG-2302` | The generated application has no kernel driver on its runtime classpath. | Add the driver artefact the message names to the module's dependencies, or set `-Dexeris.verifyRuntime.skip=true` if another module runs the generated code. |

## Code-generation pipeline — `EXT-GEN`

Logged by the pipeline in `exeris-codegen-java` through `System.Logger`, so they appear inside a
Maven build as well as from the `CodegenMain` command line. A refusal (`EXT-GEN-3003`) fails the
run instead: `exeris:generate` prints it as its goal failure, and `CodegenMain` prints it and exits
with status 1.

### 30xx — errors

| ID | Meaning | What to do |
|---|---|---|
| `EXT-GEN-3001` | The codegen command line failed to generate code. | Read the exception logged with it, which names the cause. |
| `EXT-GEN-3002` | The codegen command line was given missing or malformed arguments. | Pass `--metadata-dir` and `--output-dir`, as the usage text printed under it says. |
| `EXT-GEN-3003` | An entity field has a type the generated repository cannot store and read back: a parameterised type other than `List<…>` (`Map`, `Set`, `Optional`, …), an array (`byte[]`, …), `char` / `Character`, `BigInteger`, or a JDK value type with no static `valueOf(String)` (`LocalTime`, `OffsetTime`, `Duration`, `Period`, `Year`, `YearMonth`, `MonthDay`, `ZoneId`, `ZoneOffset`, `java.util.Date`, `Currency`, `Locale`, `URI`, `URL`, `Object`). An enum is never refused. Nothing is generated. | Declare the field as a `List<…>` (stored as a JSON column) or as a supported scalar (`String`, `UUID`, `Long`, `Integer`, `Short`, `Byte`, `Boolean`, `Float`, `Double`, `BigDecimal`, `Instant`, `LocalDateTime`, `OffsetDateTime`, `ZonedDateTime`, `LocalDate`, an enum) — `BigDecimal` in place of `BigInteger`, a `String` for a time of day or a duration. The message names every such field. |

### 31xx — warnings

| ID | Meaning | What to do |
|---|---|---|
| `EXT-GEN-3101` | Generation found no domain or capability metadata to generate from. | Usually a compile that failed before the processor ran: fix the compile, then rerun. Nothing is generated and nothing committed is deleted (see `EXT-PLUG-2001`). |
| `EXT-GEN-3102` | An optional `@Requires` has no matching provider, so the requirement is skipped. | Nothing, if the capability is meant to be optional. Otherwise add the `@Provides` the message names, or widen its version range. |
| `EXT-GEN-3103` | The capability graph did not resolve on possibly stale metadata; the post-compile gate decides. | Nothing yet: `exeris:verify-capabilities` checks the freshly compiled metadata later in the same build and fails it with `EXT-PLUG-2201` if the graph is still unresolved. |

## Retired identifiers

A retired identifier is no longer printed, and its number is never allocated again. Retiring one
moves its row here; `DiagnosticIdTest` fails if a registered identifier is listed in this section.

None yet.
