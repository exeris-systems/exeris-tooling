---
title: "ADR-058 — Generated tests: emission channel and dependency contract"
type: adr
visibility: public
owning-repo: exeris-tooling
status: active
slug: adr/ADR-058
---

# ADR-058 — Generated tests: emission channel and dependency contract

- **Status:** ACCEPTED (2026-07-31) · amended 2026-11-07 (Amendment 1 — an opt-in integration-test channel that boots the generated application on H2)
- **Repo:** `exeris-tooling`
- **Scope:** tooling / codegen pipeline
- **Visibility:** public
- **Milestone:** 0.7.0 (backlog item **T2**, slice a)
- **Supersedes / superseded by:** —

## Context

The pipeline emits handlers, services, repositories, clients, sagas, events, Flyway migrations and
an OpenAPI document — and **zero tests**. T2 has carried that gap since the completeness audit.

Emitting tests is not simply "one more `*Generator`". It changes the pipeline's shape in two ways
that ADR-015 never had to answer, which is what makes this ADR-triggering rather than a refactor:

1. **A second output root.** Every artefact so far lands in one tree (`src/main/generated/java`)
   owned by one `OutputWriter` and one T13 manifest. A test emitted there would be compiled into
   the application artefact and would put JUnit on its *runtime* classpath.
2. **A dependency contract on the consumer's build.** Tooling emits no `pom.xml`. Whatever a
   generated test imports becomes a hard requirement on every downstream project that turns the
   feature on — a decision about *their* build, made in *our* emitter.

A third question is downstream of those two: what a generated test may legitimately assert without
infrastructure. The generated repository talks to a `TransactionalExecutor`, and faking that means
faking a `PersistenceConnection` / `PersistenceStatement` / `QueryResult` / `RowCursor` stack.

## Decision

### 1. Generated tests are opt-in, and land in their own output root

A new pipeline entry point `CodegenPipeline.runTests(metadataDir, testOutputDir, basePackage)`,
driven by `exeris:generate`'s `exeris.tests` flag (default `false`), writing to
`src/test/generated/java` (`exeris.testOutputDir`). The mojo registers it via
`project.addTestCompileSourceRoot(...)`, never `addCompileSourceRoot(...)`.

A **separate entry point** rather than a sixth parameter on `run(...)`, matching the precedent set
by `validateCapabilities` and `verifyCapTierWall`: each public entry point stays self-contained and
separately callable. The metadata is loaded twice when both run; the same trade-off was accepted
and documented for `verifyCapTierWall`.

A **separate root** with its own `OutputWriter` means its own T13 manifest, so pruning a removed
entity's test is scoped to the test tree and can never reach main sources.

Zero domains is a no-op that writes and prunes nothing — the T18 masked-compile-failure reasoning
applies unchanged: empty metadata must never delete a committed tree.

### 2. The dependency contract is JUnit 5 + AssertJ, and nothing else

No mocking framework. Doubles are **emitted**, not mocked:

- `RecordingHttpExchange` (project-wide, `<basePackage>.testsupport`) implements `HttpExchange` —
  two abstract methods — and overrides **every** `respond(...)` overload including the interface's
  defaults, because those build an `HttpResponse` through the codec path and would need an encoder
  bound at runtime.
- The per-entity service double **subclasses the generated service** (`super((Repository) null)`).
  This is only possible because generated classes are `public` and non-final and the generated
  service constructor merely assigns its repository — properties this ADR now makes load-bearing.

Rationale: `-Dexeris.tests=true` should cost a consumer two test-scope dependencies they almost
certainly already have. Mockito would be a third, imposed by us, to save emitting ~40 lines.

### 3. Slice a covers the handler's bodyless routes

`<Entity>HandlerTest` covers `handleGetAll`, `handleGetById` (found / absent / malformed id) and
`handleDelete` — asserting the **status**, which is the handler's actual contract with the router.

`handleCreate` / `handleUpdate` are out of this slice: they read the request body as a
`LoanedBuffer`, so they need a heap-backed buffer double on top of the exchange double, and they
carry the `@Validation` rejection paths with them. Repository tests (a fake persistence stack) and
service-delegation tests follow the same "own slice" reasoning.

### 4. The gate runs the generated tests, it does not merely compile them

`GeneratedTestsE2ETest` walks annotated source → processor → `run` → `runTests` → `javac` over both
trees → the JUnit Platform launcher, and asserts the emitted tests **pass** (and that a non-zero
number of them executed). A test emitter whose output is only compiled is the inert-output failure
mode this repo rejects elsewhere: a wrong expected status would ship silently.

## Consequences

- Turning the flag on requires `junit-jupiter` and `assertj-core` in the consumer's `test` scope.
  Documented in MIGRATION; not enforceable from here, since tooling owns no `pom.xml`.
- Generated tests are committed like the rest of the L1 output and regenerate on demand. They are
  covered by the L2 `exeris:detach` story exactly as main sources are.
- The emitted doubles rely on generated classes staying `public`, non-final, with assignment-only
  constructors. That is now a contract, not an accident — a generator that finalises a class or
  adds a `requireNonNull` to a constructor breaks the test emitter, and the e2e gate says so.
- Emitter parity (hard-constraint #5): the FE half of T2 (`*.service.spec.ts` +
  `*.schema.spec.ts`) is a separate slice, and it carries a decision this ADR does not make — the
  emitted Angular app declares `"test": "ng test"` but ships **no** test runner or test
  dependencies, so specs alone would be unrunnable. That slice must wire the runner
  (`@angular/build:unit-test` + Vitest, founder-ruled 2026-07-31) as part of emitting the specs.

## What is NOT in scope

- Body-carrying handler routes, repository tests, service-delegation tests, saga step-wiring tests.
- Any assertion about the *database*: nothing here starts a persistence engine.
- The FE spec slice (above).

> **Note (2026-08-02, slice c).** Service-delegation tests shipped, and they cost nothing this ADR
> had reserved for them. The generated repository is `public`, non-final, and its constructor only
> assigns the `TransactionalExecutor`, so the same emitted-double pattern reaches it: `super(null)`
> and no persistence engine is involved — the "no database" exclusion above is *why* this slice is
> cheap, not a reason it was blocked. What the tests pin is the part of a delegation layer that is
> hand-wired rather than mechanical: the `delete` → `deleteById` rename, that `save`/`update` return
> the **repository's** result and not their own argument (the repository fills in a generated id
> before returning, so the wrong one compiles and silently yields a null-id entity), and that each
> T8 finder reaches the same-named repository finder. Emitting both the service's finders and the
> double's overrides from one `finderSpecs` source is load-bearing: a double that overrides a method
> the service never calls is how this test would quietly stop testing anything.

> **Note (2026-08-02, slice d).** Repository tests shipped, and the scope line above — "any
> assertion about the *database*" — turned out to be the wrong place to draw it. Nothing here
> starts a persistence engine either: a second shared double, `RecordingPersistence`, implements
> the five persistence-SPI roles a repository walks through (`TransactionalExecutor` →
> `PersistenceConnection` → `PersistenceStatement` → `QueryResult` → `RowCursor`) **in one class**,
> because collapsing them is what lets a test replay what the repository *bound* back as what it
> *reads*.
>
> The design ruling worth recording is what these tests **must not** assert: the emitted SQL. The
> test and the repository are generated from one `DomainMetadata`, so a changed column list changes
> both — a SQL-text assertion could never fail, and would be a change-detector for a change that
> cannot happen. What is genuinely at risk is the *alignment* of two independent emitter paths:
> `emitInsertBinds` numbers the INSERT's parameters, `emitReadCol` numbers `mapRow`'s cursor reads,
> each with its own counter over the shared column layout. So the central test is a save/load
> **round-trip**, which asserts runtime behaviour rather than emitted text: an index that drifts on
> one side lands on a neighbour's value, and a value bound as one type but read as another fails the
> double's cast. Verified by perturbation — shifting `mapRow`'s indices by one makes the generated
> test fail, which is the property that distinguishes this from a tautology.
>
> This generalises the slice-c lesson: a generated test earns its place only where the emitter has
> **two paths that must agree**. Where there is one path, the emitted artefact and the emitted test
> say the same thing twice.
>
> The gate runs the round-trip for two fixtures, not one: a plain entity and a second carrying every
> system-column flag (`tenantScoped` / `audited` / `softDelete` / `versioned`), because those columns
> are appended after the domain ones and each has its own accessor rule. That second fixture paid for
> itself on the first run — it surfaced ROADMAP finding **T54**, an NPE in generated *main* code when
> a versioned entity declares `Long version` rather than `long`. The emitter fix is out of this
> slice's scope (the column type is a constant, so no null guard is expressible without reading the
> declared field type), but it is exactly the class of defect this channel exists to find, and it was
> found by generated code exercising generated code.

> **Note (2026-08-02, slice e).** Saga step-wiring tests shipped, and the slice-d rule — a generated
> test earns its place only where the emitter has **two paths that must agree** — is what decided
> their content rather than their existence. Most of the saga skeleton is compile-checked:
> `builder.step(name, this::action, this::compensation)` cannot name a method that does not exist,
> so asserting the step *names* would be the circular check all over again. Three things are not
> compile-checked and are all this covers: the **transition chain** (`initialize()` walks the step
> list twice, once to register and once to lay `transition(i, i + 1)` over it, each walk deriving
> its own indices), **lazy-init idempotence**, and that **`schedule()` uses the plan `initialize()`
> built** rather than compiling its own. The chain assertion is structural and reads the *recorded*
> steps, so a chain built over a different list than the one registered fails; verified by
> perturbation, as in slice d. A third shared double, `RecordingFlow`, collapses the flow SPI the
> same way `RecordingPersistence` collapses the persistence one — engine, plan factory, definition
> builder, scheduler, plan and context in one object, because the two walks can only be compared
> where both were recorded.
>
> With this, T2's Java half is complete: handler, service, repository and saga. What remains is the
> `@Validation` decision (taken in slice f, below) and the FE spec slice.

> **Note (2026-08-01, slice b).** The body-carrying routes split at a line this ADR did not
> anticipate. Their **guard** paths need nothing new: `parseBody` throws on `hasBody() == false`
> before it resolves a decoder, and `handleUpdate`'s path-id guard runs earlier still, so
> `handleCreate`/`handleUpdate` rejections are reachable with the slice-a exchange double alone
> (they shipped in slice b). What is left is the paths **past** a successful decode — the
> `@Validation` rejections — and those need a body decoder, a memory allocator and a `LoanedBuffer`
> bound through the kernel's `ScopedValue` provider slots. Whether a generated test may bind kernel
> providers is a question §"Any assertion about the database" gestures at but does not answer; it is
> a decision, not one more test, so nothing binds them yet and a generator test asserts the emitted
> source names none of them.

> **Note (2026-08-05, slice f) — the provider-binding decision, taken.** A generated test **may**
> bind kernel `ScopedValue` provider slots with emitted doubles. What it may not require is a
> *driver* or a *bootstrap*. The line is a dependency line, not a purity line: `HttpKernelProviders`,
> `KernelProviders` and the SPI interfaces behind them all live in `exeris-kernel-spi`, the artefact
> the generated **main** code already compiles against — so binding a slot adds nothing to a
> consumer's build, and §2's contract still reads JUnit 5 + AssertJ. The kernel's own
> `KernelBootstrapHttpEngineFixture` binds `HTTP_SERVER_HANDLER` exactly this way; what it also does
> — boot a real engine on a real port — is the half this slice does not take.
>
> `@Validation` rejections shipped on that basis, through a fourth shared double,
> `RecordingRequestBody`, collapsing the four roles `parseBody` resolves past `hasBody()`: the
> decoder registry, the decoder, the `LoanedBuffer` and the `MemoryAllocator`.
>
> **The allocator is the load-bearing detail, and it is easy to miss.**
> `HttpRequestDecodingContext` is a record with `requireNonNull(allocator)`, and `parseBody` fills
> that slot from `KernelProviders.MEMORY_ALLOCATOR.get()`. An unbound `ScopedValue` throws inside
> the `try` that maps everything to `400 BAD_REQUEST` — the *same* status a validation rejection
> produces. So a suite of reject-only cases would have gone green having never once reached a
> validation guard. That is why every entity also gets an **accept** case: `201 CREATED` can only
> come out the far end of a decode that worked. Confirmed by perturbation — dropping the allocator
> binding leaves every reject case green and fails only the accepts.
>
> The same pairing answers the slice-d rule for this surface. A reject case alone is near-circular:
> the rule and the probe value both come from one `minLength = 3`, so an emitter that wrote `<=`
> where it meant `<` still rejects a 2-character string. The accept case sits **exactly on** the
> boundary, so it fails the moment the operator slips — also confirmed by perturbation. Neither half
> carries the test alone; the pair is the test.
>
> Two things are deliberately **not** covered, because a case that passes for the wrong reason is
> worse than no case. A `pattern` rule has no synthesizable member and no synthesizable near-miss,
> so a required pattern-constrained field suppresses the entity's validation cases entirely rather
> than emitting cases another field would answer. And floating-point bounds are skipped: the bound
> is a `long`, the comparison promotes, and a boundary probe that is only approximately on the
> boundary tests nothing.
>
> The rule set itself moved into `KernelValidationRules`, read by both the handler emitter and the
> handler-test emitter. Which fields carry a check is the one thing they must not derive separately
> — disagree there and the generated test covers a different set of guards than the handler emits,
> green either way. The *comparison* stays unshared on purpose: deriving the operator once and
> handing it to both would make the pair agree by construction instead of by behaviour.

## Amendment 1 — an opt-in integration-test channel that boots the generated application on H2 (2026-11-07)

**Status:** Accepted *(adds a second test channel beside §1. §1 to §4 and the slice notes are
unchanged. The scope line "Any assertion about the database: nothing here starts a persistence
engine" still holds for the unit channel, and this channel is where those assertions go.)*

**Deciders:** the founder (ruling 2026-11-07, `exeris-tooling#408`).

The generated unit tests run generated code against emitted doubles. Some pairs of emitter paths
can only be compared by a running kernel, and no double can stand in for one:

- the emitted migration and the repository's SQL, on a real engine;
- the request codec and the entity's accessors;
- the router and the handlers;
- `GeneratedRoutePolicy`'s copy of the router's matching and the router itself (ADR-105
  obligations 1 to 5).

The tooling e2e compares them by booting the emitted `Application`. A consumer had none of that.

### The decision of the amendment

1. **A second opt-in, with its own root.** `exeris:generate` takes `exeris.integrationTests`
   (default `false`) and `exeris.integrationTestOutputDir` (default `src/test/generated-it/java`).
   The command line takes `--integration-tests` and `--integration-test-output-dir`, each refused
   without the other. `CodegenPipeline.runIntegrationTests(metadataDir, itOutputDir, basePackage)` is
   a separate entry point, as §1 reasons. The root has its own `OutputWriter` and manifest, so
   neither test tree prunes the other, and it is registered with `addTestCompileSourceRoot`, under
   `exeris.codegen.skip` too. The generator catalogue (ADR-097) names the tree `integration-test`.
2. **Failsafe runs them, by name.** Every emitted test class ends in `IT`, and no support class
   matches a Surefire or Failsafe default include (`ApplicationUnderTest`, `IntegrationHttp`,
   `IntegrationTokens`). So `mvn test` runs the unit channel only, and `mvn verify` adds this one.
3. **What boots.** The emitted `Application` boots once per test JVM, on the kernel driver already
   on the test runtime classpath; the `exeris.it.application` system property names another class
   to boot, such as a subclass that overrides a hook. It boots against a fresh in-memory H2 database
   in PostgreSQL mode, handed over through the kernel's own `exeris.http.*` and
   `exeris.persistence.*` keys, which are restored once the kernel has read them. The kernel's
   persistence driver applies its own migrations only, so the support class applies the generated
   ones before the boot, read from the test classpath in version order, as a migration tool would.
   H2 knows two PostgreSQL spellings the migrations use by other names, `TIMESTAMPTZ` and a key
   column's `PRIMARY KEY DEFAULT gen_random_uuid()`, and those two are restated; nothing else in a
   migration is changed. Readiness is a response other than `503` from a path no route serves.
4. **Scope of 0.11: `GLOBAL` applications.** The channel is emitted only when every entity resolves
   to `GLOBAL` and the build derives neither `graph` nor `storage` and carries no capability
   composition. H2 runs no row-level-security DDL, and the migrations apply to the whole
   application, so one tenant-scoped entity rules H2 out for all of them. Otherwise nothing is
   written or pruned, and the build warns `EXT-GEN-3105` with the first reason. A failure to write
   the tree is `EXT-PLUG-2005`. PostgreSQL through Testcontainers, the tenant scopes and a
   `NOBYPASSRLS` role (ADR-090) are a later amendment.
5. **How a test binds a principal.** A route requirement is evaluated before the generated edge
   handler, so a `decorate` wrapper cannot satisfy one. When some route demands an identity
   (ADR-105 obligation 17), the support class starts a loopback JWKS endpoint
   (`com.sun.net.httpserver`) with an RSA key pair made at test runtime. It sets
   `security.jwks.uri`, `security.jwt.issuer` and `security.jwt.audience`, and mints RS256 tokens
   with `java.security`. The `sub` is a fixed UUID, and a token carries a `scope` claim when it
   is given scopes. The kernel verifies them exactly as it verifies a production token.
6. **What the tests assert.** The ITs assert statuses and identifiers over HTTP, never SQL text or
   emitted source.
   - Per entity: the CRUD round trip; `404` for an absent row and after a delete; `400` for a
     malformed key; `409` for a stale versioned update; `422` and `409` for foreign-key refusals;
     exclusion of a deleted row from the list; the `@Validation` boundary pair. A case that would
     have to create a row is emitted only where a valid body can be synthesized.
   - Per declared route requirement: `401` without a token, with a query string and on `HEAD`;
     `403` for a verified caller lacking every declared scope, and for one holding only the
     entity's scopes on an action that declares its own; and passing the policy with a declared
     scope.
   - The expected requirement is read from the same declaration rules `GeneratedRoutePolicy` is
     emitted from. The request is built independently of the emitted routing table, so the two
     paths are compared and not shared.
7. **The dependency contract.** The emitted integration tests import JUnit 5, AssertJ, the JDK
   (`java.net.http`, `jdk.httpserver`, `java.security`, `java.sql`) and the generated main code. At
   test runtime they need `com.h2database:h2`, which `RequiredCompileArtifacts` reports as
   `EXT-PLUG-2004` when it is absent, and a kernel driver. `exeris-app-bom` manages the H2 version
   the tooling e2e ran, and `exeris-app-parent` binds `maven-failsafe-plugin` in a profile active
   only when the default root exists (ADR-091 Amendment 7). No mocking framework and no kernel
   testkit are imported.
8. **The gate runs them.** `GeneratedIntegrationTestsE2ETest` runs annotated source through the
   processor, `run` and `runIntegrationTests`, compiles both trees with `javac` against JUnit,
   AssertJ and the kernel alone, and executes the `*IT` classes on the JUnit Platform launcher. It
   asserts that they pass and that each kind of case ran. The `app-integration-tests` invoker
   fixture runs them under Failsafe in a consumer build on `exeris-app-parent`. §4's rule applies
   unchanged.
9. **Out of scope.** PostgreSQL and the tenant scopes. `@Action` stream frames, live view and
   spectate over the booted application. Graph and blob routes. A TypeScript or browser smoke test
   against the booted backend, which is not planned. The initializr option that turns the channel
   on.

### Consequences

- Turning the channel on costs H2 at test scope and a Failsafe binding. The binding arrives with
  `exeris-app-parent`'s profile once the root exists; an application on route 2 copies the
  profile, and adds H2 either way. The build that first writes the root does not run the tests;
  the builds after it do.
- The integration tests are committed L1 output like the rest, and `exeris:detach` does not move
  them, as for the unit tree.
- A generator that changes a status, a route or a requirement fails a consumer's `mvn verify`
  when the channel is on. That is the purpose of the channel.
- The tests read nothing from `DomainMetadata` that the Java emitters do not already read, and add
  nothing to it, so the channel is Java-only for emitter parity.

## Engineering protocol

1. Every new generated-test emitter is proven by the run-the-tests gate, not by substring checks.
2. Determinism (hard-constraint #3) covers the emitted tests: no `UUID.randomUUID()` or timestamps
   in emitted sources — the fixed id literal exists for this reason.
3. When the contract of §2 is widened (a third dependency, or a mocking framework), it amends this
   ADR — it is a change to every consumer's build, not an emitter detail.
