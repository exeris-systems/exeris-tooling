---
title: Codegen core
type: module
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-11-03
---

# Codegen core

**Module:** `eu.exeris:exeris-codegen-core`

`exeris-codegen-core` holds what a generator needs that is not Java emission: the generator interface and its registry, the output writer that owns a generated tree, the derivations from `DomainMetadata` that both the emitted code and the build-time checks must agree on (subsystems, drivers, route requirements), and the capability graph with its composition stamp and bytecode Wall scan.

It depends on no emission library. JavaPoet stays in [codegen-java](04-codegen-java.md), because a helper whose signature exposes `TypeSpec.Builder` would pull it in here ([ADR-015](../adr/ADR-015-codegen-emission-strategy.md), decision 4).

## Coordinates and dependencies

| Item | Value |
|---|---|
| Group and artifact | `eu.exeris:exeris-codegen-core` |
| Published | Yes, to Maven Central |
| Compile dependencies | `exeris-sdk-source-model`, `exeris-sdk-composition-spec`, Jackson 2 `jackson-databind` and `jackson-datatype-jsr310` |
| Test dependencies | JUnit Jupiter, AssertJ, ArchUnit |

The `ban-host-and-kernel-runtime` enforcer execution holds the resolved graph free of host frameworks and of the kernel runtime. `CodegenCoreArchitectureTest` holds the bytecode: no `javax.lang.model..` other than `Modifier`, no `javax.annotation.processing..`, no clock and no randomness ([DomainMetadata contract](../../.agents/policies/domain-metadata-contract.md), [codegen determinism](../../.agents/policies/codegen-determinism.md)).

## Contract

### Generators

| Type | Guarantee |
|---|---|
| `generator.KernelArtifactGenerator` | One per artifact kind. `generate(DomainMetadata)` returns one `GeneratedFile` or `null` when the generator does not apply. `generateMultiple(metadata)` returns zero or more, and `generateMultiple(metadata, build)` sees every entity of the build; the defaults delegate down the chain. `ArtifactType` names the artifact kind |
| `generator.GeneratorRegistry` | An ordered list of generators. `generateAll(metadata, build)` runs each one whose `supports` holds, in registration order, and drops `null` results; `generateAll(metadata)` treats the entity as a build of one |
| `generator.GeneratedFile` | One emitted file: its package (or directory), its file name without extension, its content, its `ArtifactType` and its extension, `java` by default |

The build-time pipeline in `exeris-codegen-java` iterates `generateMultiple(metadata, build)` on every registered generator and does not consult `supports`; a generator decides inside `generate` whether to write a file. [ADR-097](../adr/ADR-097-generator-catalogue-and-launchable-generator.md) obligation 9 keeps the generator catalogue off this interface, because the interface is public.

### Output tree ownership

`OutputWriter` writes generated files and records every relative path it writes. `pruneOrphansAndWriteManifest()` deletes each path the previous run's `.exeris-codegen-manifest` listed and this run did not write, prunes the directories that leaves empty, and writes the new manifest, sorted. A file that never appeared in a manifest is never deleted. `countOrphans()` lets a caller ask what a prune would delete before it runs one, which is how the pipeline refuses to wipe a committed tree on empty metadata (`EXT-PLUG-2001`).

### Derivations shared with the emitted code

| Type | Derives | Read by |
|---|---|---|
| `driver.RequiredSubsystems` | The subsystem names the emitted `Application.subsystems()` returns: `http`, `persistence` and `crypto` always; `graph`, `flow`, `events`, `security`, `storage` when some entity needs them. The order is fixed whatever order the domains arrive in | `KernelApplicationGenerator` |
| `driver.RequiredDrivers` | The kernel SPIs a provider must be registered for: `SubsystemProvider`, `PersistenceProvider` and `HttpProvider` always; `EventProvider`, `GraphProvider`, `FlowProvider`, `BlobStorageProvider` on the same predicates as the subsystems. `crypto` is deliberately absent: no emitted artifact uses it | `exeris:verify-runtime` |
| `driver.RuntimeDriverCheck` | Which of those SPIs have no `META-INF/services` entry anywhere on a classpath. A file lookup: no consumer class is loaded | `exeris:verify-runtime` |
| `access.RouteRequirements` | The requirement each generated route carries: `PERMIT_ALL`, `AUTHENTICATED`, `ANY_SCOPE` over the declared scopes, or none. The nearest declaration wins, and an action's list replaces its entity's | `KernelApplicationGenerator`, the OpenAPI security builders |

Because the emitted list and the build-time requirement read the same predicates, the default `subsystems()` asks for exactly the subsystems whose providers the build requires, plus `crypto`.

### Capabilities

| Type | Guarantee |
|---|---|
| `capability.CapabilityGraph` | Resolves `@Provides` against `@Requires` over the build's capability modules, and throws `CapabilityGraphException` listing every unsatisfied requirement, version mismatch and cycle. `SCHEMA_VERSION` is `2` |
| `capability.VersionRange` | Parses and matches the version ranges a `@Requires` declares |
| `capability.CompositionStamp` | The validation stamp written into `cap-manifest.json` |
| `capability.CapTierWall` | Scans compiled classes with the JDK Class-File API, adding no dependency, and reports each reference to a host-runtime package, a kernel private package or a sibling capability's `internal` package as a `WallViolation`; `CapTierWallException` carries them |

`CapTierWall` unions the constant pool, field and method descriptors, `Signature` attributes and annotation types. Reflective reach-through by string is not caught, and string constants are deliberately not scanned; the class Javadoc names both limits.

### Also in the module

`MetadataLoader` reads `exeris-metadata/*.json` into a type the caller names, in file-name order, ignoring unknown properties. The Java pipeline does not call it: `CodegenPipeline.loadMetadata` reads the same files by the same `inCreate` and `inUpdate` rule and additionally resolves enum types and skips the `enum_` and `capability_` files. `CodegenContext` is a record of generation options that no main code in the reactor constructs.

## Hot path

None: build-time. Everything here runs inside `exeris:generate`, the `verify-*` goals or `CodegenMain`. The emitted application does not depend on this module; what it shares with it is the derivation, emitted into the code.

## Failure modes

| Situation | What the caller sees |
|---|---|
| The capability graph does not resolve | `CapabilityGraphException` with its `problems()`. The plugin and `CodegenMain` print it as `EXT-PLUG-2201` |
| A capability module's classes cross the Wall | `CapTierWallException` with its `violations()`, printed as `EXT-PLUG-2203` |
| A class file cannot be read during the Wall scan | `IOException`, printed as `EXT-PLUG-2204` |
| An unparseable version range | `IllegalArgumentException` from `VersionRange.parse` |
| A required SPI has no registration on the runtime classpath | A `RuntimeDriverCheck.Result` whose `missing()` is non-empty; `exeris:verify-runtime` fails with `EXT-PLUG-2302`. `scanned()` is carried so a pass over an empty classpath cannot read as a pass |
| A metadata file named to `MetadataLoader.load` does not exist | `IOException` naming the path |

`RuntimeDriverCheck` proves at least one registered implementation per SPI, nothing more: not that it supplies a given subsystem name, matches a version or starts. Answering those means running the provider, which a build-time gate does not do.

Trade-off: deriving subsystems and drivers from the metadata, not from the emitted `subsystems()` string, means a consumer that overrides `subsystems()` to boot less still gets the full driver requirement. The alternative, reading the string, would check something the running application may not ask for and fail correct builds.

## Owning ADRs

| ADR | Decision |
|---|---|
| [ADR-015](../adr/ADR-015-codegen-emission-strategy.md) | Core stays free of emission libraries |
| [ADR-078](../adr/ADR-078-runtime-driver-gate.md) | The runtime driver gate: required SPIs derived from what was emitted |
| [ADR-105](../adr/ADR-105-generated-route-policy-emission.md) | Route requirements, nearest declaration, any-of over permissions; `security` derived |
| [ADR-055](../adr/ADR-055-cap-tier-wall-guard.md) | The bytecode Wall scan |
| [ADR-024](https://github.com/exeris-systems/exeris-docs/blob/main/adr/ADR-024-capability-composition-model.md) | Capability composition and its build-time validation predicates |
| [ADR-097](../adr/ADR-097-generator-catalogue-and-launchable-generator.md) | The generator catalogue is not a method on `KernelArtifactGenerator` |

## Where this does not apply

- **Emitting Java.** The `Kernel*Generator` classes, JavaPoet and the shared scaffold live in [codegen-java](04-codegen-java.md).
- **The TypeScript side.** `exeris-codegen-ts` carries its own route-requirement and server-owned-field derivations in TypeScript, held to the Java ones by the shared contract files, not by this module ([e2e tests](10-e2e-tests.md)).
- **Boot-time composition.** `cap-manifest.json` is read and asserted at boot by `exeris-sdk-composition-runtime`; this module only writes what it asserts.
