---
title: Application BOM, parent and starter
type: module
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-11-03
---

# Application BOM, parent and starter

**Modules:** `eu.exeris:exeris-app-bom`, `eu.exeris:exeris-app-parent`, `eu.exeris:exeris-app-starter`, all packaging `pom`

Three opt-in POMs an application may build on. `exeris-app-bom` names the tooling, kernel and SDK versions one tooling release was built and tested against. `exeris-app-parent` wires the build: the processor, the bound codegen goals and the generated resources. `exeris-app-starter` carries the dependency set generated code compiles and runs against.

The generators never emit any of them, nor any other build file. An application that cannot take the parent can import the BOM and copy the parent's build section instead; ADR-091 keeps that path open.

## Coordinates and dependencies

| Module | Parent | Holds |
|---|---|---|
| `exeris-app-bom` | none | `dependencyManagement` for `exeris-processor`, `exeris-app-starter`, `exeris-kernel-spi`, `exeris-kernel-core`, `exeris-kernel-community`, `exeris-kernel-community-testkit`, `exeris-sdk-annotations`, `exeris-sdk-composition-runtime` and Jackson 3 `jackson-databind`; `pluginManagement` for `exeris-codegen-maven-plugin` |
| `exeris-app-parent` | `exeris-app-bom` | The build, below |
| `exeris-app-starter` | `exeris-app-bom` | The dependencies, below |

None of the three inherits `exeris-tooling-root` or `exeris-tooling-parent`, so a consumer receives none of the reactor's enforcer messages, coverage gates or internal pins. The reactor's own `exeris-tooling-bom` and `exeris-tooling-parent` are published with the reactor, are not for applications, and have no page.

## Contract

### `exeris-app-bom`

Every tooling, kernel and SDK version in it is a literal property: `exeris.tooling.version`, `exeris.kernel.version`, `exeris.sdk.version` and `jackson3.version`, never `${project.version}` or anything derived from it, because the parent passes them on by inheritance and an inherited value is interpolated in the consumer's model. The kernel, SDK and Jackson values equal the same properties in `exeris-tooling-bom`, the versions this release's suites ran against. `tools/release-readiness/release-readiness.sh` fails a release where they differ or where an Exeris version is a `-SNAPSHOT`.

It manages `com.h2database:h2` at `h2.version`, for the generated integration tests ([ADR-091](../adr/ADR-091-opt-in-application-starter.md) Amendment 7), and nothing else: no JavaPoet, swagger, Jackson 2, compile-testing, JUnit or AssertJ.

### `exeris-app-parent`

| Piece | Setting |
|---|---|
| Compiler | `maven.compiler.release` 25; `exeris-processor` on `annotationProcessorPaths` |
| Enforcer | JDK `[25,)` and Maven `[3.9,)`, each failing with one line |
| Codegen | `exeris:generate` at `generate-sources`; `exeris:verify-capabilities` and `exeris:verify-runtime` at `process-classes` |
| Resources | `src/main/resources`, and `db/migration/**` and `openapi/**` from `src/main/generated/java`, so the migrations and the OpenAPI document reach the classpath |
| Surefire | 3.2.5, which runs the JUnit 5 generated tests with no provider declared |
| Failsafe | 3.2.5, bound in `integration-test` and `verify` (execution `exeris-integration-tests`), which runs the generated `*IT` integration tests ([ADR-058](../adr/ADR-058-generated-test-emission-channel.md) Amendment 1) |

The parent declares Exeris's own `url` and inherits `licenses`, `developers` and `scm` from the BOM, because Central validates it as published. An application on it inherits them unless it declares its own; the README's application POM blanks them.

### `exeris-app-starter`

| Dependency | Scope | Why |
|---|---|---|
| `exeris-sdk-annotations` | compile | Maven does not pass a dependency's `provided` dependencies on. The annotations are `@Retention(SOURCE)`, so the jar on the runtime classpath is inert |
| `exeris-kernel-spi`, `exeris-kernel-core` | compile | Every handler and repository imports the SPI; `Application` imports `KernelBootstrap`, `HttpRouter` and `TransactionOrchestrator` from Core |
| `exeris-kernel-community` | runtime | The default driver. An application on another driver excludes it and declares its own; `exeris:verify-runtime` checks for registered providers, not for this artifact |
| `exeris-sdk-composition-runtime` | compile | The composed `Application` imports `CompositionConductor` |
| `tools.jackson.core:jackson-databind` | compile | The repository of an entity with a `List<X>` field imports Jackson 3 |

It declares no JDBC driver, no logging backend and no test library. Those are the application's: generated code logs through `System.Logger` and binds no facade, and the generated tests need JUnit 5 and AssertJ in the application's test scope.

## Hot path

None: build-time. The POMs configure a build. What they put on an application's runtime classpath is the kernel and SDK, whose hot paths are theirs.

## Failure modes

| Situation | What the build sees |
|---|---|
| Maven on a JDK older than 25, or Maven older than 3.9 | The parent's enforcer fails with one line naming the floor, instead of a classworlds `UnsupportedClassVersionError` |
| A generated import whose artifact the build lacks (a starter excluded, a driver swapped) | `EXT-PLUG-2004` from `exeris:generate`, then `javac` fails on the package |
| No kernel driver on the runtime classpath | `EXT-PLUG-2302` from `exeris:verify-runtime` |
| An application declaring its own `<resources>` | It replaces both of the parent's entries, so the migrations and the OpenAPI document no longer reach the classpath unless it restates the generated one |

The invoker fixtures in `exeris-app-starter-it` build applications on these POMs with the real plugin ([e2e tests](10-e2e-tests.md)), so an emitter that starts importing something outside the starter fails the reactor build.

Trade-off: a tested triple pins every application on the parent to one kernel and one SDK version per tooling release. An application that needs a kernel patch sooner overrides `exeris.kernel.version` and leaves the tested combination.

## Owning ADRs

| ADR | Decision |
|---|---|
| [ADR-091](../adr/ADR-091-opt-in-application-starter.md) | The three POMs, the tested triple, the literal-version rule, the starter's scopes (Amendment 2), the `eu.exeris` group (Amendment 4) |
| [ADR-078](../adr/ADR-078-runtime-driver-gate.md) | Any driver that registers the providers satisfies `verify-runtime` |
| [ADR-060](../adr/ADR-060-generated-code-logging-facade.md) | No logging backend in the starter |
| [ADR-058](../adr/ADR-058-generated-test-emission-channel.md) | Test libraries stay in the application's test scope |

## Where this does not apply

- **The reactor's own build.** `exeris-tooling-bom` and `exeris-tooling-parent` pin what this repository builds with; an application does not inherit or import them.
- **Choosing a database.** The starter carries no JDBC driver; [initializr](08-initializr.md) picks one as a scaffold default for a new project.
- **Capabilities.** The starter adds no capability module.
- **Gradle.** Only Maven POMs are published.
