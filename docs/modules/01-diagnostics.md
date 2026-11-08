---
title: Diagnostics
type: module
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-11-08
---

# Diagnostics

**Module:** `eu.exeris:exeris-diagnostics`

`exeris-diagnostics` owns the registry of every diagnostic identifier the tooling prints: the enum `eu.exeris.tooling.diagnostics.DiagnosticId`, which is the module's only type. The processor, the Java pipeline, the Maven plugin and the initializr format their messages through it, so a tool reading build output keys on one closed list.

The module prints nothing, reads nothing and registers no service. It holds identifiers and their one-line meanings; what each identifier means for a user, and what to do about it, is the [diagnostics reference](../diagnostics.md).

## Coordinates and dependencies

| Item | Value |
|---|---|
| Group and artifact | `eu.exeris:exeris-diagnostics` |
| Published | Yes, to Maven Central with the reactor, managed in `exeris-tooling-bom` |
| Compile dependencies | None. `DiagnosticId` imports nothing |
| Test dependencies | JUnit Jupiter, AssertJ, ArchUnit |
| Depended on by | `exeris-processor`, `exeris-codegen-java`, `exeris-codegen-maven-plugin`, `exeris-initializr` |

Having no dependencies is the point: the processor's permitted dependency set names this artifact, and an artifact with nothing in it but an enum can put no runtime type and no second processor on the `javac` path ([ADR-095](../adr/ADR-095-stable-diagnostic-identifiers.md) obligation 7).

## Contract

| Member | Guarantee |
|---|---|
| `DiagnosticId` constants | One constant per identifier `EXT-<AREA>-<NNNN>`. An identifier names one meaning for as long as it exists, is never renumbered and never reused |
| `code()` | The identifier string, for example `EXT-PROC-1001` |
| `meaning()` | The one-line meaning. It may be clarified, never changed to a different meaning |
| `format(String message)` | `[Exeris] <identifier>: <message>`, the printed shape every print site uses |

The areas are `PROC` (the processor, 1xxx), `PLUG` (the Maven plugin goals, 2xxx), `GEN` (the code-generation pipeline, 3xxx) and `INIT` (the initializr, 5xxx). `TS` and its 4xxx range are reserved for the TypeScript emitter, which has no identifiers yet. The numbering scheme, the groups inside each area and the retired list are in the [diagnostics reference](../diagnostics.md); the rules are ADR-095 obligations 1 to 6 and its Amendment 2.

The text after the identifier is not part of the contract and may be reworded in any release. A consumer matches `\[Exeris\] (EXT-[A-Z]+-\d{4}): ` anywhere in a line, because `javac`, Maven and the JDK logger each put their own header in front.

### Guard tests

| Test | What it holds |
|---|---|
| `DiagnosticIdTest` | `docs/diagnostics.md` and the enum list the same identifiers; no registered identifier appears under *Retired identifiers*; pinned values do not change |
| `DiagnosticsArchitectureTest` | The module's bytecode depends only on JDK `java..` packages and its own, and on no host framework |

## Hot path

None: build-time. The enum is loaded by `javac` (through the processor), by Maven (through the plugin) and by the two command-line jars. Nothing in this module reaches a generated application.

## Failure modes

The module raises nothing of its own. Its failure is a drift between the registry and the reference page, and `DiagnosticIdTest` turns that into a failed build of this module.

One gap is structural and stated in ADR-095: only the processor cannot print without an identifier, because its print helpers take a required `DiagnosticId` and only one method calls `Messager.printMessage`. A new print site in the plugin or the pipeline that skips `DiagnosticId.format` compiles; review and the per-site tests catch it.

Trade-off: a severity change keeps the identifier, so the group digit records where an identifier was allocated, not its severity today. A consumer that reads severity from the hundreds digit reads it wrong after such a change.

## Owning ADRs

| ADR | Decision |
|---|---|
| [ADR-095](../adr/ADR-095-stable-diagnostic-identifiers.md) | The registry, the `EXT` prefix, the numbering, the stability rules and the printed shape; Amendment 1 moves the artifact to the `eu.exeris` group, Amendment 2 adds `INIT` |

## Where this does not apply

- **The kernel's codes.** `EX-<DOMAIN>-<NNNN>` belongs to `KernelErrorCodes` in the kernel and is not merged with this registry.
- **The TypeScript emitter.** `exeris-codegen-ts` prints its errors without an identifier; see [codegen-ts](05-codegen-ts.md).
- **Logging inside a generated application.** Emitted code logs through the JDK's system logger ([ADR-060](../adr/ADR-060-generated-code-logging-facade.md)), not through this registry.
