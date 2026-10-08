---
title: "Generator catalogue — which generator wrote each generated file"
type: reference
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-10-08
---

# Generator catalogue

Every file the Java code-generation pipeline writes has a row here: the generator that wrote it,
what the file is, and when it is written. The machine-readable form is
`META-INF/exeris/generator-catalogue.json` in the `eu.exeris:exeris-codegen-java` artefact
(`exeris-codegen-java/src/main/resources/` in this repository); this page is the same rows for a
person. The TypeScript generators are described in the last section, without machine-readable rows.

[ADR-097](adr/ADR-097-generator-catalogue-and-launchable-generator.md) is the contract.
`GeneratorCatalogueTest` fails the build when this page and the JSON disagree, or when a generator
has no row. A conformance test in `exeris-e2e-tests` runs the processor and the pipeline over
annotated sources and fails when a path they write matches no row, or when a row matches none of
them.

## How to read it

A tool that explains a generated tree reads the `.exeris-codegen-manifest` at the root of that tree
and matches the paths it lists, and nothing else: not a directory listing, and not a prediction from
the metadata. The pipeline writes two trees, each with its own manifest: `main`, the tree
`exeris:generate` writes (`src/main/generated/java` by default), and `test`, the tree written under
`-Dexeris.tests=true` (`src/test/generated/java`, ADR-058). A manifest path is relative to its tree
and uses forward slashes. The TypeScript generator's manifest records its project-root scaffold
files with a leading `./` (`./package.json`), as composed; strip a leading `./` before matching. The
Java manifests never carry one.

For each path, take the first row, in catalogue order, whose `outputRoot` is the tree the manifest
sits in and whose `pathPattern` matches the whole path. That row names the generator. When the row
lists other rows under `ambiguousWith`, the path's shape alone cannot separate them: separate them
with the entity names the metadata directory holds, or report every candidate.

`condition` is text for a person. A tool never evaluates it: most generators decide inside
`generate(...)` whether to write a file, and evaluating the condition would be a second
implementation of that decision.

`CodegenPipeline` calls every registered generator for every entity and keeps what it returns; it
does not consult `supports()`, so a generator's own guard decides whether a file is written. Classes
named `*Generator` that write no file have no row: `OpenApiGenerator` renders the YAML that
`KernelOpenApiGenerator` writes, and `KernelGeneratorStrategy` is the registry.

A detached tree (`exeris:detach`) belongs to the application. The rows still explain its paths, but
none of them promises that a file still matches what its generator wrote.

### Placeholders

| Placeholder | Meaning |
|---|---|
| `{base}` | The entity's package without its `.domain` segment, written as a path; the segment a template puts after it (`handler/`, `repository/`, …) is the one the generator substitutes for `.domain`. The package must end in `.domain`, or the run fails. The generators substitute every occurrence of `.domain`, so a package that also has `.domain` in an inner segment is outside the catalogue's guarantee. |
| `{app}` | The run's base package (`--base-package`, else the first domain's package with `.domain` removed), written as a path. |
| `{E}` | The entity's simple class name. |
| `{Action}` | A streaming action's `@Action(name)` in Pascal case: each character that is not a letter or digit dropped, and the character after it upper-cased. |
| `{Flow}` | The saga's flow class name, as the `saga-flow` row's condition says. |
| `{table}` | The entity's table: the `@ExerisDomain(tableName)` override trimmed and lower-cased, else the snake-cased English plural of `{E}` (`ConstructionOrder` → `construction_orders`). |
| `{tier}` | The migration tier digit, as the `create-table-migration` row's condition says. |
| `{nnnnnn}` | `Math.floorMod(qualifiedName.hashCode(), 1_000_000)` over the entity's qualified class name, written with six digits: a migration version is `{tier} × 1000000 + {nnnnnn}`, so it always has seven digits and its first is the tier. |
| `{kebab(E)}` | `{E}` with a hyphen between each ASCII lower-case letter and an ASCII upper-case letter after it, then lower-cased (`Locale.ROOT` in Java). The TypeScript generator's `DslMapper.toKebabCase` applies the same rule. |

In the `test-support` row, `Recording{HttpExchange,Persistence,…}` stands for one file per name in
the braces.

### Columns of the JSON

Each row carries `id`, `generator`, `artefact`, `outputRoot`, `pathPattern`, `pathTemplate`,
`scope`, `cardinality`, `condition`, `ambiguousWith`, `adr` and `since`, in that order. `scope` is
`entity` (files per `@ExerisDomain`) or `project` (files per run); `cardinality` counts files per
entity or per run accordingly. `pathPattern` is anchored and uses only what Java's `java.util.regex`
and ECMAScript read the same way: literals, `.`, bracket classes, `?`, `*`, `+`, `{n}`, non-capturing
groups and alternation. A retired row carries the same keys followed by `retiredIn`, the release
that retired it, and `replacedBy`, the `id` of the row that replaces it. A tool ignores keys it does
not know.

**Stability.** An `id` names one path shape for as long as it exists, and is never reused. Moving a
path retires its row: the row moves to `retired`, with the release that retired it and the id that
replaces it, and the change adds an entry to `docs/MIGRATION-0.x-to-1.0.md`. Rewording `artefact` or
`condition`, adding a row and adding a key retire nothing. `catalogueFormat` increments only when an
existing key is removed or changes meaning or type.

## Rows

### Main root, entity scope

| Row | Generator | Artefact | Path template | Cardinality | Condition | ADR |
|---|---|---|---|---|---|---|
| `spectate-stream-handler` | `KernelSpectateStreamHandlerGenerator` | The SSE stream handler of one row's events, at `GET {base}/{id}/stream`. | `{base}/handler/{E}SpectateStreamHandler.java` | zero-or-one | `@ExerisDomain(realTimeApi = true)`; refused on a `TENANT` or `UNIVERSE` entity (`EXT-PROC-1014`) | ADR-044 |
| `stream-handler` | `KernelStreamHandlerGenerator` | The live-view SSE stream handler of the entity. | `{base}/handler/{E}StreamHandler.java` | zero-or-one | `@ExerisDomain(realTimeApi = true)`; refused on a `TENANT` or `UNIVERSE` entity (`EXT-PROC-1014`) | ADR-044 |
| `action-stream-handler` | `KernelActionStreamHandlerGenerator` | The SSE stream handler of one streaming action. | `{base}/handler/{E}{Action}StreamHandler.java` | one-per-streaming-action | each `@Action(streaming = true)` | ADR-044 |
| `handler` | `KernelHandlerGenerator` | The HTTP handler serving the entity's routes. | `{base}/handler/{E}Handler.java` | one | every entity | — |
| `service` | `KernelServiceGenerator` | The domain service the handler calls. | `{base}/service/{E}Service.java` | one | every entity | — |
| `repository` | `KernelRepositoryGenerator` | The repository over the kernel persistence SPI. | `{base}/repository/{E}Repository.java` | one | every entity | — |
| `not-found-exception` | `KernelErrorGenerator` | The exception a write raises when no row matches; answered with 404. | `{base}/repository/{E}NotFoundException.java` | one | every entity | ADR-076 |
| `version-conflict-exception` | `KernelErrorGenerator` | The exception an optimistic-lock update raises when it applies to no row; answered with 409. | `{base}/repository/{E}VersionConflictException.java` | zero-or-one | `@ExerisDomain(versioned = true)` | ADR-076 |
| `tenant-mismatch-exception` | `KernelErrorGenerator` | The exception a write raises when the row names another tenant; answered with 400. | `{base}/repository/{E}TenantMismatchException.java` | zero-or-one | `dataScope` `TENANT` or `UNIVERSE` | ADR-090 |
| `shared-scope-mismatch-exception` | `KernelErrorGenerator` | The exception a write raises when the row is tagged with another shared scope; answered with 400. | `{base}/repository/{E}SharedScopeMismatchException.java` | zero-or-one | `UNIVERSE` with a `@SharedScope` field | ADR-090 |
| `list-query` | `KernelListQueryGenerator` | The list route's query record: page, sort and filters. | `{base}/repository/{E}ListQuery.java` | one | every entity | ADR-096 |
| `page` | `KernelListQueryGenerator` | The list route's page envelope record. | `{base}/repository/{E}Page.java` | one | every entity | ADR-096 |
| `event-publisher` | `KernelEventGenerator` | The publisher of the entity's domain events, called by the handler. | `{base}/event/{E}EventPublisher.java` | zero-or-one | at least one `@DomainEvent` | ADR-075 |
| `event-subscriber` | `KernelEventHandlerGenerator` | The subscriber skeleton for the entity's domain events. | `{base}/event/{E}EventSubscriber.java` | zero-or-one | at least one `@DomainEvent` | — |
| `graph-sync` | `KernelGraphSyncGenerator` | The projection of the entity and its edges into the graph SPI. | `{base}/graph/{E}GraphSync.java` | zero-or-one | `@Graph` | — |
| `saga-flow` | `KernelSagaGenerator` | The saga's flow definition against the kernel flow SPI. | `{base}/saga/{Flow}.java` | zero-or-one | `@Saga`; `{Flow}` is `@Saga(name)`, default `{E}Saga`, with `Flow` appended unless it already ends in `Flow` | — |
| `create-table-migration` | `KernelFlywayGenerator` | The Flyway migration creating the entity's table and indexes, and its row-level security policy when tenant-partitioned. | `db/migration/V{tier}{nnnnnn}__create_{table}.sql` | one | every entity; `{tier}` is `2` for a tenant-partitioned table other than `tenants`, else `1`; `{nnnnnn}` is a hash of the entity's qualified name | — |
| `shared-scope-migration` | `KernelSharedScopeMigrationGenerator` | The Flyway migration widening reads of a `UNIVERSE` table to its shared scope. | `db/migration/V4{nnnnnn}__shared_scope_{table}.sql` | zero-or-one | `UNIVERSE` with a `@SharedScope` field; `{nnnnnn}` is the one in the table's create migration | ADR-059 |
| `openapi` | `KernelOpenApiGenerator` | The OpenAPI 3.1 document of the entity's routes. | `openapi/{kebab(E)}-api.yaml` | one | every entity | — |
| `client` | `KernelClientGenerator` | The typed service-to-service HTTP client of the entity's routes. | `{base}/client/{E}Client.java` | one | every entity | ADR-034 |

### Main root, project scope

| Row | Generator | Artefact | Path template | Cardinality | Condition | ADR |
|---|---|---|---|---|---|---|
| `application` | `KernelApplicationGenerator` | The application's `main` entry point, which boots the kernel. | `{app}/Application.java` | one | at least one `@ExerisDomain` | — |
| `runtime-components` | `KernelApplicationGenerator` | The composition root that constructs every generated component. | `{app}/RuntimeComponents.java` | one | at least one `@ExerisDomain` | ADR-070 |
| `runtime-lifecycle` | `KernelApplicationGenerator` | The lifecycle that registers the routes and subscriptions once the kernel is up. | `{app}/RuntimeLifecycle.java` | one | at least one `@ExerisDomain` | ADR-070 |
| `generated-route-policy` | `KernelApplicationGenerator` | The generated half of the route policy the application binds; its table holds no row, so it abstains on every route (ADR-105). | `{app}/GeneratedRoutePolicy.java` | one | at least one `@ExerisDomain` | ADR-105 |
| `foreign-key-migration` | `KernelApplicationGenerator` | The Flyway migration adding every foreign key between the build's tables. | `db/migration/V3000000__foreign_keys.sql` | zero-or-one | a `MANY_TO_ONE` relationship whose target is an entity of the same build | — |
| `cap-manifest` | `CodegenPipeline` | The resolved capability graph and its composition stamp. | `cap-manifest.json` | zero-or-one | at least one `@CapabilityModule` | ADR-024 |

### Test root, project scope

| Row | Generator | Artefact | Path template | Cardinality | Condition | ADR |
|---|---|---|---|---|---|---|
| `test-support` | `KernelTestSupportGenerator` | The recording doubles of the kernel SPIs that the generated tests use. | `{app}/testsupport/Recording{HttpExchange,Persistence,Flow,RequestBody,EventEngine}.java` | fixed-set | at least one `@ExerisDomain` | ADR-058 |

### Test root, entity scope

| Row | Generator | Artefact | Path template | Cardinality | Condition | ADR |
|---|---|---|---|---|---|---|
| `handler-test` | `KernelHandlerTestGenerator` | The generated tests of the entity's handler. | `{base}/handler/{E}HandlerTest.java` | one | every entity | ADR-058 |
| `service-test` | `KernelServiceTestGenerator` | The generated tests of the entity's service. | `{base}/service/{E}ServiceTest.java` | one | every entity | ADR-058 |
| `repository-test` | `KernelRepositoryTestGenerator` | The generated tests of the entity's repository. | `{base}/repository/{E}RepositoryTest.java` | one | every entity | ADR-058 |
| `saga-flow-test` | `KernelSagaTestGenerator` | The generated tests of the saga's flow definition. | `{base}/saga/{Flow}Test.java` | zero-or-one | `@Saga` | ADR-058 |

## Ambiguous rows

The four rows of the `handler/` package overlap. `BeaconStreamHandler.java` is the stream handler
of `Beacon`, the per-action stream handler of an entity `Bea` with an action `con`, or the handler of
an entity named `BeaconStream`; `stream-handler` and `action-stream-handler` have the same pattern.
`BeaconSpectateStreamHandler.java` is the spectate handler of `Beacon`, and also matches the three
wider rows: the stream handler of an entity `BeaconSpectate`, the per-action stream handler of
`Beacon`'s action `spectate`, or the handler of an entity `BeaconSpectateStream`. The narrowest row
comes first, then the two stream rows, then `handler`, so the first match of a spectate handler is
`spectate-stream-handler`, that of any other stream handler is `stream-handler`, and a tool that
holds the entity and action names tells them apart.

| Row | Ambiguous with |
|---|---|
| `spectate-stream-handler` | `stream-handler`, `action-stream-handler`, `handler` |
| `stream-handler` | `spectate-stream-handler`, `action-stream-handler`, `handler` |
| `action-stream-handler` | `spectate-stream-handler`, `stream-handler`, `handler` |
| `handler` | `spectate-stream-handler`, `stream-handler`, `action-stream-handler` |

## Retired rows

A retired row names a path shape the pipeline does not write, and its `id` is never used again.
Retiring one moves its row here, with the release that retired it and the row that replaces it.

None yet.

## TypeScript generators

This section is descriptive. ADR-097 reserves `outputRoot: ts` for machine-readable rows that
`@exeris/codegen-ts` will publish; the JSON carries none yet, and nothing here is held to a run's
output. `generator-catalogue.spec.ts` in `exeris-codegen-ts` fails when a `src/generators/**/*-gen.ts`
file has no row in the table below, or a row names a file that does not exist. A changed path or
condition is kept current by the change that makes it.

**Output root.** `outputPath` in the config file (`exeris-codegen.json`), or `--output`, defaulting
to `src/app/generated`. The CLI (`src/index.ts`) writes every file `buildGeneratedFiles` returns
under that root and records in the root's `.exeris-codegen-manifest` every path it owns: a file it
wrote, found unchanged, or kept as a seed. A file it skipped because it was not proven its own is not
recorded. A seed (`overwritable: false`) is written when absent and then kept unless `--overwrite`.

**How generators are invoked.** `orchestrator.ts` (`buildGeneratedFiles`) composes the run.
Per-entity and per-view output, the type surface and the peer trees are composed relative to an app
tree, then prefixed with `src/app/`. `app-structure-gen.ts` writes the project scaffold around it, at
paths relative to the output root. A contracts-only run has peers and no entity, enum or view: it
drops the `src/app/` prefix, so peer trees land at `peers/{peer}/…`, and it writes no scaffold. With
`scaffold: false` (`--no-scaffold`) the tree is written at the output root with no `src/app/`
prefix and no scaffold; the barrel is written as `index.ts` and the view routes as `view.routes.ts`.

The `generate*` keys below are configuration keys, set off by the matching `--no-zod`,
`--no-services`, `--no-forms`, `--no-lists`, `--no-details`, `--no-stores`, `--no-sagas` and
`--no-events` options; each defaults to true. `generateTests` (`--tests`) defaults to false.
`{kebab(E)}` is as above; `{kebab(V)}` is the same rule over a view's `name`, and `{peer}` is a peer's
declared name.

<!-- catalogue:ts:begin -->
| Generator | Output path (relative to the output root) | Emitted when | Scope |
|---|---|---|---|
| `api/type-gen.ts` | `src/app/types/{kebab(E)}.types.ts`; `src/app/schemas/{kebab(E)}.schema.ts`; `src/app/types/index.ts`; `src/app/schemas/index.ts` | `{kebab(E)}.types.ts` for every entity. `TypeGenerator.generateAggregate`, once when at least one entity or enum is loaded: `{kebab(E)}.schema.ts` per entity and `schemas/index.ts` under `generateZod` (the barrel only with at least one entity), and `types/index.ts` always. | entity (types, schemas); project (barrels) |
| `api/enum-module-gen.ts` | `src/app/types/enums.ts` | At least one entity or enum is loaded; written even with no enum, so the barrels' re-export resolves. Its Zod enum schemas follow `generateZod`. Also called by `peer-type-gen.ts` for each peer's tree. | project |
| `api/peer-type-gen.ts` | `src/app/peers/{peer}/types/enums.ts`; `src/app/peers/{peer}/types/{kebab(E)}.types.ts`; `src/app/peers/{peer}/schemas/{kebab(E)}.schema.ts`; `src/app/peers/{peer}/index.ts` | Once per peer declared by `--peer {peer}=<path>` or `peers` in the config; `schemas/…` under `generateZod`. | project (one tree per peer) |
| `angular/service-gen.ts` | `src/app/services/{kebab(E)}.service.ts` | `generateServices`; every entity. | entity |
| `angular/stream-client-gen.ts` | `src/app/services/{kebab(E)}.stream.ts`; `src/app/services/streams.index.ts` | `generateServices`, `realTimeApi` true, and the entity is not tenant-partitioned (`hasLiveViewClient`). The barrel is written once when any entity qualifies. | entity (client); project (barrel) |
| `angular/action-stream-client-gen.ts` | `src/app/services/{kebab(E)}.action-streams.ts`; `src/app/services/stream-types.ts`; `src/app/services/action-streams.index.ts` | `generateServices`, at least one action with `streaming` true, and the entity is not tenant-partitioned (`hasActionStreamClients`). `stream-types.ts` and the barrel are written once when any entity qualifies. | entity (client); project (shared module, barrel) |
| `angular/form-gen.ts` | `src/app/components/{kebab(E)}-form.component.ts` | `generateForms`, and `uiMetadata.createForm` or `uiMetadata.editForm` is not `false`. | entity |
| `angular/list-gen.ts` | `src/app/components/{kebab(E)}-list.component.ts` | `generateLists`, and `uiMetadata.listView` is not `false`. | entity |
| `angular/detail-gen.ts` | `src/app/components/{kebab(E)}-detail.component.ts` | `generateDetails`, and `uiMetadata.detailView` is not `false`. | entity |
| `angular/store-gen.ts` | `src/app/stores/{kebab(E)}.store.ts` | `generateStores`; every entity. | entity |
| `angular/saga-gen.ts` | `src/app/sagas/{kebab(E)}.saga.ts` | `generateSagas`, and `sagaMetadata` is present. | entity |
| `angular/event-gen.ts` | `src/app/events/{kebab(E)}.events.ts`; `src/app/events/event-bus.service.ts` | `generateEvents`, the config key `eventBusEndpoint` is set, and `events` is non-empty. The bus is written once when any entity qualifies, with `eventBusEndpoint` as its endpoint; without the key neither file is written. | entity (handler); project (bus) |
| `angular/spec-gen.ts` | `src/app/schemas/{kebab(E)}.schema.spec.ts`; `src/app/services/{kebab(E)}.service.spec.ts` | `generateTests`; the schema spec also needs `generateZod`, the service spec `generateServices`. Every entity. | entity |
| `angular/http-error-gen.ts` | `src/app/core/http-error.ts` | At least one entity is loaded and at least one of `generateDetails`, `generateLists`, `generateForms`, `generateStores`, `generateSagas` is on (`needsHttpErrorHelper`). | project |
| `angular/view-gen.ts` | `src/app/pages/{kebab(V)}.component.ts`; `src/app/pages/{kebab(V)}.route.ts` | One pair per `view_*.json` in the metadata directory. | per view |
| `angular/view-routes-gen.ts` | `view.routes.ts` | `scaffold: false` and at least one view is loaded: every view's route, as the `viewRoutes` array, for the consumer's own routes file. With the scaffold on, `app.routes.ts` carries the routes instead. | project |
| `angular/app-structure-gen.ts` | `package.json`; `angular.json`; `tsconfig.json`; `tsconfig.app.json`; `tsconfig.spec.json`; `.postcssrc.json`; `proxy.conf.js`; `src/styles.css`; `src/index.html`; `src/favicon.ico`; `src/main.ts`; `src/environments/environment.ts`; `src/environments/environment.development.ts`; `src/app/app.config.ts`; `src/app/app.component.ts`; `src/app/app.routes.ts`; `src/app/index.ts`; `src/main.server.ts`; `src/app/app.config.server.ts`; `src/app/app.routes.server.ts` | Every run that is not contracts-only, with `scaffold` on (the default). Every file except `src/favicon.ico` and the barrel `src/app/index.ts` is a seed. `tsconfig.spec.json` only under `generateTests`. `src/main.server.ts`, `src/app/app.config.server.ts` and `src/app/app.routes.server.ts` only under `render: 'ssg'` (`--render ssg`), which also adds the static prerender options to `angular.json`, `@angular/ssr` and `@angular/platform-server` to `package.json`, the server entry to `tsconfig.app.json` and client hydration to `app.config.ts`; with `scaffold: false` it emits nothing. `proxy.conf.js` only when the app has a backend: at least one entity, or an emitted file that imports `@angular/common/http`; it has one rule per entity path (`apiBasePath` followed by the path the emitted services call), sorted, each forwarding to the kernel application except a request whose `Accept` includes `text/html`, which gets `index.html`, and `package.json` starts `ng serve --proxy-config proxy.conf.js`. `proxy.conf.json` is not emitted; an existing one stays a seed and is kept. `src/app/index.ts` only when at least one entity or enum is loaded; with `scaffold: false` the same barrel is written as `index.ts`. | project |
| `api/enum-gen.ts` | none in a CLI run | Not invoked by `orchestrator.ts`; registered only by `registerAllGenerators`, which no production path calls. `src/app/types/enums.ts` comes from `enum-module-gen.ts`. | none |
| `api/query-builder-gen.ts` | none in a CLI run | Not invoked by `orchestrator.ts`; registered only by `registerAllGenerators`. | none |
| `angular/guard-gen.ts` | none in a CLI run | Not invoked by `orchestrator.ts`; registered only by `registerAllGenerators`. | none |
| `angular/landing-gen.ts` | none in a CLI run | Not invoked by `orchestrator.ts`; registered only by `registerAllGenerators`. | none |
<!-- catalogue:ts:end -->

The four generators with no CLI output stay in the table so that the guard holds the whole
`*-gen.ts` set. No path in a CLI manifest maps to them: a `queries/`, `guards/` or
`features/pitch-deck/` path in an output tree was not written by this version of the CLI.
