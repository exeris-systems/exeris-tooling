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

---

## 0.7.0 train — regeneration deltas

Same framing as the 0.6.0 section: annotation contracts and `DomainMetadata` are unchanged;
what changes is what the emitters produce from them.

### Dependency floor (hard)

The BOM moves to released **`eu.exeris:exeris-sdk-*:0.9.0` and `eu.exeris:exeris-kernel-*:0.10.2`**.

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

What is emitted so far: `<Entity>HandlerTest` plus one shared
`<basePackage>.testsupport.RecordingHttpExchange`. The test covers every status the handler owes the
router on the bodyless CRUD routes, and the guard paths of `handleCreate` / `handleUpdate` — a
missing body, and a malformed path id — each also asserting the service was never reached. The
`@Validation` rejection paths are not emitted yet: they require a decoded request body, and
therefore kernel provider slots a generated test does not currently bind. Nothing is emitted, and
nothing changes, unless you set the flag.

If you had already turned the flag on, expect the regenerated `<Entity>HandlerTest` to gain three
test methods and the emitted `RecordingHttpExchange` to gain `post(...)` / `put(...)` factories.

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

---

## Reference

- [ADR-015 — Codegen emission strategy](adr/ADR-015-codegen-emission-strategy.md)
- ADR-015 Amendment 1 — switch to Palantir's JavaPoet fork (same document)
- [ADR-034 link stub — `KernelWebClient` facade rename](adr/ADR-034.link.md) (authoritative copy kernel-side)
