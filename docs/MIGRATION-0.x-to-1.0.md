# Migration: 0.x → 1.0

This document describes one-time differences downstream consumers will see when they regenerate against the `exeris-tooling` 0.x lineage that includes the [ADR-015](adr/ADR-015-codegen-emission-strategy.md) emission-strategy migration. Skim this once per consumer; nothing here is recurring.

> **Scope:** generated code shape only. Domain metadata, annotation processor input, and runtime SPI surfaces are unchanged. This is purely an output-formatting note.

---

## Why the diff exists

The Kernel generator suite (11 `Kernel*Generator` classes in `exeris-codegen-java`) used to emit all output by hand via `StringBuilder.append(...)`. ADR-015 migrated:

- the 9 Java-emitting generators to **JavaPoet** (Palantir's fork, `com.palantir.javapoet:javapoet:0.15.0`)
- `KernelFlywayGenerator` to **Java text blocks + `String.join`** for SQL emission

JavaPoet is a deterministic pretty-printer; the prior emission was ad-hoc. Regenerating the same domain entity against the new tooling produces semantically identical Java that is **formatted differently**. The compile-gate (`KernelCodegenCompileTest`) and the JMH/runtime contracts are unchanged.

---

## What changes in regenerated Java sources

The first regen against ADR-015-migrated tooling will produce a one-time large diff. Every delta below falls into one of these buckets — none of them are semantic regressions.

### Imports
- **Sorted alphabetically.** Square's JavaPoet (and Palantir's fork) emit imports in alphabetical order. The prior emission order matched the order each `import` line was hand-written.
- **Dead imports dropped.** JavaPoet only emits an import when a `$T` substitution actually references that type. Imports that the StringBuilder code wrote but never used (e.g., `SagaDefinition`, `StepAction`, `Function` in the saga generator) disappear from the output.

### Banner / section comments
- **Section banners (`// ═══...`) dropped.** JavaPoet has no comment passthrough at the field-/method-separator level. Sections that used to be visually divided are now identifiable by member ordering only.

### Whitespace
- **Blank lines between fields.** JavaPoet inserts a blank line between consecutive fields. Hand-rolled output sometimes packed them.
- **Blank line before nested types.** JavaPoet emits all nested types after methods, separated by a blank line.
- **One-liner getter expansion.** Hand-rolled `public X getX() { return x; }` becomes
  ```java
  public X getX() {
      return x;
  }
  ```
  This affects, in particular, the `CompositionRoot.java` getters (≈18 of them).

### Records
- **Record components on a wrapped line.** JavaPoet emits record components on a wrapped multi-component line:
  ```java
  public record State(UUID sagaId, UUID entityId, UUID tenantId, String currentStep,
          Instant startedAt, Map<String, Object> stepData) { ... }
  ```
  Hand-rolled output put each component on its own line.

### Nested-type position
- **Nested types emitted after methods.** Hand-rolled output sometimes placed the nested `record State` before methods. JavaPoet emits all nested types after methods.

### File ordering
- **Generated file count is unchanged.** Each generator still emits the same set of files (`OrderHandler.java`, `OrderRepository.java`, etc.).

---

## What changes in regenerated SQL

`KernelFlywayGenerator` was migrated to text blocks **with byte-equivalence preservation as an explicit requirement**. The golden-snapshot test (`KernelFlywayGeneratorTest`) pins this — for any of the eight metadata-flag combinations the tests cover, the SQL output is bit-for-bit identical to what 0.x emitted.

If you see SQL diffs in the migration **body**, please open an issue — that is a regression, not the expected migration shape.

### Flyway migration filenames are now deterministic (one-time rename)

The Flyway migration **filename** changed from a wall-clock version
(`V<System.currentTimeMillis()>__create_<table>.sql`) to a deterministic one
(`V<tier><fqn-hash>__create_<table>.sql`) so regeneration is byte-identical
(hard-constraint #3). Tenant-scoped tables tier above unscoped ones, so the
`tenants` table is always created before tables that `REFERENCES tenants(id)` —
the `tenants` table is pinned to tier 1 regardless of its flags, so the ordering
holds even if a `Tenant` entity is mistakenly marked tenant-scoped. (The
within-tier discriminator is a 1,000,000-bucket FQN hash; at well under ~1,000
entities per tier a collision is negligible, and would surface as a loud Flyway
"more than one migration with version N" — rename a colliding class if it ever
happens.)

**One-time action for apps that committed generated migrations:** the first
regen against this train writes the new deterministic filename and leaves the
old timestamped file orphaned. Delete the old `V<digits>__create_*.sql` files in
the same commit — otherwise Flyway sees two migrations for the same table. The
SQL *content* is unchanged, so this is a rename, not a schema change.

---

## What does NOT change

| Surface | Status |
|---|---|
| `@ExerisDomain`, `@DomainEvent`, `@Saga`, etc. annotation contracts | Unchanged |
| `DomainMetadata` AST (input to generators) | Unchanged |
| Annotation processor (`exeris-processor`) output | Unchanged |
| Runtime SPIs (`KernelBootstrap`, `EventStore`, `SagaEngine`, `Http3Router`) | Unchanged |
| Generated SQL migration **body** | Byte-equivalent (the **filename** is now deterministic — see above) |
| Generated OpenAPI YAML | Byte-equivalent (`KernelOpenApiGenerator` already used Swagger model objects + Jackson YAMLMapper — no migration needed) |
| Generated TypeScript / Angular (`exeris-codegen-ts`) | Out of scope for ADR-015 |

---

## Recommended migration step

Run regen once against the new tooling. Expect a single large diff in your generated-source tree that touches every `Kernel*` artifact. Skim it for the buckets above. After landing the regen commit, future regens are stable again — JavaPoet's output is deterministic.

If your repository commits generated sources, add the regen as its own commit so the formatting diff stays cleanly separable from feature work.

---

## ADR-034 — `KernelWebClient` facade rename

> **Out-of-band:** this is a runtime FQN change in the kernel side (broader scope than ADR-015's output-formatting story). It is noted here because the lockstep update on the tooling side lives in this repo (`KernelClientGenerator`'s `WEB_CLIENT` / `WEB_CLIENT_EXCEPTION` constants — see [ADR-034 link stub](adr/ADR-034.link.md)).

Effective at the ADR-034 kernel landing (kernel-side PRs A and B), the tier-neutral HTTP client facade moves:

| Surface | Before | After |
|---|---|---|
| Class | `ExerisWebClient` | `KernelWebClient` |
| Package | `eu.exeris.kernel.transport.http3.client` | `eu.exeris.kernel.core.http.client` |
| Nested exception | `ExerisWebClient.WebClientException` | `KernelWebClient.WebClientException` |

The facade is **tier-neutral by design** — the name no longer encodes a Community / Enterprise (or H1 / H2 / H3 transport) decision, which is now an internal Kernel-runtime detail. ADR-034 supersedes ADR-026.

### Who is affected

- **Manually-written HTTP clients in downstream user code** that imported the old FQN: update the import to `eu.exeris.kernel.core.http.client.KernelWebClient` (and `KernelWebClient.WebClientException` for the nested exception). No method signatures change.
- **Generated `*Client.java`** under `src/main/generated/java/…`: `KernelClientGenerator` is **parked** in this `exeris-tooling` train — no released artifact emits client code yet. When the generator unparks (blocked on a higher-level convenience SPI OR an `HttpEntityCodec<T>` collaborator — see `KernelGeneratorStrategy` parked-section Javadoc), the emitted FQN is already correct for ADR-034.
- **Bypass callers** that drive `HttpClientEngine.send(HttpRequest) → HttpResponse` directly: no migration needed (the SPI surface is unchanged; ADR-034 §Alternatives A documents why the typed API does not live on the engine SPI itself).

### What does NOT change in this train

| Surface | Status |
|---|---|
| `HttpClientEngine` SPI (`send(HttpRequest) → HttpResponse`) | Unchanged |
| `HttpRequest` / `HttpResponse` records (incl. `LoanedBuffer` body) | Unchanged |
| Generator registration (`KernelClientGenerator` still parked) | Unchanged |
| Generated SQL / OpenAPI / Angular / TypeScript | Unaffected |

---

## `exeris-codegen-ts` — single-target collapse (kernel-only)

The TypeScript emitter dropped the residual multi-backend abstraction so it
matches the Java side's single-target story (hard-constraint #1: Exeris kernel
only). This narrows the package's published surface (`exeris-codegen-ts`
re-exports `./core/backend-strategy` via `src/core/index.ts`).

**Breaking (pre-1.0, semver-permitted at 0.x):**

| Removed / narrowed export | Change |
|---|---|
| `BackendType` | Narrowed from `'KERNEL' \| 'SPRING' \| 'QUARKUS' \| 'MICRONAUT' \| 'VANILLA'` to `'KERNEL'` |
| `SpringStrategy` | Class removed |
| `QuarkusStrategy` | Class removed |
| `MicronautStrategy` | Class removed |
| `VanillaStrategy` | Class removed |

**Who is affected**

- **Config files / scripts** that set `"backend": "SPRING"` (or another
  non-kernel value) in `exeris-codegen.json`, or pass `--backend SPRING` on the
  CLI: the zod config parse now **throws** on the next run
  (`Invalid enum value. Expected 'KERNEL', received 'SPRING'`) rather than
  silently ignoring it. Remove the `backend` key (the default is `'KERNEL'`) or
  set it explicitly to `'KERNEL'`.
- **Code importing the removed strategy classes / non-kernel `BackendType`
  members** from `@exeris/codegen-ts`: drop the import — only `KernelStrategy`
  remains, and it is auto-registered.

**What does NOT change**

The `BackendStrategy` interface, the strategy registry, and the
`backend` / `supportedBackends` plumbing remain (now single-valued); generated
Angular/TypeScript output is byte-identical for the kernel target.

---

## 0.6.0 train — regeneration and build-behavior deltas

One-time notes for consumers moving from a 0.5.x regen to 0.6.0. As everywhere in this
document: annotation contracts and `DomainMetadata` are unchanged; these are output and
build-behavior deltas.

### Dependency floor (hard)

Regenerated code binds kernel-0.10 SPI surfaces (`HttpExchange.pathParams()`,
`PersistenceStatement.bindInstant` / `RowCursor.getInstant`, the ADR-043 streaming SPI, the
3-arg `EventTypeSpec.ofPersistent(name, ordinal, topic)`, the ADR-046 codec registry). The
tooling BOM pins **released `eu.exeris:exeris-kernel-*:0.10.0` and `eu.exeris:exeris-sdk-*:0.8.0`**
— a downstream app on an older kernel will not compile the regenerated tree.

### Schema deltas in regenerated migrations

- **T8:** FK indexes for every `MANY_TO_ONE` relationship + indexes for `filterable` fields
  (new `CREATE INDEX` statements), and `findBy<Rel>Id` / `findBy<Field>` finders on the
  Repository/Service pair.
- **T10:** `CHECK` constraints derived from `@Validation` bounds land in the DDL.
- Both appear on first regen as new statements in the deterministic migration files —
  additive, but review them like any schema change before applying to a live database.

### Build-behavior changes (T18 — two-pass safety)

- `mvn clean compile` on a metadata-less tree **no longer silently wipes** a committed
  `src/main/generated` tree: the run fails with `EmptyMetadataException` and a recipe.
  Seed metadata first (`mvn compile -Dexeris.codegen.skip=true`), or opt into a genuine
  teardown with `-Dexeris.codegen.allowEmpty=true`.
- New goal **`exeris:verify-capabilities`** (default phase `process-classes`): bind it
  alongside `exeris:generate` and a capability-graph failure at `generate-sources` (which by
  construction sees the *previous* build's `capability_*.json`) degrades to a WARNING, with
  the fail-closed verdict delivered against fresh metadata after `compile`. Unbound, the
  historical fail-fast behavior is unchanged.

### Regenerated Angular app (v22)

The emitted frontend is Angular v22 (`@angular/build` builder, `rxResource` detail fetch,
ui-kit token utilities, configurable `--app-name`): **Node 22+** is the floor for building
the *generated* app (the generator package itself still runs on Node 18+).

### Emitted event publishers

- Payloads are **codec-resolved** (ADR-046) — no longer `EventPayload.empty()`; redaction
  (`sensitiveFields`) happens before encode; unresolvable codec falls back to the empty
  payload with a producer-side JFR event.
- `@DomainEvent.topic` now lands on the per-type `EventTypeSpec` (ADR-050) — broker
  bindings honour it on publish and subscribe; the in-memory bus treats it as advisory.

### Building the tooling reactor itself

`maven-enforcer` now fails fast unless Maven runs on **exactly JDK 26** (preview
compilation pins the release) with Maven 3.9+ (D1). This guards contributors building
*this* repo; downstream apps are unaffected beyond the JDK 26 requirement they already had
for running the processor/plugin.

> **Superseded in the 0.7.0 train** — the floor is now `[25,)` and no preview flag is used.
> See "JDK floor drops 26 → 25 LTS" below; this paragraph describes 0.6.0 as shipped.

---

## 0.7.0 train — regeneration deltas

Same framing as the 0.6.0 section: annotation contracts and `DomainMetadata` are unchanged;
what changes is what the emitters produce from them.

### The tooling coordinate you depend on is now a release, not a SNAPSHOT

`v0.5.0` and `v0.6.0` were tagged with the reactor POM still reading `0.5.0-SNAPSHOT` /
`0.6.0-SNAPSHOT`, so the only way to consume a tagged tooling release was a snapshot coordinate that
could change underneath you. From `v0.7.0` the tag carries the final version: `eu.exeris.tooling:*`
is **`0.7.0`**.

If your build pins `0.6.0-SNAPSHOT` (typically on `exeris-codegen-maven-plugin`), move it to `0.7.0`.
Nothing else changes with it — this is a coordinate fix, not an API change — but a snapshot pin will
keep resolving to whatever was last installed locally rather than to anything this train describes.

### Dependency floor (hard)

The BOM moves to released **`eu.exeris:exeris-sdk-*:0.10.0` and `eu.exeris:exeris-kernel-*:0.11.0`**.

Two things ride along that a version bump does not usually carry.

**The metadata schema stamp moves `0.9.0` → `0.10.0`.** SDK 0.10.0 bumps `SchemaVersion.CURRENT`, and
the processor stamps it by reading `BaselineTrust.current(...)` rather than the compile-time constant,
so regenerated `exeris-metadata/*.json` carries the new value with no tooling change. Per ADR-042 a
pre-0.10.0 baseline reads back as `SCHEMA_VERSION_SKEW` — cross-shape baselines are refused rather
than assumed compatible — so **re-run codegen once after upgrading**. Nothing in tooling reads a
baseline for skew today; the refusal is the SDK `-io` reader's, and it is the thing to expect if you
hold an older `.json` tree.

**Kernel 0.11.0 ships two lines, and this BOM pins the default one.** `eu.exeris:*:0.11.0` is JDK 25
LTS, class-file major 69, and requires no `--enable-preview` from you (kernel ADR-066); a second line
publishes the same kernel under `eu.exeris.preview:*:0.11.0` for JDK 28 EA with Valhalla `value
record`. Generated code is **identical** against either — of the 46 kernel types the emitters name,
only `EventBus` (javadoc) and `MemoryStats` (the `value` modifier, transparent to a consumer) differ —
so this is a build choice on your side, not a codegen variant. Note that 0.11.0 is also what makes an
LTS toolchain reachable at all: 0.10.2 was major 70 and `exeris-kernel-core` carried preview-stamped
classes, which JDK 25 refuses outright.

### JDK floor drops 26 → 25 LTS — a widening, and it may remove work from your build

Tooling now compiles at `--release 25` and publishes class-file **major 69**, with no
`--enable-preview` anywhere (kernel ADR-066, SDK ADR-069). The `maven-enforcer` range moves from
exactly `[26,27)` to open-ended `[25,)`.

For you this only ever adds toolchains: anything that built on JDK 26 still does, and JDK 25 LTS
becomes usable where a major-70 artifact was refused outright. `exeris-codegen-maven-plugin`'s
classes load into Maven's own JVM, so this floor is the real constraint on which JDK your Maven can
run — that is what previously stopped an LTS-only shop from running the processor at all.

**Two things you can now delete**, if you added them only for us: any `--enable-preview` you were
passing to build against preview-stamped kernel classes, and any JDK-26 pin in CI. Neither is
required by anything this repo publishes. If your own code uses preview features, keep your flags —
they were never ours to remove.

### `@Relationship(relationshipType = …)` is honoured — review your schema diff

Until now the processor read an attribute named `type`, which `@Relationship` does not have
(it declares `relationshipType`), so **every** relationship reached the generators as
`MANY_TO_ONE`. Since the Flyway, repository, service and FK-constraint emitters all gate on
`MANY_TO_ONE`, the non-owning side of a relationship was getting artefacts that belong to the
owning side. After the fix a regenerated tree **loses**, for every relationship declared
`ONE_TO_MANY` / `MANY_TO_MANY` / `ONE_TO_ONE`:

- the `<rel>_id` column in that entity's `CREATE TABLE`,
- its `CREATE INDEX`,
- its `ALTER TABLE … ADD CONSTRAINT … FOREIGN KEY` in `V3000000__foreign_keys`,
- the `findBy<Rel>Id` finder on the Repository/Service pair.

**This is a removal from generated DDL, so it needs a decision, not just a regen.** Flyway
validates checksums of migrations that were already applied: if the affected `CREATE TABLE`
file has been applied to a live database, do **not** silently commit the regenerated version —
either keep the old file and drop the column with a new hand-written migration, or repair the
checksum, per your Flyway policy. A database that never ran the old file simply gets the
correct schema.

`@Relationship(cascadeDelete = …)` / `cascadeUpdate` are extracted for the first time in the
same change: `cascadeDelete` now emits `ON DELETE CASCADE` (previously every generated FK was
`RESTRICT` regardless of the annotation). `cascadeUpdate` alone does not change the delete
policy.

### An entity field named `id` no longer breaks generation

A filterable field named `id` emitted a second `findById(UUID)` on the repository and the service,
colliding with the built-in primary-key lookup (`method findById(UUID) is already defined`). It was
easy to hit without meaning to: the processor records a field with no `@Field` annotation via
`FieldMetadata.simple(...)`, which sets `filterable(true)` — so a plain `private UUID id;` on an
entity was enough to make the whole generated tree uncompilable. The finder that shadows the
primary-key lookup is now skipped. Nothing to do on your side; if you had worked around it by
annotating or renaming the field, that workaround is no longer needed.

### Entities with collection fields now generate at all

A field whose type is a collection (`List<Tag> tags`) crashed the pipeline with
`IllegalArgumentException: not a valid name: List<…`. The repository emitter recognised only
the short spelling `List<`, while the processor records the type as javac renders it —
fully qualified, `java.util.List<…>`. Nothing to do on your side; entities that previously
could not be generated now can.

### Generated code no longer depends on SLF4J

Generated repositories, services, handlers, event publishers, event handlers, graph sync, sagas and
the application bootstrap logged through `org.slf4j.Logger`. They now log through
`java.lang.System.Logger` ([ADR-060](adr/ADR-060-generated-code-logging-facade.md)).

**Why it mattered:** `slf4j-api` is not a dependency of `exeris-kernel-spi` or `exeris-kernel-core` —
it reached an application only through the driver tier (`exeris-kernel-community` pulls it). Since
tooling emits no `pom.xml`, a consumer on a different driver set could end up with generated code
that does not compile, against a requirement no document carried.

**What you need to do:**

- If you declared `org.slf4j:slf4j-api` **only** because generated code needed it, you can drop it.
- If you want these log records in your SLF4J (or Log4j) backend, put a `System.LoggerFinder`
  provider on the classpath — `org.slf4j:slf4j-jdk-platform-logging` is the SLF4J one. **Without a
  provider the records go to `java.util.logging` instead**, so a configuration that previously showed
  them will look silent. This is the only user-visible behaviour change; the messages and their
  levels are unchanged.

Message *syntax* in the emitted source changes with the facade — `{}` becomes `{0}`, `{1}`, … because
`System.Logger` formats with `MessageFormat` — but rendered output is the same. Regenerate and commit;
there is nothing to hand-edit.

### Versioned entities with a `Long` version field no longer fail on first save

If an entity is `@ExerisDomain(versioned = true)` and declares its version field as the **wrapper**
`Long` rather than the primitive `long`, the previously generated repository threw
`NullPointerException` on the first `save()` of a freshly constructed entity — the bind unboxed a
null. `update()` unboxed the same way.

Both now read through a boxed local and default a null to `0`, so a wrapper-typed field behaves
exactly like the primitive it shadows. Regenerate; there is nothing to hand-edit and no API change.
Entities that already declared `long` are unaffected in behaviour and in emitted semantics.

### Generated tests (opt-in, new)

`exeris:generate` gains `-Dexeris.tests=true`. It is **off by default**, because turning it on adds
two hard requirements to your build — tooling emits no `pom.xml`, so what the generated tests import
is a contract on you:

```xml
<dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><scope>test</scope></dependency>
<dependency><groupId>org.assertj</groupId><artifactId>assertj-core</artifactId><scope>test</scope></dependency>
```

…and nothing else: no mocking framework is imposed, because the doubles are emitted rather than
mocked (ADR-058).

Output goes to a **separate root**, `src/test/generated/java` (`-Dexeris.testOutputDir`), registered
as a *test* compile source root and carrying its own generated-output manifest — pruning one tree
can never touch the other. Commit it like the main generated tree.

What is emitted so far, per entity: `<Entity>HandlerTest`, `<Entity>ServiceTest` and
`<Entity>RepositoryTest` — plus `<Saga>FlowTest` for each entity that declares a `@Saga` — and four
shared doubles under `<basePackage>.testsupport`: `RecordingHttpExchange`, `RecordingPersistence`,
`RecordingFlow` and `RecordingRequestBody`.

The handler test covers every status the handler owes the router on the bodyless CRUD routes, and
the guard paths of `handleCreate` / `handleUpdate` — a missing body, and a malformed path id — each
also asserting the service was never reached.

It also covers the `@Validation` guards past a successful decode, for any entity whose fields carry
enforceable rules. Those cases bind `RecordingRequestBody` into two kernel `ScopedValue` slots
(`HttpKernelProviders.HTTP_REQUEST_BODY_DECODER_REGISTRY` and `KernelProviders.MEMORY_ALLOCATOR`)
and run the handler inside that scope. **This adds nothing to your build**: both slots and the SPI
types behind them come from `exeris-kernel-spi`, which your generated main code already compiles
against. No driver, no bootstrap, no port, no engine — the tests stay in-process, and the
JUnit + AssertJ contract above is unchanged.

Each rule gets a case that violates it, and each *bounded* rule also gets one sitting exactly on the
boundary and expecting `201 CREATED`, because `min` / `max` / `minLength` / `maxLength` are
inclusive. Every entity with rules additionally gets an all-rules-satisfied accept case. If you are
wondering why the accepts are there: every failure past the body guard answers `400`, the same
status a rejection does, so `201` is the only outcome that proves the request was decoded at all.

Two cases are deliberately not emitted. A field with a `pattern` gets no length or numeric cases
(and if it is also `required`, the entity gets no validation cases at all) — a regex has no
synthesizable member, so nothing can build a valid baseline for the other fields to be tested
against. Floating-point `min` / `max` are skipped too: the bound is a `long`, the comparison
promotes, and a boundary probe that is only approximately on the boundary tests nothing.

The service test covers the delegation contract: which repository method each call reaches (the
`delete` → `deleteById` rename included), that `save` / `update` hand back the *repository's* result
rather than the argument they were given, and one case per T8 finder. Its repository double
subclasses the generated repository with a null `TransactionalExecutor`, so no database, driver or
transaction is involved.

The repository test covers the one invariant no compile check reaches: that the parameter indices
the INSERT binds and the column indices `mapRow` reads are the same layout. It proves that by
saving an entity against `RecordingPersistence`, replaying the recorded binds back as the query
result, and comparing the loaded entity column for column — plus the id fill-in, the WHERE-clause
id, an empty result, the two zero-rows-affected rejections, and `count()`. It deliberately asserts
**no SQL text**: the test and the repository come from the same metadata, so that check could never
fail. No database or driver is involved.

One consequence worth knowing: for an entity with a collection field, the generated repository test
initialises the repository class, whose static Jackson mapper is constructed then. That is not a new
requirement — such an entity's generated *main* code already imports Jackson.

The saga test covers what the compiler cannot: that the transition chain spans exactly the steps
that were registered, that `initialize()` is idempotent, and that `schedule()` hands the scheduler
the plan `initialize()` built rather than compiling its own. No scheduler thread and no engine
lifecycle are involved.

Nothing is emitted, and nothing changes, unless you set the flag.

If you had already turned the flag on, expect new `<Entity>ServiceTest` and
`<Entity>RepositoryTest` files per entity, a `<Saga>FlowTest` per saga, new `RecordingPersistence`,
`RecordingFlow` and `RecordingRequestBody` doubles, the regenerated
`<Entity>HandlerTest` to gain three test methods plus its `@Validation` cases, and the emitted
`RecordingHttpExchange` to gain `post(...)` / `put(...)` factories in both bodyless and
body-carrying forms.

### Composed applications drive the boot conductor

A build that also carries `@CapabilityModule` metadata now emits a `CompositionConductor`
call site into `Application.run()`, inside the `KernelBootstrap.boot(...)` callback. Two
consequences for such a build:

- add `eu.exeris:exeris-sdk-composition-runtime` to the app's runtime classpath;
- make `cap-manifest.json` reachable at runtime. The default is the `exeris.capManifest`
  system property, falling back to `cap-manifest.json` in the working directory — the build
  writes the manifest at the codegen output root, which is a *source* root and never on the
  classpath. Override `protected Path capManifest()` to resolve it any other way.

A build with no capability metadata is unaffected: it emits byte-identically to 0.6.0, down
to the absent import.

### `@ExerisDomain.tenantScoped` is deprecated — a build warning, not a break

ADR-059 makes `@ExerisDomain.dataScope` (`GLOBAL` / `TENANT` / `UNIVERSE`) the canonical
expression of an entity's data-scope tier. `tenantScoped` is `@Deprecated(forRemoval = true)`
in SDK 0.10.0 and stays readable for the whole 0.10.x line — removal is a 1.0.0 item.

What this train changes for an existing build:

- **Nothing in the output.** An entity that declares only `tenantScoped` resolves through
  the fallback (`true → TENANT`, `false → GLOBAL`), which is exactly what the boolean always
  meant. Emitted SQL, column layouts and Flyway versions are byte-identical — asserted by
  `KernelFlywayGeneratorTest.deprecatedBooleanStillDrivesTheSameOutput`.
- **One warning per entity that still declares it**, naming the tier it resolved to. Builds
  running `-Werror` need the migration now rather than at 1.0.0.
- **A new error** if an entity declares both and they disagree (`dataScope = GLOBAL` with
  `tenantScoped = true`, or any pairing with `UNIVERSE` — no boolean value can express the
  third tier). Declare the tier once.

Migrating is a mechanical rewrite: `tenantScoped = true` → `dataScope = DataScope.TENANT`,
`tenantScoped = false` → drop the attribute (`GLOBAL` is the default). Note that moving an
entity *between* tiers — in either direction, and whichever attribute expresses it — changes
its `migrationVersion` discriminator, which Flyway sees as a new migration rather than an edit
to the existing one. That has always been true of a `tenantScoped` flip; it is worth restating
because a one-word enum change makes it easier to do without noticing.

`UNIVERSE` is accepted and **reserved**: it currently fails closed to the `TENANT` shape
(owner column, owner index, owner-pinned RLS policy — UNIVERSE minus the cross-tenant
read-widen) with a warning saying so. Do not declare it expecting cross-tenant reads yet.
(As of 0.9.0 the tier is refused outright at the declaration — see the 0.8.0 train — and what
gates the transcription is stated in the 0.9.0 train, not the `sharedScopeKey` carrier this
paragraph originally named.)

## 0.8.0 train — regeneration deltas

### Dependency floor (hard)

The BOM moves to released **`eu.exeris:exeris-sdk-*:0.11.0`**. The kernel pin does not move — it
stays at `0.11.0`, both lines and all the notes from the 0.7.0 train still apply.

**The metadata schema stamp moves `0.10.0` → `0.11.0`.** SDK 0.11.0 bumps `SchemaVersion.CURRENT`
for two reserved AST components: `FieldMetadata.blob` and `ActionMetadata.schedule`, the twins of
`@Blob` and `@Schedule` (SDK ADR-072). Nothing in this train populates them — the processor extracts
neither annotation and no generator consumes either carrier — but the schema names the shape rather
than its population, so the stamp moves anyway and a pre-`0.11.0` baseline reads back as
`SCHEMA_VERSION_SKEW`. **Re-run codegen once after upgrading**, exactly as at the previous bump. As
before, the refusal is the SDK `-io` reader's; nothing in tooling reads a baseline for skew today.

**Nothing else in emitted output changes.** The rest of the SDK delta is additive record components
(`DomainEventMetadata` grows `trigger` / `actionName` / `fieldName`, alongside the two above) plus
one narrowing on the annotation surface: `@Action.path` is now `default` rather than `required`.
`path` stays registered inert (T44), so the only difference you can observe is under
`-Aexeris.strict`, where an action that never sets it no longer draws the "set but no code generator
consumes it" warning.

### A third bootstrap file, and `RuntimeLifecycle`'s constructor changed (ADR-070)

Regeneration now emits `RuntimeComponents.java` next to `Application.java` and
`RuntimeLifecycle.java`. It owns the construction of every generated repository, service, handler
and SSE stream handler; `RuntimeLifecycle` takes it as its second constructor argument and no longer
calls `new` on a generated type.

**If you only regenerate, there is nothing to do** — all three files are generated and change
together. Two cases need action:

- **You hand-wrote something that constructs `RuntimeLifecycle` directly** (a custom launcher, a
  test harness). `new RuntimeLifecycle(handlerSlot, transactionalExecutor)` becomes
  `new RuntimeLifecycle(handlerSlot, new RuntimeComponents(transactionalExecutor))`.
- **You forked a generated file to install your own service.** That was the only way to do it
  before; it is no longer necessary and the fork can be dropped. Subclass `RuntimeComponents`,
  override the one `create*` factory, and return the subclass from
  `Application#components(TransactionalExecutor)`:

  ```java
  class MyComponents extends RuntimeComponents {
      MyComponents(TransactionalExecutor tx) { super(tx); }

      @Override protected OrderService createOrderService() {
          return new MyOrderService(orderRepository(),
                  new OrderEventPublisher(KernelProviders.eventEngine()));
      }
  }

  class MyApplication extends Application {
      @Override protected RuntimeComponents components(TransactionalExecutor tx) {
          return new MyComponents(tx);
      }

      public static void main(String[] args) { new MyApplication().run(); }
  }
  ```

**Point your launcher at the subclass.** The generated `Application.main()` does
`new Application().run()` — it is not polymorphic, so running it ignores your overrides silently.
Give the subclass its own `main`, as above.

Two properties worth knowing rather than rediscovering: every factory runs inside the
`KernelBootstrap.boot(...)` callback, so a body may resolve any bound provider
(`KernelProviders.flowEngine()`, `eventEngine()`, …); and `configureRoutes(HttpRouter.Builder)` is
called after every generated route and before `build()`, so a hand-written route can add to the
routing table but never displace a generated one.


### `dataScope = UNIVERSE` is now refused at the declaration (T29)

A `UNIVERSE` declaration used to compile with a warning and emit the `TENANT` shape. It now fails the
build with a processor **ERROR** on the annotated type.

This is not a policy change — `UNIVERSE` never delivered cross-tenant read-widening from this
pipeline, and still does not. What changed is where you find out. On the entity `UNIVERSE` actually
describes — a shared-world row, which by definition has no tenant property — the emitted repository
bound `entity.getTenantId()`, so the build failed anyway, with `cannot find symbol` inside a
generated file you are told not to edit, pointing at a getter nobody asked you to write.

**If you declared `UNIVERSE` and your entity has no tenant property:** your build was already
failing. It now fails at the declaration with a message that says why.

**If you declared `UNIVERSE` and your entity does have a tenant property:** your build worked and was
silently giving you the `TENANT` shape. Change the declaration to `dataScope = DataScope.TENANT`,
which is what you were getting. Emitted output is byte-identical.

There is no way to obtain cross-tenant read-widening from this build yet. What gates it is
measured in the 0.9.0 train below: the session variable an emitted policy must read is not named
in kernel SPI, Core or the TCK on the pinned `0.11.0` line.

### An unbound `MemoryAllocator` now answers 5xx instead of 400 (T43)

Regenerated handlers bound-check `KernelProviders.MEMORY_ALLOCATOR` before building the request
decoding context. If it is unbound, the request fails with a server-side `IllegalStateException`
naming the wiring, rather than an `IllegalArgumentException("Invalid request body")` that the call
site mapped to **400 Bad Request**.

Nothing works that did not work before — the request failed either way. What changes is who the
response blames. If you have monitoring or tests that treat `POST`/`PUT` 400s as client errors, a
deployment with this fault will now show up as a 5xx, which is where it belongs: the body has not
been read at the point the failure occurs.

> **Superseded later in the same train.** The bound-check described above is gone, together with the
> per-request read it protected — see *"Generated handlers take a `MemoryAllocator`"* below. The
> fault it reported can no longer reach a request at all; it fails the boot instead.

### A tenant-scoped repository now stamps the tenant it writes (T36)

`save` and `update` on a repository generated for a tenant-partitioned entity fill an absent tenant
from the ambient `StorageContext` before binding it, the same way `save` has always filled an absent
`id`. A tenant the caller *did* set is left alone.

This is a fix, not a new requirement. Nothing upstream ever supplied the value: the generated handler
decodes a request body straight into the entity, and the generated Angular form treats the tenant as
a system field it never sends. So every create — and every update built from a request body rather
than from a read — bound `null`, and the RLS policy this pipeline's own migration installs refused
the row. The failure surfaced as a row-level-security violation, which reads as an attempted
cross-tenant write rather than as the missing default it was.

**If you were working around it** by setting the tenant on the entity before calling the repository —
in a service, an interceptor, or a hand-written route — nothing breaks: a value you set is kept.
The workaround is now redundant, not wrong.

**Two new failure modes, both deployment faults, both `IllegalStateException` → 5xx:** the bound
context carries no isolation key (that is the system/global scope, which has no owner to stamp), or
it carries one that is not a UUID. The second was already fatal one layer down — the generated RLS
predicate casts the session key `::uuid` — it just failed at the database instead of at the write.

**Regenerated tests:** a tenant-partitioned entity's `<Entity>RepositoryTest` now binds a tenant
around each write and gains two cases (`saveStampsTheActingTenantWhenTheCallerLeftItUnset`,
`saveKeepsATenantTheCallerSet`). The scaffold uses `KernelProviders` and `ImmutableStorageContext`,
both already compile-time requirements of the repository under test — the ADR-058 "JUnit 5 and
AssertJ and nothing else" contract is unchanged.

### `-Aexeris.strict` now reports `@Action.path` and `@ExerisDomain.apiVersion` (D5)

Only if you pass `-Aexeris.strict`. A default build is unchanged and stays silent, and nothing about
what the compiler *produces* changes either way.

Both attributes have been registered inert since the flag shipped, but the audit is driven by a
per-annotation call site and `@Action` and `@ExerisDomain` had none — so a strict build never
reported them. It does now. Expect one new warning per `@ExerisDomain(apiVersion = …)` and one per
`@Action(path = …)`.

Both are safe to delete from your sources: neither is read by any generator, `apiVersion` reaches no
emitted route or document, and `path` no longer has to be written at all now that SDK 0.11.0 has
given it a default.

### `-Aexeris.strict` now reports `@Blob` and `@Schedule` (D6)

Only if you pass `-Aexeris.strict`, and again nothing about what the compiler produces changes.

Both annotations shipped in SDK 0.11.0 and are **reserved**: no processor extracts them and no
generator consumes them, so a field or method carrying one is emitted exactly as if it were absent.
Strict mode now says so. Before this change it could not have, even with the entries registered —
the inert-annotation sweep only inspected type-level annotations, and `@Blob` is `@Target(FIELD)`,
`@Schedule` is `@Target(METHOD)`.

Expect one warning per `@Blob` field and one per `@Schedule` method. Each names why the annotation is
inert and where the transcription is gated — `@Blob` on the kernel (no bootable storage subsystem,
and blob storage is post-1.0 kernel-side), `@Schedule` on the identity a declared job runs as. See
[`adr/ADR-072.link.md`](adr/ADR-072.link.md).

**Not** a signal to remove them from your sources: unlike `@Action.path`, these are a reserved
surface you are meant to be able to declare against. The warning tells you the build does nothing
with it *yet*.

### Generated handlers now publish domain events, and take the publisher as an argument (T48)

`<Entity>Handler`'s constructor gains a second parameter, `<Entity>EventPublisher`, for every entity
that declares a `@DomainEvent`. `RuntimeComponents` supplies it and gains a
`create<Entity>EventPublisher()` factory alongside the ones it already had, so the wiring is the
seam's, not yours — **unless you construct a handler by hand**, in which case that call site needs
the extra argument.

**What starts happening:** an event whose `trigger` is `CREATE`, `UPDATE`, `DELETE` or `ACTION` is
now published by the handler method that satisfies it, after the mutation and before the response.
Before this change the emitted publisher existed and nothing called it, so a declared
`@Action` → `@DomainEvent` → saga chain returned `200` and did nothing.

**What still publishes nothing:** an event with no `trigger`, and the `FIELD_CHANGED`,
`STATE_TRANSITION`, `SCHEDULED`, `MANUAL` and `SNAPSHOT` triggers. Each needs a source of truth the
handler does not have.

**Two behaviours worth knowing before you rely on it.** The publish runs *after* the commit — the
transaction boundary is in the repository, below the service — so a crash between the two loses the
event; `FLAG_PERSISTENT` makes delivery durable once published, not the publish itself. And
publishing is coupled to the HTTP transport: a saga or a scheduled job calling the service directly
publishes nothing. ADR-070's seam is where you install a publishing service of your own if you need
that today.

**Two smaller shape changes ride along.** `<Entity>EventPublisher` is no longer `final`, so that a
consumer overriding `create<Entity>EventPublisher()` can decorate the default by calling `super`
rather than only replacing it. And a `DELETE`-triggered event that carries a payload makes
`handleDelete` read the aggregate before deleting it, emitted only when such an event exists.

**No delete publishes an event for a row that was not there.** Not because of a guard — because the
generated repository's `deleteById` already throws when the delete affects no rows, and the service
delegates straight to it. A `DELETE` on an unknown id, including a retried one, leaves the handler
through its 5xx catch and never reaches the publish. That behaviour predates this change; it is
stated here because the publish call now depends on it.

**Regenerated tests:** a new project-wide double, `RecordingEventEngine`, joins
`RecordingHttpExchange` / `RecordingPersistence` / `RecordingFlow` / `RecordingRequestBody` under
`<basePackage>.testsupport`; the emitted handler test routes construction through one `newHandler`
helper; and the emitted stub service now fills an absent id on `save`, matching what the real
repository does. The ADR-058 "JUnit 5 and AssertJ and nothing else" contract is unchanged.

See [`adr/ADR-075-generated-event-publisher-caller.md`](adr/ADR-075-generated-event-publisher-caller.md).

### `PUT` and `DELETE` against an absent id now answer `404`, not `500` (D7)

**This changes the status codes your deployed API returns.** Read it before regenerating in front of
a live client.

| Request | Before | Now |
|---|---|---|
| `DELETE /{path}/{unknown-id}` | `500` | `404` |
| `PUT /{path}/{unknown-id}`, entity **not** `versioned` | `500` | `404` |
| `PUT /{path}/{id}` with a stale version, entity `versioned` | `500` | `409` |
| `PUT /{path}/{unknown-id}`, entity `versioned` | `500` | `409` |
| `POST /{path}/{id}/actions/{name}` where the row vanished mid-request | `500` | `404` / `409` as above |

Nothing else moves: a row that *was* there still answers `204` / `200` exactly as before, and a
genuine infrastructure failure still answers `500`.

**New emitted types.** Per entity, in the generated **repository** package:
`<Entity>NotFoundException` always, and `<Entity>VersionConflictException` when the entity is
`versioned`. Both extend `RuntimeException` and expose the id through `id()`. They are how the fact
crosses the layer boundary — it used to travel only as a message substring, which the handler's
single `catch (RuntimeException)` could not read.

> A versioned `PUT` answers `409` for a **missing** row too. That is deliberate: the emitted
> statement matches on `id` and on the expected version together, so a zero row count cannot say
> which of the two missed. `409` — *your write did not apply, re-read and retry* — is true of both;
> `404` would be a lie about one. See ADR-076 for why the extra query that would split them is not
> worth its cost.

**Client impact.** If your client treats `5xx` as retryable and `4xx` as terminal, a delete of an
already-deleted row stops being retried — which is the point. If it treats any non-`2xx` from `PUT`
as fatal, a `409` is now the signal to re-read and retry rather than to page someone.

**Regenerated OpenAPI:** every operation now declares `500`, and a versioned entity's write routes
declare `409`. The spec previously declared `404` on every operation and `500` on none — it named
the one status the handler could not give, and omitted the one it did.

**Regenerated tests:** `Stub<Entity>Service` carries a `boolean rowExists = true` field and throws
the rejection type from `delete` / `update` when it is false, so the double has the failure mode the
real service has. The existing delete case is unchanged in behaviour (the flag defaults to "the row
is there"); one new case, `handleDeleteRespondsNotFoundWhenNoRowMatched`, covers the other branch.
The emitted repository test now asserts the exception **type** rather than
`hasMessageContaining("not found")`. ADR-058's "JUnit 5 and AssertJ and nothing else" is unchanged.

**If you have hand-written code catching the old shape:** a `catch (RuntimeException e)` around a
generated `update` / `delete` still catches these — they are subclasses. Code that matched on
`e.getMessage().contains("not found")` should switch to `instanceof <Entity>NotFoundException`.

See [`adr/ADR-076-write-rejection-status.md`](adr/ADR-076-write-rejection-status.md).

### New goal `exeris:verify-runtime` — bind it, or it does nothing (T50)

**Action required to get the check.** Like `exeris:verify-capabilities`, this goal is inert
until you bind it. Add the execution alongside the ones you already have:

```xml
<execution>
  <id>exeris-verify-runtime</id>
  <goals><goal>verify-runtime</goal></goals>
</execution>
```

Default phase is `process-classes` — the same phase, and for the same reason, as
`verify-capabilities`: it reads the metadata the annotation processor emitted during `compile`.

**What it catches.** The generated `Application.main()` boots the kernel by subsystem *name*,
and every provider behind those names is discovered through `ServiceLoader` from a runtime driver
artefact. Tooling emits no `pom.xml`, so nothing in your build declared that dependency and
nothing verified it — the first sign was a bootstrap error at start-up naming a subsystem, when
the missing thing was a jar.

Measured against the published artefacts: `exeris-kernel-core-0.11.0.jar` carries **zero**
`META-INF/services` entries, and `BootstrapSelector` selects by name from
`ServiceLoader<SubsystemProvider>` — so an application on SPI + Core alone has no name that can
resolve at all.

**What it requires**, derived from what the pipeline emitted into *your* project — not from the
`subsystems()` string, which you are free to override:

| SPI | Required when |
|---|---|
| `eu.exeris.kernel.spi.bootstrap.SubsystemProvider` | always |
| `eu.exeris.kernel.spi.persistence.PersistenceProvider` | always (a repository per entity) |
| `eu.exeris.kernel.spi.http.HttpProvider` | always (a handler per entity) |
| `eu.exeris.kernel.spi.events.EventProvider` | some entity declares a `@DomainEvent` |
| `eu.exeris.kernel.spi.graph.GraphProvider` | some entity carries graph metadata |
| `eu.exeris.kernel.spi.flow.FlowProvider` | some entity declares a saga |

Crypto is **not** required, though the default `subsystems()` names it — no emitted artefact
uses it.

**How to satisfy it.** Add a runtime driver to the module that runs the generated application:
`eu.exeris.kernel:exeris-kernel-community` in the open-core tree. Enterprise and third-party
drivers register the same SPIs and satisfy the check equally.

**Opt-out.** `-Dexeris.verifyRuntime.skip=true` degrades the verdict to a WARNING — intended for a
module that *generates* code another module runs, and so has no driver on its own runtime
classpath by design. `-Dexeris.codegen.skip=true` skips it along with the rest of the pipeline.

> **What a pass does not prove:** that the registered provider supplies a particular subsystem
> name, satisfies a version range, or starts. Answering any of those means running the provider,
> which a build-time gate deliberately does not do. The check is a `META-INF/services` resource
> scan of the resolved **runtime** classpath — it loads no class of yours.

See [`adr/ADR-078-runtime-driver-gate.md`](adr/ADR-078-runtime-driver-gate.md).

### The regenerated OpenAPI no longer claims authentication (D8, ADR-079)

**What changed.** The emitted spec attached a `bearerAuth` (JWT) requirement to every operation and
declared `401` on every operation. It no longer declares either, and the `securitySchemes` block is
gone from both the per-entity and the aggregate document.

**Why, in one measurement.** The kernel — not the emitted handler — answers `401`, so the absence of
`UNAUTHORIZED` in the emitters proves nothing on its own. What proves it is the dispatch path:
`CommunityHttpRequestProcessor` reads `HttpKernelProviders.httpRoutePolicy()`, a `ScopedValue` slot
**your application** binds; with nothing bound every route resolves to `RouteRequirement.permitAll()`,
and a permit-all route is admitted *without running the `SecurityInterceptor`* — no token is read and
no identity is bound. No emitter binds `HTTP_ROUTE_POLICY`, so every generated route is permit-all
and the `401` the spec promised was unreachable.

> **Read this as a document catching up with the code, not as a capability being withdrawn.** The
> generated API was never authenticating requests. What changed is that the spec stops saying it
> was.

**Response sets are now per operation.** One set used to serve every route shape:

| Route | Now declares | Was |
|---|---|---|
| `GET` collection | `200`, `500` | `200`, `400`, `401`, `404`, `500` |
| `POST` collection | `201`, `400`, `500` | `201`, `400`, `401`, `404`, `500` |
| `GET /{id}` | `200`, `400`, `404`, `500` | + `401` |
| `PUT /{id}` unversioned | `200`, `400`, `404`, `500` | + `401` |
| `PUT /{id}` versioned | `200`, `400`, `409`, `500` | + `401`, + `404` |
| `DELETE /{id}` | `204`, `400`, `404`, `500` | + `401` |
| `POST /{id}/actions/…` | `200`, `400`, `404`, `500` (+ `409` versioned) | + `401` |

The versioned `PUT` row is the one behavioural correction beyond the removals: ADR-076's emitted
catch raises the conflict *instead of* the not-found, and the spec now says so.

**Client impact.** A client generated from the new spec stops emitting an `Authorization` header
and stops modelling a `401` branch. If you generate clients from this document and your deployment
*does* front the app with identity, keep the old header handling — the spec now under-describes
your deployment, which is the safe direction, and the accurate fix is T53 (renumbered from T51 on
2026-09-01 — the dog-food log had already minted T51 for a different finding).

**Regenerated handlers:** the tenant-guard log message changed. It told you to "install the kernel
`SecurityInterceptor` ahead of this router", which is inoperative at kernel 0.11 — the interceptor
is already inside the dispatcher and runs only for a route whose requirement is not `permitAll()`.
It now names the operative step: bind `HttpKernelProviders.HTTP_ROUTE_POLICY`, or bind
`KernelProviders.STORAGE_CONTEXT` around the dispatch. Behaviour is unchanged; only the text is.

**If you want the security block back:** that needs a route policy the application actually binds,
which needs a declaration surface and a binding seam — neither exists today. Tracked as T53, scoped
in ADR-079.

See [`adr/ADR-079-emitted-openapi-authentication-claim.md`](adr/ADR-079-emitted-openapi-authentication-claim.md).

### The regenerated OpenAPI is ~90% smaller, with the same content (D9)

**What changed.** The spec was written by a hand-configured `ObjectMapper`, which serialises every
unset field of the swagger model. One entity with two fields and one action emitted **1664 lines,
1479 of them `: null`** (`contact: null`, `externalDocs: null`, `callbacks: null`, and ~30 nulls
inside every schema). It is now written by swagger's own `Yaml31.mapper()`: **167 lines**, no nulls.

**It was also invalid, not just noisy.** `exampleSetFlag` — swagger-model bookkeeping, not an
OpenAPI field — was emitted into every media-type object, and OpenAPI 3.1's schema rejects unknown
properties there. A strict validator was entitled to fail the old document. `NON_NULL` alone would
not have fixed that; the library's own mapper does.

**Expect a large diff on first regeneration, and a style change.** Arrays now use swagger's
canonical indentation (`- url:` at the parent's indent rather than indented under it). Content is
unchanged: the emitted document is parsed back with `OpenAPIV3Parser` in the test suite, which
asserts zero messages and the same paths and schemas.

**Action required:** none, unless you diff generated files in review — in which case regenerate in
its own commit so the shrink does not bury the change you are actually reviewing.

**If you post-process the spec:** a step that relied on a key always being present (even as `null`)
now has to handle its absence, which is what every OpenAPI reader already does.

### An entity whose name the emitted app already uses now compiles (T40)

**Who is affected:** two groups, both of whom have a generated frontend that does **not** build today.

**1. An entity named after something an emitted module binds.** `Component`, `Page`, `PageRequest`,
`Observable`, `Injectable`, `HttpClient`, `Validators`, `Routes`, `Subject` and 14 more — 23 in
all, listed as `RESERVED_MODULE_IDENTIFIERS` in `src/models/model-naming.ts`. The emitted form and list
components imported the name twice, once from a framework package and once from the entity's own
service.

Such an entity's **type** is now emitted as `<Entity>Model` — `ComponentModel`,
`ComponentModelCreate`, `ComponentModelUpdate`. Everything else keeps the entity's own name: the
service class stays `ComponentService`, the files stay `component.service.ts` /
`component-form.component.ts`, and the routes stay `/components`. Only the TypeScript type is
renamed, because only the TypeScript type collided.

**2. An entity whose name ends in `Entity`.** The types module used to declare `Customer` for a
`CustomerEntity` domain while every other emitted file imported `CustomerEntity` from it. The
suffix strip is gone: the type is now declared as `CustomerEntity`, matching what the importers
already asked for.

**Action required:** none for an ordinary entity — emitted output is byte-identical, verified by
diffing a full generated app. If you are in either group above, regenerate; your app compiles for
the first time, and any hand-written code that referenced the emitted type should use the name the
types module now declares.

### `exeris-codegen-ts` drops `templatesDir` and the `handlebars` dependency (D11)

**What changed.** The package shipped three Handlebars templates (`entity.service`, `form.component`,
`list.component`), a `templatesDir` config key, and a `handlebars` runtime dependency. Nothing in
the package ever called `Handlebars.compile`: the generators build their output with string
assembly, `resolveTemplatesPath` had no callers, and the templates had drifted away from the
generators they shadowed. All of it is deleted.

**Action required:** none. Emitted output is byte-identical. If your config sets `templatesDir`, the
schema strips unknown keys, so the build keeps working — the key did nothing before and does
nothing now, with one fewer place to look for the reason.

**If you installed the package and audit your dependency tree:** `handlebars` is gone from
`exeris-codegen-ts`'s runtime dependencies.

### Generated handlers take a `MemoryAllocator`, and `create<Entity>Handler()` changed with them (T43-follow-up)

**What changed.** `<Entity>Handler`'s constructor gained a `MemoryAllocator` parameter, between the
service and the event publisher. `RuntimeComponents.create<Entity>Handler()` resolves
`KernelProviders.MEMORY_ALLOCATOR` and passes it in.

**Why.** That `ScopedValue`'s binding is established around the bootstrap callback. A request is
served on a virtual thread started with `Thread.ofVirtual().start()` — which inherits no
`ScopedValue` binding, only `StructuredTaskScope` forks do — so the previous per-request `.get()`
could only ever find it unbound. Resolving it where the binding is live is what the kernel's own
benchmark runtime does.

**Action required — only if you override the factory.** If your `RuntimeComponents` subclass
overrides `create<Entity>Handler()`, the `new <Entity>Handler(...)` call inside it needs the extra
argument; `super.create<Entity>Handler()` needs nothing. Everything else regenerates.

**Behaviour change worth knowing:** every handler now resolves the allocator at composition time,
including entities whose routes never decode a body. An unwired allocator therefore fails the
**boot**, for every entity, instead of the first body-carrying request against one entity. That is
the point of the change — a wiring fault belongs at boot with the composition on the stack — but a
boot failure that used to be a runtime 5xx can read as a regression if you do not know why.

### A repeated `@SagaStep` now contributes its steps (S2)

**What changed.** `@SagaStep` is `@Repeatable`. Repeating it on one method used to contribute
**nothing** — `javac` replaces the repeats with the synthesised container, and the processor looked
up the exact annotation type. The steps were dropped silently and the emitted flow was short.

**Action required:** none, but **check your emitted flow if you wrote repeated steps.** It gains the
steps it should always have had, which changes the generated orchestrator's transition chain. A saga
already in flight resumes against a plan whose step list has changed — kernel ADR-062 makes that a
drain-before-deploy situation, not a hot swap.

### `@Saga(version = …)` reaches the metadata (S1, processor half)

`SagaMetadata.version` reported `1` for every saga regardless of what the annotation said. It now
carries the declared value. **No emitted Java changes**: `FlowDefinitionBuilder` has no `version`
setter on kernel 0.11.0, so the generator still emits no version. If you read the metadata JSON
directly, the field stops contradicting your source.

### `@GraphEdge` now reaches the emitted graph sync, and a repeat on one field is refused (S3)

**What changed.** `GraphMetadata.edges` was a hardcoded empty list, so every generated graph-sync
artefact carried zero edges whatever the entity declared. Edges are now extracted, and
`<Entity>GraphSync` emits one `GraphEdgeDescriptor` constant and one `upsertEdge` call per declared
edge.

**Action required if you declared `@GraphEdge`:** your regenerated graph sync starts writing edges
it never wrote. That is the intended behaviour, and it is new traffic against your graph engine.

**Breaking, narrowly:** two `@GraphEdge` on one field are now a **compile error**, naming the field.
`GraphEdgeMetadata.name` is both the edge's identity and the source of the entity getter, so the
shape cannot be carried — previously it compiled and the edges vanished. Declare each edge on its
own field.

**Target labels:** an edge with `target = Foo.class` but no `targetLabel` now resolves to `Foo`
rather than the generator's `"Node"` fallback. Precedence is `targetLabel` → `target` simple name →
`targetName`.

### `-Aexeris.strict` reports eleven more annotations (C0, net of S3)

**What changed.** Strict mode audited only *extracted-but-unconsumed* attributes, driven from the
extraction call sites — so an annotation the processor never reads could not produce a warning by
any path. It now also reports every SDK annotation the processor does not read.

**Action required:** none, and only if you pass `-Aexeris.strict`, which is opt-in. Expect new
warnings for `@Derived`, `@EventHandler`, `@GraphProperty`, `@GraphQuery`, `@NavMenu`,
`@Projection`, `@QueryParam`, `@Rule`, `@SagaTransition`, `@Tab` and `@UIGroup` — eleven, each saying
whether it is reserved (design-gated, AST carriers exist) or simply unbuilt; the warning text carries
the difference.

C0 itself opened sixteen: twelve annotations plus four `@Repeatable` containers, which report under
their member's name. S3 then extracted `@GraphEdge` later in the same train, and its container went
quiet with it — so eleven is what a 0.8.0 consumer actually sees. An SDK annotation with no
registered reason still reports, with a generic one; that is what makes the audit complete rather
than a list somebody has to remember to extend.

### `exeris-codegen-ts`: three config flags now do what they said (0.8.0)

`generateDetails`, `generateEvents` and `generateSagas` all defaulted to `true` and were read by
nothing. A regenerated app now gains, per entity: a detail component plus the `{plural}/:id` and
`{plural}/:id/edit` routes the emitted list already linked to; a domain-event handler and the shared
`events/event-bus.service.ts`; and a saga state machine for any entity declaring `@Saga`. All are
exported from the app barrel. Turn any of them off with `--no-details` / `--no-events` /
`--no-sagas`, which now also do what they said.

**The emitted saga state machine carries no transport, deliberately.** It used to call
`/api/v1/sagas/<entity>/{start,cancel,retry,status}` and poll once a second. No layer of this stack
serves that contract — no emitted route, no OpenAPI path, and no per-execution handle in the kernel
flow SPI — so the machine now tracks a run and takes its updates from your code:
`begin(entityId, executionId)`, `applyStatus(snapshot)`, `failToStart` / `cancelling` / `retrying` /
`reset`.

**`$localize` is gone from all emitted output.** The emitted app declares `"polyfills": []` and no
`@angular/localize`, so an emitted symbol requiring one was an undeclared requirement on your build.
Labels are plain strings. If you were relying on extraction from generated files, that surface never
compiled.

### `exeris-codegen-ts`: `--api-base` no longer defaults to `/api` on the CLI path (#191)

The config default became `''` in 0.7.0 so the emitted client requests exactly what the emitted
router serves — but commander's own default re-applied `/api` on every CLI run, so CLI-generated
services called `/api/orders` against a router serving `/orders`. **If you generate through the CLI
and your deployment really does sit behind a gateway at `/api`, pass `--api-base /api` explicitly.**
Otherwise your regenerated services stop prefixing.

### `exeris-codegen-ts`: peer DTOs, and an opt-in test surface (T42, T2)

`--peer <name=path>` emits a self-contained `peers/<name>/` tree from a peer's contract artifact —
its own types, schemas, enum module and barrel, never merged with your app's. Additive; nothing
changes if you pass no peer.

`--tests` (off by default) emits a schema spec and a service spec per entity, plus a `test` target on
`@angular/build:unit-test`, a `tsconfig.spec.json`, and the `vitest` + `jsdom` devDependencies the
runner cannot start without. Specs are excluded from `tsconfig.app.json`, so a production build never
requires them.

---

## 0.9.0 train — regeneration deltas

### Dependency floor (unchanged)

**Neither pin moves in this train.** `exeris.sdk.version` and `exeris.kernel.version` both stay at
`0.11.0`, so the metadata schema stamp stays at `0.11.0` too and no re-run is forced by a skew
check. Everything below is a change to what the generators emit, not to what they are built against.

The 0.12 lines exist only as snapshots. When they cut finals, that bump is its own entry.

### A missing request-body decoder now answers 500 and says so (T52)

Regenerated handlers gain a private `respondDecoderUnavailable(HttpExchange, IllegalStateException)`
and call it from every body-parsing site. Before, an `IllegalStateException` out of `parseBody` — no
decoder registered for the request's content type — escaped the handler: the kernel's own fallback
answered a bare 500 with nothing logged, so a misconfigured
`HttpKernelProviders.HTTP_REQUEST_BODY_DECODER_REGISTRY` looked identical to a crash in your service.

**The status is unchanged and deliberately so** (ADR-036 §2): a missing decoder is a deployment
fault, not a client error, and is never downgraded to 400. What changes is that the response is now
the handler's own and the cause reaches your log at `ERROR`, naming the registry.

**If you only regenerate, there is nothing to do.** If you asserted on the old behaviour — an
unlogged 500 — that assertion now sees a logged one.

### A primitive `boolean` field now renders as a checkbox (T20d)

Three sites in `form-gen` tested the literal type name `java.lang.Boolean`, so a field declared
`boolean` missed all three: it rendered as a **text input**, seeded with `''`, and was then cast as
if it held a boolean. All three now go through `DslMapper.mapType(field.type).tsType`, which is the
DTO type the rest of the pipeline already agrees on.

**Regenerated forms change for every primitive-`boolean` field**: input type `checkbox`, and the
control seeds `false` rather than `''` — a checkbox has no empty state, so the seed is the fix, not
the cast. A boxed `Boolean` field is unaffected; it was already correct.

### `RuntimeComponents` gains a `decorate` hook, and refuses one combination at boot (T49, ADR-070)

`RuntimeComponents` now has `public HttpHandler decorate(HttpRouter router) { return router; }`, and
`Application` publishes `components.decorate(router)` rather than the router itself. Override it to
wrap the whole router once — a per-request scope, a tracing span, a request-id filter — instead of
copying the generated bootstrap to get at the publish site.

**A generated app that both wraps and streams now refuses to start.** The kernel resolves a stream
only through `handler instanceof HttpRouter`, so any wrapper erases the type and every `streamRoute`
registers and then never matches, silently. If your domain declares `realTimeApi` or any
`@Action(streaming = true)`, `decorate` must return the router it was given; returning anything else
throws `IllegalStateException` at boot with that explanation. This is a tooling guard over a kernel
limitation, and it goes away when a stream can be resolved through a delegable interface.

### `exeris-codegen-ts`: `GraphEdgeMetadata` and `GraphMetadata` change shape (#208)

Two exported TypeScript types are realigned onto the records they mirror. `GraphEdgeMetadata` was
`name` / `targetEntity` / `edgeType` / `direction`; it is now `name` / `targetLabel?` /
`relationType?`. `GraphMetadata` gains `properties` and `queries`, which it had been dropping
silently.

**`direction` is gone rather than renamed.** `@GraphEdge.direction` has no component on the metadata
record, so no document has ever carried it; the old schema supplied a `'OUTGOING'` default for every
edge regardless.

**No generated output changes** — no TypeScript generator reads `graphMetadata` yet. This matters
only if your own code imports those types from the package.

### `exeris-codegen-ts`: `environment.apiUrl` stops announcing `/api` (#209)

`resolveApiSettings` used `config.apiBasePath || clientConfig.baseUrl || '/api'`. `apiBasePath`
defaults to `''`, which is falsy, so the default fell through to the KERNEL strategy's `/api` and
every emitted `environment.ts` published a prefix the services beside it never requested. It now
publishes `config.apiBasePath` and nothing else.

**Nothing in the emitted tree imports `environment`**, so this changes a file that used to
contradict its siblings and now does not. If your own code reads `environment.apiUrl`, it now
matches what the generated services actually call.

Separately, `createGeneratorContext` filled `apiBasePath` with `/api` when a caller omitted it,
contradicting the schema default. **Only programmatic callers are affected** — the CLI and config
paths always pass a resolved value.

### `exeris-codegen-ts`: the app barrel gains a Stores section (#210)

`src/app/index.ts` now exports `<Entity>Store` and the `<Entity>StoreState` type for every visible
entity, gated on `generateStores` like every other section. Previously a store was reachable only by
its internal path, `./stores/<kebab>.store`.

Additive: no existing export changes name, path or kind. The `<Model>Filter` type still comes from
the service alone — the store file declares its own, and exporting both would make the name
ambiguous, which TypeScript resolves by dropping it without a diagnostic.

### `exeris-codegen-ts`: `systemFields` overrides reach the front end (#211)

Four of the ten `SystemFieldsMetadata` keys did not exist on the TypeScript side: the schema
declared `idField` and `deletedAtField` where the record serialises `primaryKeyField` and
`softDeleteTimestampField`, and declared nothing at all for `softDeleteField` / `softDeletedByField`.
Zod strips unknown keys, so all four values were discarded in silence.

**If your entity declares `@ExerisDomain(softDeleteField = …, softDeleteTimestampField = …,
softDeletedByField = …)`, the regenerated create and update schemas now omit those columns.** They
are server-owned — `KernelFlywayGenerator` maps all three — and were previously client-writable.

**`@ExerisDomain.primaryKeyField` is the exception, and it now says so.** Nothing in the pipeline
honours it: the schema emits `id UUID PRIMARY KEY` unconditionally, the repository identifies rows
through `WHERE id = ?`, and every by-id handler binds `{id}`. The emitted Angular app therefore uses
`id` for identity, as it always has in practice. Under `-Aexeris.strict` the attribute now draws the
"set but no code generator consumes it" warning, so setting it is no longer silent. Renaming a
primary key end to end is a change across the SQL, the repository and the route template together,
and is not available in this train.

### `annotation.system.*` on a field now names the column (C1)

Nine of the ten field-level system annotations — `@TenantId`, `@Version`, `@SoftDelete`,
`@SoftDeleteTimestamp`, `@SoftDeletedBy`, `@AuditCreatedAt`, `@AuditCreatedBy`, `@AuditUpdatedAt`,
`@AuditUpdatedBy` — are extracted. Until now only `@ExerisDomain`'s override attributes named those
fields, so annotating a field had no effect on emitted output.

**If your entity carries any of the nine on a field whose name is not the canonical one, the
regenerated schema and repository change with it.** `@AuditCreatedAt private Instant bornAt` on an
`audited` entity emits `born_at` where it emitted `created_at`. **That is a column rename in a
Flyway migration** — review the generated migration before applying it to a database that already
has the old column.

**They rename a column; they do not add one.** Whether the audit, soft-delete and version columns
exist is still decided by `@ExerisDomain(audited = …, softDelete = …, versioned = …)`. Annotating a
field on an entity that sets none of those flags still emits nothing.

**Two shapes are now refused at `javac`**, both previously accepted and silently mis-compiled: the
same annotation on two fields (the metadata record holds one field name per role), and an
`@ExerisDomain` override naming a different field than the annotation does.

**`@PrimaryKey` is unchanged and still has no effect.** Nothing in the pipeline honours
`primaryKeyField` — the schema emits `id UUID PRIMARY KEY`, the repository identifies rows through
`WHERE id = ?`, and every by-id handler binds `{id}`. Under `-Aexeris.strict` it still draws a
never-read warning, now with that reason.

**`-Aexeris.strict` gets 19 new attribute warnings**, one per attribute the nine annotations carry
and no generator reads (`@TenantId.autoPopulate`, `@Version.useForETag`,
`@SoftDelete.retentionPeriod`, …). Setting any of them changes no emitted output; the warning says
so rather than letting the extraction hide it.

### `dataScope = UNIVERSE` is gated on kernel 0.12, not on the 0.11 pin

**No emitted output changes.** `UNIVERSE` is still refused at the declaration and the tenant tier is
untouched. What changes is the reason five places in this repo gave for the refusal, including the
`javac` message you would actually read.

Those places said the kernel carrier had landed on the pinned `0.11.0` line and only tooling's
transcription was outstanding. That was measured on `StorageContext.sharedScopeKey()`, which does
exist on 0.11 — but an emitted RLS policy does not read an accessor, it names a PostgreSQL session
variable. Measured at `v0.11.0`:

| session variable | named in |
|---|---|
| `exeris.tenant_id` | kernel SPI (4 files), Core, TCK, Community |
| `exeris.shared_scope` | Community only — no SPI, no Core, no TCK |

The persistence driver is swappable Community/Enterprise, so a migration you commit may not depend
on one driver's internal literal. That is why the tenant policy already emitted is sound and the
shared-scope one is not yet emittable. Kernel 0.12 promotes both to
`ConnectionInterceptor.SESSION_KEY_TENANT_ID` / `SESSION_KEY_SHARED_SCOPE`, which is what makes the
transcription legitimate.

A second half is missing independently of the pin: the policy also needs a shared-scope **column**,
and no SDK carrier names one — `SystemFieldsMetadata` declares ten components and none is it.

**If you were waiting on this:** nothing you can do changes, but the wait is longer than the docs
said. Declare `dataScope = TENANT` for entities that really are partitioned by an owner.

---

## Reference

- [ADR-015 — Codegen emission strategy](adr/ADR-015-codegen-emission-strategy.md)
- ADR-015 Amendment 1 — switch to Palantir's JavaPoet fork (same document)
- [ADR-034 link stub — `KernelWebClient` facade rename](adr/ADR-034.link.md) (authoritative copy kernel-side)
- [ADR-059 link stub — `DataScope` supersedes `tenantScoped`](adr/ADR-059.link.md) (authoritative copy SDK-side)
- [ADR-070 — Open the generated composition root: `RuntimeComponents`](adr/ADR-070-generated-composition-root-seam.md)
- [ADR-075 — The generated event publisher is invoked from the generated handler](adr/ADR-075-generated-event-publisher-caller.md)
- [ADR-076 — A write against a row that is not there answers 404, not 500](adr/ADR-076-write-rejection-status.md)
- [ADR-078 — The build fails when the generated application has no driver to run on](adr/ADR-078-runtime-driver-gate.md)
- [ADR-079 — The emitted OpenAPI describes no authentication](adr/ADR-079-emitted-openapi-authentication-claim.md)
