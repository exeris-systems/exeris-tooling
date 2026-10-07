---
title: "ADR-097 — exeris-tooling publishes a generator catalogue and a launchable generator"
type: adr
visibility: public
owning-repo: exeris-tooling
status: active
slug: adr/ADR-097
---

# ADR-097 — exeris-tooling publishes a generator catalogue and a launchable generator

- **Status:** ACCEPTED (2026-10-07) · accepted-on-merge per the per-repo pattern (ADR-047 / ADR-058)
- **Deciders:** the founder; `exeris-tooling` (catalogue format, module layout)
- **Repo:** `exeris-tooling`
- **Scope:** tooling / build — cross-repo: `exeris-ai-bridge` consumes the catalogue and the launchable generator
- **Visibility:** public
- **Milestone:** 0.10.0 (`docs/0.10.0-release-plan.md`, wave J2b)
- **Relates to:** ADR-025 (the consumer), ADR-095 (the identifiers the command line prints), ADR-058 (the test output root), ADR-091 (the starter the command line stays out of), ADR-015 (output stability), ADR-085 (registry and stub rules)
- **Supersedes / superseded by:** —

## Context and Problem Statement

`exeris-ai-bridge` (ADR-025) answers two questions about a user's build that only this repository
can answer authoritatively.

`build-explain_artefacts` reads the `.exeris-codegen-manifest` that `OutputWriter` writes at the root
of each output tree, and tells an agent which generator wrote each listed file and what drives it.
The bridge does not predict emission: most generators decide by returning `null` from inside
`generate(...)`, so a prediction would be a second implementation of their guards. The explanation
attached to each path shape, however, is maintained by hand in the bridge, copied from this
repository's source. Nothing here tells the bridge when a generator is added or a path moves, and the
copy drifts: it counts thirteen registered generators where `KernelGeneratorStrategy` registers
fifteen.

`build-preview_generation` runs a generator over a user's metadata into a scratch directory. It covers
the TypeScript generator (the `exeris-gen` command of `@exeris/codegen-ts`) and reports the Java side
unavailable, because `exeris-codegen-java` publishes `CodegenMain` with no `Main-Class` and no bundled
dependencies: it runs only on a classpath assembled by hand. `CodegenMain` also prints a fixed
`v0.1.0` as its version, and exposes neither the test output root (ADR-058) nor the `allowEmpty`
teardown that `CodegenPipeline` and `exeris:generate` support.

A path is the only key the bridge has: the manifest records relative paths and nothing else. A shared
description therefore has to describe path shapes, cover every file the pipeline writes, and be
checked against what the processor and the pipeline actually produce.

**The question this ADR answers:** what does exeris-tooling publish so that a tool can name the
generator of every file in a generated tree without predicting emission, and can run the Java
generator without assembling a classpath?

## 🏁 The Decision

**exeris-tooling publishes a generator catalogue, a committed JSON file in `exeris-codegen-java` that
maps every path the Java pipeline writes to the generator that wrote it, and a launchable generator,
`eu.exeris:exeris-codegen-cli`, a self-contained jar whose `Main-Class` is `CodegenMain`.**

**Concrete obligations — the catalogue:**

1. **Location and form.** The catalogue is
   `exeris-codegen-java/src/main/resources/META-INF/exeris/generator-catalogue.json`, written by hand
   and committed. It is a JSON object with `catalogueFormat: 1`, a `rows` array in a fixed order and a
   `retired` array. It carries no timestamp, no build version and nothing computed at build time, so
   the file in the jar is byte-identical to the file in the repository. `docs/generators.md` is the
   human table of the same rows. `GeneratorCatalogueTest` fails the build when the two disagree, as
   `DiagnosticIdTest` does for `docs/diagnostics.md` (ADR-095).
2. **Columns.** Every row carries:

   | Key | Meaning |
   |---|---|
   | `id` | A stable slug (`[a-z0-9-]+`), unique across `rows` and `retired`. Never reused. |
   | `generator` | The simple name of the class that writes the file (`KernelHandlerGenerator`, `KernelApplicationGenerator`, `CodegenPipeline`). |
   | `artefact` | One line saying what the file is. |
   | `outputRoot` | `main`, the tree `exeris:generate` writes (`src/main/generated/java` by default), or `test`, the tree written under `-Dexeris.tests=true` (ADR-058). `ts` is reserved for a TypeScript catalogue published with `@exeris/codegen-ts`; no row carries it. |
   | `pathPattern` | A regular expression over the path as the manifest records it: relative to the output root, with forward slashes. |
   | `pathTemplate` | The same path for a person, with the placeholders `{base}`, `{app}`, `{E}`, `{Action}`, `{Flow}`, `{table}`, `{tier}`, `{nnnnnn}`, `{kebab(E)}`. |
   | `scope` | `entity` (files per `@ExerisDomain`) or `project` (files per run). |
   | `cardinality` | `one`, `zero-or-one`, `one-per-streaming-action` or `fixed-set`, counted per entity or per run as `scope` says. |
   | `condition` | When the file is written, in the SDK's vocabulary (`@ExerisDomain(versioned = true)`, `@Graph`). Descriptive only. |
   | `ambiguousWith` | The ids of other rows whose `pathPattern` matches some of the same paths. Empty for most rows. |
   | `adr` | The ADR that fixes the artefact's shape, or `null`. |
   | `since` | The first tooling release whose catalogue carries the row. |

   There is no target, backend or framework column: the pipeline has one target, the Exeris kernel.
3. **Patterns.** `pathPattern` is anchored (`^…$`) and uses only constructs that Java's
   `java.util.regex` and ECMAScript read the same way: literals, `.`, bracket classes, `?`, `*`, `+`,
   `{n}`, non-capturing groups and alternation. No lookaround, backreferences, named groups, inline
   flags, shorthand classes (`\w`, `\d`) or Unicode property classes. A name segment is `[^/]+`,
   because a Java identifier may use any Unicode letter.
4. **Order and ambiguity.** Rows are ordered so that, for every path the conformance fixtures
   produce, the first row whose pattern matches is the row of the generator that wrote the file, or
   a row that lists that row in `ambiguousWith`. Where a path's shape cannot separate two generators
   for every possible entity name, each row lists the other in `ambiguousWith`. The one case that
   exists is the handler package: `{E}StreamHandler.java`, `{E}{Action}StreamHandler.java` and
   `{E}Handler.java` all live in `{base}/handler/`. `OrderStreamHandler.java` is the stream handler
   of `Order`, a per-action handler of an entity whose name starts with `Order`, or the handler of an
   entity named `OrderStream`. The two stream shapes are the same shape, so `stream-handler` and
   `action-stream-handler` carry the same pattern and the first match of either file is
   `stream-handler`. The two stream rows come before `handler`, and all three list one another.
5. **How a consumer reads it.** A consumer matches the paths listed in an `.exeris-codegen-manifest`,
   and nothing else: not a directory listing, and not a prediction from metadata. It takes the first
   row, in catalogue order, whose `outputRoot` is the tree the manifest sits in and whose
   `pathPattern` matches. When that row has `ambiguousWith` entries, the consumer either separates
   them with entity names it already holds (from the metadata directory) or reports every candidate.
   A consumer never evaluates `condition`: it is text for a person, and a tool that evaluated it would
   be predicting emission.
6. **Stability.** An `id` names one path shape for as long as it exists. Changing a row's path shape
   retires the id: its row moves to `retired` with the release that retired it and the id that
   replaces it. The change also adds a new row with a new id, and an entry in
   `docs/MIGRATION-0.x-to-1.0.md`, because the committed files of every regenerated application
   move. Rewording `artefact` or `condition`, adding a row, and adding a key retire nothing.
   Consumers ignore keys they do not know. `catalogueFormat` increments only when an existing key is
   removed or changes meaning or type.
7. **Completeness test.** A unit test in `exeris-codegen-java` fails when a writer has no row, or a
   row names a writer outside this set: every generator in
   `new KernelGeneratorStrategy().getRegistry()`, `KernelApplicationGenerator`'s `generateAll` and
   `generateForeignKeys`, the five test generators `CodegenPipeline.runTests` drives
   (`KernelTestSupportGenerator`, `KernelHandlerTestGenerator`, `KernelServiceTestGenerator`,
   `KernelRepositoryTestGenerator`, `KernelSagaTestGenerator`), and the `cap-manifest.json` that
   `CodegenPipeline` writes itself.
8. **Conformance test.** An end-to-end test in `exeris-e2e-tests` runs the processor and the pipeline,
   main and test roots, over fixtures that between them include:
   - a versioned entity;
   - a `TENANT` entity;
   - a `UNIVERSE` entity with a `@SharedScope` field;
   - a saga;
   - a `@Graph` entity;
   - an entity with `@DomainEvent`s;
   - a `realTimeApi` entity;
   - a streaming `@Action`;
   - a `MANY_TO_ONE` relationship between two entities of the build;
   - a `@CapabilityModule`.

   It reads the two manifests the run wrote. It fails when a listed path matches no row, when a
   path's first matching row is neither the row of the generator that wrote it nor a row whose
   `ambiguousWith` lists that row, or when no path reaches a row. A path reaches its first matching
   row when that row is its writer's, and otherwise the writer's row that the first match lists and
   whose pattern also matches the path. A change that adds or moves an emitted file therefore cannot
   merge without its row. The metadata comes from the processor, not from a hand-built fixture, so an
   attribute the processor never reads cannot make a row look covered.
9. **Not a descriptor on the generator interface.** The catalogue is not a method or an annotation on
   `KernelArtifactGenerator`. That interface is public and carries a 1.0 stability commitment, and
   several writers sit outside it: the five test generators, `KernelApplicationGenerator`'s
   project-wide entry points, and `CodegenPipeline`'s capability manifest. One file covers all of
   them, and the tests in obligations 7 and 8 hold it to the code.

**Concrete obligations — the launchable generator:**

10. **The module.** `exeris-codegen-cli` is a reactor module whose artefact is a shaded jar of
    `exeris-codegen-java` and its runtime dependencies, with `CodegenMain` as `Main-Class`.
    `CodegenMain` and `CliArgs` stay in `exeris-codegen-java`, so ADR-095's `GEN` print sites keep
    their place. The shade configuration follows `exeris-kernel-diagnostics-cli`:
    - `ManifestResourceTransformer` with the main class and an `Implementation-Version` entry;
    - `ServicesResourceTransformer`;
    - signature files (`META-INF/*.SF`, `*.DSA`, `*.RSA`) stripped;
    - `createDependencyReducedPom` false.

    The licence and notice files of the bundled dependencies are kept in the jar.
11. **Arguments.** Beyond `--metadata-dir`, `--output-dir` and `--base-package`:
    - `--tests` also writes the generated tests into `--test-output-dir`, which `--tests` requires;
    - `--allow-empty` permits the teardown that deletes a previously generated tree when no
      `@ExerisDomain` is found. It prunes the main root only: the test root is never pruned on
      empty metadata, as under `exeris:generate`;
    - `--print-catalogue` writes the bundled catalogue to standard output and exits, and needs no
      other argument.

    An unknown `--` switch is an argument error, so a flag a given release does not know fails
    instead of being ignored. The command line validates the capability graph fail-fast. It has no
    equivalent of `exeris:generate`'s deferral, which exists only for a Maven build that
    re-validates after `compile`.
12. **Exit codes.**
    - `0`: the run succeeded, including a run that found no metadata and owned no tree.
    - `1`: generation was refused or failed (empty metadata over an owned tree, a field type the
      repository cannot store, an unresolvable capability graph, an I/O failure).
    - `2`: the arguments were invalid.

    Every non-zero exit prints a line carrying its identifier (ADR-095).
13. **Version.** The jar's manifest carries `Implementation-Version` with the project version, and
    `CodegenMain` prints the version from there. Run from a classpath without that entry, it says the
    version is unknown. The version appears in log output only, never in a generated file.
14. **Same output as the pipeline.** A test runs the shaded jar as a separate process and
    `CodegenPipeline` in-process over the same metadata and base package. It fails unless the two
    output trees, manifests included, are byte-identical: the main root, and with `--tests` the
    test root.
15. **JDK.** The jar runs on the JDK the reactor targets, JDK 25 (`maven.compiler.release` 25), and on
    any newer JDK. It declares no upper bound.
16. **Distribution.** `exeris-codegen-cli` is published to Maven Central with the reactor, with sources
    and javadoc jars like every other module, and is managed in `exeris-tooling-bom`. It is not in
    `exeris-app-bom`, `exeris-app-parent` or `exeris-app-starter` (ADR-091): an application builds
    through the Maven plugin, and the jar is a tool, not a dependency.

**The catalogue as of this decision.** An entity's package ends in `.domain`; the run fails
otherwise. `{base}` is that package without its `.domain` segment, written as a path, and the
segment a template puts after it (`handler/`, `repository/`, …) is the one the generator substitutes
for `.domain`. The generators substitute every occurrence of `.domain`, so a package that also has
`.domain` in an inner segment is outside the catalogue's guarantee. `{app}` is the run's base package
(`--base-package`, else the first domain's package with `.domain` removed). Rows in catalogue order:

*Main root, entity scope*

| `id` | Generator | Path template | Cardinality | Condition |
|---|---|---|---|---|
| `stream-handler` | `KernelStreamHandlerGenerator` | `{base}/handler/{E}StreamHandler.java` | zero-or-one | `@ExerisDomain(realTimeApi = true)`; refused on a `TENANT` or `UNIVERSE` entity (`EXT-PROC-1014`) |
| `action-stream-handler` | `KernelActionStreamHandlerGenerator` | `{base}/handler/{E}{Action}StreamHandler.java` | one-per-streaming-action | each `@Action(streaming = true)` |
| `handler` | `KernelHandlerGenerator` | `{base}/handler/{E}Handler.java` | one | every entity |
| `service` | `KernelServiceGenerator` | `{base}/service/{E}Service.java` | one | every entity |
| `repository` | `KernelRepositoryGenerator` | `{base}/repository/{E}Repository.java` | one | every entity |
| `not-found-exception` | `KernelErrorGenerator` | `{base}/repository/{E}NotFoundException.java` | one | every entity |
| `version-conflict-exception` | `KernelErrorGenerator` | `{base}/repository/{E}VersionConflictException.java` | zero-or-one | `@ExerisDomain(versioned = true)` |
| `tenant-mismatch-exception` | `KernelErrorGenerator` | `{base}/repository/{E}TenantMismatchException.java` | zero-or-one | `dataScope` `TENANT` or `UNIVERSE` |
| `shared-scope-mismatch-exception` | `KernelErrorGenerator` | `{base}/repository/{E}SharedScopeMismatchException.java` | zero-or-one | `UNIVERSE` with a `@SharedScope` field |
| `list-query` | `KernelListQueryGenerator` | `{base}/repository/{E}ListQuery.java` | one | every entity (ADR-096) |
| `page` | `KernelListQueryGenerator` | `{base}/repository/{E}Page.java` | one | every entity (ADR-096) |
| `event-publisher` | `KernelEventGenerator` | `{base}/event/{E}EventPublisher.java` | zero-or-one | at least one `@DomainEvent` |
| `event-subscriber` | `KernelEventHandlerGenerator` | `{base}/event/{E}EventSubscriber.java` | zero-or-one | at least one `@DomainEvent` |
| `graph-sync` | `KernelGraphSyncGenerator` | `{base}/graph/{E}GraphSync.java` | zero-or-one | `@Graph` |
| `saga-flow` | `KernelSagaGenerator` | `{base}/saga/{Flow}.java` | zero-or-one | `@Saga`; `{Flow}` is `@Saga(name)`, default `{E}Saga`, with `Flow` appended unless it already ends in `Flow` |
| `create-table-migration` | `KernelFlywayGenerator` | `db/migration/V{tier}{nnnnnn}__create_{table}.sql` | one | every entity; `{tier}` is `2` for a tenant-partitioned table other than `tenants`, else `1` |
| `shared-scope-migration` | `KernelSharedScopeMigrationGenerator` | `db/migration/V4{nnnnnn}__shared_scope_{table}.sql` | zero-or-one | `UNIVERSE` with a `@SharedScope` field |
| `openapi` | `KernelOpenApiGenerator` | `openapi/{kebab(E)}-api.yaml` | one | every entity |
| `client` | `KernelClientGenerator` | `{base}/client/{E}Client.java` | one | every entity |

*Main root, project scope*

| `id` | Generator | Path template | Cardinality | Condition |
|---|---|---|---|---|
| `application` | `KernelApplicationGenerator` | `{app}/Application.java` | one | at least one `@ExerisDomain` |
| `runtime-components` | `KernelApplicationGenerator` | `{app}/RuntimeComponents.java` | one | at least one `@ExerisDomain` (ADR-070) |
| `runtime-lifecycle` | `KernelApplicationGenerator` | `{app}/RuntimeLifecycle.java` | one | at least one `@ExerisDomain` |
| `foreign-key-migration` | `KernelApplicationGenerator` | `db/migration/V3000000__foreign_keys.sql` | zero-or-one | a `MANY_TO_ONE` relationship whose target is an entity of the same build |
| `cap-manifest` | `CodegenPipeline` | `cap-manifest.json` | zero-or-one | at least one `@CapabilityModule` (ADR-024) |

*Test root (`-Dexeris.tests=true`, ADR-058)*

| `id` | Generator | Path template | Cardinality | Condition |
|---|---|---|---|---|
| `test-support` | `KernelTestSupportGenerator` | `{app}/testsupport/` + `RecordingHttpExchange`, `RecordingPersistence`, `RecordingFlow`, `RecordingRequestBody`, `RecordingEventEngine` `.java` | fixed-set | at least one `@ExerisDomain` |
| `handler-test` | `KernelHandlerTestGenerator` | `{base}/handler/{E}HandlerTest.java` | one | every entity |
| `service-test` | `KernelServiceTestGenerator` | `{base}/service/{E}ServiceTest.java` | one | every entity |
| `repository-test` | `KernelRepositoryTestGenerator` | `{base}/repository/{E}RepositoryTest.java` | one | every entity |
| `saga-flow-test` | `KernelSagaTestGenerator` | `{base}/saga/{Flow}Test.java` | zero-or-one | `@Saga` |

## Consequences

### ✅ Positive Outcomes

- **[+] One source for "which generator wrote this file".** The bridge reads the catalogue instead of
  copying generator source, and a catalogue that lags the code fails this repository's build, not the
  bridge's answers.
- **[+] Coverage is checkable.** The catalogue is a closed list held to the generator set and to
  processor-produced output, so a consumer can state that it explains every path a release writes.
- **[+] The Java generator runs without a Maven build.** `java -jar` over a metadata directory gives a
  tool the same output the plugin writes.
- **[+] A path shape change is visible.** Moving an emitted file retires an id and adds a MIGRATION
  entry; it cannot happen silently.

### ⚠️ Trade-offs

- **[-] Two hand-maintained surfaces.** The JSON and `docs/generators.md` are written by hand. The
  tests catch disagreement; they do not write the row.
- **[-] Ambiguity is admitted, not removed.** The handler package keeps three rows whose shapes overlap
  for some entity names; a consumer without entity names reports candidates.
- **[-] One more published artefact**, shaded, whose bundled dependencies are re-released with every
  tooling release and whose licence files must travel with it.
- **[-] Argument handling becomes stricter.** An unknown switch is an error on the command line; a
  script that relied on extra switches being ignored stops working.

### 📋 What is NOT in scope

- **A TypeScript catalogue.** `outputRoot: ts` is reserved. A catalogue for `@exeris/codegen-ts`
  output is published with that package, under ADR-092, by a later decision. Until then the
  TypeScript generators are described in `docs/generators.md`, with a guard that holds the table to
  the `*-gen.ts` files and no machine-readable rows.
- **The content of generated files.** The catalogue describes paths, not what a file contains.
- **Detached trees.** After `exeris:detach` the files are the consumer's own (ADR-015's L2); the
  catalogue still explains the paths, but no row promises the file still matches its generator.
- **A Maven goal or an npm wrapper for the jar.**

### 🚫 Non-Goals

- **Predicting emission.** No consumer evaluates `condition`, and the catalogue does not carry the
  generators' guards in machine-readable form.
- **Putting the jar in an application build.** It stays out of the starter so that an application
  has one way to generate.

### ⚠️ Risks and Assumptions

- **Assumes:** `OutputWriter` keeps recording every written path in `.exeris-codegen-manifest`,
  relative to the output root with forward slashes. The rule in obligation 5 rests on it.
- **Assumes:** the conformance fixtures reach every row. A row only some unusual metadata reaches is
  covered by the completeness test, not by conformance, until a fixture reaches it.
- **Risk:** an entity name ending in `Stream`, or one that starts with another entity's name plus an
  action name, makes a path's first match the wrong handler row. `ambiguousWith` says so, and a
  consumer with entity names resolves it.
- **Reversed by:** a manifest that records the generator of each path. That would supersede
  obligations 3 to 5 and leave the catalogue as documentation.

## Cross-references

- **ADR-025** — `exeris-ai-bridge`. `build-explain_artefacts` reads the catalogue, and
  `build-preview_generation` runs the jar.
- **ADR-095** — the `EXT-` identifiers the command line prints. `docs/diagnostics.md` and
  `DiagnosticIdTest` are the pattern `docs/generators.md` and `GeneratorCatalogueTest` follow.
- **ADR-058** — the test output root and its separate manifest.
- **ADR-091** — the application starter, which does not include the jar.
- **ADR-015** — output stability; obligation 6 is its rule applied to paths.
- **ADR-085** — the registry row is reserved before content, and a cross-repo ADR leaves a `.link.md`
  stub in each affected repository.
- **ADR-024**, **ADR-070**, **ADR-096** — the capability manifest, the composition root, and the list
  route's records, each of which has a row.
- `docs/generators.md` — every row, its meaning and its condition.
- `exeris-kernel/exeris-kernel-diagnostics-cli/pom.xml` — the shade configuration obligation 10
  follows.

## Engineering Protocol

1. **`exeris-codegen-cli` lands as a reactor module** in 0.10.0, after `exeris-codegen-java` in the
   root `<modules>`, parented by `exeris-tooling-parent` and managed in `exeris-tooling-bom`.
   `tools/release-readiness` walks `<modules>` and requires a jar, a sources jar and a javadoc jar
   with an `index.html` for each module. The module has no source of its own, so its build produces
   both from the bundled `exeris-codegen-java` sources.
2. **`GeneratorCatalogueTest`** (in `exeris-codegen-java`) enforces obligations 1, 2, 3, 6 and 7.
   **The conformance test** in `exeris-e2e-tests` enforces obligations 4 and 8. **The command-line
   tests** enforce obligations 11 to 14, the last against the shaded jar in `exeris-codegen-cli`'s
   integration-test phase.
3. **The registry row for ADR-097** in `exeris-docs/adr-index.md` names `exeris-tooling` as owner and
   the scope as cross-repo, with `exeris-ai-bridge` as the stub holder.
4. **`exeris-ai-bridge` adds `docs/adr/ADR-097.link.md`**. It reads the catalogue in
   `build-explain_artefacts` in place of its own producer table, and runs the jar in
   `build-preview_generation`.
5. Migration owner: `exeris-tooling`, target 0.10.0, release-plan wave J2b.
