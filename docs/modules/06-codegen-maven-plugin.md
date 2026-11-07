---
title: Maven plugin
type: module
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-11-03
---

# Maven plugin

**Module:** `eu.exeris:exeris-codegen-maven-plugin`, goal prefix `exeris`

`exeris-codegen-maven-plugin` runs the Java pipeline inside an application's Maven build and checks what the build hands the generated code: `generate` writes the generated trees, `verify-capabilities` validates the capability graph and the Wall against fresh metadata, `verify-runtime` checks that a kernel driver is on the runtime classpath, and `detach` hands the generated sources to the application.

Each goal is a thin shell. Emission and every check live in `CodegenPipeline` and [codegen-core](03-codegen-core.md); the plugin chooses where to read metadata and where to write sources, and maps failures to `EXT-PLUG` identifiers.

## Coordinates and dependencies

| Item | Value |
|---|---|
| Group and artifact | `eu.exeris:exeris-codegen-maven-plugin`, packaging `maven-plugin` |
| Published | Yes, to Maven Central; managed in `exeris-app-bom`'s `pluginManagement`, and its goals bound by `exeris-app-parent` |
| Compile dependencies | `exeris-codegen-java`, `exeris-diagnostics`; `maven-plugin-api`, `maven-core`, `maven-plugin-annotations` provided |
| JDK | Its classes load into Maven's own JVM, so Maven must run on JDK 25 or newer; the reactor's enforcer rule says so in one line |

## Contract

Every goal is `threadSafe`.

| Goal | Default phase | Parameters (user property, default) |
|---|---|---|
| `generate` | `generate-sources` | `exeris.metadataDir` (`${project.build.outputDirectory}/exeris-metadata`), `exeris.outputDir` (`src/main/generated/java`), `exeris.basePackage` (unset), `exeris.codegen.skip` (`false`), `exeris.codegen.allowEmpty` (`false`), `exeris.tests` (`false`), `exeris.testOutputDir` (`src/test/generated/java`), `exeris.integrationTests` (`false`), `exeris.integrationTestOutputDir` (`src/test/generated-it/java`), `exeris.addCompileSourceRoot` (`true`) |
| `verify-capabilities` | `process-classes` | `exeris.metadataDir`, `exeris.classesDir` (`${project.build.outputDirectory}`), `exeris.codegen.skip`, `exeris.wall.skip` (`false`) |
| `verify-runtime` | `process-classes` | `exeris.metadataDir`, `exeris.codegen.skip`, `exeris.verifyRuntime.skip` (`false`); reads the resolved runtime classpath |
| `detach` | none (run by hand) | `exeris.generatedDir` (`src/main/generated/java`), `exeris.targetDir` (`src/main/java`), `exeris.gitignore` (`.gitignore`), `exeris.ignoreEntry` (`src/main/generated/`), `exeris.failOnConflict` (`false`) |

### `generate`

Runs `CodegenPipeline.run`, under `exeris.tests` `runTests`, and under `exeris.integrationTests` `runIntegrationTests`, and registers each output root as a compile or test-compile source root unless `exeris.addCompileSourceRoot=false`. Because the processor writes metadata during `compile`, which runs after `generate-sources`, the metadata this goal reads is the previous build's. The first build of a project is two passes: `mvn compile -Dexeris.codegen.skip=true` to seed the metadata, then an ordinary build.

When `verify-capabilities` is bound at `process-classes` or later, a capability-graph failure here degrades to a warning, the previous `cap-manifest.json` is kept, and the later gate decides on fresh metadata. Without that gate the failure stays fatal here.

After writing, the goal compares the artifacts the generated code imports, plus H2, which the generated integration tests need at test runtime (`RequiredCompileArtifacts`), with the dependencies Maven collected for the module, and warns with `EXT-PLUG-2004` for each one absent. The check reads coordinates from dependency collection and downloads nothing.

### `verify-capabilities`

Validates the capability graph over the metadata the processor wrote in this build's `compile`, then scans `exeris.classesDir` with `CapTierWall`. It writes nothing. A build with no capability metadata passes.

### `verify-runtime`

Fails when an SPI that `RequiredDrivers` derives from the emitted artifacts has no `META-INF/services` registration on the runtime classpath. It accepts any driver that registers the providers, not a particular artifact ([ADR-078](../adr/ADR-078-runtime-driver-gate.md)).

### `detach`

Moves the files under `exeris.generatedDir` byte for byte into `exeris.targetDir`, prunes the emptied generated tree and removes `exeris.ignoreEntry` from the ignore file. A missing or empty generated tree is a no-op, so rerunning is safe. A file that already exists at the target is a conflict and is left in place, never overwritten. After it the application owns the code and removes the `generate` execution from its build. It moves the one tree `exeris.generatedDir` names; the generated-test tree is not moved.

## Hot path

None: build-time. Each goal runs once per module per build.

## Failure modes

| Goal | Situation | What the build sees |
|---|---|---|
| `generate` | No metadata, and a previous run owns a committed tree | Failure `EXT-PLUG-2001`; nothing deleted. `-Dexeris.codegen.allowEmpty=true` permits the teardown |
| `generate` | Reading metadata or writing the main tree fails | Error `EXT-PLUG-2002`, ending with the cause |
| `generate` | Writing the generated-test tree fails | Error `EXT-PLUG-2003` |
| `generate` | Writing the generated integration-test tree fails | Error `EXT-PLUG-2005` |
| `generate` | `exeris.integrationTests` on a build the integration tests do not boot: an entity not `GLOBAL`, a graph entity, a `@Blob` field or a `@CapabilityModule` | Warning `EXT-GEN-3105` naming the reason; nothing written or pruned in that root |
| `generate` | A generated import's artifact, or H2 under `exeris.integrationTests`, is not on the classpath | Warning `EXT-PLUG-2004` naming `groupId:artifactId`; `javac` fails next on a missing import, the integration tests on a missing H2 driver; add H2 at test scope (ADR-058 Amendment 1) |
| `generate` | A refusal from the pipeline (`EXT-PROC-1033`, `EXT-GEN-3003`) | Failure carrying the pipeline's message |
| `generate`, `verify-capabilities` | The capability graph does not resolve | Failure `EXT-PLUG-2201`, every problem listed |
| `verify-capabilities` | Capability metadata cannot be read | Error `EXT-PLUG-2202` |
| `verify-capabilities` | A capability module's classes cross the Wall | Failure `EXT-PLUG-2203` |
| `verify-capabilities` | A class file cannot be read during the scan | Error `EXT-PLUG-2204` |
| `verify-capabilities` | `exeris.wall.skip=true` | Warning `EXT-PLUG-2205` on every build |
| `verify-capabilities` | Capability modules declared, no compiled class found | Warning `EXT-PLUG-2206` |
| `verify-runtime` | Metadata cannot be read | Error `EXT-PLUG-2301` |
| `verify-runtime` | A required SPI has no provider on the runtime classpath | Failure `EXT-PLUG-2302` naming what is missing |
| `detach` | Moving the sources or editing the ignore file fails | Error `EXT-PLUG-2101`; the tree may be partly moved |
| `detach` | Files already exist at the target | Warning `EXT-PLUG-2102`, or a failure under `exeris.failOnConflict=true` |

What to do about each is in the [diagnostics reference](../diagnostics.md). An I/O failure ends with the cause's `toString()`, so it is visible without `mvn -e`.

Trade-off: binding `generate` before `compile` makes the generated sources part of the same build, at the price of reading the previous build's metadata. The two-pass first build and the deferred capability verdict are what that costs; `exeris-app-parent` binds the goals so that a consumer pays it once.

## Owning ADRs

| ADR | Decision |
|---|---|
| [ADR-078](../adr/ADR-078-runtime-driver-gate.md) | `verify-runtime` |
| [ADR-055](../adr/ADR-055-cap-tier-wall-guard.md) | The Wall scan behind `verify-capabilities` |
| [ADR-058](../adr/ADR-058-generated-test-emission-channel.md) | `exeris.tests` and the separate test root; Amendment 1, `exeris.integrationTests` and the integration-test root |
| [ADR-095](../adr/ADR-095-stable-diagnostic-identifiers.md) | The `EXT-PLUG` identifiers, grouped by goal |
| [ADR-091](../adr/ADR-091-opt-in-application-starter.md) | Which goals `exeris-app-parent` binds, and where |

## Where this does not apply

- **Reattaching a detached tree.** There is no `reattach` goal; the edit-safety question is open in [RFC-2026-06-05](../rfc/RFC-2026-06-05-reattach-edit-safety-and-provenance.md).
- **Generating the frontend.** The plugin runs the Java pipeline only; the Angular application comes from `exeris-gen`.
- **A build without Maven.** [codegen-cli](07-codegen-cli.md) runs the same pipeline from `java -jar`.
- **Plugin API stability.** No ADR freezes the goals or their parameters; see the [stability matrix](../stability-matrix.md).
