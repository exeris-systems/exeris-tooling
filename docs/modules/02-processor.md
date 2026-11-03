---
title: Annotation processor
type: module
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-11-03
---

# Annotation processor

**Module:** `eu.exeris:exeris-processor`

`exeris-processor` is the one place where the tooling reads Java source. `ExerisDomainProcessor`, a JSR 269 processor registered through `@AutoService`, runs inside the application's `javac`, extracts each annotated type into the SDK's source-model records, refuses declarations the generated application cannot serve, and writes one JSON file per type into `exeris-metadata/` in the class output.

It never generates code, never loads a consumer class and never depends on the kernel. Everything downstream reads the JSON it writes; see [architecture](../architecture.md) for why that file is the hand-off.

## Coordinates and dependencies

| Item | Value |
|---|---|
| Group and artifact | `eu.exeris:exeris-processor` |
| Published | Yes, to Maven Central; managed in `exeris-app-bom` and put on `annotationProcessorPaths` by `exeris-app-parent` |
| Compile dependencies | `exeris-sdk-annotations`, `exeris-sdk-source-model`, `exeris-diagnostics`, Jackson 2 `jackson-databind` and `jackson-datatype-jsr310`; `auto-service` optional |
| Test dependencies | JUnit Jupiter, AssertJ, ArchUnit, `compile-testing`, `exeris-sdk-tck`, `exeris-sdk-source-model-io`, Lombok |

`ProcessorArchitectureTest` holds the bytecode to the build-time set: the JDK (`java..`, `javax.lang.model..`, `javax.annotation.processing..`, `javax.tools..`, `com.sun.source..`), `eu.exeris.sdk.sourcemodel..`, `eu.exeris.tooling.diagnostics..`, `com.google.auto.service..` and `com.fasterxml.jackson..`. The SDK annotations are matched by name and never referenced as types. The `ban-host-and-kernel-runtime` enforcer execution keeps Spring, Quarkus, Micronaut, `exeris-kernel-core` and `exeris-kernel-community*` off the resolved graph. The rules are written once, in [processor build-time only](../../.agents/policies/processor-build-time-only.md).

## Contract

### What it reads

`@SupportedAnnotationTypes` names five roots: `@ExerisDomain`, `@Saga`, `@CapabilityModule`, `@View` and `@Schedule`. The processor walks each root's members for the annotations they carry. `getSupportedSourceVersion()` returns `SourceVersion.latestSupported()`, so a consumer compiling at a newer release than 25 gets no source-version warning.

### What it writes

Every file goes to `StandardLocation.CLASS_OUTPUT` under `exeris-metadata/`, which in a Maven build is `target/classes/exeris-metadata/`.

| File | Written for | Content |
|---|---|---|
| `<Entity>.json` | each `@ExerisDomain` type | A serialized `DomainMetadata`, plus the two ADR-042 trust fields `schemaVersion` and `sourceDigest` as siblings in the same object |
| `<Saga>.json` | each standalone `@Saga` type | A `DomainMetadata` carrying the saga, stamped the same way |
| `enum_<Name>.json` | each enum type a field references, written in the final round | An `EnumMetadata` |
| `capability_<Name>.json` | each `@CapabilityModule` type | The module's name, package, qualified name and `CapabilityModuleMetadata` |
| `view_<Name>.json` | each `@View` type | The view's name, package, qualified name and `ViewMetadata` |

`schemaVersion` is read from the SDK's `BaselineTrust.current`, not inlined from a constant. `sourceDigest` is `SourceDigest.of` over the entity's raw source text, read through the compiler tree API; when that text is unavailable (no `javac` tree API, as in some IDE builds) the field is omitted and `EXT-PROC-1902` is noted under `-Aexeris.verbose`. The record shapes and their wire rules belong to `exeris-sdk-source-model`; see the [SDK source model page](https://github.com/exeris-systems/exeris-sdk/blob/main/docs/modules/02-source-model.md).

### Options

All three are off unless passed as `-A<name>=true`.

| Option | Effect |
|---|---|
| `exeris.verbose` | Prints the `EXT-PROC-19xx` notes: a progress line per processed element, and full stack traces on a processing failure |
| `exeris.strict` | Runs the completeness audit and prints `EXT-PROC-1201` to `1203`: an attribute or annotation that is set but that no generator consumes (`INERT_ATTRIBUTES`, `INERT_ANNOTATIONS`), and an SDK annotation the processor never reads (outside `EXTRACTED_ANNOTATIONS`) |
| `exeris.metadataOnly` | Declares that the metadata feeds no generated kernel application. It skips `EXT-PROC-1033` and `EXT-PROC-1034`, the two refusals only that application needs. The metadata written is the same either way |

`StrictAuditRegistryKeysTest` holds every key of the strict-audit registries to an annotation and attribute that exist in the SDK on the classpath, so a renamed SDK attribute cannot leave a dead audit entry.

### Parity with the SDK reader

ADR-042 requires the processor and the SDK's `SourceModelReader` to produce the same `DomainMetadata` from the same source. The processor binds the SDK conformance kit in `src/test/java/eu/exeris/tooling/processor/tck/`:

| Test | Suite it extends | What it holds |
|---|---|---|
| `ProcessorProducerTckTest` | `AbstractMetadataProducerTck` | The processor's output over the SDK corpus |
| `ProcessorAsReaderTckTest` | `AbstractMetadataReaderTck` | The same output read back as the reader contract expects |
| `ProcessorReaderParityTckTest` | `AbstractMetadataParityTck` | Processor output and `SourceModelReader` output from the same source agree |
| `TckFacetRatchetTest` | none | The set of facets the binding declares unsupported only shrinks |

`TckCorpusProducer` compiles the whole corpus once, with `-Aexeris.metadataOnly=true`, and declares no facet unsupported: both `UNSUPPORTED` and the ratchet's `CEILING` are empty.

## Hot path

None: build-time. The processor runs once per `javac` invocation and is single-threaded within it. Nothing it contains is on a generated application's classpath at run time.

## Failure modes

Every diagnostic is printed through one method, `print`, which `ProcessorArchitectureTest` holds as the only caller of `Messager.printMessage`; every message therefore carries an `EXT-PROC` identifier. Failure text carries `e.toString()`, never `e.getMessage()`, which can be `null`. The meaning of each identifier and what to do about it is the [diagnostics reference](../diagnostics.md).

| Situation | What the author sees |
|---|---|
| A declaration the generated application cannot serve: wrong element kind, contradictory data scope, a `UNIVERSE` entity without its tenant or shared-scope field, a primary key that is not a `UUID` field, a reserved stream frame name, an unbindable action parameter, an inconsistent saga step order, `@Schedule`, a misplaced `@Blob` | A `javac` error, `EXT-PROC-1001` and `1003` to `1032`. Compilation fails |
| An entity whose package does not end in `.domain`, or that lacks a getter or setter the generated code calls | `EXT-PROC-1033`, `EXT-PROC-1034` (one per missing accessor; Lombok `@Getter`, `@Setter`, `@Data` and `@Value` count). Skipped under `-Aexeris.metadataOnly` |
| Extraction of one element throws | A `javac` error, `EXT-PROC-1002`, with the exception's `toString()`; no file is written for that element. The stack trace needs `-Aexeris.verbose=true` |
| A deprecated attribute or an attribute with no effect | A warning, `EXT-PROC-1101` to `1109`; the build continues |
| Strict audit findings | Warnings `EXT-PROC-1201` to `1203`, only under `-Aexeris.strict` |

The full list of what `KernelAccessors` derives for `EXT-PROC-1034` is in that class's Javadoc.

Trade-off: the processor refuses at the declaration, which puts every refusal in the author's IDE at the right line, at the price of encoding knowledge of the generated application (its `.domain` layout, its accessor calls) inside a module that otherwise knows only the SDK. `-Aexeris.metadataOnly` exists because a consumer that generates no application, such as the SDK conformance kit or the SDK examples gate, would otherwise be refused for a layout it does not need.

## Owning ADRs

| ADR | Decision |
|---|---|
| [ADR-042](../adr/ADR-042.link.md) | Processor and SDK reader agree; the `schemaVersion` and `sourceDigest` trust fields |
| [ADR-095](../adr/ADR-095-stable-diagnostic-identifiers.md) | Every diagnostic carries an `EXT-PROC` identifier; the processor cannot print without one |
| [ADR-059](../adr/ADR-059.link.md) | `dataScope`, and the `UNIVERSE` refusals |
| [ADR-104](../adr/ADR-104-declared-primary-key-uuid-only.md) | A declared primary key is a `UUID` |
| [ADR-044](../adr/ADR-044-tooling-sse-stream-emitter-shape.md) | Reserved stream frame names and the real-time refusal on tenant-partitioned entities |
| [ADR-108](../adr/ADR-108-blob-field-routes.md) | `@Blob` refused on a `GLOBAL` entity and on a field that is not a `BlobRef` |

## Where this does not apply

- **Code generation.** The processor writes JSON and nothing else; the generators are in [codegen-java](04-codegen-java.md) and [codegen-ts](05-codegen-ts.md).
- **The record shapes.** `DomainMetadata` and its family are defined in `exeris-sdk-source-model`, not here.
- **Design-time reading.** Studio, the LSP and other editors read source through the SDK's `SourceModelReader`, not through this processor.
- **What a generator does with an extracted attribute.** An attribute being in the JSON is not proof that output depends on it; `-Aexeris.strict` reports the ones that do not.
