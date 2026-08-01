# ADR-058 — Generated tests: emission channel and dependency contract

- **Status:** ACCEPTED (2026-07-31)
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

## Engineering protocol

1. Every new generated-test emitter is proven by the run-the-tests gate, not by substring checks.
2. Determinism (hard-constraint #3) covers the emitted tests: no `UUID.randomUUID()` or timestamps
   in emitted sources — the fixed id literal exists for this reason.
3. When the contract of §2 is widened (a third dependency, or a mocking framework), it amends this
   ADR — it is a change to every consumer's build, not an emitter detail.
