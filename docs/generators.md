---
title: "Generator catalogue — which generator wrote each generated file"
type: reference
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-10-07
---

# Generator catalogue

Every file the Java code-generation pipeline writes has a row here: the generator that wrote it,
what the file is, and when it is written. The machine-readable form is
`META-INF/exeris/generator-catalogue.json` in the `eu.exeris:exeris-codegen-java` artefact
(`exeris-codegen-java/src/main/resources/` in this repository); this page is the same rows for a
person.

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
and uses forward slashes.

For each path, take the first row, in catalogue order, whose `outputRoot` is the tree the manifest
sits in and whose `pathPattern` matches the whole path. That row names the generator. When the row
lists other rows under `ambiguousWith`, the path's shape alone cannot separate them: separate them
with the entity names the metadata directory holds, or report every candidate.

`condition` is text for a person. A tool never evaluates it: most generators decide inside
`generate(...)` whether to write a file, and evaluating the condition would be a second
implementation of that decision.

A detached tree (`exeris:detach`) belongs to the application. The rows still explain its paths, but
none of them promises that a file still matches what its generator wrote.

### Placeholders

| Placeholder | Meaning |
|---|---|
| `{base}` | The entity's package with its trailing `.domain` removed, written as a path. |
| `{app}` | The run's base package (`--base-package`, else the first domain's package with `.domain` removed), written as a path. |
| `{E}` | The entity's simple class name. |
| `{Action}` | A streaming action's `@Action(name)` in Pascal case: each character that is not a letter or digit dropped, and the character after it upper-cased. |
| `{Flow}` | The saga's flow class name, as the `saga-flow` row's condition says. |
| `{table}` | The entity's table: `@ExerisDomain(tableName)` lower-cased, else the snake-cased plural of `{E}`. |
| `{tier}` | The migration tier digit, as the `create-table-migration` row's condition says. |
| `{nnnnnn}` | Six digits derived from the entity's qualified name. |
| `{kebab(E)}` | `{E}` with a hyphen between each ASCII lower-case letter and an ASCII upper-case letter after it, then lower-cased. |

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

The three rows of the `handler/` package overlap. `BeaconStreamHandler.java` is the stream handler
of `Beacon`, the per-action stream handler of an entity `Bea` with an action `con`, or the handler of
an entity named `BeaconStream`; `stream-handler` and `action-stream-handler` have the same pattern.
The two stream rows come before `handler`, so the first match of a stream handler is
`stream-handler`, and a tool that holds the entity and action names tells the two apart.

| Row | Ambiguous with |
|---|---|
| `stream-handler` | `action-stream-handler`, `handler` |
| `action-stream-handler` | `stream-handler`, `handler` |
| `handler` | `stream-handler`, `action-stream-handler` |

## Retired rows

A retired row names a path shape the pipeline does not write, and its `id` is never used again.
Retiring one moves its row here, with the release that retired it and the row that replaces it.

None yet.
