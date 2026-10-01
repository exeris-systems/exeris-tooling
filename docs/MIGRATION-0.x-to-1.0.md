---
title: "Migration: 0.x → 1.0"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-10-02
---

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

- add `eu.exeris:exeris-sdk-composition-runtime` to the app's **compile** classpath — the emitted
  `Application` imports `CompositionConductor`, so the generated tree does not compile without it
  (Maven's default `compile` scope also puts it on the runtime classpath). *(Corrected 2026-09-26:
  this line said "runtime classpath"; the failure it prevents is a `javac` error — T30.)*
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
(As of the 0.8.0 train the tier is refused outright at the declaration.)

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

There is no way to obtain cross-tenant read-widening from this build yet. An emitted RLS policy
names a PostgreSQL session variable, and on the pinned `0.11.0` line the shared-scope one is named
only inside the Community driver — not in SPI, Core or the TCK — so there is nothing contracted to
emit against. Kernel 0.12 promotes it to a constant on `ConnectionInterceptor`.

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
inert and what keeps it so — `@Blob` on tooling alone (no extraction and no generator; kernel 0.12
boots the `storage` subsystem, opt-in through `storage.blob.provider`), `@Schedule` on the identity
a declared job runs as. See
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
`eu.exeris:exeris-kernel-community` in the open-core tree. Enterprise and third-party
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

### A repeated `@SagaStep` now contributes its steps (T56, was S2)

**What changed.** `@SagaStep` is `@Repeatable`. Repeating it on one method used to contribute
**nothing** — `javac` replaces the repeats with the synthesised container, and the processor looked
up the exact annotation type. The steps were dropped silently and the emitted flow was short.

**Action required:** none, but **check your emitted flow if you wrote repeated steps.** It gains the
steps it should always have had, which changes the generated orchestrator's transition chain. A saga
already in flight resumes against a plan whose step list has changed — kernel ADR-062 makes that a
drain-before-deploy situation, not a hot swap.

### `@Saga(version = …)` reaches the metadata (T55, was S1; processor half)

`SagaMetadata.version` reported `1` for every saga regardless of what the annotation said. It now
carries the declared value. **No emitted Java changes**: `FlowDefinitionBuilder` has no `version`
setter on kernel 0.11.0, so the generator still emits no version. If you read the metadata JSON
directly, the field stops contradicting your source.

### `@GraphEdge` now reaches the emitted graph sync, and a repeat on one field is refused (T57, was S3)

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

### `-Aexeris.strict` reports eleven more annotations (C0, net of T57)

**What changed.** Strict mode audited only *extracted-but-unconsumed* attributes, driven from the
extraction call sites — so an annotation the processor never reads could not produce a warning by
any path. It now also reports every SDK annotation the processor does not read.

**Action required:** none, and only if you pass `-Aexeris.strict`, which is opt-in. Expect new
warnings for `@Derived`, `@EventHandler`, `@GraphProperty`, `@GraphQuery`, `@NavMenu`,
`@Projection`, `@QueryParam`, `@Rule`, `@SagaTransition`, `@Tab` and `@UIGroup` — eleven, each saying
whether it is reserved (design-gated, AST carriers exist) or simply unbuilt; the warning text carries
the difference.

C0 itself opened sixteen: twelve annotations plus four `@Repeatable` containers, which report under
their member's name. T57 then extracted `@GraphEdge` later in the same train, and its container went
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

### Dependency floor (hard, pre-release)

*(Replaced 2026-09-26. This entry first said "Neither pin moves in this train"; B0 moved both.)*

The BOM moves to **`eu.exeris:exeris-sdk-*:0.12.0-SNAPSHOT`** and **`eu.exeris:exeris-kernel-*:0.12.0`**.
The kernel pin is the final `0.12.0` release on Maven Central. The SDK pin is not published yet:
build the SDK from `main`. A tooling release is not cut until the SDK pin is final too.

**The metadata schema stamp moves `0.11.0` → `0.12.0`.** SDK 0.12 moves `SchemaVersion.CURRENT`, so
a baseline stamped `0.11.0` reads as schema skew (ADR-042). Re-run codegen once after upgrading.
Otherwise the emitted metadata JSON changes only for a `UNIVERSE` entity. SDK 0.12's
`SystemFieldsMetadata.sharedScopeField` is filled only there (T29 slice B, below) and is omitted when
null, so every `GLOBAL` and `TENANT` entity's JSON is byte-identical.

**Two regeneration deltas come from the pins, and both depend on what you declare:**
- A saga with `@Saga(version = n)`, `n > 1`, now emits `DEFINITION_VERSION` and
  `builder.version(DEFINITION_VERSION)`. Your plan is registered as `(name, n)` rather than
  `(name, 1)`, so instances parked before the upgrade resume only if version 1 is still hosted, or
  through a registered migration (kernel ADR-064). Sagas without a version regenerate
  byte-identical, because the call is emitted only for a version other than 1. So an engine whose
  builder keeps the throwing `default` of `version(int)` is reached only by a saga that asked for a
  version. A declared version below 1 now fails generation.
- With `exeris.tests` on, `RecordingFlow` gains `definitionVersion` and overrides `version(int)` and
  `definitionVersion()`.

**Kernel 0.12 changes what a CLIENT-mode `HttpConfig(bindHost, port, …)` means (ADR-074):** it is no
longer dialled. Address the peer with `KernelWebClient.withAuthority("host:port")` or the
`http.client.defaultAuthority` key, or an unaddressed request is refused at its first call. The
emitted `*Client` Javadoc now shows this, and every emitted `*Client` changes by Javadoc only.

**`dataScope = UNIVERSE` is no longer refused outright.** See the T29 slice B entry below. B0 first
reworded the refusal to name the missing `@SharedScope` read. Slice B, later in the same train,
replaced the refusal with precise checks at the declaration.

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
`RuntimeLifecycle` puts `components.decorate(router)` in the handler slot rather than the router
itself. Override it to wrap the whole router once — a per-request scope, a tracing span, a
request-id filter — instead of copying the generated bootstrap to get at the publish site.

**A generated app that both wraps and streams now refuses to start.** The kernel resolves a stream
only through `handler instanceof HttpRouter`, so any wrapper erases the type and every `streamRoute`
registers and then never matches, silently. If your domain declares `realTimeApi` or any
`@Action(streaming = true)`, `decorate` must return the router it was given; returning anything else
throws `IllegalStateException` at boot with that explanation. This is a tooling guard over a kernel
limitation, and it goes away when a stream can be resolved through a delegable interface.

**Correction, 2026-09-26 — no stream route resolves on a real boot of the generated app, decorated or
not (T23, reopened).** Returning the router unchanged does not make streams work. The kernel is bound
to the forwarding handler `Application.run()` installs, never to what `decorate` returns, and it
resolves a stream only when that handler is an `HttpRouter`. A streaming request therefore falls
through to respond-once dispatch: a `realTimeApi` `GET <base>/stream` lands on the `<base>/{id}`
route with `stream` as the id and answers `400`. Regenerating does not change this, and no emitted
setting does; the fix is kernel-side (K9 in the dog-food log). The refusal above still fires; on the
generated boot it protects nothing.

**Superseded later in the same train — the refusal is gone (T23 slice B1, K9).** See "Stream routes
resolve on a real boot; `Application` binds an edge handler" and "`decorate` must resolve streams
in a streaming app; `configureRoutes` takes stream routes" below.

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

### `exeris-codegen-ts`: `environment.apiVersion` is deprecated (T38)

The emitted `environment.ts` and `environment.development.ts` still carry `apiVersion: 'v1'`, now
marked `@deprecated`. No emitted service, store or client reads it, because none of them requests a
version segment, and SDK 0.12.0 deprecates `@ExerisDomain.apiVersion` for removal. **0.10.0 stops
emitting the key.** If your own code reads `environment.apiVersion`, your editor now shows the read
as deprecated; remove it before 0.10.0, when the regenerated `environment.development.ts` stops
carrying it. `environment.ts` is written only when it is absent, so an existing app keeps the key
there until you delete it.

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

### Stream routes resolve on a real boot; `Application` binds an edge handler (T23, ADR-070)

`Application.run()` no longer binds a forwarding lambda. It binds
`RuntimeLifecycle.edgeHandler(handlerSlot)`, a named class that forwards every request to the handler
slot and implements kernel 0.12's `StreamRouteResolver`, delegating stream resolution to the slot as
well. `RuntimeLifecycle.run()` registers every generated stream route on the router it composes,
beside the respond-once routes.

**Regenerated diff:** `Application`, `RuntimeLifecycle` and `RuntimeComponents` change. Every EV1
live-view `<Entity>StreamHandler` (an entity with `realTimeApi` and a `@DomainEvent`) gains an
`EventEngine` constructor parameter. `RuntimeLifecycle` gains a static `edgeHandler(...)` and a
private nested class, `EdgeHandler`. Its constructor is still `(handlerSlot, components)`.

**If you only regenerate, streams now work.** `GET <base>/stream` opens an SSE stream instead of
answering `400`. A stream opened while the application is still composing is answered `503`, like
any other request.

**Hand-rolled launchers** (a copied `Application.run()`, a dev server, an integration harness) bind
the edge handler and share the slot:

    var handlerSlot = new AtomicReference<HttpHandler>();
    ScopedValue.where(HttpKernelProviders.HTTP_SERVER_HANDLER,
            RuntimeLifecycle.edgeHandler(handlerSlot)).call(() -> {
        KernelBootstrap.builder().selector(selector).build().boot(() ->
                new RuntimeLifecycle(handlerSlot, components).run());
        return null;
    });

A launcher that binds its own forwarding lambda still compiles and serves no stream: the kernel
resolves a stream only through a bound handler that implements `StreamRouteResolver`. A launcher that
binds what `run()` puts in the slot gets streams too.

### `decorate` must resolve streams in a streaming app; `configureRoutes` takes stream routes (K9, ADR-070)

Kernel 0.12 resolves a stream through `StreamRouteResolver` on the bound handler, which a wrapper
can implement by delegating. The generated application publishes exactly what `decorate` returns,
so if the router serves any stream route — your domain declares `realTimeApi` or any
`@Action(streaming = true)`, or your `configureRoutes` registers a `streamRoute` — and you override
`decorate`:

- **Returning the router** (the default) needs nothing.
- **A wrapper that implements `StreamRouteResolver`** resolves every stream route, and what it binds
  around the stream handler it returns is bound for that stream. Each generated stream route is
  probed through it at boot; one it answers `null` for fails the boot with `IllegalStateException`
  naming the wrapper's class and the route. This is how a tenant bound in
  `decorate` reaches a live view. The emitted `decorate` Javadoc shows the shape: delegate
  `resolveStream` to the router, and wrap the `HttpStreamHandler` of a hit. `resolveStream` itself
  runs before route authorization and outside every binding, so decide from the method and path
  alone. Bind only immutable values around a stream; the binding lives as long as the stream does.
- **Any other wrapper, a lambda included, now fails the boot** with `IllegalStateException`
  naming its class. Implement `StreamRouteResolver` on it as above. The check asks the built router
  `servesStreams()`, so it applies to an application whose only streams come from `configureRoutes`
  as much as to one with generated stream routes.

An application whose router serves no stream route accepts any wrapper, as before.

**A `streamRoute` registered in `configureRoutes` now resolves**, with its path parameters, while
`decorate` returns the router or a resolver. One registered at a method and path a generated stream
route already serves fails the boot from the kernel: `HttpRouter.Builder#streamRoute` throws
`IllegalArgumentException("a stream route is already registered for <METHOD> <path>")` as it is
registered, since generated stream routes are registered first. Serve it at another path, or
override the generated stream handler's factory instead. A stream at a concrete path under a
generated template, such as `POST /orders/42/actions/track`, is not refused: the kernel resolves an
exact path before a template, so it takes precedence for that one path and the generated route still
serves every other.

### Subscribers and saga flows are composed and started at boot (T48 slice C1, ADR-075 Amendment 2)

`RuntimeComponents` gains `create<Entity>EventSubscriber()` and `create<Flow>()`. The accessor is
the flow's class name, lower-camel. `RuntimeLifecycle.run()` builds every publisher, then calls
`initialize()` on every saga flow and `subscribe()` on every subscriber before it serves, and
`unsubscribe()` in reverse after shutdown. Install a saga or subscriber subclass by overriding its
factory, for example `createConstructionSagaFlow()` returning
`new ConstructionSaga(KernelProviders.flowEngine())`.

**A regenerated app now needs, at boot, the subsystems its domain uses:** `flow` for any `@Saga`,
and `events` for any `@DomainEvent`. Omitting one used to leave those artefacts silently unused. It
now fails the boot.

**Harnesses that compose outside a kernel boot** must now also bind `KernelProviders.EVENT_ENGINE`
and `FLOW_ENGINE`, or override every subscriber, flow and EV1 stream-handler factory. The
`RuntimeComponents` scope lists entry below has the full list.

### `Application.subsystems()` names only the subsystems the domain uses

The emitted `subsystems()` used to return `http,persistence,graph,flow,events,crypto` in every
application. It now returns `http`, `persistence` and `crypto`, plus:

| Name | Listed when |
|---|---|
| `graph` | some entity carries graph metadata (`@Graph`), so a `<Entity>GraphSync` is emitted |
| `flow` | some entity, or a standalone `@Saga`, declares a saga |
| `events` | some entity declares a `@DomainEvent` |

The order is fixed. An application with none of the three regenerates to
`return "http,persistence,crypto";`, and its kernel no longer starts a graph engine, a flow engine
with its snapshot store, or an event engine at boot. Nothing else changes: the generated code read
none of those engines, and the kernel still adds `memory` and every other dependency itself.

`exeris:verify-runtime` already required `GraphProvider`, `FlowProvider` and `EventProvider` only
under these conditions, so the driver it asks for is unchanged.

**If your own code reads an engine the domain does not** (for example `KernelProviders.graphEngine()`
in a hand-written component with no `@Graph` entity), add the name back in your `Application`
subclass:

```java
@Override
protected String subsystems() {
    return super.subsystems() + ",graph";
}
```

An override that returns a fixed string keeps working, and keeps booting exactly the names it
returns. Removing a name that generated code reads still fails the boot, in the factory that reads
it.

### Payload-bearing event publishers encode their payloads (T48 slice C1)

A publisher whose events carry `payloadFields` now takes the `EventPayloadCodecRegistry` at
construction. `RuntimeComponents` passes `KernelProviders.eventPayloadCodecRegistry().orElse(null)`
from inside the boot callback. Before, the publisher resolved the registry per publish on the
request thread, where the kernel binds none, and **every payload was published empty**. Subscribers
and SSE clients now receive the declared payload. The one-argument constructor remains, and
captures whatever registry is bound where it is called.

### `RuntimeComponents` lists the kernel scopes the generated code reads (T51)

`RuntimeComponents.COMPOSITION_SCOPES` lists what the generated factories read while composing;
`KernelBootstrap.boot(...)` binds them. `REQUEST_SCOPES` lists what the generated code reads while
serving a request: `HTTP_REQUEST_BODY_DECODER_REGISTRY`, which the kernel binds per request, and
`STORAGE_CONTEXT` for tenant-partitioned entities, which your deployment binds. Each list's Javadoc
names every reader.

This is additive, so there is nothing to do unless you compose outside a boot. If you do, bind
`COMPOSITION_SCOPES` (or override their readers) and supply `REQUEST_SCOPES` per request.

### `dataScope = UNIVERSE` is transcribed — it needs an owner and a `@SharedScope` field, and adds a V4 migration (T29 slice B)

A `UNIVERSE` declaration is no longer refused outright. It compiles when the entity names both
columns the tier needs:

    @ExerisDomain(module = "universe", path = "/presences", dataScope = ExerisDomain.DataScope.UNIVERSE)
    public class GalaxyPresence {
        @Field(label = "Owner")    @TenantId    private UUID ownerTenantId;  // writes stay pinned here
        @Field(label = "Universe") @SharedScope private UUID universeId;     // reads widen across this
    }

**A UNIVERSE row is owned** (kernel ADR-012 §4b.2). An entity with no owner field is still refused,
now with a message naming the missing field. That is the shape the 0.8.0 refusal called "a
shared-world row". Also refused:
- no `@SharedScope` field;
- a `@SharedScope` field that is not `UUID`/`String`;
- a `@SharedScope` field that is the owner field;
- a `@SharedScope` field that is `required`. The repository fills it, and `required` would make
  the handler answer 400 and the column `NOT NULL` first.

Rows that should belong to nobody (NPCs, neutral objects) need a designated system owner tenant.

**What is emitted:**
- the CREATE migration — byte-identical to what `dataScope = TENANT` emits (owner column, owner
  index, `FORCE ROW LEVEL SECURITY`, owner-pinned `<table>_tenant_policy`);
- a **new** migration `V4<nnnnnn>__shared_scope_<table>.sql` (same six digits as the CREATE): an
  index on the shared-scope column and one additive `FOR SELECT` policy,
  `<col> = NULLIF(current_setting('exeris.shared_scope', true), '')` (cast `::uuid` for a UUID
  column). It widens reads only — a partition-mate can read a row, never update or delete it;
- a repository that fills an absent shared-scope field from the bound `StorageContext` on
  save/update.

**Byte-stable** for every GLOBAL and TENANT entity. A `@SharedScope` marker on a non-UNIVERSE entity
now draws a warning and is ignored.

**Existing databases:**
- **TENANT → UNIVERSE:** the CREATE migration is unchanged and V4 applies forward. If the
  `@SharedScope` field is new, the CREATE migration gains a column — as with any added field, the
  generator emits no `ALTER`: add the column by hand (`ALTER TABLE <t> ADD COLUMN <col> UUID`) and
  `flyway repair`, or declare the field before the first migration.
- **GLOBAL → UNIVERSE:** the CREATE migration moves from tier `V1…` to `V2…` and the V1 file is
  pruned; Flyway reports V1 missing and the V2 `CREATE POLICY` fails on the absent owner column. Use
  a fresh database or a hand-written migration (same limitation as GLOBAL → TENANT).
- **UNIVERSE → TENANT:** V4 is pruned, its policy stays in the database — drop it by hand:
  `DROP POLICY IF EXISTS <table>_shared_scope_policy ON <table>;`.
- **Detached (L2) apps:** hand-write the V4 migration from the shape above.

**Runtime prerequisite (not emitted):** a request widens only if its `StorageContext` carries a
shared-scope key. That key comes from the kernel's `SecurityInterceptor` on a non-`permitAll` route
with `IdentityStorageMapping` shared-scope enforcement (claim `x-exeris-shared-scope`), or from a
deployment wrapper. Without one, reads are owner-only and new rows are owner-private, so it fails
closed.

### A write naming another tenant now answers `400`, and an update no longer writes the owner (T36)

On a tenant-partitioned entity (TENANT and UNIVERSE), while a tenant is bound:
- a `POST`, `PUT` or action whose entity names a **different** tenant is refused by the repository
  with `<Entity>TenantMismatchException` and answered **400**. It used to reach the database and,
  under RLS, come back as a **500**; on a bypassing role or a non-RLS engine it used to succeed;
- on a UNIVERSE entity, a row tagged with a different shared scope than the bound one is refused the
  same way (`<Entity>SharedScopeMismatchException`, 400).

With **no** tenant bound, nothing changes: the owner you set is written and row-level security
decides.

**The owner column is no longer in the `UPDATE … SET` list**, so an update cannot move a row to
another tenant. If you relied on `update(...)` to re-own a row, do it with a hand-written statement.
An entity with nothing else to update emits `UPDATE … SET id = id WHERE id = ?` (a field-less GLOBAL
entity emitted an invalid empty `SET` list before).

**Contract changes:** the emitted OpenAPI marks the owner (and a UNIVERSE entity's shared-scope
field) `readOnly: true` and removes them from `…CreateDto` / `…UpdateDto`. On the TypeScript side:
- An owner named by a `systemFields` block (a `@TenantId` field, or `tenantIdField`) is omitted
  from the `…Create` type and schema, as before.
- A tenant-partitioned entity with no `systemFields` block keeps `tenantId` in its `…Create` and
  `…Update` types and its create schema for one more release, marked `@deprecated`. **0.10.0
  omits it.** Stop setting it on create and update calls.
- A UNIVERSE entity's shared-scope field is omitted from the `…Create` type and schema. 0.8.0
  refuses a UNIVERSE declaration, so no app built on it has the field to set.

Sending the bound tenant anyway is harmless; sending another one is now a 400.

**Regenerated code:** one new type per tenant-partitioned entity, and two per UNIVERSE entity with a
`@SharedScope` field, in the `.repository` package. Generated repository tests replace
`saveKeepsATenantTheCallerSet` with four cases (accepted / refused / unbound / update never writes
the owner). Generated handler tests of tenant-partitioned entities now dispatch with a tenant bound
(they failed with 500 on every case before) and gain the 400 cases.

**Database role:** the remaining isolation still rests on RLS. Connect as `NOSUPERUSER NOBYPASSRLS`,
not as the table owner (or keep tables `FORCE`d, as the generated migrations do).

### Generated clients update with the verb the server serves (T58, PATCH/PUT parity)

- **TypeScript service:** `update(id, data)` now sends `PUT {base}/{id}`. It sent `PATCH`, which no
  generated server answered. `softDelete(id)` now sends `DELETE {base}/{id}`, which on a
  `@SoftDelete` entity is the archive. `restore(id)` on `<Entity>Service` and `<Entity>Store` is
  deprecated. It PATCHed a route nothing serves, so it always failed. Now it fails without a
  request: the service's Observable errors and the store sets its error and rejects, each with a
  message that says why. **0.10.0 stops emitting it.** There is no generated replacement, because
  nothing on the server un-sets the soft-delete flag, so remove the call.
- **Emitted service spec:** asserts `PUT` for update, and adds an archive case for soft-delete
  entities.
- **Java `*Client`:** `update(id, entity)` now sends `PUT {base}/{id}` through
  `KernelWebClient.put` (kernel 0.12), matching the generated router and the TS service. It sent
  `PATCH`, which no generated server answered. A serving application that added a
  `PATCH {base}/{id}` route to reach the generated server can remove it.
- A hand-written TS client that copied the generated `PATCH` should switch to `PUT`.

### `exeris-codegen-ts`: the edit route edits, and a routed form navigates

The emitted app routes `/<plural>/:id/edit` to `<Entity>FormComponent`, but the form had no input
for the `:id` parameter. On that route it opened empty in create mode, and submitting it POSTed a
new row. The regenerated `<entity>-form.component.ts` changes as follows:

- **New `id` input** (`input<string | undefined>()`), which `withComponentInputBinding()` fills from
  `:id`. When it is set, the form loads the entity through `rxResource` and `service.findById`,
  and shows a loading state and a retryable error while it does.
- **Edit mode also follows the route.** `editMode` is true when `id` is set or `mode` is `'edit'`.
  The entity it edits (`current`) is the `entity` input when a host supplies one, and otherwise
  the loaded entity. `update` sends `current.id`. Submit is disabled in edit mode until an entity
  is present, so the form never overwrites a row it has not read.
- **Save and cancel navigate when the form is the routed page.** A form counts as routed when its
  `ActivatedRoute.component` is the form itself. After a save it goes to `/<plural>/<id>`. Cancel
  goes back to that detail page when editing, and to `/<plural>` when creating. The form still
  emits `saved` and `cancelled` in every case. Embedded in a host's template, the form sees the
  host's route, so it does not navigate, and `[mode]` / `[entity]` bindings work as before.

`app.routes.ts` is unchanged, so an existing app picks this up by regenerating only the form
component. A domain entity named `ActivatedRoute` now takes the `ActivatedRouteModel` type name,
like any entity whose name the emitted app already imports.

### `exeris-codegen-ts`: a versioned entity's update carries its version

On an `@ExerisDomain(versioned = true)` entity, the generated repository reads the version in the
`PUT` body as the version the edit was loaded at, and answers `409` when the row has moved on. The
emitted front end never sent it, so the server read it as `0`: the first update of a row succeeded
and every later one was refused.

**For a versioned entity only**, regeneration changes three things. Unversioned entities emit
byte-for-byte what they did.

- **`<Entity>Update` requires the version field** (named by `systemFields.versionField`, else
  `version`), typed as the entity declares it:
  `export type OrderUpdate = Partial<OrderCreate> & { version: number | null };`.
  `OrderUpdateSchema` gains the same key through `.extend(…)`. `<Entity>Create` is unchanged, because
  the server owns the initial version. **A call site that builds an `<Entity>Update` by hand no longer
  compiles until it passes the version of the row it loaded.**
- **The edit form sends the version it loaded** with the update. The field is never a form control;
  before this, a version field renamed through `systemFields.versionField` rendered as an editable
  input.
- **A `409` on update shows a conflict message with a Reload button**, which fetches the row as it
  now stands and resets the form to it, including its current version.

### `exeris-codegen-ts`: the detail view's system panel follows `audited`, `versioned` and `systemFields`

The generated repository stamps `createdAt` / `updatedAt` on every row of an
`@ExerisDomain(audited = true)` entity (named by `systemFields.createdAtField` / `updatedAtField`,
else the defaults), whether or not the entity declares them as fields. The emitted detail view keyed
its "System Information" panel on declared field names instead, so an audited entity that inherits
its stamps showed neither.

- **The panel shows the stamps on every audited entity**, under the `systemFields` names. A stamp
  the entity does not declare is read through a typed cast (`systemInfo`), because the
  `<Entity>` interface carries only declared fields. **It shows the version on a versioned
  entity**, named by `systemFields.versionField`, else `version`. The id row is unchanged. An entity
  that is neither audited nor versioned still shows a declared `createdAt` / `updatedAt` as before,
  and a panel with the id alone when it declares neither.
- **The form, the detail field table and the default list columns share one system-field set**:
  the `systemFields` block, plus the audit stamps on `audited`, the version on `versioned`, the
  soft-delete flag on `softDelete`, and `tenantId`. An entity **without** a `systemFields` block
  keeps the conventional names (`version`, `createdAt`, `updatedAt`, `createdBy`, `updatedBy`,
  `deleted`, `deletedAt`, `tenantId`): its form, field table and list columns regenerate
  unchanged, except that the default list columns now leave out a field named `deleted`, as the form
  and the field table already did.
- **An entity with a `systemFields` block now hides what the block names, and only that.** The
  soft-delete fields it names (`softDeleteField`, `softDeleteTimestampField`, `softDeletedByField`)
  leave the form, the detail field table and the default list columns: they were rendered as
  editable controls although the create and update DTOs omit them. Conversely, a field the block
  does **not** name is an ordinary field and appears in the form, the detail view and the default
  list columns. A block the processor writes names every key but the soft-delete trio, so in
  practice this reaches a field named `deletedAt`, or `deleted` on an entity without `softDelete`.
  The form still leaves out `createdAt`, `updatedAt`, `version` and `deleted` under any name
  mapping, as the create DTO does.

### `exeris-codegen-ts`: a UUID foreign key links to its target

A `MANY_TO_ONE` relationship whose field is a `java.util.UUID` (`@Relationship UUID customerId`)
now renders as a `routerLink` to the target's detail page, `/<plural>/<id>`, in the list cell and
the detail row. The link text is the id; the target is not fetched. The plural is the route
table's own (`DslMapper.routePlural`), and a qualified `targetEntity` resolves by its simple name.

- **Only when the target is generated in the same app.** A target that is not among the loaded
  domains, or is `internalApi.hidden`, has no route, and its field renders as plain text.
- **Only when detail views are generated.** With `generateDetails: false` there is no detail route
  to link to, and every foreign key renders as plain text.
- **An empty foreign key renders as before**, as does every entity-typed relationship field and
  every `ONE_TO_ONE`, `ONE_TO_MANY` and `MANY_TO_MANY` relationship.
- **The detail component changes only for an entity with such a link.** Its `FieldDisplay`
  interface gains `link?: string`, the linked row's `DISPLAY_FIELDS` entry carries
  `link: '/<plural>'`, and the row template wraps its value switch in
  `@if (field.link && …) { <a [routerLink]> } @else { … }`. Both components already import
  `RouterModule`, so no import changes.
- **`generateList` and `generateDetail` take an optional third argument**, the loaded domains
  (default: the entity alone, which links nothing). A caller of these helpers that wants links
  passes the full domain list; the orchestrator and the generator registry already do.

### `exeris-codegen-ts`: failed requests show a message per status, and delete no longer uses `alert()`

The generated handler answers a failed request with a status and no body: `400` for malformed or
rejected input, `404` for an absent row, `409` for a stale version, `500` for anything the server
could not complete. The emitted front end showed the raw `HttpErrorResponse` text instead, and a
failed delete opened a browser `alert()`.

An app with at least one visible entity now gets one more file, **`src/app/core/http-error.ts`**,
exporting `httpErrorMessage(err, { entity?, action? })` and the `HttpErrorAction` /
`HttpErrorContext` types. It maps status `0` (server unreachable), `400`, `404`, `409` and `5xx` to
a fixed English sentence, and any other failure to a generic one; it never shows the error's own
text. A zero-entity app emits exactly what it did. Every emitted error display goes through it:

- **Detail:** the load error, and a failed delete, which now shows a `role="alert"` banner
  (`deleteError` signal, `data-testid="delete-error"`) instead of `alert()`.
- **List:** the page-load error, and a failed delete, shown the same way.
- **Form:** the by-id load error, and the submit error, which is now rendered
  (`data-testid="submit-error"`); before this the `error` signal was set and never shown. A
  versioned entity's `409` still shows the conflict message first.
- **Store:** `error()` carries the mapped message. The private `extractErrorMessage` now takes the
  action.
- **Saga:** the public `extractErrorMessage(err)` returns the mapped message, so a string fed to
  `failToStart` reads the same way.

A domain entity named `HttpErrorResponse` or `HttpErrorAction` now takes the `…Model` type name.
Code that matched on the old raw messages (`'Failed to load data'`, `'An error occurred'`,
`'An unknown error occurred'`) has to match on the new sentences or, better, on the status.

### `exeris-codegen-ts`: SSE stream clients for every stream route the backend serves

The generated kernel application serves `GET {base}/stream` for an `@ExerisDomain(realTimeApi = true)`
entity and `POST {base}/{id}/actions/{kebab}` as a stream for each `@Action(streaming = true)`. The
emitted front end now has a client for each, whenever services are generated (`generateServices`,
the default) and the entity is `GLOBAL`. An app with neither emits exactly what it did.

- **No stream client for a tenant-partitioned entity** (`dataScope` `TENANT` or `UNIVERSE`, or the
  deprecated `tenantScoped: true`). The generated stream routes carry no tenant guard and the
  live-view producer subscribes to the event bus unfiltered, so such a stream would deliver every
  tenant's events to every subscriber. The clients are emitted once the server guards the route.
  A streaming action on such an entity therefore has no front-end entry point at all: it has no
  stream client, and no service method either (below).

- **`src/app/services/<entity>.stream.ts`** — `<Entity>StreamClient` for a `realTimeApi` entity,
  plus `services/streams.index.ts`. `stream()` returns `Observable<MessageEvent<string>>` over a
  native `EventSource` (`withCredentials: true`). Every frame the handler sends is named, so the
  client registers one listener per `@DomainEvent` name (`STREAM_EVENT_TYPES`; the entity name +
  `Event` for a blank name); `type` is the event name and `data` the payload JSON. The
  `keep-alive` heartbeat is not delivered, so an entity with no `@DomainEvent` gets a stream that
  emits nothing. A server-side close is followed by the browser's own reconnect; the Observable
  errors only when the `EventSource` gives up (`readyState` `CLOSED`).
- **`src/app/services/<entity>.action-streams.ts`** — one `<Entity><Action>StreamClient` per
  streaming action, plus `services/stream-types.ts` (the shared `StreamFrame { event, data }`) and
  `services/action-streams.index.ts`. `stream(id)` opens the route with `fetch` (`POST`,
  `credentials: 'include'`, no body) and emits each parsed frame, the heartbeat included; a field
  value loses exactly one leading space, so payload whitespace is kept.
- **The app barrel** (`src/app/index.ts`) gains an `// SSE stream clients` section re-exporting
  whichever of the two sub-barrels exist.
- **A streaming action loses its service method.** The kernel serves that path as a stream only,
  so `<Entity>Service.<action>(id, …)` is no longer emitted for it; call the action stream client
  instead. Non-streaming actions are unchanged.

### `exeris-codegen-ts`: `FieldMetadata` and `UIMetadata` declare what the processor writes

Two exported TypeScript types are realigned onto the SDK records they mirror.

- `FieldMetadata` loses `inList`, `inDetail`, `order`, `ui` and `dependencies`. None is a component
  of the SDK `FieldMetadata` record, so no metadata document has carried one; the schema supplied
  `inList: true` / `inDetail: true` defaults that no generator read. The form no longer sorts on
  `order`, which was always absent, so fields stay in declaration order as before; a computed field's
  dependencies are `computedFrom` alone.
- `UIMetadata` loses `listColumns`, `searchFields`, `filterFields` and `formLayout`, which no record
  declares, and gains the seven `@UI` view switches the processor writes: `listView`, `detailView`,
  `createForm`, `editForm`, `searchable`, `filterable`, `exportable` (all optional). The list page's
  columns are the first five visible non-system fields, which is what every real build already got.

**No generated output changes.** No generator reads the new `UIMetadata` keys yet — that is a known
gap, recorded in `UI_CONTRACT_COVERAGE`. This matters only if your own code builds metadata by hand
with the removed keys, or imports these types from the package: Zod now strips the removed keys.

### `@View`: wrong attributes on STATIC/NONE bindings are diagnosed

`@Bind(source = STATIC)` or `NONE` carrying `ref`, `path`, `expression` or `language` now produces a
compiler warning at the `@Bind` and a wrong-attribute comment in the emitted template. It used to
produce a `TODO(@View G1)` or `TODO(@View G2)` marker. Authored text belongs in `@Block(props)`; to
bind data use `source = ENTITY`, `PROJECTION` or `ACTION`. `TODO(@View G2)` is no longer emitted at
all.

### `exeris-codegen-ts`: an app without a backend no longer gets backend wiring

An app whose metadata declares no visible entity — only `@View` pages with authored (`STATIC` /
`NONE`) content, or nothing at all — has no API to call, and its scaffold no longer pretends it has
one. The scaffold now wires a backend piece only when the app has an API: a visible entity, or an
emitted file that imports `@angular/common/http`. **An app with a visible entity emits exactly what
it did**, whichever client emitters are on.

A regenerated backend-less app no longer gets:

- `provideHttpClient()` and its import in `src/app/app.config.ts`; the router and
  `provideZonelessChangeDetection()` stay;
- `proxy.conf.json`, and `--proxy-config proxy.conf.json` in the `start` script (now `ng serve`);
- `apiUrl` and the deprecated `apiVersion` in `src/environments/environment*.ts`, which keep
  `production`;
- `zod`, `@angular/cdk` and `@angular/forms` in `package.json`, each of which returns as soon as an
  emitted file imports it (an `@ExerisEnum` emitted with its Zod schema keeps `zod`, and so does a
  peer contract);
- the `types/` and `schemas/` barrels and the empty enum module; with an enum, `types/enums.ts`,
  `types/index.ts` and an app barrel exporting only the enums are emitted, and no empty section;
- `src/app/index.ts`, when there is neither an entity nor an enum to re-export;
- a `redirectTo: ''` route pointing at itself, when there is no entity and no `PAGE` view.

`@angular/common`, `@angular/router`, `rxjs` (a peer dependency of `@angular/core`), `tslib` and
the ui-kit stay. The CLI does not replace an existing file without `--overwrite`, so an
existing app keeps its `package.json`, `app.config.ts` and environments until you regenerate with
it. Files the run no longer produces — `proxy.conf.json` and the empty barrels — are pruned when the
output tree carries the generation manifest from an earlier run. If your own code uses `HttpClient`
in a backend-less app, add `provideHttpClient()` to `app.config.ts` yourself.

### `exeris-codegen-ts`: the app depends on `@exeris/ui-kit` from the public npm registry

**Breaking:** an emitted dependency is renamed and two emitted files are no longer produced.

The UI kit moved from GitHub Packages (`@exeris-systems/ui-kit` 0.1.x) to the public npm registry as
`@exeris/ui-kit` 0.2.0, which supports Tailwind CSS v4 only and no longer exports the v3
`tailwind.preset.js`. The regenerated app changes as follows:

- **`package.json`:** the dependency is `"@exeris/ui-kit": "^0.2.0"` in place of
  `"@exeris-systems/ui-kit": "^0.1.0"`.
- **`src/styles.css`:** imports `@exeris/ui-kit/theme` in place of `@exeris-systems/ui-kit/theme`,
  still after `@import "tailwindcss"`. `angular.json` keeps listing only `src/styles.css`; the
  kit's CSS belongs in that global stylesheet, because a kit file listed in the `styles` array is
  compiled without Tailwind and fails.
- **`.npmrc` is no longer emitted.** It only pointed the `@exeris-systems` scope at GitHub Packages.
  Installing the app needs no GitHub token and no `read:packages` scope, locally or in CI.
- **`tailwind.config.js` is no longer emitted.** Tailwind v4 never reads it, and the preset it
  imported is gone from the kit: loading it fails with `ERR_PACKAGE_PATH_NOT_EXPORTED`. The v4
  setup is `.postcssrc.json` (`@tailwindcss/postcss`) plus the imports in `src/styles.css`, both
  unchanged.

On regeneration, an output tree that carries the generation manifest from an earlier run has its
`.npmrc` and `tailwind.config.js` **deleted**, as is every file a run no longer produces. The
pruner does not look at content, so a line you added to that `.npmrc` (another registry, a token
reference) goes with it; move it to your user `~/.npmrc` or re-create the project file after
regenerating. A tree without a manifest keeps both files: delete `tailwind.config.js`, and drop
the `@exeris-systems:registry` line from `.npmrc`. `package.json` and `styles.css` are replaced only
with `--overwrite`, as before; without it, rename the dependency and the import by hand. A CI
step that appended a GitHub Packages token for the install can be removed.

### Compile-classpath requirements are named in the emitted Javadoc (T30)

The regenerated `Application.java` Javadoc separates compile requirements from runtime ones. If an
entity has a `List<X>` field, its repository imports Jackson 3. Declare
`tools.jackson.core:jackson-databind` at compile scope: the Community driver brings it only
transitively, and a runtime-scoped driver does not reach `javac`. Such a repository's Javadoc says
so, and the `Application` Javadoc names Jackson 3 only when a repository in the tree imports it. No
code change.

### Generation no longer depends on the JVM locale

Tables, columns, OpenAPI file names and DSL identifiers are lower-cased with `Locale.ROOT`. A build
that ran under a locale such as `tr-TR` and committed `ınvoices`-style names will regenerate them
with a plain `i`, new migration file names included. Rename the applied migrations or keep the old
output. A route for an entity without a declared `path` comes from the SDK's `effectivePath()`,
which SDK 0.12 also lower-cases with `Locale.ROOT`.

### `@ExerisDomain.apiVersion` no longer reaches the metadata (T38)

SDK 0.12.0 deprecates the attribute for removal at 1.0.0, with no replacement: no emitted route,
document or client ever carried a version segment. The processor no longer reads it, and SDK 0.12.0
fills no default for it, so the metadata JSON carries no `apiVersion`. No generator read it, so no
emitted file changes.

A source that still sets it compiles with javac's `[removal]` warning, and under `-Aexeris.strict`
with the inert-attribute warning as well. Delete it. A versioned route is spelled in `path`
(`path = "/v2/orders"`).

### `@RouteAccess(PUBLIC)` beside `permissions` is a compile error

SDK 0.12.0 adds `@RouteAccess(Level.PUBLIC | AUTHENTICATED)` on an entity or on an `@Action` method;
the method-level declaration overrides the entity's. A public route runs with no principal bound, so
a permission on it can never be satisfied. The processor now refuses the pair with an `[Exeris]`
error in three shapes:

- `@RouteAccess(PUBLIC)` on the entity and a non-empty `@ExerisDomain(permissions = …)`;
- `@RouteAccess(PUBLIC)` on an action method and a non-empty `@Action(permissions = …)`;
- a non-empty `@Action(permissions = …)` on a method with no `@RouteAccess` of its own, under an
  entity declared `@RouteAccess(PUBLIC)`. The method inherits PUBLIC.

To fix one, drop the permissions, or declare `@RouteAccess(AUTHENTICATED)` on that element. In the
third shape, declare it on the method. A source that never uses `@RouteAccess` is unaffected.

The annotation is not compiled into a route policy yet. Every generated route is registered as
if it were absent, the metadata JSON carries no `routeAccess`, and no emitted file changes. Under
`-Aexeris.strict` each occurrence, on an entity or a method, draws the inert-annotation warning.
The transcription onto the kernel's `HttpRoutePolicy` is T53, tracked in `ROADMAP.md`.

### A `@GraphEdge` beside a hand-written `@GraphEdges` is a compile error

A field may carry one graph edge. The processor already refused two `@GraphEdge` on one field, but
it counted the direct annotation and a hand-written `@GraphEdges` container separately, so

```java
@GraphEdge(type = "DIRECT")
@GraphEdges({@GraphEdge(type = "CONTAINED")})
private UUID mixed;
```

compiled, and generation then failed with `Duplicate edge names`. The two are now counted together,
and this declaration draws the same `[Exeris] @GraphEdge is declared 2 times on field 'mixed'` error
at the field. To fix it, declare each edge on its own field.

### `-Aexeris.strict` now reports four `@Saga` / `@SagaStep` attributes

Only if you pass `-Aexeris.strict`. A default build is unchanged and stays silent, and nothing about
what the compiler produces changes either way.

The attribute audit is driven by a per-annotation call site, and `@Saga` and `@SagaStep` had none, so
a strict build said nothing about any of their attributes. They are now audited on both paths a saga
takes: a standalone `@Saga` class and an `@ExerisDomain` entity carrying `@Saga`. Four attributes are
registered as read by no generator:

- `@Saga.description` and `@SagaStep.description`: neither reaches emitted code.
- `@SagaStep.service` and `@SagaStep.command`: the emitted step method is a skeleton that logs and
  returns `CONTINUE` for you to override. It does not dispatch the named command to the named
  service. **Both are required by the SDK, so expect two warnings per saga step.** You cannot remove
  them from your source to quiet the warning. The warning tells you the step does nothing until you
  override it.

`@SagaStep.parallel` and `@SagaStep.timeout` are also read by no generator, but they are left
unreported on purpose: the kernel's flow model has no way to express concurrent steps or a per-step
deadline, so the linear chain the generator emits is the only correct output. The flow-level
`@Saga.timeout`, `maxRetries` and `version` are honoured and do not warn, and neither does
`@SagaStep.compensation`, which adds the step's compensation method.

### `-Aexeris.strict` now reports `@UI`, `@Tab`, `@UIGroup` and the `@Field` attributes nothing honours

Only if you pass `-Aexeris.strict`. A default build is unchanged and stays silent, and no emitted
file changes. A strict build that also passes `-Werror` now **fails** on each of the following until
the source changes.

- **`@UI` on a field** — warned as never read. The processor reads `@UI` on a type only, so a
  field-level `@UI` reaches no metadata and the field renders from `@Field` alone. A field's
  presentation facet arrives with `@View`'s field facet.
- **`@UI` on a type** — warned once as consumed by no generator. `listView`, `detailView`,
  `createForm`, `editForm`, `searchable`, `filterable` and `exportable` reach `UIMetadata`, but no
  emitter reads them, and the other attributes are not extracted. Every entity gets the same
  output whatever `@UI` says.
- **`@Tab`, `@UIGroup`** — warned as never read, with a reason that no longer claims `@UI` is
  extracted per field.
- **`@Field` attributes** — one warning per attribute set:
  - not extracted at all: `labelKey`, `descriptionKey`, `inList`, `inDetail`, `order`, `ui`,
    `validation`, `defaultValue`, `cssClass`, `group`, `sensitive`, `encrypted`, `maskPattern`,
    `writeOnly`, `compositeUnique`;
  - extracted but read by no generator: `indexed` (the schema indexes a `searchable`, `filterable`
    or `unique` field and no other) and `inUpdate` (the update DTO, the OpenAPI schema and the edit
    form carry the field anyway).

**What to do.** Remove the attribute or annotation, or keep it knowing it has no effect in this
tooling version. Two cases have a working alternative:

- `@Field(validation = @Validation(…))`: move the rules to a standalone `@Validation` on the same
  field. That one is read and reaches the emitted constraints.
- `@Field(indexed = true)`: mark the field `searchable`, `filterable` or `unique` if one of those
  is true of it.

`sensitive`, `encrypted`, `maskPattern` and `writeOnly` deserve a look before they are dismissed. The
field is stored, returned and rendered exactly as an unmarked field, so data protection that relies
on them does not exist in generated code. A field-level `@UI` can stay in place knowing it has no
effect until the `@View` field facet lands; the same holds for the other presentation attributes
(`inList`, `inDetail`, `order`, `cssClass`, `group`, `@Tab`, `@UIGroup`), which nothing reads in
this tooling version.

### SDK 0.12.0 needs no source change for S6

`SystemFieldsMetadata`, `DomainMetadata` and `ActionMetadata` keep their 0.11.0 constructors. Code
that builds `SystemFieldsMetadata` positionally may switch to `SystemFieldsMetadata.builder()`.

### Default table names and Angular routes follow the English plural (T6)

A table used to be the snake-cased entity name plus "s". It now comes from the SDK's
`DomainMetadata.effectiveTableName()`, the snake-cased `pluralName()`:

| Entity name ends in | Rule | Example (old → new) |
|---|---|---|
| `s`, `x`, `z`, `ch`, `sh` | `+es` | `boxs` → `boxes`, `statuss` → `statuses`, `addresss` → `addresses`, `branchs` → `branches` |
| consonant + `y` | `y` → `ies` | `colonys` → `colonies`, `technologys` → `technologies`, `reassemblys` → `reassemblies` |
| anything else, vowel + `y` included | `+s` | `orders`, `construction_orders`, `keys` — unchanged |

Every entity whose plural is a plain "s" keeps its table and its migration file byte-for-byte. For
an entity that moves, the repository's SQL, the `CREATE TABLE`, the migration file name
(`V…__create_colonies.sql`; the version number does not change), the foreign keys that point at
it, a UNIVERSE entity's shared-scope migration and a graph-sync node descriptor all name the new
table. On an existing database that table does not exist yet.

The processor warns once for each such entity, with the value that keeps the old name:

    warning: [Exeris] Colony: default table changes from 'colonys' to 'colonies'; set @ExerisDomain(tableName = "colonys") to keep the existing table and migration

To keep the existing table and the migration that created it, set the attribute:

    @ExerisDomain(module = "empire", path = "/colonies", tableName = "colonys")

The processor now reads `@ExerisDomain.tableName` (SDK 0.12.0) into `DomainMetadata.tableName`,
and an entity that sets it draws no warning. The value is trimmed and lower-cased. It is also how
an irregular or pre-existing table is named (`tableName = "people"`). A blank value derives the
name.

The warning is not behind a flag, and it also fires in a project that never had the old table, for
example for a new `Box` or `Status` entity. A build compiled with `-Werror` fails on it. Set
`tableName` to either name to silence it (`"boxes"` takes the new one). 0.10.0 removes the warning.

Taking the new name on an existing database is a schema change of its own. The regenerated
`CREATE` migration keeps its version but changes its description and its content, and Flyway's
validation rejects both for an applied migration. It takes renaming the table
(`ALTER TABLE colonys RENAME TO colonies`) and repairing the schema history (`flyway repair`), so
keep the old name through `tableName` unless the database is disposable.

`exeris-codegen-ts` takes the same plural (`DslMapper.pluralName`). The Angular route segments,
sidebar links, default redirect, nav labels and list-page titles move: `colonys` → `colonies`,
`address` → `addresses`, `news` → `newses`. A name that is already plural gets a second ending,
because the rule is the SDK's and knows no irregular nouns. Bookmarks to the old client-side
routes stop resolving. Server routes do not move, because `@ExerisDomain.path` is required; only
a service or stream client built from metadata with no `path` falls back to the derived segment,
and that fallback now matches the SDK's `effectivePath()`.

### Kernel 0.12: a generated client verifies the TLS server it calls (`crypto.tls.client.trustFile`)

No emitted file changes. On kernel 0.12 the Community TLS client verifies the server's certificate
chain against the PEM file named by `crypto.tls.client.trustFile`, else against OpenSSL's default
trust, and checks the certificate's subject alternative names against the host it dialled (kernel
ADR-074 Amendment A1). A generated `*Client` whose peer presents a self-signed or private-CA
certificate now fails the handshake with `EX-NET-2001` before any request byte is sent.

**What to do:** set `crypto.tls.client.trustFile` to a PEM file holding that CA. A file that is not
readable is refused with `EX-NET-2002`. No setting keeps TLS and skips verification. Generated code
sets neither key, so this is deployment configuration only.

### Kernel 0.12: a public request that reaches persistence without a tenant is recorded (`UnscopedRequestSession`)

No emitted file changes, and nothing starts failing. Kernel 0.12 emits the JFR event
`eu.exeris.kernel.security.UnscopedRequestSession` (fields `method`, `path`, `readOnly`) once for
every `permitAll()` request whose persistence session was opened for a storage context that declares
no tenant. The event type is enabled by default.

The emitted application binds no `HttpRoutePolicy` (ADR-079), so the kernel treats every generated
route as `permitAll()`. A tenant-scoped handler refuses a request with no `STORAGE_CONTEXT` before it
reaches the repository, so it records nothing. A `GLOBAL` entity's handler does not refuse, so
**every request to a `GLOBAL` entity's route that reaches its repository records one event.** Stream
routes and `LONG_RUNNING` routes are not covered.

**To stop a route being reported**, do one of:
- bind an `HttpRoutePolicy` (`HttpKernelProviders.HTTP_ROUTE_POLICY`) that requires
  `authenticated()` for it;
- bind a `KernelProviders.STORAGE_CONTEXT` that carries a tenant around its handler;
- disable the event type in your JFR settings, which hides it for every route.

### Kernel 0.12: no stream route is served over HTTP/2

No emitted file changes. The kernel resolves a stream route only on its HTTP/1.1 path; an `h2`
request to a stream route is served respond-once instead. The Community default for
`http.maxVersion` is `HTTP_2`, so a browser `EventSource` against TLS that the kernel terminates
negotiates `h2` and does not stream. This reaches every generated stream route: a `realTimeApi`
`GET <base>/stream` and every `@Action(streaming = true)` route.

**What to do:** terminate TLS upstream of the kernel, or, without TLS, set
`http.maxVersion=HTTP_1_1`. Under kernel-terminated TLS the ALPN selection does not honour
`http.maxVersion` (exeris-systems/exeris-kernel#533), so that key is not a workaround there. The
Enterprise HTTP engine serves no stream route at all.

### Kernel 0.12: `StreamMatch` moved from `HttpRouter` into the SPI

Kernel 0.11's nested `eu.exeris.kernel.core.http.routing.HttpRouter.StreamMatch` is now the
top-level record `eu.exeris.kernel.spi.http.StreamMatch` (`preview`, `@since 0.12`), and
`HttpRouter#resolveStream(HttpMethod, String)` returns it. Generated code names neither, so no
emitted file changes.

**What to do:** hand-written code that names `HttpRouter.StreamMatch` changes its import to
`eu.exeris.kernel.spi.http.StreamMatch`. Code that calls `HttpRouter#resolveStream` without naming
the type compiles unchanged, but must be recompiled against kernel 0.12: the method's return type
changed, so a class compiled against 0.11 fails to link.

### An application can build on a published parent and starter (ADR-091)

0.9.0 publishes three POMs for an application's build: `eu.exeris.tooling:exeris-app-bom`,
`exeris-app-parent` and `exeris-app-starter` (type `pom`). The parent runs the processor, binds
`exeris:generate`, `exeris:verify-capabilities` and `exeris:verify-runtime`, and puts the generated
migrations and OpenAPI on the classpath. The starter carries the dependencies the generated code
imports, plus the community driver at runtime. The BOM names the kernel and SDK versions this release
was tested against.

**Opt-in; nothing changes if you keep your own build.** Generated code is unchanged, and no generator
writes a build file. To adopt it, replace your hand-written dependencies and plugin configuration with
the parent and the starter, or import the BOM and copy the build block (README, "Quick start"). Keep
your JDBC driver and logging backend: the starter declares neither. The parent carries Exeris's own
`url`, `licenses`, `developers`, `scm` and `description`, so declare yours, or blank them as the README
shows, or your effective POM inherits them.

**A hand-written build should check one thing it may be missing.** The plugin registers
`src/main/generated/java` as a source root only, so the generated `db/migration/*.sql` and
`openapi/*.yaml` are not on your classpath unless you declare a `<resource>` for them. The parent
does; the README shows the entry.

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
- [ADR-091 — Publish an opt-in application starter, so a consumer does not hand-write its build](adr/ADR-091-opt-in-application-starter.md)
