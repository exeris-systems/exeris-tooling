---
title: Codegen Java
type: module
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-11-08
---

# Codegen Java

**Module:** `eu.exeris:exeris-codegen-java`

`exeris-codegen-java` turns the processor's metadata into a kernel application: per entity a handler, service, repository, typed errors, list-route records, a client, an OpenAPI document and Flyway migrations, plus event, graph, saga and stream artifacts where the metadata asks for them; per build an `Application`, its composition root, its lifecycle and its route policy. `CodegenPipeline` is the one entry point every caller uses, the Maven plugin and `CodegenMain` alike.

It has one target, the Exeris kernel. It emits no build file, no Spring, Quarkus or Micronaut code, and no code that depends on tooling at run time ([kernel target only](../../.agents/policies/kernel-target-only.md)).

## Coordinates and dependencies

| Item | Value |
|---|---|
| Group and artifact | `eu.exeris:exeris-codegen-java` |
| Published | Yes, to Maven Central |
| Compile dependencies | `exeris-codegen-core`, `exeris-diagnostics`, `com.palantir.javapoet:javapoet`, `swagger-parser` (without `commons-logging`), `swagger-core`, `swagger-models` |
| Test dependencies | JUnit Jupiter, AssertJ, ArchUnit |
| Resource | `META-INF/exeris/generator-catalogue.json`, the machine-readable [generator catalogue](../generators.md) |

The kernel is not a dependency of this module: generators write source that names kernel types, and the [e2e tests](10-e2e-tests.md) compile that source against the real kernel jars. The `ban-host-and-kernel-runtime` enforcer execution keeps the kernel runtime and every host framework off the resolved graph. `swagger-parser` logs through `slf4j-api`; this module leaves the binding to whoever builds the classpath.

## Contract

### Entry points

| Type | Guarantee |
|---|---|
| `CodegenPipeline` | `createDefault()` wires the per-entity registry of `KernelGeneratorStrategy`, `KernelApplicationGenerator` and a mapper that ignores unknown metadata fields. `run(metadataDir, outputDir, basePackage, allowEmpty, deferCapabilityFailure)` writes the main tree and returns the file count; `runTests(...)` writes the generated tests; `runIntegrationTests(...)` writes the generated integration tests, or reports why it withheld them; `validateCapabilities`, `verifyCapTierWall`, `verifyRuntimeDrivers` and `requiredCompileArtifacts` serve the plugin goals |
| `CodegenMain`, `CliArgs` | The command line packaged by [codegen-cli](07-codegen-cli.md): parses `--switch=value` arguments and maps outcomes to exit status `0`, `1` or `2` |
| `kernel.KernelGeneratorStrategy` | The registered per-entity generators, in dispatch order |
| `kernel.KernelApplicationGenerator` | The project-wide files: `generateAll(domains, basePackage, composed)` and `generateForeignKeys(domains)` |

`CodegenPipeline.run` loads every domain from `metadataDir`, skipping the `enum_` and `capability_` files, resolves each field's enum type against the `enum_*.json` files, and sorts the domains by fully qualified name. It refuses before the first write (below), calls every registered generator for every entity with the whole build, writes the project-wide files, writes `cap-manifest.json` when the build has a `@CapabilityModule`, and prunes what the previous run wrote and this run did not.

### The generated application

The full path list, with the generator and condition of each file, is the [generator catalogue](../generators.md); this is its shape. For an entity in package `com.acme.order.domain`, `{base}` is `com/acme/order` and the generated packages are its siblings:

| Package or path | Holds |
|---|---|
| `{base}/handler/` | `{E}Handler`, the routes of the entity, its actions and its `@Blob` fields; the SSE stream handlers of a real-time entity and of each streaming action |
| `{base}/service/` | `{E}Service`, which the handler calls |
| `{base}/repository/` | `{E}Repository` over the kernel persistence SPI; `{E}ListQuery` and `{E}Page`; the typed write-rejection exceptions |
| `{base}/event/`, `{base}/graph/`, `{base}/saga/`, `{base}/client/` | The event publisher and subscriber, graph sync, saga flow and typed HTTP client, where the metadata asks for them |
| `{app}/` | `Application`, `RuntimeComponents`, `RuntimeLifecycle` and `GeneratedRoutePolicy`, in the base package: `exeris.basePackage` (`--base-package`), else the first domain's package with its trailing `.domain` removed |
| `db/migration/` | `V{tier}{nnnnnn}__create_{table}.sql` per entity, the shared-scope and tenant-policy migrations where they apply, and one `V3000000__foreign_keys.sql` per build |
| `openapi/` | `{kebab(E)}-api.yaml`, an OpenAPI 3.1 document per entity |

The create-table migration of an `audited = true` entity declares `created_by` and `updated_by` as `VARCHAR(255)` columns beside the two timestamps, and a `softDelete = true` entity's declares `deleted_by`. These actor columns are the consumer's: generated code stamps the times and never an actor, because it has no principal to record. When the entity declares a field for an actor role, a create request leaves it out and the handler sets it to `null`, and the insert stores the value the consumer's service sets on the entity before it calls `save` ([ADR-090](../adr/ADR-090-reject-mismatched-tenant.md) Amendment 4). An update keeps the stored actor (Amendment 1), so recording who changed a row is a statement of the consumer's own. Without a declared field, nothing generated writes the column and it stays `NULL`.

`Application.main()` boots the kernel with `BootstrapSelector.forNames(subsystems())`, binds the route policy and runs `RuntimeLifecycle` inside the boot callback. Its `protected` hooks are the seams a consumer overrides without editing generated code: `subsystems()`, `transactionalExecutor()`, `components(TransactionalExecutor)`, `routePolicy()`, `applicationPolicy()` and `unmatchedRoutes()`. `RuntimeComponents` holds one `protected create*` factory per generated component (repository, graph sync, service, event publisher and subscriber, saga flow, handler and stream handler), and a `configureRoutes` hook ([ADR-070](../adr/ADR-070-generated-composition-root-seam.md)).

Relationships map to the schema by `relationshipType`. A `MANY_TO_ONE` to an entity of the build is the referencing entity's `UUID` key: a column, an index, a finder and a constraint in `V3000000__foreign_keys.sql`. A `ONE_TO_MANY` with `mappedBy` on a `List` of the target is the inverse of the target's `MANY_TO_ONE` back to the entity: no column, in neither request body, `null` in the entity's JSON, and `readOnly` in OpenAPI with the child list route its rows are read from. `cascadeDelete` on either side sets `ON DELETE CASCADE` on the owning side's constraint. A `MANY_TO_MANY` held as `List<UUID>` keys is a JSON column of keys, with no join table and no effect of `cascadeDelete`. Any other `List` of entities is refused with `EXT-GEN-3003`. `ONE_TO_ONE` gets neither a constraint nor an inverse.

A `@Graph` entity's `{E}GraphSync` is a component too: `create{E}GraphSync()` builds it from the boot-bound `GraphEngine`, `RuntimeLifecycle.run()` registers its descriptors before anything else, and `{E}Service` takes it and calls `syncToGraph` after each create and update and `deleteFromGraph` after each delete, once the repository has returned, on the row as stored, for a hard and a soft delete alike. Every generated write route, each action included, goes through the service. The graph SPI offers no transaction that spans the relational store, so the sync runs after the row has committed: a write the repository refuses throws before the graph is touched, and a graph failure propagates, so the route answers `500` for a stored row. A failed delete publishes none of its `DELETE` events, and a retried `DELETE` answers `404`; the delete route of a hard-deleted entity with `@Blob` fields still deletes the row's stored objects when the row is gone. Registration at boot is what makes the Community SQL graph create `graph_nodes` and one table per edge type, and what lets a node delete cascade through them. The service of a `TENANT` or `UNIVERSE` entity does not call the sync: the Community graph engine cannot scope a traversal to the acting tenant on a request thread, where no `PersistenceEngine` is bound, and it keeps every node in one `graph_nodes` table with no tenant column. `RuntimeComponents` still builds that entity's sync (ADR-070 Amendment 4). The graph projection is server-side only: `exeris-codegen-ts` emits no counterpart, because a client reads the entity through its routes and never the graph.

`routePolicy()` folds `HttpRoutePolicy.firstDeclared(List.of(applicationPolicy(), GeneratedRoutePolicy.INSTANCE), unmatchedRoutes())` and hands it the request path without its query string, and a `HEAD` request as `GET`. `GeneratedRoutePolicy` answers each route whose entity or action declares access and abstains on every other route; `applicationPolicy()` abstains everywhere by default; `unmatchedRoutes()` returns `permitAll()` by default, so an undeclared route is public unless the application overrides it ([ADR-105](../adr/ADR-105-generated-route-policy-emission.md)).

Generated code logs through `System.Logger` and binds no logging facade ([ADR-060](../adr/ADR-060-generated-code-logging-facade.md)). Its compile-time imports outside the JDK are the kernel SPI and Core, the SDK composition runtime when the build is composed, and Jackson 3 when an entity has a `List<X>` column (an inverse collection is none); `RequiredCompileArtifacts` derives that list and `exeris:generate` warns with `EXT-PLUG-2004` when one is missing.

### Generated tests

Under `exeris.tests=true` (`--tests`), `runTests` writes tests for the handler, service and repository of each entity, the flow of each saga and the route policy of the generated `Application`, and a `testsupport` package of recording doubles of the kernel SPIs, into a separate root, `src/test/generated/java` by default. The tests import only JUnit 5 and AssertJ ([ADR-058](../adr/ADR-058-generated-test-emission-channel.md)). A run with no domains writes nothing into the test root and prunes nothing in it.

Under `exeris.integrationTests=true` (`--integration-tests`), `runIntegrationTests` writes integration tests into a third root, `src/test/generated-it/java` by default: a `testsupport` package that boots the generated `Application` on in-memory H2 (`ApplicationUnderTest`), sends requests (`IntegrationHttp`) and, when a route demands an identity, mints tokens over a loopback key set (`IntegrationTokens`); an `<Entity>CrudIT` per entity; and a `GeneratedRoutePolicyIT` when a route demands an identity (ADR-058 Amendment 1). `KernelIntegrationScope` decides whether a build is one they boot; any other build gets `EXT-GEN-3105` and nothing is written or pruned in that root. They import JUnit 5, AssertJ and the JDK, and need H2 at test runtime, which `RequiredCompileArtifacts.forGeneratedIntegrationTests` names.

### Emission technique and output stability

Java is emitted with JavaPoet through the shared `support.KernelScaffold`; Flyway SQL is emitted from text blocks ([ADR-015](../adr/ADR-015-codegen-emission-strategy.md)). The OpenAPI document is built as a swagger-core `OpenAPI` model and serialized with `Yaml31`. Output must be byte-identical for the same metadata on any machine and locale ([codegen determinism](../../.agents/policies/codegen-determinism.md)). The guards in this module:

| Test | What it holds |
|---|---|
| `CodegenJavaArchitectureTest` | No clock, no randomness and no `javax.lang.model` beyond `Modifier` in the generators' bytecode |
| `LocaleIndependenceTest` | Output does not change with the default locale |
| `KernelOpenApiGoldenDocumentTest` | The emitted OpenAPI document byte for byte against a committed golden |
| `GeneratorCatalogueTest` | Every writer has a catalogue row, the JSON and `docs/generators.md` agree |

## Hot path

None: build-time. The pipeline makes one pass over the build per run, inside `exeris:generate` or `CodegenMain`. The emitted application has hot paths of its own, on kernel code, and they are the kernel's to describe; nothing in this module is loaded by them.

## Failure modes

| Situation | What the caller sees |
|---|---|
| An entity's package does not end in `.domain` | `EntityPackageLayoutException`, `EXT-PROC-1033`, before the first write. The processor refuses the same entity earlier unless it ran with `-Aexeris.metadataOnly` |
| An entity field has a type the generated repository cannot store and read back, a field typed as another entity and a `List` of entities that is no resolvable `mappedBy` collection included | `UnpersistableFieldTypeException`, `EXT-GEN-3003`, before the first write |
| No metadata, and a previous run owns a committed tree the prune would delete | `EmptyMetadataException`, printed as `EXT-PLUG-2001`; nothing is deleted unless `allowEmpty` |
| No domain or capability metadata, and no previous tree | Warning `EXT-GEN-3101`; nothing written |
| No base package given and the entities span several packages | Warning `EXT-GEN-3104` naming the package chosen |
| Integration tests requested for a build they do not boot: an entity that is not `GLOBAL`, a graph entity, a `@Blob` field or a `@CapabilityModule` | Warning `EXT-GEN-3105` naming the first reason; the integration-test root is left as it was |
| The capability graph does not resolve | `CapabilityGraphException`; with `deferCapabilityFailure`, warning `EXT-GEN-3103` and the previous `cap-manifest.json` kept |
| An optional `@Requires` has no provider | Warning `EXT-GEN-3102` |
| A metadata or output path cannot be read or written | `IOException` |

Each identifier is explained in the [diagnostics reference](../diagnostics.md). A refused run leaves the generated tree as it was.

Trade-off: committed, regenerated output makes every emitter change a diff in every consumer's repository. JavaPoet's printer keeps that diff to real changes, at the cost of an emission dependency still on a 0.x line: the Palantir fork, pinned as `palantir.javapoet.version` in `exeris-tooling-bom`.

## Owning ADRs

| ADR | Decision |
|---|---|
| [ADR-015](../adr/ADR-015-codegen-emission-strategy.md) | JavaPoet for Java, text blocks for SQL; the Palantir fork (Amendment 1) |
| [ADR-070](../adr/ADR-070-generated-composition-root-seam.md) | `RuntimeComponents`, `RuntimeLifecycle` and the edge handler |
| [ADR-105](../adr/ADR-105-generated-route-policy-emission.md) | `GeneratedRoutePolicy` and the three policy hooks |
| [ADR-076](../adr/ADR-076-write-rejection-status.md), [ADR-090](../adr/ADR-090-reject-mismatched-tenant.md) | Write rejections and their statuses; server-owned columns kept on update |
| [ADR-096](../adr/ADR-096-generated-list-route-query-and-page-envelope.md) | The list route's query, its search over the searchable `String` fields (Amendment 4) and page envelope |
| [ADR-044](../adr/ADR-044-tooling-sse-stream-emitter-shape.md), [ADR-075](../adr/ADR-075-generated-event-publisher-caller.md) | Stream handlers; who calls the event publisher |
| [ADR-079](../adr/ADR-079-emitted-openapi-authentication-claim.md) | What the OpenAPI document claims about authentication |
| [ADR-104](../adr/ADR-104-declared-primary-key-uuid-only.md), [ADR-108](../adr/ADR-108-blob-field-routes.md) | The declared `UUID` key; `@Blob` field routes |
| [ADR-058](../adr/ADR-058-generated-test-emission-channel.md), [ADR-060](../adr/ADR-060-generated-code-logging-facade.md) | The generated-test channel; `System.Logger` in generated code |
| [ADR-097](../adr/ADR-097-generator-catalogue-and-launchable-generator.md) | The generator catalogue and `CodegenMain` |

## Where this does not apply

- **Reading Java source.** Generators read metadata JSON only; extraction is the [processor](02-processor.md).
- **A runtime other than the kernel.** Hosting on Spring is `exeris-spring-runtime`'s, and is not generated here.
- **A detached tree.** After `exeris:detach` the code belongs to the application; nothing here keeps it in step ([Maven plugin](06-codegen-maven-plugin.md)).
- **The frontend.** The Angular application comes from [codegen-ts](05-codegen-ts.md), which agrees with this module through the shared contract files, not through shared code.
