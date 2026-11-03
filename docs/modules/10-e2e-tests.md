---
title: End-to-end tests
type: module
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-11-03
---

# End-to-end tests

**Modules:** `exeris-e2e-tests`, `exeris-app-starter-it`, `exeris-coverage-aggregate`; none is published

`exeris-e2e-tests` is where the pipeline is held to what it produces: generated sources compiled against the real kernel jars, generated applications booted on a real kernel, generated SQL run on H2 and PostgreSQL, and the contract files that the Java build and the TypeScript build both read. `exeris-app-starter-it` builds whole applications on the published POMs with the real plugin. `exeris-coverage-aggregate` folds coverage from every module into one report.

Unit tests of each module stay in that module. These modules test the seams between modules, and between the tooling and the kernel, which no module can test alone.

## Coordinates and dependencies

| Module | Shape | Release |
|---|---|---|
| `exeris-e2e-tests` | Test sources only, under `eu.exeris.e2e` (`app`, `boot`, `build`, `caps`, `codegen`, `examples`, `postgres`) | `maven.deploy.skip`, and excluded from the Central upload |
| `exeris-app-starter-it` | Maven Invoker fixtures under `src/it/`, no code | Same |
| `exeris-coverage-aggregate` | JaCoCo `report-aggregate`, no code | Same, and `maven.install.skip` |

`exeris-e2e-tests` puts the processor, `exeris-codegen-core` and `exeris-codegen-java` beside the real `exeris-kernel-spi`, `-core`, `-community`, `-community-testkit` and `exeris-sdk-composition-runtime` at test scope, with H2, the PostgreSQL driver, Jackson 3 and swagger for reading what was emitted. The versions come from `exeris-tooling-bom`; no test restates one.

## Contract

### What the gates prove

| Gate | What it holds |
|---|---|
| `KernelCodegenCompileTest` | Generated sources compile with `javac` against the real kernel SPI and Core, once without capability composition and once with it, against the SDK composition runtime |
| `KernelCodegenE2ETest` | Substring and structural assertions on the emitted Java |
| `EmittedLintGateTest` | Emitted Java compiled with `-Xlint:all`; an unaccepted warning fails |
| `GeneratedTestsE2ETest` | The emitted tests run against the emitted services |
| `boot/*BootE2ETest` | The emitted `Application` booted through `KernelBootstrap`, on in-memory H2 or, in the `postgres` group, on PostgreSQL, and driven over a real socket |
| `GeneratorCatalogueConformanceE2ETest` | Every path the processor and pipeline write, main and test roots, reaches its row in the [generator catalogue](../generators.md), and every row is reached ([ADR-097](../adr/ADR-097-generator-catalogue-and-launchable-generator.md) obligation 8) |
| `RoutePolicyRouterConformanceE2ETest` | `GeneratedRoutePolicy` answers for the route the kernel's `HttpRouter` actually dispatches to |
| `CapCompositionE2ETest` | Annotated `@CapabilityModule` sources through the processor, the capability checks and the pipeline to a booted kernel, where the SDK's `CompositionStampAssertion` must agree with the emitted stamp and `CompositionConductor` replays the emitted `initOrder` |
| `ClassFileBaselineTest` | The class files this reactor ships: major version and no preview stamp, read off the bytes |
| `SdkExamplesE2ETest` | The SDK's example corpus at the pinned SDK tag, through the processor and the pipeline, twice, compared file by file |

### Contract files

`src/test/resources/contract/` is the one place the Java build and the TypeScript build meet; they never run together. Each file is read by a Java test here and a spec in `exeris-codegen-ts`, and is changed only together with the generator it describes.

| File | Java test | TypeScript spec | Pins |
|---|---|---|---|
| `crud-routes.json` | `CrudRouteParityE2ETest` | `crud-route-parity.spec.ts` | The routes the router registers per entity; the Java client and the TS service call only these |
| `stream-routes.json` | `StreamRouteParityE2ETest` | `stream-route-parity.spec.ts` | The SSE routes, and which of them a TS client opens |
| `list-query.json` | `ListQueryContractE2ETest` | `list-query-contract.spec.ts` | The list route's query parameters and page envelope |
| `route-policy.json` | `RoutePolicyContractE2ETest` | `route-policy-parity.spec.ts`, `route-requirements.spec.ts` | The requirement `GeneratedRoutePolicy` declares per route of the `RoutePolicyFixture` model |
| `route-policy.example.json` | `RoutePolicyContractE2ETest` (shape only) | `auth-interceptor-gen.spec.ts`, `route-requirements.spec.ts` | Representative rows, never held against emitted code |
| `update-parity/` | `UpdateSchemaParityE2ETest` | `server-owned-fields.spec.ts` | The create and update body properties per entity: which fields are server-owned |
| `lifecycle-flags/` | `LifecycleFlagsContractE2ETest` | `lifecycle-flags.spec.ts` | Processor-written metadata of an entity with `inCreate = false` and `inUpdate = false` fields, and the property names of its create and update bodies |

### Test groups

The surefire run reads `e2e.groups` and `e2e.excludedGroups`. By default nothing is selected by group and `postgres` is excluded. `-Pe2e-postgres` selects only the `@Tag("postgres")` classes and refuses to start without `-Dexeris.e2e.postgres.url`, so a run that asked for PostgreSQL cannot pass by skipping. The `e2e-postgres` job in `.github/workflows/build.yml` runs it on PostgreSQL 16.

`SdkExamplesE2ETest` reads `exeris-sdk-examples` at tag `v<sdk version>`, the version taken from the `exeris-sdk-annotations` jar on the classpath. It takes the checkout named by `exeris.sdk.examples.dir` or `EXERIS_SDK_EXAMPLES_DIR`, else an `exeris-sdk` clone beside this repository that carries the tag. Without either it is skipped, except under `CI=true`, where it fails. The corpus's packages have no `.domain` suffix and its entities declare no accessors, so the gate asserts that refusal, then works on a copy moved into `.domain` packages, with metadata written under `-Aexeris.metadataOnly=true`.

### `exeris-app-starter-it`

| Fixture | What it builds |
|---|---|
| `app-on-starter` | An application on `exeris-app-parent` and `exeris-app-starter`: the two-pass build, then `verify`, then the effective POM under `-P release` |
| `app-off-starter` | An application without the starter, which must fail at `javac` after `exeris:generate` named the one missing artifact (`EXT-PLUG-2004`) |
| `initializr-backend`, `initializr-e2e` | The projects `exeris-initializr` writes, built the same two-pass way |

`-DskipTests` skips the fixture builds as well.

### `exeris-coverage-aggregate`

Reporting only, and no merge gate: per-module JaCoCo gates in `exeris-tooling-parent` stay the enforcement. It is also where the Sonar scanner is pointed at the `exeris-codegen-ts` sources, which no Maven module carries.

## Hot path

None: build-time. These modules run under `mvn verify`.

## Failure modes

A failure here is a failed build, and the test names what drifted. Three are worth knowing in advance:

| Situation | What the build sees |
|---|---|
| A contract file changed without the generator, or the reverse | The Java test or the TS spec of that file fails; the other build stays green until it runs |
| An emitted path with no catalogue row, or a row nothing writes | `GeneratorCatalogueConformanceE2ETest` fails |
| `-Pe2e-postgres` without `-Dexeris.e2e.postgres.url` | The enforcer's `require-postgres-url` rule fails before any test runs |

Trade-off: the boot tests run on H2, which accepts SQL that PostgreSQL refuses; only the `postgres` group shows those failures, and it does not run in the default build.

## Owning ADRs

| ADR | Decision |
|---|---|
| [ADR-097](../adr/ADR-097-generator-catalogue-and-launchable-generator.md) | The catalogue conformance test |
| [ADR-105](../adr/ADR-105-generated-route-policy-emission.md) | `route-policy.json` and the router conformance test (obligations 4 and 20) |
| [ADR-096](../adr/ADR-096-generated-list-route-query-and-page-envelope.md) | `list-query.json` |
| [ADR-091](../adr/ADR-091-opt-in-application-starter.md) | Obligation 7 and Amendment 5: the invoker fixtures |
| [ADR-058](../adr/ADR-058-generated-test-emission-channel.md) | The generated tests that `GeneratedTestsE2ETest` runs |

## Where this does not apply

- **Runtime behavior of a deployed application.** Heavyweight integration tests against a real deployment belong to downstream repositories.
- **Performance.** Nothing here measures throughput or latency; benchmarks are `exeris-benchmarks`'s.
- **The TypeScript suite.** Its specs run under Vitest in `exeris-codegen-ts`; this module only owns the contract files they read.
