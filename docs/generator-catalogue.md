---
title: "Generator catalogue — every Java and TypeScript generator, the paths it writes and when"
type: reference
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-10-07
---

# Generator catalogue

## What this catalogue is for

Each output tree the pipeline writes carries a `.exeris-codegen-manifest` at its root: a `#` header
line, then one forward-slash path per line, relative to that root and sorted. The manifest is the
authority on **which** files a build owns. This catalogue explains **why** each one is there: given
a path observed in a manifest, it names the generator that wrote it, the `DomainMetadata` field or
configuration flag that decides whether it is written, and whether it is written once per entity
or once per project.

The intended reader is a tool that maps a manifest path back to its producer, such as an MCP
server answering "what generated this file?". Such a tool should take the set of paths from the
manifest and use this catalogue only to explain them. Predicting the set from metadata would be a
second implementation of every generator's guard, and it would drift.

The catalogue is complete by construction. `GeneratorCatalogueTest` (exeris-codegen-java) fails
when a concrete `*Generator` class in the Java sources is missing from the Java table, or when the
table names a class that does not exist. `generator-catalogue.spec.ts` (exeris-codegen-ts) does
the same for every `src/generators/**/*-gen.ts` file and the TypeScript table. The guards check
names. A changed path or condition is kept current by the change that makes it.

## Notation

| Placeholder | Meaning |
| --- | --- |
| `<Entity>` | `DomainMetadata.entityName()`, verbatim. |
| `<pkg>` | The entity's package with its `.domain` segment removed, written as a path: `com.shop.order.domain` → `com/shop/order`. Every per-entity Java generator derives its package from it: `.handler`, `.service`, `.repository`, `.event`, `.graph`, `.saga`, `.client`. |
| `<base>` | The project base package as a path: `-Dexeris.basePackage` (`<basePackage>` in the plugin), else the first loaded entity's package with `.domain` removed. |
| `<table>` | The effective SQL table: the `@ExerisDomain(tableName)` override trimmed and lower-cased, else `DomainMetadata.effectiveTableName()` (the snake-cased English plural, `ConstructionOrder` → `construction_orders`). |
| `<h>` | `Math.floorMod(fullyQualifiedName().hashCode(), 1_000_000)`, the per-entity migration discriminator. A migration version is `tier × 1000000 + <h>`, so it always has seven digits and its first digit is the tier. |
| `<kebab>` | Java: the entity name with `-` inserted at each lower-to-upper boundary, lower-cased under `Locale.ROOT`. TypeScript: `DslMapper.toKebabCase(entityName)` (for a view, of `ViewMetadata.name`). |
| scope | **entity**: written zero or more times per `@ExerisDomain`, and its name carries the entity. **project**: written at most once per run. |

## Java — `exeris-codegen-java`

**Output roots.** The main root is `exeris.outputDir`, which defaults to `src/main/generated/java`.
Java sources, Flyway SQL (`db/migration/…`) and OpenAPI YAML (`openapi/…`) all land in this one
root, each through `OutputWriter`. The test root is `exeris.testOutputDir`, which defaults to
`src/test/generated/java`. It is written only under `-Dexeris.tests=true`, by
`CodegenPipeline.runTests`, and it has its own manifest. A test generator's output never lands in
the main root, so the root that holds a path decides which half of the table explains it.

**How generators are invoked.** `KernelGeneratorStrategy` registers fifteen per-entity generators.
For each loaded entity `CodegenPipeline.run` calls `generateMultiple(domain)` on every one of them,
in priority order, and drops `null` results. It does **not** consult `supports()`, so the
condition a generator applies inside `generate` / `generateMultiple` is the one that decides.
`KernelApplicationGenerator` runs once after that loop. The test generators run only from
`runTests`. A run that loads no entity writes no domain file at all.

<!-- catalogue:java:begin -->
| Generator | Output path (relative to the root) | Root | Emitted when | Scope |
| --- | --- | --- | --- | --- |
| `KernelHandlerGenerator` | `<pkg>/handler/<Entity>Handler.java` | main | Every entity; no guard. | entity |
| `KernelStreamHandlerGenerator` | `<pkg>/handler/<Entity>StreamHandler.java` | main | `realTimeApi()` is true: `generate` returns `null` otherwise (and `supports()` states the same). | entity |
| `KernelActionStreamHandlerGenerator` | `<pkg>/handler/<Entity><Action>StreamHandler.java`, where `<Action>` is the action name in PascalCase | main | One file per entry of `actions()` with `streaming()` true, in declared order; an empty list when there is none. | entity (one per streaming action) |
| `KernelServiceGenerator` | `<pkg>/service/<Entity>Service.java` | main | Every entity; no guard. | entity |
| `KernelRepositoryGenerator` | `<pkg>/repository/<Entity>Repository.java` | main | Every entity. A run in which any entity has a field type the repository cannot store and read back is refused before the first write (`EXT-GEN-3003`), so then no file is written. | entity |
| `KernelErrorGenerator` | `<pkg>/repository/<Entity>NotFoundException.java`; `<pkg>/repository/<Entity>VersionConflictException.java`; `<pkg>/repository/<Entity>TenantMismatchException.java`; `<pkg>/repository/<Entity>SharedScopeMismatchException.java` | main | `NotFoundException`: every entity. `VersionConflictException`: `versioned()`. `TenantMismatchException`: `effectiveDataScope()` is not `GLOBAL`. `SharedScopeMismatchException`: `effectiveDataScope()` is `UNIVERSE` and `systemFields().sharedScopeField()` names a field. | entity |
| `KernelListQueryGenerator` | `<pkg>/repository/<Entity>ListQuery.java`; `<pkg>/repository/<Entity>Page.java` | main | Every entity; both files, no guard. | entity |
| `KernelEventGenerator` | `<pkg>/event/<Entity>EventPublisher.java` | main | `hasEvents()`: `events()` is non-empty. | entity |
| `KernelEventHandlerGenerator` | `<pkg>/event/<Entity>EventSubscriber.java` | main | `hasEvents()`. | entity |
| `KernelGraphSyncGenerator` | `<pkg>/graph/<Entity>GraphSync.java` | main | `hasGraphMetadata()`: `graphMetadata()` is non-null. | entity |
| `KernelSagaGenerator` | `<pkg>/saga/<Saga>.java`, where `<Saga>` is `sagaMetadata().name()` (else `<Entity>Saga`) with `Flow` appended unless it already ends in `Flow`, so the file need not carry the entity name | main | `isSaga()`: `sagaMetadata()` is non-null. | entity |
| `KernelFlywayGenerator` | `db/migration/V<1000000+h>__create_<table>.sql` or `db/migration/V<2000000+h>__create_<table>.sql` | main | Every entity. Tier 2 when `effectiveDataScope()` is not `GLOBAL`; tier 1 otherwise, and always for a table named `tenants`. | entity |
| `KernelSharedScopeMigrationGenerator` | `db/migration/V<4000000+h>__shared_scope_<table>.sql` | main | `effectiveDataScope()` is `UNIVERSE` and `systemFields().sharedScopeField()` names a field. Its `<h>` is the entity's CREATE migration's, so the pair shares six digits. | entity |
| `KernelOpenApiGenerator` | `openapi/<kebab>-api.yaml` | main | Every entity; no guard. | entity |
| `KernelClientGenerator` | `<pkg>/client/<Entity>Client.java` | main | Every entity; no guard. | entity |
| `KernelApplicationGenerator` | `<base>/Application.java`; `<base>/RuntimeComponents.java`; `<base>/RuntimeLifecycle.java` | main | Once per run that loads at least one entity (`generateAll`). | project |
| `KernelApplicationGenerator` | `db/migration/V3000000__foreign_keys.sql` | main | `generateForeignKeys`: at least one entity has a `MANY_TO_ONE` entry in `relationships()` whose `targetEntity` is an entity of the same run. A target outside the run is skipped. | project |
| `KernelTestSupportGenerator` | `<base>/testsupport/RecordingHttpExchange.java`; `RecordingPersistence.java`; `RecordingFlow.java`; `RecordingRequestBody.java`; `RecordingEventEngine.java` (all in `<base>/testsupport/`) | test | `-Dexeris.tests=true` and at least one entity loaded; all five files. | project |
| `KernelHandlerTestGenerator` | `<pkg>/handler/<Entity>HandlerTest.java` | test | `-Dexeris.tests=true`; every entity. An entity package that does not end in `.domain` fails the run. | entity |
| `KernelServiceTestGenerator` | `<pkg>/service/<Entity>ServiceTest.java` | test | `-Dexeris.tests=true`; every entity. Same `.domain` requirement. | entity |
| `KernelRepositoryTestGenerator` | `<pkg>/repository/<Entity>RepositoryTest.java` | test | `-Dexeris.tests=true`; every entity. Same `.domain` requirement. | entity |
| `KernelSagaTestGenerator` | `<pkg>/saga/<Saga>Test.java`, with `<Saga>` as for `KernelSagaGenerator` | test | `-Dexeris.tests=true` and `isSaga()`. | entity |
<!-- catalogue:java:end -->

**Classes named `*Generator` that are not producers.** `openapi/OpenApiGenerator` renders the YAML
that `KernelOpenApiGenerator` writes. Nothing in the pipeline asks it to write a file, so no manifest
path maps to it. The guard test excludes it by name, and fails if the class is removed or renamed
so that the exclusion is not left behind. `KernelGeneratorStrategy` is the registry, not a generator.

**Files in the main manifest that no generator class writes.**

| Path | Writer | Emitted when | Scope |
| --- | --- | --- | --- |
| `cap-manifest.json` | `CodegenPipeline` (`emitCapabilityManifest`) | The metadata directory holds at least one `capability_*.json` and the capability graph resolves. When `exeris:generate` defers a graph failure, the previous file is kept and stays in the manifest. | project |

## TypeScript — `@exeris/codegen-ts`

**Output root.** `outputPath` in the config file (`exeris-codegen.json`), or `--output`, defaulting to
`src/app/generated`. The CLI (`src/index.ts`) writes every file `buildGeneratedFiles` returns under
that root and records each path in the root's `.exeris-codegen-manifest`, including files it skipped
because they already existed and `overwrite` is off.

**How generators are invoked.** `orchestrator.ts` (`buildGeneratedFiles`) composes the run. Per-entity
and per-view output, the type surface and peer trees are composed relative to an app tree, then
prefixed with `src/app/`. `app-structure-gen.ts` writes the project scaffold around it, at paths
relative to the output root. A **contracts-only run** has peers and no entity, enum or view. It drops
the `src/app/` prefix, so peer trees land at `peers/<name>/…` at the root, and it writes no scaffold.
The scaffold files at the project root are recorded in the manifest with a leading `./`, exactly
as composed (`./package.json`).

The `generate*` flags below are configuration keys (`generateZod`, …), set by the matching
`--no-zod`, `--no-services`, `--no-forms`, `--no-lists`, `--no-details`, `--no-stores`, `--no-sagas`,
`--no-events` options. Each defaults to true. `generateTests` (`--tests`) defaults to false.

Paths in the table are relative to the output root, as they are recorded in a normal run. In a
contracts-only run, drop the `src/app/` prefix from the peer paths.

<!-- catalogue:ts:begin -->
| Generator | Output path (relative to the output root) | Root | Emitted when | Scope |
| --- | --- | --- | --- | --- |
| `api/type-gen.ts` | `src/app/types/<kebab>.types.ts`; `src/app/schemas/<kebab>.schema.ts`; `src/app/types/index.ts`; `src/app/schemas/index.ts` | output root | `generateTypes`, per entity: `<kebab>.types.ts` for every entity. `TypeGenerator.generateAggregate`, once, when at least one entity or enum is loaded: `<kebab>.schema.ts` per entity and `schemas/index.ts` under `generateZod` (the barrel only with at least one entity), and `types/index.ts` always. | entity (types, schemas); project (barrels) |
| `api/enum-module-gen.ts` | `src/app/types/enums.ts` | output root | At least one entity or enum is loaded. Emitted even when there are no enums, so that the barrels' re-export resolves. Zod enum schemas inside it follow `generateZod`. Also called by `peer-type-gen.ts` for each peer's tree. | project |
| `api/peer-type-gen.ts` | `src/app/peers/<name>/types/enums.ts`; `src/app/peers/<name>/types/<kebab>.types.ts`; `src/app/peers/<name>/schemas/<kebab>.schema.ts`; `src/app/peers/<name>/index.ts` | output root | Once per peer declared by `--peer <name>=<path>` or `peers` in the config. `schemas/…` under `generateZod`. | project (one tree per peer) |
| `angular/service-gen.ts` | `src/app/services/<kebab>.service.ts` | output root | `generateServices`; every entity. | entity |
| `angular/stream-client-gen.ts` | `src/app/services/<kebab>.stream.ts`; `src/app/services/streams.index.ts` | output root | `generateServices`, `realTimeApi` true, and the effective data scope is `GLOBAL` (`hasLiveViewClient`). The barrel is written once when any entity qualifies. | entity (client); project (barrel) |
| `angular/action-stream-client-gen.ts` | `src/app/services/<kebab>.action-streams.ts`; `src/app/services/stream-types.ts`; `src/app/services/action-streams.index.ts` | output root | `generateServices`, at least one action with `streaming` true, and the effective data scope is `GLOBAL` (`hasActionStreamClients`). `stream-types.ts` and the barrel are written once when any entity qualifies. | entity (client); project (shared module, barrel) |
| `angular/form-gen.ts` | `src/app/components/<kebab>-form.component.ts` | output root | `generateForms`, and `uiMetadata.createForm` or `uiMetadata.editForm` is not `false`. | entity |
| `angular/list-gen.ts` | `src/app/components/<kebab>-list.component.ts` | output root | `generateLists`, and `uiMetadata.listView` is not `false`. | entity |
| `angular/detail-gen.ts` | `src/app/components/<kebab>-detail.component.ts` | output root | `generateDetails`, and `uiMetadata.detailView` is not `false`. | entity |
| `angular/store-gen.ts` | `src/app/stores/<kebab>.store.ts` | output root | `generateStores`; every entity. | entity |
| `angular/saga-gen.ts` | `src/app/sagas/<kebab>.saga.ts` | output root | `generateSagas`, and `sagaMetadata` is present. | entity |
| `angular/event-gen.ts` | `src/app/events/<kebab>.events.ts`; `src/app/events/event-bus.service.ts` | output root | `generateEvents`, and `events` is non-empty. The bus is written once when any entity qualifies. | entity (handler); project (bus) |
| `angular/spec-gen.ts` | `src/app/schemas/<kebab>.schema.spec.ts`; `src/app/services/<kebab>.service.spec.ts` | output root | `generateTests`. The schema spec also requires `generateZod`, the service spec `generateServices`. Every entity. | entity |
| `angular/http-error-gen.ts` | `src/app/core/http-error.ts` | output root | At least one entity is loaded and at least one of `generateDetails`, `generateLists`, `generateForms`, `generateStores`, `generateSagas` is on (`needsHttpErrorHelper`). | project |
| `angular/view-gen.ts` | `src/app/pages/<kebab>.component.ts`; `src/app/pages/<kebab>.route.ts` | output root | One pair per `view_*.json` in the metadata directory; `<kebab>` is from the view's `name`. | per view |
| `angular/app-structure-gen.ts` | `./package.json`; `./angular.json`; `./tsconfig.json`; `./tsconfig.app.json`; `./tsconfig.spec.json`; `./.postcssrc.json`; `./proxy.conf.json`; `src/styles.css`; `src/index.html`; `src/favicon.ico`; `src/main.ts`; `src/environments/environment.ts`; `src/environments/environment.development.ts`; `src/app/app.config.ts`; `src/app/app.component.ts`; `src/app/app.routes.ts`; `src/app/index.ts` | output root | Every run that is not contracts-only. `tsconfig.spec.json` only under `generateTests`. `proxy.conf.json` only when the app has a backend: at least one entity, or an emitted file that imports `@angular/common/http`. `src/app/index.ts` only when at least one entity or enum is loaded. | project |
| `api/enum-gen.ts` | none in a CLI run | none | Not invoked by `orchestrator.ts`. It is registered only by `registerAllGenerators`, which no production path calls. `src/app/types/enums.ts` comes from `enum-module-gen.ts`. | none |
| `api/query-builder-gen.ts` | none in a CLI run | none | Not invoked by `orchestrator.ts`; registered only by `registerAllGenerators`. | none |
| `angular/guard-gen.ts` | none in a CLI run | none | Not invoked by `orchestrator.ts`; registered only by `registerAllGenerators`. | none |
| `angular/landing-gen.ts` | none in a CLI run | none | Not invoked by `orchestrator.ts`; registered only by `registerAllGenerators`. | none |
<!-- catalogue:ts:end -->

The four generators with no CLI output stay in the table so that the guard can hold the whole
`*-gen.ts` set. No path in a CLI manifest maps to them: a `queries/`, `guards/` or
`features/pitch-deck/` path in an output tree was not written by this version of the CLI.
