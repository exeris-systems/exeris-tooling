# Exeris Tooling — Roadmap to 1.0.0 GA

The tooling layer is the **build-time pipeline**: annotation processor reads
`@ExerisDomain` user code → emits `DomainMetadata` JSON → kernel-target Java
generators consume it → emit handlers, services, repositories, OpenAPI specs,
sagas. 1.0.0 GA means: **the codegen output is stable**, the Maven plugin API
is stable, and downstream user apps can pin to it with semver guarantees.

This file tracks scope per milestone. Items marked `[ ]` are open; `[x]` shipped;
`[~]` partial / in-progress. In the backlog table below, a ✅ in the target column marks an
item already shipped (its full status lives in the per-item detail entry); the table is a
cross-reference index, so it retains shipped items alongside open ones.

---

## 0.1.0 — scaffold (shipped)

- [x] Maven multi-module reactor (`bom`, `parent`, `processor`, `codegen-core`, `codegen-java`, `e2e-tests`)
- [x] `@AutoService`-registered `ExerisDomainProcessor` reading `javax.lang.model`
- [x] `KernelArtifactGenerator` interface + 12 kernel generators (Handler, Service, Repository, Saga, Events, EventHandler, GraphSync, OpenAPI, Flyway, Application, Client, …)
- [x] Single-target story (Spring/Quarkus/Micronaut/Vanilla generators removed; multi-backend abstraction deleted)
- [x] Round-1 + round-2 review fixes (sendError JSON escape, OutputWriter timestamp drop, dep cleanup, BackendGenerator rename, ArtifactType trim)
- [x] `exeris-codegen-ts` Angular generator (npm package, separate build)

## 0.2.0 — quality gates + processor hardening

> Goal: regressions caught by CI, generated code provably compiles, processor diagnostics usable in real builds.

- [ ] **CI** — `.github/workflows/build.yml` (clones + installs `exeris-sdk` first, then `mvn verify`)
- [x] **Compile-test gate** — `KernelCodegenCompileTest` runs the full Kernel generator strategy and feeds the union of generated `.java` artifacts plus a synthetic source entity through `javax.tools.JavaCompiler`, against minimal kernel SPI stubs in `src/test/java/eu/exeris/kernel/...`. Catches broken imports / removed-symbol references that the substring assertions in `KernelCodegenE2ETest` miss. Currently scoped to the no-events / no-saga / no-graph CRUD path; richer scenarios (Saga generator imports `tools.jackson.databind.*`, Events generator references `EventStore` / `OutboxSignal`, etc.) require Jackson 3 + larger SPI stub coverage and are tracked as a 0.3 follow-up
- [x] **Processor minors** (see [issue #2](https://github.com/exeris-systems/exeris-tooling/issues/2)): `triggerToEventSuffix` switched to exact-string match (a future `BULK_CREATE` enum value will no longer silently match `CREATE`); `-Aexeris.verbose` opt-in flag gates per-entity `note()` chatter and adds stack-trace dumps to processing-failure diagnostics; processing-failure messages now use `e.toString()` (always populated) instead of `e.getMessage()` (often `null` for JDK exceptions, which produced "Failed to process …: null"); typed `getString`/`getBoolean`/`getInt`/`getLong` helpers added over the raw annotation-value map (kills the cross-cast hazard at numeric extraction sites); `// LIMITATION:` comment on `extractTargetEntityFromType` documenting the Map<K,V> case (`extractPathId` no longer exists); `MetadataLoader` unused `DomainMetadata` import dropped; `@ActionParam.required` default verified aligned (SDK and processor both `true`); `@InternalApi` SDK↔AST drift flagged in code with a comment — the SDK annotation only exposes `consumers/rateLimit/requireMtls/timeout/documented`, none of the AST's `hidden/readOnly/internal/reason` fields, so the processor now only signals presence (`internal=true`) and the larger reconciliation moves to the SDK side
- [x] **Warn-and-read for deprecated `@Validation.required` / `@Validation.validateOn`** — implements the SDK 0.2.0 contract from `exeris-sdk` PR #8 (the canonical Field/Validation scoping fix). `ExerisDomainProcessor.applyDeprecatedValidationFallbacks` reads the deprecated attributes as a fallback when the canonical `@Field` ones are unset, and emits a build warning pointing each user to the migration target. Both attributes are removed in SDK 1.0.0; this code is dead at that point and must be removed in the same tooling release that adopts SDK 1.0.x
- [x] **Pre-publish POM metadata** — root POM now declares `<url>`, `<organization>`, `<licenses>`, `<developers>`, `<scm>`, `<issueManagement>`, `<distributionManagement>` (Sonatype Central Portal). Required by Maven Central

## 0.3.0 — codegen Maven plugin

> Goal: `mvn exeris:generate` and `mvn exeris:detach` are first-class build steps in user apps.

- [x] `exeris-codegen-maven-plugin` module — `maven-plugin` packaging, reactor-wired; a thin Maven shell over `CodegenPipeline` (no emission logic). ASM override (9.9.x) on `maven-plugin-plugin` so the descriptor scanner reads the reactor's bytecode
- [x] `exeris:generate` — bound to `generate-sources`; runs the pipeline against the processor-emitted `DomainMetadata` and writes to `src/main/generated/java`, registering it as a compile source root (`skip` / `addCompileSourceRoot` toggles)
- [x] `exeris:detach` — promotes generated code to `src/main/java/`, prunes the emptied tree, strips the `.gitignore` entry (L2). Idempotent; never overwrites an owned file (conflicts reported, `failOnConflict` opt-in). Logic in a testable `DetachService`
- [ ] `exeris:reattach` — inverse; re-enables on-demand regen. **Blocked on SDK 0.3.0** source-model round-trip (must re-derive `DomainMetadata` from owned `.java` to know what to regenerate)
- [x] Plugin wraps the pipeline directly (Jackson 3 stays inside `codegen-java`; no `compile-testing` on the plugin classpath)

## 0.4.0 — codegen quality refactor

> Goal: collapse the duplication Sonar flagged (3.8% on new code, KernelHandlerGenerator 59.8%, KernelClientGenerator 40.6%).
>
> Strategy: see [ADR-015 — Codegen Emission Strategy](docs/adr/ADR-015-codegen-emission-strategy.md).

- [x] **`StringBuilder.append(...)` → text blocks** for SQL/YAML emission paths — `KernelFlywayGenerator` emits SQL via text blocks + `String.join`; the per-column assembly is plain concatenation (no `StringBuilder`). Output byte-equivalence is pinned by `KernelFlywayGeneratorTest`'s golden snapshots. The only remaining `StringBuilder`s in the kernel package are char-by-char case-conversion **utilities** (`toSnakeCase` in GraphSync, `toCamelCase` in Saga) — idiomatic there, not emission paths, deliberately left as-is
- [x] **JavaPoet** for Java-emitting paths — type-safe, compile-checked. All 9 Java-emitting `Kernel*Generator`s are JavaPoet-based (Palantir fork)
- [x] Shared scaffold extraction — `KernelScaffold` (`publicClass` + `render`) owns the package decl / imports / class-header scaffold; every Java-emitting generator routes through it
- [ ] **Re-confirm the Sonar duplication target** (was 59.8% `KernelHandlerGenerator`, 40.6% `KernelClientGenerator`) now that JavaPoet + `KernelScaffold` are in across the suite — the strategy is applied; the headline duplication figure needs a fresh Sonar read to close the goal
- [x] **`System.Logger` in `CodegenMain`** — replaced `System.out/err.println` + emoji + box-drawing with JDK-standard `System.Logger` (JSR 264). No third-party logging dep; downstream consumers plug a `LoggerFinder` (or, for the eventual Maven plugin, opt in to `slf4j-jdk-platform-logging`). Per-domain detail at `DEBUG`, milestones at `INFO`, missing-metadata at `WARNING`, failure path at `ERROR` with attached `Throwable`. Argument-parsing usage hint stays on stderr (CLI contract; emitted before the JVM exits).

## 0.5.0 — `@Capability`-aware codegen

> Goal: capability annotations (`@CapabilityModule`/`@Provides`/`@Requires`/`@CapabilityLifecycle`,
> SDK capability package) drive build-time composition validation + a platform registry artifact.

- [x] Processor extracts `@CapabilityModule` (+ repeatable `@Provides`/`@Requires`, `@CapabilityLifecycle`)
      into `capability_*.json` — app-wide, parallel to `DomainMetadata`, never nested in it. (Resolves
      **S5**, the SDK-side "extracted by no processor pass" gap.)
- [x] `CapabilityGraph` (codegen-core) resolves the `@Requires`→`@Provides` graph with Maven-style
      version-range matching (`VersionRange`), **fails the build** on an unsatisfied non-optional
      requirement / version mismatch / dependency cycle, and **warns** on an unsatisfied optional.
- [x] Deterministic `cap-manifest.json` emitted at the output root — the platform-side capability
      registry (input for the cross-app mesh contract, **T12**). T13-tracked like every emitted file.

> **Adopting this pass downstream surfaced its next limits** (backlog): the validator is closed-world
> per app, so a legitimate cross-service `@Requires` hard-fails the build (**T17**, the capability-axis
> twin of T12), and the `generate-sources`-before-`compile` ordering makes the pass deadlock on stale
> metadata and lets `mvn clean` + the T13 pruner wipe the committed L1 tree (**T18**).

> **Capabilities are a PLATFORM concern, not a kernel one.** `@Provides`/`@Requires` model composition /
> SKU / mesh, and every SDK annotation is `@Retention(SOURCE)` (erased from bytecode), so the kernel —
> the runtime substrate — neither sees nor *should* see the platform registry; the dependency direction
> is platform → kernel, never the reverse. The earlier "capability port clients / event-handler wiring"
> framing is **dropped**: it had no SDK AST backing and would have made the kernel aware of the platform
> registry (a Wall inversion). If a *runtime* module-composition story ever materialises (assembling
> active modules per SKU/deployment), it is **host-runtime** (`exeris-spring-runtime`) work consuming
> this manifest — never a kernel SPI, and never a second backend here (hard-constraint #1). This is the
> key distinction from `@EventSourced` (**EV2**), which genuinely *is* runtime and so genuinely needs a
> kernel SPI.
>
> This satisfies prerequisite (1) of **T12**'s contract-registry. See **Codegen completeness backlog → T12**.

## 0.6.0 — codegen-ts hardening (shipped 2026-07-02)

> Goal: TS/Angular generator is on equal footing with Java (currently treated as preview-grade).

- [x] **ADR-024 composition validation stamp (obligation 7) — emitted into `cap-manifest.json`.** The
      0.5.0 cap pass validated the graph (build-fail on unsatisfied `@Requires`/cycle/version) and emitted
      the manifest, but the *explicit* stamp the platform composition runtime asserts (ADR-024 2026-06-17
      "Validation Stamp Lifecycle" amendment, obligation 7) was never emitted — validation was only
      *implicit* ("the build succeeded → a manifest exists"). *Done (0.6.0):* `CompositionStamp(validated,
      compositionVersion, contentBinding)` stamped onto `CapabilityGraph` on the validation-success path and
      serialized into `cap-manifest.json` (manifest `schemaVersion` 1→2). `contentBinding` =
      `sha256:<hex>` over the sorted resolved cap set (modules + provided `service@version`) — the
      non-transferable "*this* composition is valid" attestation. `compositionVersion` is a build input via
      `-Dexeris.composition.version` (degrades to `0.0.0`). Deterministic (#3), Wall-safe (kernel stays
      cap-blind, obligation 9). **Pulled forward from 0.7.0 to unblock Caps/SKU — it is the precondition for
      a publishable-unit corpus** (the gate the presentation + marketplace RFCs wait on). *Cross-repo pair
      (handoff issued):* the **platform composition runtime** assertion (obligation 8 — presence +
      well-formedness + version-match + binding-match, no DAG re-resolution) is the consumer that makes this
      non-inert; `exeris-platform` owns it. Contract pinned so platform asserts the exact emitted shape.
- [ ] Add `exeris-codegen-ts` to a top-level orchestration target (Makefile or `frontend-maven-plugin`).
      *Status (0.6.0): DEFERRED → 0.7.0* — CI already orchestrates both toolchains (separate jobs below);
      a local single-command target is DX polish, not a gate.
- [x] CI: separate npm-build job — *done during the cycle, checkbox was stale:* `build.yml` runs
      `ts-coverage` (vitest + data-layer `tsc --noEmit`) and `fe-app-build` (`ng build` of a generated
      sample app) as separate gating jobs alongside the JVM matrix.
- [~] **Angular v22 migration** (emitted scaffold + idioms; scope T-C, phased A→B→C). **Done:**
      Phase A compat bump — `@angular/* ^22`, TS `~6`, drop `withFetch()` (#95); Phase B1 scaffold cleanup —
      drop `@angular/platform-browser-dynamic`, Node-22 floor, `@angular/build` builder (#96); Phase B3
      data-fetch fix — `rxResource` in detail, `firstValueFrom` in store (#98). **Pending:** Phase C
      (Reactive Forms → Signal Forms, ADR-worthy → flag-gated WebMCP); small tidy (`@Service`, `?.` audit).
      `debounced()` rejected (experimental). Gated by the `ng build` round-trip above.
      *Status (0.6.0): Phases A/B1/B3 shipped; Phase C DEFERRED → 0.7.0* (ADR-worthy Signal Forms
      reshape — reserve the ADR before code, per the shape gate).
- [x] Round-trip tests against generated Angular workspace (compiles + `ng build` / `tsc --noEmit` green)
      — the FE analog of `KernelCodegenCompileTest`; **this was the catch for T20** (the generated frontend
      wasn't building before #101/#102) and guards the Phase C reshape just as it caught T20. Shipped (#101/#102).

### 0.6.0 scope (agreed) — "codegen-ts on par with Java + parity/correctness debts closed + a gate that keeps it honest"

**Shipped:** **T1** action-serving (#92) · Angular v22 Phase A/B1/B3 (#95/#96/#98) · SDK pinned to released 0.7.0 (#100) · **T20 + FE build gate** (#101/#102) · **T7** remainder — configurable title/redirect via `--app-name` (#120) · **T10** server-side `@Validation` (#103) · **T8** finders + FK/`filterable` indexes (#118) · **U1** ui-kit wiring (#120) · **release pins** — kernel `0.10.0` + SDK `0.8.0`, CI SDK checkout on the `v0.8.0` tag, stale `-U` dropped (#133/#136; SDK 0.8.0 released the same day as kernel 0.10.0, so the planned "bump the SDK pin as the last step" gate collapsed into the same batch — both `eu.exeris` pins are releases, no-SNAPSHOT-deps-at-release satisfied).

**In this cut:**
- **T2 (FE slice)** — emit `*.service.spec.ts` + `*.schema.spec.ts` into the generated app, run by the
  FE gate. *Status (0.6.0): DEFERRED → 0.7.0* (release-cut decision 2026-07-02) — rides with the full
  Java `Kernel*TestGenerator`, so 0.7.0 delivers the generated-test story in one piece.
  *Superseded 2026-08-18: deferred again, to 0.8.0.* The "one piece" argument was made before the
  gateway-caps track was folded into 0.7.0 and before U0/U1 arrived as forced upstream work; holding
  a milestone whose stated goal (G0–G3) was complete, for a slice on the other side of the
  toolchain split, would have paid the delay for a symmetry no consumer can observe.
- **T18** — build-safety: guard the T13 pruner on empty input (#129) + the capability-pass phase
  ordering (`exeris:verify-capabilities` fresh-metadata gate + deferred validation at
  `generate-sources`) — **done**, see the backlog entry.
- **D1** — `requireJavaVersion` enforcer + README up-front. ✅ 0.6.0 (root-POM enforcer at `validate` + README rewrite; detail in Build & DX).
- **Release-cut riders (agreed 2026-07-02, after kernel 0.10.0 released):** **ADR-045** client-retry
  composition-root wiring + `ADR-045.link.md` stub (#135) · roadmap truth-fixes post-0.10 (EV2
  blocker text, EV1-stream/U7 per-action kernel gate — this entry). The release pins shipped with
  the same batch — see **Shipped**. The EV1-stream per-action slice is explicitly **not** in
  0.6.0 — see the Events section (kernel stream-route `{id}` gate, v0.11 ask).

**Deferred to 0.7.0+:** **T12 + T17** (the cross-app mesh epic), **T9** (FK-constraint relationship graph), the full **T2** test-emitter (Java **and** the FE spec slice, per the 2026-07-02 release-cut decision), the **EV1-stream per-action slice** (kernel stream-route `{id}` gate, v0.11 ask), **Angular Phase C** (Signal Forms, ADR-worthy), the codegen-ts **orchestration target**, and the **U2–U5** UI-depth cluster (U2 universal lists is the lead 0.7.0 item). EV1/EV2 per their own section. (**T19** typed `Instant` bind — done in 0.6.0, the kernel persistence-SPI gate cleared in 0.10.)

See the **Codegen completeness backlog** below for per-item detail.

## 0.7.0 — gateway-caps enablement (tooling's half of the first SKU) (shipped 2026-08-18)

> Goal: unblock Phase 2 of the 2026-07-21 gateway-caps implementation plan — the first
> `exeris-caps-*` repository — by closing the tooling-owned remainder of the plan's Phases 0/1.
>
> Contract layer: [ADR-024](https://github.com/exeris-systems/exeris-docs/blob/main/adr/ADR-024-capability-composition-model.md)
> (composition model + the 2026-06-17 stamp-lifecycle and 2026-07-21 boot-conductor-call-site
> amendments) and [ADR-053](https://github.com/exeris-systems/exeris-docs/blob/main/adr/ADR-053-sku-composition-manifest-format.md)
> (manifest format = JSON).
>
> **The SDK half shipped in 0.9.0** — `CapabilityLifecycleHooks` (new zero-dep
> `exeris-sdk-composition-lifecycle`) and `CompositionConductor` / `CompositionBootException`
> (`exeris-sdk-composition-runtime`). Everything still open on the plan's critical path is
> tooling-owned, which makes this milestone the gate on the whole gateway track.

- [x] **G0 — release-pin bump.** SDK `0.8.0` → `0.9.0`, kernel `0.10.0` → `0.10.2`; CI SDK
      checkout re-pinned to the `v0.9.0` tag. Prerequisite for G1–G3 (the conductor types must
      resolve). Carries two riders: the **ADR-054 lockstep** (drop the dead TS
      `ValidationMetadataSchema` — a never-consumed orphan whose Java counterpart was removed
      outright in SDK 0.9.0) and the `CapManifest.ModuleBody` adaptation for the trailing
      `lifecycleOwner` component added in 0.9.0 (binding-invariant — `CompositionBinding`
      canonicalizes `qualifiedName` + sorted `provides` only).
- [x] **G1 — cap-tier Wall guard** (plan P1.3; ADR-024 validation predicate 4 — the last
      unimplemented one). Bytecode import scan over `target/classes` wired into
      `exeris:verify-capabilities` at `process-classes`, gated on the module actually being a cap.
      Forbidden: `org.springframework.*`, `io.netty.*`, `reactor.*`, `jakarta.servlet.*`, kernel
      `**.internal.**`, and **sibling**-cap internals (a cap may read its own).
      **Bytecode, not sources** (founder-ruled 2026-07-29): a source scan sees only the cap's own
      files and misses a violation pulled in transitively when a dependency changes. Decided in
      [ADR-055](docs/adr/ADR-055-cap-tier-wall-guard.md), which also records the two findings that
      shaped it: the scan needs **no new dependency** (JDK-standard Class-File API, JEP 484 — not
      ASM, since the repo pins a JDK floor the API predates), and a constant-pool walk alone is
      **unsound** (a `void configure(ApplicationContext)` parameter and a `List<Forbidden>` type
      argument appear only in a descriptor and a `Signature` attribute respectively), so the
      extraction surface — pool ∪ descriptors ∪ signatures ∪ annotations — is the load-bearing
      part rather than the forbidden-prefix list.
- [x] **G2 — SKU bootstrap emitter.** `KernelApplicationGenerator` emits the conductor call site
      inside `KernelBootstrap.boot(...)`, after `KERNEL READY`, per the shape pinned in
      `CompositionConductor`'s javadoc. The `@CapabilityLifecycle` → `cap-manifest.json`
      (`lifecycleOwner`) → conductor chain is already complete on the data side, so the emitter is
      the last missing link. **Conditional on the build actually having a composition** (driven by
      the `capability_*.json` this run loaded, not by the manifest on disk — that one may be a
      preserved older file on the T18(a) deferred path): a cap-less build emits byte-identically
      to every release before 0.7.0, down to the absent import. The call site is a
      try-with-resources over the *concrete* conductor type — its `close()` declares no checked
      exception, which is what lets it sit inside `boot(Runnable)` — so cap `initialize`/`ready`
      complete **before** `RuntimeLifecycle` sets the handler slot (no request is served against a
      half-initialized composition), and drain/terminate run **after** the shutdown latch releases
      and before the kernel stops. The compile gate now runs twice, once per bootstrap variant.
      Runtime manifest location is an overridable `protected Path capManifest()` seam
      (`exeris.capManifest` system property, default `cap-manifest.json` in the working directory):
      the build writes the manifest at the codegen output root, which is a *source* root and so
      never on the runtime classpath. **Packaging it as a deployment artefact stays a SKU-scaffold
      concern** (Phase 5, alongside the ADR-053 canonical `composition.json` reader) — tooling owns
      the seam, not the deployment layout.
- [x] **G3 — e2e composition proof** (plan P1.4; the plan's Phase-1 **exit gate**). Sample two-cap
      composition → processor → `verify-capabilities` → kernel boot via
      `KernelBootstrapHttpEngineFixture` (`exeris-kernel-community-testkit`) → conductor run
      SKU-style after `KERNEL READY`; asserts verbatim `initOrder` and drain semantics. Negative
      half: a Wall-violating sample fails the build (host-runtime reach *and* sibling-internal
      reach). Unlike every other capability test here, it starts from **annotated Java** — real
      `javac` + `ExerisDomainProcessor`, real class files — so it is the only place two joints are
      held: (1) the build-time `CompositionStamp` and the SDK's runtime
      `CompositionStampAssertion` recomputation must agree, and (2) the sample is arranged so the
      topological order is the **reverse** of the alphabetical one (`vault` provides, `audit`
      requires), so neither a re-sorting conductor nor a lexicographic emitter can pass by
      coincidence. The kernel runs the `http` subsystem only (no database, ~0.5 s), and is
      deliberately never wired to the conductor — ADR-024 obligation 9 keeps it cap-blind. Two
      build consequences worth knowing: `exeris-e2e-tests` gained the community driver + testkit,
      and its surefire JVM now runs `--enable-preview` (module-scoped) because this is the first
      test that *executes* kernel classes rather than only compiling against them.

**Not in 0.7.0:** the cross-app mesh epic (T12/T17) — the gateway-caps plan (§2, item 6) explicitly
defers mesh resolution as unnecessary for a single-node API Gateway MVP. The ADR-053 canonical
`composition.json` reader is Phase 5 (it ships with the `exeris-sku-api-gateway` scaffold alongside
the authored-manifest schema in `exeris-sdk-composition-spec`), not this milestone.

- [~] **T2 — Generated tests for the generated surface** (slices a–f shipped; the Java half is complete). Opt-in
      (`-Dexeris.tests=true`) emission into a **second output root** `src/test/generated/java`, with
      its own `OutputWriter` + T13 manifest and registered via `addTestCompileSourceRoot` — a test
      under the main root would compile into the application artefact and put JUnit on its runtime
      classpath. Decided in [ADR-058](docs/adr/ADR-058-generated-test-emission-channel.md), which
      also fixes the half this repo cannot enforce: tooling emits no `pom.xml`, so every import in a
      generated test is a hard requirement on the consumer's build. The contract is **JUnit 5 +
      AssertJ and nothing else** — the doubles are emitted (an `HttpExchange` recorder; a service
      stub that subclasses the generated service) rather than mocked, which in turn makes `public` +
      non-final + assignment-only constructors a *contract* of generated code rather than an
      accident. Slice a covers the handler's bodyless routes (`handleGetAll`, `handleGetById`
      found/absent/malformed-id, `handleDelete`) — the statuses the handler owes the router. Slice b
      adds the body-carrying routes' **guard paths**: `handleCreate` and `handleUpdate` both reject
      before the body is read (`parseBody` throws on `hasBody() == false` ahead of resolving any
      decoder, and `handleUpdate`'s path-id guard runs ahead of that again), so those three cases
      need no request-body double at all, and each asserts the service was never reached. Slice c
      adds `<Entity>ServiceTest` — the delegation contract, which is the whole of what a service
      owes its callers: which repository method each call reaches (the `delete` → `deleteById`
      rename included), that `save`/`update` return the **repository's** result and not their own
      argument (the repository fills in a generated id before returning, so the wrong wiring
      compiles and hands callers a null-id entity), and one case per T8 finder. It needed no new
      machinery: the generated repository is `public`, non-final and assignment-only, so the same
      emitted-double pattern reaches it with `super(null)` and no persistence engine. Its finders
      and the double's overrides are emitted from one `KernelRepositoryGenerator.finderSpecs`
      source — three surfaces (repository, service, double) each carrying their own copy of "which
      finders exist, in what order" is how the double would drift into overriding methods the
      service never calls, i.e. quietly testing nothing. The
      Slice d adds `<Entity>RepositoryTest` and a second shared double, `RecordingPersistence`,
      which implements the five persistence-SPI roles a repository walks through
      (`TransactionalExecutor` → `PersistenceConnection` → `PersistenceStatement` → `QueryResult` →
      `RowCursor`) in **one class** — collapsing them is what lets a test replay what the repository
      *bound* back as what it *reads*. The ruling that shapes it is what these tests must **not**
      assert: the emitted SQL. Test and repository come from one `DomainMetadata`, so a changed
      column list changes both and a SQL-text check could never fail. What is at risk is the
      alignment of two independent emitter paths — `emitInsertBinds` numbers the INSERT's
      parameters, `emitReadCol` numbers `mapRow`'s reads — so the central test is a save/load
      **round-trip**, asserting runtime behaviour instead of text. Verified by perturbation:
      shifting `mapRow`'s indices by one makes the generated test fail. Still no database, driver or
      transaction. The gate runs it over two fixtures — a plain entity and one carrying every
      system-column flag — and that second fixture immediately paid for itself by surfacing **T26**
      (a versioned entity with a wrapper `Long version` NPEs on its first `save()`). The
      gate **runs** the emitted tests through the JUnit Platform launcher instead of just compiling
      them; a test emitter whose output is never executed is the inert-output failure mode this repo
      rejects everywhere else. Slice e closed saga step-wiring:
      most of that skeleton is compile-checked, so what the emitted `<Saga>FlowTest` covers is the
      three things that are not — the **transition chain** (`initialize()` walks the step list
      twice, once to register and once to lay `transition(i, i + 1)` over it, with each walk
      deriving its own indices), lazy-init **idempotence**, and that **`schedule()` reuses the plan
      `initialize()` built**. The chain assertion reads the *recorded* steps rather than a baked-in
      count, and a third shared double (`RecordingFlow`) collapses engine, plan factory, definition
      builder, scheduler, plan and context into one object for the same reason
      `RecordingPersistence` does. Perturbation-verified. Slice f takes the decision ADR-058 had
      deferred — a generated test **may** bind kernel `ScopedValue` provider slots with emitted
      doubles, but may not require a driver or a bootstrap. The line is a dependency line: those
      slots live in `exeris-kernel-spi`, which the generated *main* code already binds, so the
      JUnit + AssertJ contract is untouched (the kernel's own `KernelBootstrapHttpEngineFixture`
      binds `HTTP_SERVER_HANDLER` the same way — booting an engine is the half not taken). On that
      basis the `@Validation` paths ship through a fourth double, `RecordingRequestBody`, collapsing
      the decoder registry, decoder, `LoanedBuffer` and `MemoryAllocator`. The allocator is the
      detail that decides the slice: `HttpRequestDecodingContext` rejects a null one, and an unbound
      slot throws inside the `try` that maps everything to **400** — the same status a rejection
      produces, so a reject-only suite would go green having never reached a guard. Hence an
      **accept** case per entity (`201` cannot be faked that way) and, for every bounded rule, an
      accept sitting *exactly on* the boundary next to a reject one step outside it — a reject alone
      would survive an emitter that wrote `<=` for `<`, since rule and probe come from the same
      metadata. Both perturbations verified. `pattern` rules and floating-point bounds are
      deliberately uncovered: neither has a probe that fails for the right reason. Which fields
      carry a check now comes from one `KernelValidationRules`, read by the guard emitter and the
      test emitter; the *comparison* stays unshared, or the pair would agree by construction rather
      than by behaviour. **The FE spec slice is deferred to 0.8.0** (release-cut decision
      2026-08-18) — see the 0.8.0 entry for what it owes and why it did not ride along.
      *Found on the way:* a filterable field named `id` emitted a second `findById(UUID)` on both
      the repository and the service — uncompilable. Easy to hit by accident, since the processor
      records any field without `@Field` via `FieldMetadata.simple(...)`, which sets
      `filterable(true)`: a plain `private UUID id;` was enough. Fixed by skipping the finder that
      shadows the primary-key lookup.

- [x] **T4-follow-up — `@Relationship(relationshipType = …)` reaches the emitters.** The processor
      read an attribute named `type`, which the SDK annotation does not have (it declares
      `relationshipType`), so every relationship arrived as the builder default `MANY_TO_ONE` — and
      because the Flyway, repository, service and FK-constraint emitters all *correctly* gate on
      `MANY_TO_ONE`, the non-owning side of a relationship was emitting an FK column, its index, its
      `FOREIGN KEY` constraint and a `findBy<Rel>Id` finder. `cascadeDelete`/`cascadeUpdate` were
      likewise never extracted, so every generated FK was `RESTRICT` regardless of the annotation.
      **Why it survived T11 strict mode:** that audit reports attributes which are *extracted but
      unconsumed*; an attribute the processor never reads at all is invisible to it. Also why the
      unit suites missed it — the generators are tested against hand-built metadata that sets the
      type explicitly, and the processor tests asserted only that *a* relationships array exists.
      The fix ships with an `annotation → emitted SQL` e2e (`RelationshipSqlE2ETest`) that closes
      that seam, and it found a second, unrelated defect: a field of a collection type crashed the
      pipeline outright (`not a valid name: List<…`) because the repository emitter accepted only
      the short spelling `List<` while the processor records javac's fully-qualified
      `java.util.List<…>`. Schema consequences are written up in
      [MIGRATION](docs/MIGRATION-0.x-to-1.0.md#070-train--regeneration-deltas) — a regen *removes*
      DDL, so it needs a Flyway decision, not just a commit.

**T9 was not open in this milestone after all** — FK *constraints* shipped in 0.6.0 alongside T8
(single trailing `V3000000__foreign_keys` migration; see the T8 entry). The 0.7.0 line and the
completeness-table row saying otherwise were doc drift, corrected here. What was genuinely missing
was that the constraints could never see a non-`MANY_TO_ONE` relationship or a cascade — the
extraction fix above. The remaining backlog item already targeted at 0.7.0 before the gateway track
was folded in is **T2** (the full test-emitter, Java + the FE spec slice); G0–G3 lead the milestone
because they gate the cap track, not because T2 was displaced. In the end T2 *was* split at the cut:
the Java half shipped here, the FE spec slice moved to 0.8.0.

### Upstream catch-up (U0–U3) — added 2026-08-12

Kernel 0.11.0 and SDK 0.10.0 both released, and both moved off the JDK this repo pins. The sequence
below is **forced, not preferred**: 0.10.2 was class-file major 70 (and `exeris-kernel-core` carried
9 preview-stamped classes), which JDK 25 refuses outright — so the pin bump has to land before the
LTS descent can even compile. Verified by reading the jars.

- [x] **U0 — release pins.** SDK `0.10.0-SNAPSHOT` → released `0.10.0`, kernel `0.10.2` → `0.11.0`;
      CI SDK checkout back from the moving `main` ref to the `v0.10.0` tag. A SNAPSHOT pin blocks
      cutting *any* release, so this is a release blocker rather than a convenience. Rider: SDK
      bumps `SchemaVersion.CURRENT` `0.9.0` → `0.10.0`, which the processor picks up for free (it
      stamps from `BaselineTrust.current(...)`, never the inlined constant) — consumers re-run
      codegen once, per ADR-042 skew. Behaviour-neutral by design and by result: 82 / 99 / 388 / 43
      / 24, unchanged.
- [x] **U1 — JDK 25 LTS baseline.** Enforcer `[26,27)` → `[25,)`, `maven.compiler.release` 26 → 25,
      `@SupportedSourceVersion(RELEASE_26)` → an override returning `SourceVersion.latestSupported()`
      (a pinned constant would warn on a consumer compiling at release 28 on the preview line), the
      two hardcoded `--release 26` sites in `InMemoryJavaCompiler` + `GeneratedTestsE2ETest`, the
      now-vestigial `--enable-preview` on the e2e surefire JVM, and the README D1 text. Net
      *removal*. Measured ahead of the work: the whole main pipeline compiles clean at `--release 25`
      against kernel 0.11.0, with that one `SourceVersion` constant as the sole blocker — the same
      trap kernel ADR-066 documented. **This is what unblocks LTS consumers**; SDK 0.10.0's notes
      name this repo as the remaining stop ("an LTS build can compile against the annotations but
      not run the processor"). Pinned by a new `ClassFileBaselineTest`, which reads the emitted
      class files rather than the build property — the two can disagree, and the bytes are what a
      consumer's JVM refuses. Proven to fail by building at release 26. The ASM override on
      `maven-plugin-plugin` was re-measured and **stays**: plugin-tools 3.13.1 aborts on major 69
      too, not just 70.
- [x] **U2 — CI matrix `['25','26']`**, 25 as the release-bearing row, copying the shape SDK 0.10.0
      adopted. A third `eu.exeris.preview` row is *schedulable* (JDK 28 EA is publicly downloadable)
      but deliberately not taken yet — see below. The matrix itself landed with U1 (#160); what
      remained here was the half a workflow file cannot express — branch protection still required
      `mvn verify (JDK 26)`, so the row that gated merges was the *forward-compatibility* row and
      not the release-bearing one. Flipped to `mvn verify (JDK 25)` on 2026-08-18. The 26 row still
      runs and still reports; it no longer blocks. `ng build (generated sample app)` is **not** in
      the required list — the workflow comment claimed it was, which was never true and is corrected
      rather than made true, because adding a gate is a decision and not a doc-fix.
- [x] **U3 — SDK pin `0.10.0` → `0.11.0`** (SDK released 2026-08-26). Same shape as U0 and the same
      rider: SDK bumps `SchemaVersion.CURRENT` `0.10.0` → `0.11.0`, the processor picks it up for
      free (it stamps from `BaselineTrust.current(...)`, never the inlined constant), and consumers
      re-run codegen once per ADR-042 skew. Behaviour-neutral by design and by result: **83 / 99 /
      402 / 45 / 28 on both sides of the pin**, measured by flipping it back and re-running, and no
      source change was needed at all. The delta is additive on every record this repo reads —
      `FieldMetadata.blob`, `ActionMetadata.schedule`, `DomainEventMetadata.{trigger, actionName,
      fieldName}`, plus the new `BlobMetadata` / `ScheduleMetadata` carriers — and all of it is
      reserved: the processor extracts none of it, no generator consumes it. One narrowing rides
      along: `@Action.path` moved from `required` to `default`, and stays registered inert (T44).

      **What it puts on a released coordinate**, which is the point of doing it now rather than at
      the cut: T48, and the `@Schedule` half of the transcription. Worth stating the shape of what
      moved, because it does not move everything — a released coordinate unblocks work whose blocker
      was **metadata-shaped**. T48's was (no carrier for the trigger), `@Schedule`'s design-time half
      was. `@Blob`'s blocker is **runtime-hosting-shaped** (K6), so the same bump changed nothing
      binding for it: `FieldMetadata.blob` and `BlobMetadata` arrived with everything else and were
      simply never the constraint. T48 needed the trigger triple specifically: until
      0.11.0 the processor read `@DomainEvent.trigger` only to derive the event *name* suffix and
      then discarded it — and only when the author supplied no explicit `name` — so
      `@DomainEvent(name = "OrderPlaced", trigger = CREATE)` left no trace of the trigger anywhere
      and no generator could know where a publish call belongs. Extracting the triple is a processor
      slice this repo owes; the carrier for it now exists.

**Not a codegen fork.** Kernel 0.11 ships two lines (`eu.exeris` on JDK 25, `eu.exeris.preview` on
JDK 28 EA with Valhalla `value record`), but the emitted source is identical against either: of the
46 kernel types the emitters name, only `EventBus` (javadoc-only) and `MemoryStats` (the `value`
modifier, transparent to a consumer) differ, and tooling emits no `pom.xml`, so no groupId reaches
the output. Choosing a line is a build-matrix question. *Emitting* Valhalla shapes ourselves is a
separate and legitimate question — it would be a language-level profile, not a backend target — but
the prize today is two emitted record shapes (`<Event>Payload`, `<Action>Request`; entities are
user-authored and mutable), and it needs measurement first (does the codec/decoder path construct a
`value record` reflectively; what does the committed-L1 provenance manifest do with a profile stamp).
Deferred by decision, not blocked.

## 0.8.0–0.9.0 — feedback-driven cleanups

### Annotation-surface debt (S, C) — inventoried 2026-08-12

An evidence survey against SDK 0.10.0 (matching on `eu.exeris.sdk.annotation.*` FQNs, since a
word-grep false-positives on `Rule` / `EventHandler` / `GraphEdge` against our own and the kernel's
types) found the processor names **21 of ~44** annotations.

- [ ] **S1 — `@Saga.version` is never extracted.** `SagaMetadata.version` is never set, so
      `@Saga(version = 3)` silently yields `1`. Cosmetic until kernel 0.11 / ADR-064, which keys the
      plan catalog by `(name, version)` and **fails closed on an unregistered version** — so this is
      now a wrong-output defect with a runtime consequence, not a coverage gap. `KernelSagaGenerator`
      emits no version at all.
- [ ] **S2 — a repeated `@SagaStep` yields zero steps** from the processor (SDK 0.10's survey; the
      `-io` reader yields only the first). `@SagaSteps` compiles and contributes nothing.
- [ ] **S3 — `@GraphEdge` processor half.** `GraphEdgeMetadata` exists and nobody fills it.
- [ ] **C0 — strict mode audits the wrong half, and this is why the list above exists.**
      `-Aexeris.strict` reports *extracted-but-unconsumed*; an attribute the processor never reads is
      invisible to it, which is why `INERT_ATTRIBUTES` holds 2 entries against ~23 uncovered
      annotations. Do this first in the C phase — it closes the defect *class* rather than the
      instances, the same move SDK 0.10 made with its `@Repeatable`-container guard.
- [ ] **C1 — `annotation.system.*` (10 field-level).** Trap worth naming: `DomainMetadata.systemFields`
      *is* populated, but from `@ExerisDomain`'s override attributes (`extractSystemFieldsOverrides`),
      never from `@PrimaryKey` / `@TenantId` / `@Version` / `@SoftDelete*` / `@Audit*`.
- [ ] **C2 — `annotation.security.*`.** `@Encrypted` and `@RowLevelSecurity`; the latter overlaps the
      RLS predicate `KernelFlywayGenerator` already drives from `dataScope`, so it needs a design
      call, not just extraction.

Deliberately **not** debt: `@Projection`, `@EventHandler`, `@Derived`, `@Rule` — AST carriers and
`DomainMetadata` components exist, filled and read by nobody, and that reservation is design-gated on
the behavioural corpus rather than overlooked.

### Dog-food reconciliation (T-namespace) — 2026-08-18

The `T*` numbers in this file and the `T*` numbers in the Stellar-Tactics dog-food log
(`dogfooding-findings.md`, in the showcase repo) are **one namespace**, not two. This file carries
T1–T26 because that is as far as the backlog had been transcribed; the log continues to **T50**, and
adds `V1–V3` (the `@View` emitter), `S1–S5` (SDK), `K1–K7` (kernel) and `D1–D3` (DX). Findings are
numbered *there*, by the consumer that hits them; this file records the tooling-owned subset and its
disposition. That direction is deliberate — a finding is minted by whoever measures it, and tooling
does not get to renumber somebody else's evidence.

Everything below was **re-verified against this tree** rather than transcribed. The log is a good
document and it says so itself: it records three occasions where it published an inferred cause as a
measured one (the OpenAPI determinism claim, the T19 re-grade, the T43 cause). So a status is listed
here only with the `path:line` that settles it, and anything not re-checked in this pass is marked.

**Closed by the 0.8.0 dog-food batch (#166), with the log's numbering:** T31 (`REFERENCES tenants(id)`
against a table nothing emits), T33 (RLS enabled but never forced — the owner role read every
tenant's rows), T34 (`current_setting(…, true)` returns `''` after a pool `RESET`, so `''::uuid`
raised on every recycled connection), T35 (the policy read `app.tenant_id`; the kernel publishes
`exeris.tenant_id`), T38 (client requested `/api/<version>/…` against a router serving the entity
path), T39 (`findAll()` decoded `List<LinkedHashMap>` under a `@SuppressWarnings`), T41 + T45 (the
tenant guard, and its extension past CRUD to actions), T44 (`@Action.path` registered inert),
V1–V3 (the emitted `@View` did not parse, bound a `current()` no generator produced, and did not
iterate). Plus one the batch itself introduced and the FE gate caught: wiring `StoreGenerator` made a
never-invoked emitter start emitting, and its output did not build.

**Open and tooling-owned.** These enter the 0.8.0 backlog:

- [x] **T49 — the generated composition root is closed.** *Shipped 2026-08-18 (ADR-070).* A third
      bootstrap file, `RuntimeComponents`, owns the construction of every generated repository,
      service, handler and stream handler behind a `protected create*` factory with a memoising
      public accessor; `RuntimeLifecycle` calls `new` on no generated type and offers its
      `HttpRouter.Builder` to a `configureRoutes` hook after the last generated route.
      `Application#components(TransactionalExecutor)` is the installation point, and it is invoked
      inside the boot callback so a factory body can resolve `KernelProviders.flowEngine()` /
      `eventEngine()`. The emitted `main()` now states that it is **not** polymorphic — a subclass
      overriding a hook is not reached through it — because a seam whose obvious entry point ignores
      it silently is the failure class this backlog keeps recording. Original finding below.

      **T49 — the generated composition root is closed.** `KernelApplicationGenerator` emits
      `public final class RuntimeLifecycle` (`KernelApplicationGenerator.java:493-494` **in the
      pre-ADR-070 tree** — the line moved with the fix), constructing every service inline. `Application` exposes three `protected` hooks, all infrastructure
      (`subsystems()`, `transactionalExecutor()`, `capManifest()`); none admits application logic. So
      a consumer's hand-written service subclasses — the arrangement this repo's own rule 1
      prescribes — cannot be installed into the running app at all. The seam has to admit
      *construction*, not just configuration: a saga needs the `FlowEngine`, a publisher the
      `EventEngine`. **Highest-leverage item in this list**: T48 and T50 are both downstream of it,
      and it is the difference between an app that serves CRUD and an app that runs.
- [x] **T48 — emitted event publishers are never invoked.** *Shipped 2026-08-27 (ADR-075).* The
      publisher is a component in `RuntimeComponents` and a constructor argument of
      `<Entity>Handler`; the processor extracts the trigger triple SDK 0.11.0 shipped; and
      `handleCreate` / `handleUpdate` / `handleDelete` / each action handler publish the events whose
      trigger they satisfy, after the mutation and before the response.

      **Neither option this entry recorded was taken, and the measurement is why.** An action is
      invoked on the *entity, by the handler*, and never reaches the service — so a publisher held by
      the service, whether as a constructor argument or as a generated decorator, is structurally
      unable to see the `ACTION` trigger, which is the case this finding names. Both options would
      have closed the CRUD half and left the half that motivated it.

      Three consequences stated rather than designed around: publishing is coupled to the HTTP
      transport (a saga calling the service directly publishes nothing); the publish runs after the
      commit, since the transaction boundary is in the repository, so `FLAG_PERSISTENT` makes
      delivery durable but not the publish; and `FIELD_CHANGED` / `STATE_TRANSITION` / `SCHEDULED` /
      `MANUAL` / `SNAPSHOT` publish nothing, each needing a source of truth the handler does not
      have. Original finding below.

      **T48 — emitted event publishers are never invoked.** `KernelServiceGenerator`'s javadoc
      (`KernelServiceGenerator.java:24-27`) says publishing is "intentionally out of scope" and that
      the emitted `*EventPublisher` "is wired separately by the application bootstrap". No emitter
      performs that wiring. The declared chain `@Action` → `@DomainEvent` → saga is generated at every
      link except the call, so an action whose real work lives in a saga returns `200` and does
      nothing. Worth stating precisely: the emitted code *documents* a step nothing does.
      **Now has somewhere to land** (ADR-070): a consumer can already close the chain by overriding
      `createOrderService()` to return a publishing subclass built with
      `new OrderEventPublisher(KernelProviders.eventEngine())`. Doing it in the pipeline is what
      remains, and the design question the seam does not answer is whether the publisher becomes a
      constructor argument of the generated service — changing every consumer's tree — or a
      generated decorator installed by the default factory.
- [x] **T29 — `DataScope.UNIVERSE` fails the build on the one shape it describes.**
      *Shipped 2026-08-18.* The processor now refuses a `UNIVERSE` declaration with an ERROR
      naming what would have been emitted (the TENANT shape, binding `getTenantId()`), why it
      breaks (a shared-world row has no tenant property), and the one thing the author can do
      today (`dataScope = TENANT`, and there is no cross-tenant read-widening from this build
      yet). The emitter policy is **unchanged** — `isTenantPartitioned` stays fail-closed,
      because metadata also reaches the emitters from the `-io` reader and from the Maven
      plugin reading metadata JSON, neither of which passes the processor's diagnostics.
      A contradicted declaration still reports the contradiction only, so one line never
      raises two errors. Original finding below.

      **T29 — `DataScope.UNIVERSE` fails the build on the one shape it describes.**
      `DataScopeSupport.isTenantPartitioned` is fail-closed ("not GLOBAL"), which is the right policy
      (`DataScopeSupport.java:63`). The defect is the consequence: an entity with no tenant
      system-field block — which is what a shared-world row *is* — gets a `tenant_id` column and a
      `getTenantId()` call, so the build fails with `cannot find symbol` inside generated code the
      consumer is told not to edit. Fix is a processor **ERROR** refusing the declaration, not a
      change of policy. (The stale "this repo does not pin 0.11 yet" rationale is corrected in the
      same change; U0 pinned it.)
- [x] **T43 — a missing binding is reported as the caller's bad request.** *Shipped 2026-08-18 —
      the refusal half. Two corrections to this entry came out of doing it, both below.*
      `parseBody` now bound-checks `MEMORY_ALLOCATOR` and throws `IllegalStateException` naming the
      wiring, which the existing dual-catch re-throws unchanged, so it surfaces as 5xx exactly like
      the unbound-registry case one line above. The generator comment that listed "or the allocator"
      among the *intended* 400 sources is gone — that comment was the defect, written down as a
      decision.

      **Correction 1: the "resolve lazily" half is not implementable as filed.** It read "the
      community JSON decoder null-checks the context and then ignores the allocator", so deferring
      resolution would cost nothing. But the context is constructed before the decoder ever sees it,
      and `HttpRequestDecodingContext`'s compact constructor does
      `Objects.requireNonNull(allocator, "allocator must not be null")`. A null allocator is refused
      by the SPI, one frame earlier and still inside the same `try`. There is nothing to defer.

      **Correction 2: the likely root cause is a capture-site difference, not a missing subsystem —
      inferred, not measured.** `MEMORY_ALLOCATOR` is supplied by the `memory` subsystem, and the
      default selector does boot it: `CommunityHttpSubsystem.dependsOn()` returns `["memory"]`, so
      it arrives transitively even though the emitted list
      (`http,persistence,graph,flow,events,crypto`) does not name it. Adding `memory` to that list
      would therefore change nothing. What differs from the working reference is *where the
      allocator is read*: `CommunityBenchmarkRuntimeLifecycle.java:100` resolves it **once, at
      composition time, inside the boot callback** and passes it into its handler as a constructor
      argument, whereas the generated handler calls `.get()` **per request**, on whatever thread the
      transport dispatches. A `ScopedValue` binding is visible only within its dynamic scope, which
      is consistent with the dog-food probe finding it unbound at request time — and with the T41
      guard in the same file existing at all, since `STORAGE_CONTEXT` has the same shape. Not
      verified by running a generated app, so it is recorded as the leading hypothesis rather than
      as the cause. **The follow-up is now cheap:** capture the allocator in
      `RuntimeComponents.createOrderHandler()` and pass it to the handler — the T49 seam is exactly
      the place that construction belongs, and it is the reference's shape. Original finding below.

      **T43 — a missing binding is reported as the caller's bad request.** `parseBody` resolves
      `KernelProviders.MEMORY_ALLOCATOR.get()` eagerly when building `HttpRequestDecodingContext`
      (`KernelHandlerGenerator.java:553`). An unbound allocator raises inside the `try` that maps
      everything to **400**, so a deployment fault is blamed on the request body. Two independent
      fixes: give it the T41 treatment (refuse, naming the missing binding), and resolve lazily —
      the community JSON decoder null-checks the context and then ignores the allocator.
- [ ] **T43-follow-up — capture the allocator at composition time.** Split out of T43 so it does not
      live as prose inside a closed item. T43 made the failure honest; this is what would stop it
      happening. `RuntimeComponents.createOrderHandler()` resolves `KernelProviders.MEMORY_ALLOCATOR`
      once, inside the boot callback where the binding is live, and passes it to the handler as a
      constructor argument — the shape `CommunityBenchmarkRuntimeLifecycle.java:100` already uses and
      the reason that app works with the same subsystem selector. Costs a handler constructor
      parameter, which the T49 seam absorbs. **Do the measurement first:** the capture-site theory is
      inferred from the reference and from the dog-food probe, not from running a generated app, and
      a fix aimed at the wrong cause is worse than the honest 5xx we now emit.

      *Deliberately not minted as `T51`.* The `T*` space is shared with the dog-food log, which runs
      to T50 and is not readable from this checkout; taking the next number from a stale local view
      is exactly how ADR-070 got claimed twice on 2026-08-18. A follow-up to a numbered finding does
      not need a number of its own.
- [x] **T36 — the repository stamps three system fields and not the fourth.** *Shipped 2026-08-26.
      Stamped, not documented — two corrections to this entry came out of doing it, both below.*
      `save` and `update` now fill an absent tenant from the ambient `StorageContext` before binding
      it, exactly as `save` has always filled an absent `id`. A tenant the caller **did** set is left
      alone: whether that value is one this deployment may write is the RLS `WITH CHECK` predicate's
      decision, and re-deciding it in emitted Java would be a second implementation of a rule the
      database already enforces. The resolver reads `KernelProviders.storageContext()` — the accessor
      documented for request-scoped code, which throws rather than falling back to the system scope —
      and raises `IllegalStateException`, never `IllegalArgumentException`, on both an empty
      isolation key and a non-UUID one, so a deployment fault cannot reach the handler as the 400
      that `UUID.fromString` would otherwise have produced (ADR-036 §2; T43, same lesson).
      The generated `<Entity>RepositoryTest` binds a tenant around each write and gains two cases
      proving the fill and the non-overwrite; `GeneratedTestsE2ETest` executes them.

      **Correction 1: "forget it" understates it — nothing upstream ever supplied the value.** The
      entry reads as a caller mistake. It is not: the emitted handler decodes a request body straight
      into the entity (`KernelHandlerGenerator.java:408`), and the emitted Angular form lists the
      tenant among the system fields it never renders or sends (`form-gen.ts:90`). So the value bound
      was `null` on every create, and on every update built from a request body rather than from a
      read — which is what the emitted `PUT` path does. The emitted stack, end to end, could not
      write a row to a tenant-scoped table. That also settles the entry's third option: "say the
      caller owns it" was not available, because on the generated path the caller has no way to.

      **Correction 2: the carrier is not a `UUID`, and the conversion is not a new assumption.**
      `StorageContext.isolationKey()` is `Optional<String>`, while the tenant column is `UUID` and
      binds through `bindUuid`. The bridge is `UUID.fromString` — and it imposes nothing new, because
      the RLS policy this pipeline's own migration emits already casts the session key
      (`NULLIF(current_setting('exeris.tenant_id', true), '')::uuid`, `KernelFlywayGenerator.java:88`).
      A non-UUID isolation key was already fatal one layer down; it now fails at the write, named.
      Original finding below.

      **T36 — the repository stamps three system fields and not the fourth.** `save` stamps
      `setId(randomUUID())` and both audit timestamps (`KernelRepositoryGenerator.java:620`) and never
      touches `tenantId`, although the kernel has it bound as a `ScopedValue`. The asymmetry is the
      trap: forget it and the write is refused by the RLS `WITH CHECK`, reported as a security
      violation rather than the omission it is. Either stamp it from the ambient `StorageContext` or
      say in the emitted javadoc that the caller owns it — silence is the worst of the three.
- [x] **T40 — an entity named `Component` breaks its own generated Angular code.** Shipped 0.8.0.
      The finding named `form-gen.ts:141,144` and guessed at the blast radius — "`Directive`,
      `Injectable`, `Pipe`, `Input`, `Output`, `Signal` and `Type` are the same shape". Generating
      an app per candidate name and reading the emitted modules back said otherwise: `Directive`,
      `Pipe`, `Input`, `Output`, `Signal` and `Type` are **not** imported by any emitted module and
      never collided, while `Page` and `PageRequest` — helper types the emitted *service* declares,
      not framework names at all — collided in four files each. The measured set is 22 names, and
      the two the finding could not have guessed are the ones this repo emits itself.

      So the fix is neither the finding's "alias unconditionally" nor a reserved-word list against
      Angular releases. `modelTypeName` renames the entity's own type **only** when the name is one
      an emitted module already binds, and `model-naming.spec.ts` derives the candidate set *from
      freshly generated output* — every identifier the emitted app imports from a package, plus the
      helpers it declares — and asserts no module binds a name twice. A new import or helper type
      enters that set on its own. Emitted output for an ordinary entity is byte-identical (verified
      by diffing a full generated app against `main`), and the sample app in CI now carries an
      entity literally named `Component`, so `ng build` proves it rather than a substring assertion —
      and it earned its place immediately: it failed on the first CI run over a site the unit spec
      could not see, the emitted `src/app/index.ts` barrel, which **re-exports** `<Entity>Filter`
      rather than importing it. The spec now reads a re-export clause the same way it reads an
      import, and runs the check for a colliding name as well as the suffix case.

      A second bug of the same shape fell out and is fixed here: `DslMapper.toInterfaceName` stripped
      a trailing `Entity` from the **declared** interface name only, so an entity named
      `CustomerEntity` had its types module declare `Customer` while every other emitter imported
      `CustomerEntity` from it. Unreachable behaviour — an app with a `*Entity` domain never
      compiled — visible only to a unit test that pinned it in isolation. The strip is gone, the
      helper with it, and one function now decides the name for declarer and importer alike. No ADR:
      neither half had a working consumer to migrate. Original finding below.

      **T40 — an entity named `Component` breaks its own generated Angular code.** `form-gen` emits
      `import { Component, … } from '@angular/core'` and `import { <Entity>, … } from '../services/…'`
      unaliased (`form-gen.ts:141,144`), so an entity called `Component` collides with the decorator
      in the file it decorates. `Directive`, `Injectable`, `Pipe`, `Input`, `Output`, `Signal` and
      `Type` are the same shape. Alias unconditionally rather than maintaining a reserved-word list
      against every Angular release.
- [x] **T50 — the build fails when the generated app has no driver to run on (ADR-078).** Shipped
      0.8.0, taking the finding's own second option. Checking the premise against the **published
      jars** rather than the source tree sharpened it: `exeris-kernel-core-0.11.0.jar` carries
      **zero** `META-INF/services` entries, and subsystems are not resolved one SPI at a time —
      `BootstrapSelector` selects *by name* from `ServiceLoader<SubsystemProvider>`, and Core
      registers no `SubsystemProvider` at all. So SPI + Core alone is not "a subsystem that fails to
      start", it is an application in which no requested name can resolve. That is also what makes
      the gate non-vacuous: Core cannot satisfy it.

      New goal `exeris:verify-runtime` at `process-classes`, `requiresDependencyResolution = RUNTIME`
      so Maven injects the set the application actually starts with. It scans that classpath for a
      `META-INF/services/<spi>` registration — a file read, no consumer class loaded, no dependency
      added beyond `java.nio.file` and `java.util.zip`.

      The required SPIs come from **what the pipeline emitted**, never from the `subsystems()`
      string: repositories and handlers always (so `SubsystemProvider`, `PersistenceProvider`,
      `HttpProvider`), plus `EventProvider` / `GraphProvider` / `FlowProvider` where a
      `@DomainEvent`, graph metadata or a saga made the corresponding artefact exist. Crypto is
      absent even though the default string names it — the emitted javadoc invites the consumer to
      override that string, so a check driven by it would fail correct builds.

      **Named rather than discovered later:** an unbound goal is silent, exactly like an unbound
      `verify-capabilities` — the same shape as a config flag read by nobody. A plugin cannot bind
      itself into a consumer's lifecycle, so the migration entry leads with the execution block
      instead. Original finding below.

      **T50 — the emitted app declares no runtime driver.** `Application.main()` boots subsystems by
      name and every provider behind those names is in `exeris-kernel-community`; nothing in the
      emitted build says so, and the failure is a bootstrap error naming a subsystem rather than a
      jar. This is **T30 one phase later** and it fails worse. Tooling emits no `pom.xml`, so the
      honest options are a documented requirement or — smaller and better-timed — failing the *build*
      when a selected subsystem has no provider on the runtime classpath.
- [x] **T42 — the mesh has no generated frontend contract.** Types slice shipped 0.8.0 (ADR-048).
      `codegen-ts` was single-service by construction: one metadata directory in, one app out. A mesh
      consumer retyped the other service's vocabulary by hand across a language boundary with no
      compiler between.

      **What shipped:** `--peer <name>=<path>` (repeatable; also `peers` in the config file) reads a
      peer's **contract artifact** — its `cap-manifest.json` plus the `DomainMetadata` of the
      entities it provides — and emits that peer's DTOs under `src/app/peers/<name>/`: the entity
      interface, its `Create`/`Update` shapes, its Zod schemas under `generateZod`, its own enum
      module and its own barrel. No client, no registry, no saga dispatch — those are the 0.9.0
      slice, and the client half is the one pinned to a final kernel 0.12.

      Three things the implementation had to settle that the ADR left to it. **The manifest is
      required even though nothing reads its body yet** — it is what makes a directory a contract
      rather than a pile of JSON, and accepting a manifest-less directory would ship exactly the
      input model ADR-048 rejects, with no way to take it back once consumers adopt it. **`Filter`
      and `ListResponse` are not emitted for a peer**, unlike the local emitter: they describe this
      app's own list/query surface, and for a peer whose client arrives in 0.9.0 they would describe
      a query nobody can make — the inert-emitted-surface failure mode D10, D11 and the unwired
      templates each record. **Peer entities are sorted by name**; the local path inherits
      `readdirSync` order, which is stable for one tree on one machine, but a published artifact is
      unpacked by tools we do not own onto filesystems we do not own.

      The guarantee is a compile, not a substring: `verify:generated` now runs a
      `two-peers-same-entity` case where the app and two peers each declare `Order` and one peer
      declares an enum with the app's own enum name, and a hand-written consumer module imports all
      three `Order`s into one namespace under distinct local names. Perturbed both ways — merging the
      peer namespaces gives TS2307, re-exporting a peer from the app's `types/` barrel gives TS2308
      on `Order` — which is the T40 break at mesh scale. The CI `ng build` sample carries the same
      three-`Order` shape plus a peer entity named `Component`; a deliberate type error in an
      unreferenced peer file was confirmed to fail it, so that gate is not vacuous either.

      One measurement worth carrying: the contract artifact has **two independently-versioned
      `schemaVersion` fields** — an integer on `cap-manifest.json` (`CapabilityGraph.SCHEMA_VERSION`,
      today 2) and a *string* on each entity JSON (`SchemaVersion.CURRENT`, today `"0.11.0"`, the
      ADR-042 baseline-trust stamp). ADR-048's "floor 2" can only be the manifest's. The entity
      stamp is a *skew* check, not a floor, and enforcing it as equality here would require every
      peer to have been built with the consumer's exact SDK version — which contradicts ADR-048's
      own "apps stay independently built and versioned". Left unchecked in this slice, deliberately;
      it belongs with the registry slice that actually reads the peer's contract for trust.

      Original finding below.

      **Blocked on the mesh RFC being accepted, not on effort** (established 2026-08-28 on picking it
      up). The entry's own "cheap honest version" — *point the CLI at a second metadata directory and
      emit `types/`* — contradicts two things
      [`RFC-2026-06-29`](docs/rfc/RFC-2026-06-29-cross-app-contract-mesh-tooling.md) has already
      decided, and that RFC is the design gate for the reserved **ADR-048**: the contract source is
      the **published contract artifact** (cap-manifest + provided-entity `DomainMetadata`,
      `schemaVersion` floor 2, ADR-042 baseline trust), with peers-in-one-build as the *degenerate
      same-build case* — which is exactly what "a second metadata directory" is. Building it that way
      would ship a second input model the artifact model then retires, and skip the trust check that
      makes a peer contract worth importing. The RFC also pins the peer DTO emitter as Java∪TS, so a
      TS-only emission needs its parity story stated rather than assumed.

      Recorded as **Amendment 1** on the RFC: T42 becomes the RFC's **first slice** — *peer types, no
      peer client* — emitted per peer under its own namespace with its own enum module and barrel,
      never merged into the app's own `types/` barrel (two peers may both call an entity `Order`, and
      **T40** is the record of what happens when two identifiers meet in one namespace). It needs
      neither K4 addressing nor the T17 capability twin, so it can ship while those stay gated. The
      peer-namespace scheme and DTO dedup are the two questions it cannot settle by itself; the ADR
      does.

      **Amendment 2, same day:** checking the RFC against kernel `development/0.12.0` before
      accepting it falsified one of its premises. Kernel **ADR-074 is ACCEPTED** — the addressee now
      rides on `HttpRequest` as an `authority`, and `KernelWebClient.withAuthority(String)` addresses
      a peer while sharing engine and retry policy. So "`KernelWebClient` is single-host today" is
      false on 0.12, the client+DTO slice stops waiting on addressing, and the RFC's proposed
      `PeerAddressResolver` should be **dropped rather than stubbed**: the kernel deliberately holds
      the `ServiceResolver` seam post-1.0, and ADR-074 records that a future resolver's output simply
      *becomes* the authority. Emitting our own resolver interface would stand up a second addressing
      vocabulary one train before the platform's. Cost to carry: `HttpRequest` is a `stable` carrier
      and ADR-074 takes a binary break on it behind a bridge constructor, so the client slice needs
      the kernel pin moved to a **final** 0.12. T42 itself is untouched — peer types need no client,
      no authority and no resolver.

      **T42 is not gated on kernel 0.12** — worth stating plainly, because Amendment 2 makes it easy
      to assume otherwise. Peer types are `DomainMetadata` in, TypeScript types out: no client, no
      authority, no resolver, no kernel version at all. The 0.12 pin belongs to the **client+DTO**
      slice, which ADR-074 turned from kernel-free-with-a-stub into kernel-pinned. Which release
      carries T42 is therefore a scheduling choice, not a dependency.

      **RFC ACCEPTED 2026-08-28**, with the artifact-format ruling taken at acceptance: **full
      `DomainMetadata`, no pruning**. Ratified as
      [`ADR-048`](docs/adr/ADR-048-cross-app-contract-mesh.md), which also settles the two questions
      the slice could not: a peer is **named by the consumer** (measured: nothing in the emitted
      artefacts carries an app identity — `CapabilityModuleDescriptor` names a *module*), and peer
      DTOs are **per-consumer copies** rather than a shared package. T42 is unblocked and gated on
      nothing: next action is the types slice itself.

- [ ] **`npm start` proxies a prefix the emitted client no longer requests.** Measured while
      fixing the CLI-override defect (which had every generated app calling `/api/<path>` at a
      router serving `/<path>`). With `apiBasePath` correctly empty, the emitted service requests
      `/orders`; the emitted `proxy.conf.json` still declares a single rule for `/api` with **no**
      `pathRewrite`, and `package.json` wires it as `ng serve --proxy-config proxy.conf.json`. So
      the rule now matches nothing and `npm start` cannot reach the backend at all — before the
      fix it matched and forwarded `/api/orders` verbatim to a server serving `/orders`, i.e. it
      404'd. Both states are broken; the prefix fix changes which way.

      Not folded into that fix deliberately: `generateProxyConfig()` takes no arguments and cannot
      know the entity paths, so making it correct is a **design** choice (one rule per
      `effectivePath()`, or a single catch-all) rather than a one-line correction, and it wants its
      own evidence — including what `ng serve` does with a path that collides with an Angular
      route. `generateAppStructure` already has `domains` at the call site, so the input is there.

      **Three more `/api` sites survive the same fix** (found in the #191 review, verified against
      real CLI output). None reproduces the 404 today; all three are the same `''`-vs-`/api`
      confusion and would resurrect it:

      - `app-structure-gen.ts:710` — `apiUrl: config.apiBasePath || clientConfig.baseUrl || '/api'`
        uses `||`, so the now-correct `''` is falsy and falls through to the strategy's hardcoded
        `/api` (`backend-strategy.ts:222`). A real CLI run emits `environment.ts` declaring
        `apiUrl: '/api'` while the services beside it call `/orders` — a generated artefact that
        contradicts its own siblings. Harmless only because **nothing imports it**: grepping the
        emitted tree for an `environment` import returns zero hits, so it is also an instance of
        the "emitted and read by nobody" class this list already tracks. Note this contradiction
        is *introduced* by the #191 prefix fix on the CLI path — before it, both said `/api`.
      - `generator-registry.ts:308` — `apiBasePath: config.apiBasePath ?? '/api'`, a second,
        drifted declaration of the old default. Dormant: its only caller (`orchestrator.ts:112`)
        always passes a fully-resolved config where the value is `''`, never `undefined`. It would
        wake for any future partial-config caller that bypasses `loadConfig`.
      - `backend-strategy.ts:310` — `KernelStrategy.generateClientCode` builds
        `transformPath('/api', entityPath)` with the prefix hardcoded past any config. It has **no
        production caller at all** (only its own two tests), making it a third emitter wired by
        nobody, alongside `enum-gen.ts` and the D10 bearer path.

- [ ] **Three config flags are declared, default `true`, and read by nothing.** `generateDetails`,
      `generateSagas`, `generateEvents` (`config.ts:54,60,63`); only `generateStores` is read, and
      only since #166 (`orchestrator.ts:164`). That a flag can default to `true` and be honoured by
      nobody is worth more than any of the three individually — it is the same silent-failure shape
      as an inert annotation, one layer out, and it is exactly how `view-gen` came to bind a
      `current()` that no generator produced. Each wiring changes every consumer's tree, so each
      needs its own change with its own evidence.
- [ ] **The inert registries are far smaller than the unread surface.** `INERT_ATTRIBUTES` holds four
      entries and `INERT_ANNOTATIONS` one (`ExerisDomainProcessor.java:175,215`), against ~23
      annotations the processor never reads. `@Derived`, `@Rule` and `@NavMenu` reach no generator —
      verified: `DerivedMetadata`/`RuleMetadata` appear in no emitter or processor source — and none
      is registered, so `-Aexeris.strict` is silent about all three. This is **C0** in the section
      above, and the dog-food log is the corpus that sizes it: `@NavMenu` is decorative on most of a
      real domain.

**Open, verified, and deliberately not scheduled here:**

- **T6 (naive plural) is centralised but unchanged.** `KernelTableNaming` exists precisely so the
  repository and the migration cannot disagree, and it deliberately returns
  `toSnakeCase(entityName) + "s"` because changing it would change default output for every existing
  consumer (`KernelTableNaming.java:15,42`). The seam for a fix now exists in one place; the fix is a
  breaking regeneration and wants a migration note, not a quiet edit. Fresh evidence that it still
  bites: a downstream hand-copied DDL said `reassemblies` while the generator emits `reassemblys`.
- **OpenAPI key order is toolchain-dependent, not per-run.** The log filed this as hash-seed churn and
  then corrected itself: five consecutive fresh-JVM runs are byte-identical, and the order moved once,
  across a toolchain change. Still worth sorting the keys — every toolchain bump emits a large
  content-free diff on a committed L1 tree — but it is a per-bump cost, not the determinism violation
  first reported.

**Not re-verified in this pass**, carried from the log and marked as such: T5 (system-field overrides
ignored by the repository generator), T20b (the TS pruner does not remove cross-service orphans),
T20d (`form-gen` coerces numeric fields but not booleans), T25/G6 (theme-variant binding for `@View`),
T30 (emitted imports as undeclared build requirements — the general case behind T50).

- [ ] **T2 — the FE spec slice.** The half of the generated-test story that did not ship in 0.7.0
      (the Java half is complete — slices a–f, ADR-058). Deferred at the 0.7.0 cut on 2026-08-18.
      It is not "emit some spec files": the emitted app declares `"test": "ng test"` and ships
      **no runner and no test dependencies**, so a spec alone would be unrunnable. The slice owes,
      in one piece: a `test` target on `@angular/build:unit-test` in the emitted `angular.json`
      (Vitest, founder-ruled 2026-07-31), a `tsconfig.spec.json`, the matching devDependencies, an
      opt-in flag that is the TS-side counterpart of `-Dexeris.tests=true`, the spec emitters
      themselves, and a CI gate that **runs** them rather than only type-checking them — the same
      ruling ADR-058 made for the Java half, for the same reason.
      Two constraints carry over from that ADR and are worth restating before anyone starts: tooling
      emits no `package.json` dependency the consumer did not ask for, so whatever a generated spec
      imports becomes a hard requirement on their build; and the emitted doubles must not drift from
      the surface they double — on the Java side that was solved by emitting double and subject from
      one source of truth, and the FE side has the same trap in `service-gen.ts`.
- [ ] **T12 + T17 — the cross-app contract mesh epic.** Deferred out of 0.7.0 by the gateway-caps
      plan; ADR-048 is reserved and RFC-2026-06-29 is the design gate that must close first.
- [ ] Generator output adjustments based on real budgetHQ + IDP-cap consumer feedback
- [ ] Remaining items from the **Codegen completeness backlog** below — High-severity items
      (T1, T8, T10, T12) are pulled forward into earlier milestones; the rest land here
- [ ] Performance: large-corpus generation profiling + fixes
- [ ] Memory: stream metadata loading instead of slurping all `*.json` upfront

## 1.0.0 GA — stable codegen + plugin

> Goal: any 1.x release produces source-compatible output; user apps' generated code keeps compiling across 1.x bumps.

- [ ] **Annotation-surface coverage — every SDK annotation the kernel makes buildable reaches emitted
      output.** Founder scope call, 2026-08-18. Measured the same day against `exeris-sdk` `main`:
      **51 annotation types** are declared (55 files under `annotation/**` less four `package-info`),
      and `ExerisDomainProcessor` references **21** of them by FQN — the four roots
      (`@ExerisDomain`, `@Saga`, `@CapabilityModule`, `@View`) plus the members it walks. Of those 21,
      exactly one — `@EventSourced` — is registered in `INERT_ANNOTATIONS`, i.e. extracted and
      consumed by no generator.

      **One is excluded, because the kernel cannot yet host it**, verified rather than assumed:

      - `@Blob` — the v0.11 blob SPI shipped with two Community drivers, but there is **no
        `CommunityStorageSubsystem`**: the ten bootable subsystems are crypto, events, flow, graph,
        http, memory, persistence, scheduling, security, transport. `Application.main()` boots
        subsystems *by name*, so a generated app has no name to declare. Kernel-side ask (K6).
        SDK 0.11.0 shipped the design-time half (`@Blob` + `FieldMetadata.blob`, ADR-072) — see
        [`docs/adr/ADR-072.link.md`](docs/adr/ADR-072.link.md), which also carries the build-time
        rejection this repo owes: `@Blob` on a `dataScope = GLOBAL` entity is unstorable by
        construction, because an absent `isolationKey` is a terminal deny in `BlobStore` and `GLOBAL`
        is exactly the tier that leaves it empty.

        **The "no subsystem to name" reading is the smaller half of K6 — corrected 2026-08-27** from
        a kernel-side reading, then re-verified here against the tag this repo pins (`v0.11.0`), not
        transcribed. Three things a transcription slice would otherwise walk into:

        1. **A subsystem name alone would emit an app that does not boot.** Both drivers
           (`CommunityFilesystemBlobStorageProvider`, `CommunityS3BlobStorageProvider`) register at
           the same Community priority, and the kernel's disposition is that an unset selection key
           with more than one provider present is a **startup failure, not a default** — by design.
           So whatever K6 delivers, the emitter's obligation is a name *plus* a configuration key, or
           forcing the author to supply one. This is the exact asymmetry against `@Schedule`, and it
           is why K6 exists at all: scheduling has one provider and can fall back
           (`JobSchedulerConfig.DEFAULT_NAME`); storage has two, so a fallback is not available to it.
        2. **There is no `KernelProviders` slot to bind, and the obvious name is taken.** At `v0.11.0`
           the slot list carries `JOB_SCHEDULER` + `JOB_SCHEDULER_PROVIDER` for scheduling and
           **nothing** for blobs — while `STORAGE_CONTEXT` already exists and is the ADR-012
           isolation-key carrier (the one T36's emitted `actingTenantId()` reads), not a store. K6
           should name its slot unambiguously; "storage" is spoken for.
        3. **Blob storage is invisible to kernel introspection.** `CommunityProviderInventory.discover`
           sweeps nine SPIs — memory, crypto, telemetry, persistence, events, flow, transport, graph,
           security — and `BlobStorageProvider` is not among them despite being `ServiceLoader`-
           registered. Measured here at `v0.11.0`, and one addition to the kernel-side report:
           **`JobSchedulerProvider` is missing from that sweep too**, so the gap is not blob-specific.
           This matters to us specifically if tooling ever probes kernel capability through
           `exeris-ai-bridge`'s `kernel:list_providers` (D4's neighbourhood): it would report "no
           storage backend" for the wrong reason — the SPI is there, the inventory does not look. A
           cheap, independent kernel-side ask, and the only one that unblocks introspection *before*
           the subsystem exists. Unnumbered here on purpose: the kernel mints K-numbers.

        **And the timeline is not ours.** `exeris-kernel/docs/subsystems/storage.md` opens its status
        with "Post-1.0 per the ROADMAP's narrowed-core decision — 1.0 GA is not gated on this
        subsystem." So `@Blob` is not an exclusion waiting to be revoked before our 1.0; it is
        excluded against a subsystem the kernel has deliberately scheduled *after* it. The 50-of-51
        target below is honest only while it says that, which it now does.

      *(`@EventSourced` sat here as a second kernel-gated exclusion until 2026-08-18. That was
      wrong — see the correction below the table; the target moved from 49 to 50.)*

      Everything else in the unread 30 is buildable against the kernel as it stands today, and most
      needs no kernel surface at all:

      | Family | Count | What it needs |
      |---|---|---|
      | `system.*` — `@PrimaryKey`, `@TenantId`, `@Version`, `@SoftDelete*`, `@Audit*` | 10 | nothing new; the columns are already emitted, just derived from `@ExerisDomain`'s override attributes instead of these markers (**T5 / C1**) |
      | presentation — `@Tab`, `@UIGroup`, `@NavMenu` | 3 | frontend emission only, no kernel involvement |
      | behavioural — `@Derived`, `@Rule`, `@Rules`, `@EventHandler`, `@Projection` | 5 | pure emission + the events subsystem; design-gated, not capability-gated |
      | graph — `@GraphEdge`, `@GraphEdges`, `@GraphProperty`, `@GraphQuery` | 4 | `CommunityGraphSubsystem` + the existing `KernelGraphSyncGenerator` (**S3**) |
      | saga — `@SagaSteps`, `@SagaTransition`, `@SagaTransitions` | 3 | `CommunityFlowSubsystem`; `FlowDefinitionBuilder` already carries transitions (**S2**) |
      | `security.*` — `@Encrypted`, `@RowLevelSecurity` | 2 | `CommunityCryptoSubsystem` exists; RLS overlaps the `dataScope`-driven predicate, so it is a design call (**C2**) |
      | `@QueryParam`, `@Schedule` | 2 | the HTTP layer and `CommunitySchedulingSubsystem`, both present — but `@Schedule` carries one open question that is not ours (below) |
      | `@EventSourced` | 1 | **re-measured 2026-08-18: not kernel-gated** — the replay SPI is on the pinned line; see below (**EV2**) |

      **`@EventSourced` is tooling debt, not a kernel ask — corrected 2026-08-18.** It was excluded on
      the reasoning that event sourcing needs a replayable per-aggregate stream read and the kernel
      had none. The evidence for that was
      `eu.exeris.kernel.spi.persistence.EventStore`, which is the **transactional outbox** (`append` /
      `pollPending` / `markPublished`) and genuinely is not the SPI — but concluding *absence* from
      the wrong type being wrong is not a measurement. Enumerating
      `eu.exeris.kernel.spi.events` at the tag this repo already pins (**kernel `0.11.0`**, per the
      BOM) finds both halves: `EventStreamReader.replayFromVersion(StreamId, long)` is the replayable
      per-aggregate read, `EventStreamAppender.append(StreamId, expectedVersion, …)` the
      optimistic-concurrency write, with `JdbcEventStream{Reader,Appender}` and Kafka Community
      bindings and a TCK IT. One real constraint survives and belongs in the design:
      `KernelProviders.eventStreamReader()` returns an `Optional` — a broker may not support replay —
      so emitted code must handle absence rather than assume a binding.

      **`@Schedule` has its subsystem and still cannot be transcribed yet** — noted 2026-08-27, when
      SDK 0.11.0 landed the annotation (ADR-072). `CommunitySchedulingSubsystem` is bootable, so the
      table row above is right about capability and incomplete about readiness. `JobScheduler.submit(…)`
      captures the ambient `PrincipalContext` and `StorageContext` and **fails a job closed at dispatch**
      when neither is bound (kernel `spi/scheduling/JobScheduler.java:14-17` at `v0.11.0`), and a
      declared schedule has no submission event, hence no principal to capture. So a naive transcription
      emits a job that fails on every fire. The answer is a kernel-side system principal for declared
      jobs, or an SDK attribute naming the identity to run as — an authorization decision in a
      design-time annotation, and the worse option on its face. Owner: kernel; consumer: this repo. See
      [`docs/adr/ADR-072.link.md`](docs/adr/ADR-072.link.md).

      **This does not move the count, and mints no K-number.** The two exclusions are different kinds.
      `@Blob` is excluded because the platform cannot host it: `Application.main()` boots subsystems by
      name and there is no name to declare, which no amount of design in this repo fixes. `@Schedule`
      has its subsystem and is gated on a design question — the same bucket the behavioural family
      sits in (`@Derived`, `@Rule`, `@EventHandler`, `@Projection`, listed above as "design-gated, not
      capability-gated"), and those count toward the 50. An ask this repo may be able to discharge
      itself is not a kernel ask. The likeliest discharge is the seam ADR-070 already built —
      `RuntimeComponents` is where a consumer supplies construction-time collaborators, and the
      identity a declared job runs as is one — but that is a slice to design, not a decision to record
      here.

      **So the 1.0 target is 50 of 51**, with only `@Blob` carried as a kernel ask — and carried
      knowing the kernel has scheduled its subsystem post-1.0, so this is an exclusion that stands at
      our GA rather than one expected to close before it. Two rules that
      follow from the shape of this list, and matter more than the count: an annotation is "covered"
      when it **reaches emitted output**, not when the processor extracts it — `@EventSourced` is
      still the standing counter-example, now as pure tooling debt. And every one that lands must
      have its `INERT_ATTRIBUTES` / `INERT_ANNOTATIONS` entry deleted in the same change, or
      `-Aexeris.strict` starts lying in the other direction.

      Sequencing lives in the **S/C** entries under 0.8.0–0.9.0; `C0` comes first because it closes
      the defect class (strict mode audits extracted-but-unconsumed, and is structurally blind to the
      30 the processor never reads) rather than the instances. `@EventSourced` keeps its own track
      (**EV2**) — it is the one item here that is a subsystem rather than an extraction.
- [ ] Generated-code golden snapshot suite (per generator, per scenario)
- [ ] `exeris-codegen-maven-plugin` API frozen (mojo parameters, lifecycle phase semantics)
- [ ] `KernelArtifactGenerator` SPI frozen (third-party generators can plug in)
- [ ] `MIGRATION-0.x-to-1.0.md`
- [ ] Maven Central release (processor + codegen-core + codegen-java + plugin)
- [ ] npm registry release for `@exeris/codegen-ts`

---

## Codegen completeness backlog

> Tooling gaps surfaced by exercising the full pipeline (processor + codegen + `javac` + the
> Angular emitter) against a larger, multi-aggregate, multi-service domain than the `Order` sample.
> Each item is the gap plus the concrete *needed update*; SDK/kernel/DX halves of these findings are
> owned and tracked in their own repos. Where a tooling fix has an SDK/kernel counterpart
> (T4↔S2, T5↔S1, T3↔S3, T12↔S5/K3/K4) the dependency is named but the cross-repo half is out of
> scope here. Stable handles (`T*`) are kept so cross-references resolve.

| # | Finding | Severity | Recommended target |
|---|---|:---:|---|
| T1  | `@Action` endpoints advertised (OpenAPI + Angular) but no kernel route serves them — 404 | **High** | ✅ 0.6.0 (#92) |
| T20 | Generated Angular frontend doesn't compile (`npm run build` fails) — two parallel TS emission paths; the `src/app` sourceRoot ships an empty enum stub that shadows the real `types/enums.ts`, so enum-typed code fails (TS2304/2305) | **High** (latent) | ✅ 0.6.0 (#101/#102; FE gate + POSIX-path determinism fix 2026-06-28) |
| T23 | Stream-route boot-reachability — the generated `RuntimeLifecycle` published a `router::handle` lambda, erasing the `HttpRouter` type the kernel stream dispatcher resolves via `instanceof`, so every generated `streamRoute(...)` silently 404'd / fell back to respond-once on a real boot | **High** (latent) | ✅ 0.6.0 (folded into #106, ADR-044 Slice 2 — `handlerSlot.set(router)`; pinned by `KernelApplicationGeneratorTest`) |
| T8  | No generated finders/indexes for FK + `filterable` fields → O(n) `findAll().filter()` everywhere | **High** | ✅ 2026-06-28 (finders + FK/filterable indexes; T9 constraints deferred) |
| T10 | `@Validation` enforced client-side (Zod) but dropped server-side (handler/service/DB) | **High** | ✅ 0.6.0 (#103) |
| T12 | N generated apps can't form a mesh — client is own-app/relative-host, saga step is local, no cross-app contract | **High** | **T42 (types) SHIPPED 0.8.0**, no kernel gate; client+registry 0.9.0 — split by ADR-048; the client half needs a final kernel 0.12 (ADR-074's binary break on `HttpRequest`) |
| T17 | Capability-graph validation is closed-world per app — a legitimate cross-service `@Requires` hard-fails the build | **High** | **0.9.0** — ships with the client+registry slice per ADR-048 |
| T26 | A `@ExerisDomain(versioned = true)` entity whose `version` field is the **wrapper** `Long` throws NPE on the first `save()` of a fresh entity: `buildColumnLayout` hardcodes the VERSION column's type as `Long` and the emitter binds it by unboxing (`stmt.bindLong(i, entity.getVersion())`), with no null guard — and no guard is possible while the column type is a constant, since a primitive `long version` field cannot be null-compared. `update()` has the same unboxing (`long expected = entity.getVersion()`). Every other nullable system column (`createdAt`/`updatedAt`) *is* guarded, so this is the one gap. Fix is to read the declared field type into the column instead of assuming, which makes it a repository-emitter change rather than a test one | **Medium** (latent; primitive-`long` entities were unaffected) | ✅ 0.7.x — found 2026-08-02 by the T2 slice-d system-column fixture, fixed the same day: both the version bind and `update()`'s expected-version read go through a boxed local with a null default, so a wrapper-typed field behaves exactly like the primitive it shadows. The e2e fixture keeps the **wrapper** declaration (a primitive would pass either way) and the generated repository test no longer pre-stages the version, which makes every consumer's emitted test a regression test for it |
| T2  | Zero tests generated for the generated surface | Medium | 🔶 0.7.0 slices a–f — the **Java half is complete** (handler bodyless routes + body-route guards + service delegation + repository round-trip + saga wiring + `@Validation` boundary pairs, ADR-058); **FE spec slice → 0.8.0** |
| T3  | Action identity = method name, not `@Action(name=…)` → bean-setter collisions | Medium | 0.5.x |
| T4  | `@Relationship` target derived from field Java type, not `targetEntity` | Medium | 0.5.x |
| T5  | System-field overrides (`tenantIdField`, …) ignored by the repository generator | Medium | 0.5.x |
| T9  | Generated schema has no inter-entity foreign keys — zero referential integrity | Medium | ✅ 0.6.0 (constraints, with T8) + 0.7.0 (`relationshipType`/cascade extraction) |
| T11 | No fidelity/strict mode — annotation attributes set but consumed by no generator fail silently | Medium | 0.5.x |
| T13 | Codegen emits per-entity output but never prunes it — a removed/renamed entity breaks the build | Medium | 0.5.x |
| T18 | Capability validation × two-pass build deadlock; `mvn clean` + T13 prune wipes the committed L1 tree | Medium | ✅ 0.6.0 (#129 + `exeris:verify-capabilities` deferred-validation gate) |
| T19 | Repository binds `Instant` as ISO string but DDL declares `TIMESTAMPTZ` — round-trip latent-broken on real Postgres | Medium | **Done 0.6.0** (native `bindInstant`/`getInstant`, kernel 0.10 SPI) |
| T7  | TS app-structure seams — per-entity path vs `app.routes` import mismatch breaks the build; hardcoded title/redirect | Medium | ✅ 0.6.0 (routes fix + `--app-name` title/redirect, #120) |
| T6  | Naive English pluralization (`colony → colonys`) in SQL tables + Angular routes | Low | 0.5.x |

### High severity

- [x] **T14 — Repository column de-dupe.** `KernelRepositoryGenerator.buildColumnLayout` emitted a
      hardcoded `id` PK + *every* instance field + the system columns with no de-dupe, so an entity
      that declares its own `id` (or a `version`/`createdAt`/… field on a versioned/audited entity)
      produced the column twice — an invalid `SELECT`/`INSERT` and a double bind. *Done (0.5.x, PR #86):*
      de-dupe by SQL column name; the PK + active system columns are reserved and a shadowing domain
      field is dropped (system semantics win). No-collision entities stay byte-identical. Surfaced by
      a larger multi-aggregate trial; previously uncovered by any test.

- [x] **T15 — Boolean bind accessor.** `emitBindDomain` hardcoded `get` for every domain field, so a
      primitive `boolean onVacation` bound via `getOnVacation()` — absent on the entity
      (JavaBean/Lombok emit `isOnVacation()`), breaking the generated repository's compile. *Done
      (0.5.x, PR #86):* primitive `boolean` binds via `isX()` (matching the system `DELETED` column);
      `Boolean` wrappers keep `getX()`. Surfaced by the same multi-aggregate trial; previously uncovered.

- [x] **T16 — ADR-042 baseline-trust fields (`sourceDigest` + `schemaVersion`).** Codegen did not emit
      the two fields ADR-042 obligation #5 requires into `exeris-metadata/<entity>.json`, so the `-io`
      conflict reader could not validate its baseline (always `NO_BASELINE`). *Done (0.5.x, PR-G):* the
      processor stamps both as siblings of the serialized `DomainMetadata` in the same JSON object —
      `sourceDigest` = SDK `SourceDigest.of` over the entity's raw source-file text (read via the javac
      Compiler Tree API; the identical input the `-io` reader recomputes, so the concurrency token agrees
      byte-for-byte), `schemaVersion` = SDK `SchemaVersion.CURRENT`. A `DomainMetadata` read ignores the
      two (unknown-field tolerant); a `BaselineTrust` read of the same file picks up just them. The digest
      contract was the gating decision — resolved by **exeris-sdk** owning `SourceDigest.of` (textual
      normalize: LF + trailing-whitespace strip), so both sides compute it identically. Off-javac
      environments degrade to a `schemaVersion`-only stamp. The metadata JSON is a build intermediate
      (not committed) → no committed churn from the source-dependent digest.
      *Still open (separate, cross-repo):* coordinated population of `@Field.dataType` (B5) + the new
      `@UI` i18n keys / `customComponent` into metadata, matched by the `-io` reader for parity; and the
      ADR-037/038 `.link.md` stubs.

- [x] **T1 — Serve custom actions (Java/TS parity restored).** `@Action` methods reached the OpenAPI
      spec and the Angular service, but the generated `RuntimeLifecycle` wired **CRUD only** — no route
      or handler for any action, so a generated frontend `POST`ed endpoints the backend answered with 404.
      *Done (0.6.0, PR #92):* full Entity-First dispatch — `KernelHandlerGenerator` emits a
      `handle<Action>(HttpExchange)` per action (extract id → decode `@ActionParam` body via the ADR-036
      codec SPI → invoke the real entity method via `ActionMetadata.effectiveMethodName()` → persist →
      respond with the updated aggregate), and `KernelApplicationGenerator` registers
      `POST {base}/{id}/actions/{kebab(name)}` routes; the TS service posts the same path; a shared
      `NameCasing` (PR #92 review) keeps the route segment and handler-method name in sync. Required the
      SDK `ActionMetadata.methodName` (exeris-sdk#58) so the action identity (`@Action(name)`, **T3**) and
      the JVM method can diverge. *v1 limits (tracked):* non-void return not surfaced; `@Action(httpMethod)`
      not yet honoured (POST everywhere); domain exceptions map to 500.

- [x] **T20 — Generated Angular frontend didn't compile (`npm run build` failed). DONE (0.6.0, #101/#102).** The FE analog of
      **T14**: the generated frontend is L1-committed but the codegen e2e never *builds* it (it asserts
      emitted *text* only, as the Java side did before `KernelCodegenCompileTest`), so the breakage stayed
      latent. **Root cause: `exeris-codegen-ts` runs two parallel, conflicting emission paths in `main()`.**
      The CLI loop writes a *real* `types/enums.ts` (values + `DisplayNames` + Zod, `index.ts:329`) plus
      type/barrel artefacts at `<out>/`, while `generateAppStructure` emits the full per-entity tree the
      app actually **builds from** under `<out>/src/app/{components,services,types,schemas}` (`index.ts:260`,
      `app-structure-gen.ts:92-113`) — and *its* `types/enums.ts` is an **empty stub**
      (`export enum X { // TODO … }`, `app-structure-gen.ts:646`). Since `src/app` is the Angular
      `sourceRoot`, the build never sees the real top-level `enums.ts`; it resolves enum-typed fields and
      action-method signatures to the stub's absent members → **TS2304/TS2305**. The metadata *does* carry enum constants
      (`ExerisDomainProcessor.java:254`) and the CLI path emits them fully — so this is **not** "enum
      extraction unimplemented"; it is the duplicate `src/app` path **shadowing** the real output with a
      stub, plus the full `EnumGenerator` (`api/enum-gen.ts`) left unwired. (The service *does* emit
      `import … from '../types/enums'` (`service-gen.ts:182`) — it just resolves to the stub.)
      *Done (#101/#102):* collapsed to **one** emission path — the `src/app` (sourceRoot)
      tree is the canonical output of the real per-entity generators, and the `app-structure-gen` enum stub
      was dropped in favour of the real `generateEnumTypes` / `EnumGenerator`. Unifying the path fixed the
      enum-stub and the import-resolves-to-stub symptoms together. **The permanent catch is the 0.6.0
      "generated workspace compiles + `ng build` green" gate** — a
      `tsc --noEmit`/`ng build` over a generated sample, the FE analog of `KernelCodegenCompileTest`.
      (FE orphan-pruning — the other suspected FE-twin — is **already done**: the **T13** manifest pruner
      runs on the TS CLI path, `index.ts:302`.) Surfaced by a larger multi-entity, multi-service frontend trial.

- [x] **T8 — Generate finders + FK/`filterable` indexes. DONE (2026-06-28).** Repositories exposed only
      `findById/findAll/save/update/deleteById/count`; every cross-aggregate lookup forced a
      `findAll().stream().filter(...)` — O(n) per call — and FK columns weren't indexed (only
      `tenant_id` + `searchable` fields got indexes). The intent was already in the annotations
      (`@Field(filterable=true)`, the FK relationships).
      *Done:* `KernelRepositoryGenerator` emits `findBy<Field>(…)` for every `filterable()` field and
      `findBy<Rel>Id(UUID)` for every `MANY_TO_ONE` relationship (same kernel-SPI read shape as `findAll`,
      soft-delete filter, trailing `ORDER BY id` for deterministic row order); `KernelServiceGenerator`
      delegates them for parity; `KernelFlywayGenerator` emits the FK column + a `CREATE INDEX` per
      filterable/FK column. The FK-column convention strips a trailing `Id` so both the explicit-UUID-FK
      style (`UUID customerId` → `customer_id`/`findByCustomerId`) and the entity-typed style
      (`@Relationship Customer customer`) normalise correctly. Deterministic (sorted by name; generate-twice
      tests). `KernelCodegenCompileTest` fixture grew a `filterable` field + a `MANY_TO_ONE` relationship so the
      finders/indexes are javac-compiled against the real kernel SPI — which surfaced and fixed a harness gap:
      `InMemoryJavaCompiler` then passed `--enable-preview --release 26` (it previously couldn't load the
      Java-26-preview kernel-spi 0.10 classes). Server-side only — no HTTP/client finder (would need a served
      route; no TS surface, parity-neutral). **T9 FK *constraints* also ship here** as a single trailing
      `V3000000__foreign_keys` migration (`KernelApplicationGenerator.generateForeignKeys`, wired in
      `CodegenPipeline`) — `ALTER TABLE … ADD CONSTRAINT … FOREIGN KEY` per `MANY_TO_ONE` to a generated
      target, `ON DELETE` policy from cascade, external targets skipped. The trailing-`ALTER` shape avoids the
      migration-ordering hazard (a `REFERENCES` to a table created in a later migration would fail); it sorts
      after every `CREATE TABLE` tier. Deterministic (sorted; generate-twice test).

- [x] **T10 — Emit server-side validation (handler/service) + `CHECK` constraints. DONE (0.6.0, #103 + DB half).**
      `@Validation(min/max/pattern/minLength/…)` flowed into the Angular Zod schemas but the generated
      server handler/service enforced nothing — a malformed request the UI rejects sailed into the
      backend; the DB got only `NOT NULL` + `VARCHAR(255)`. Same contract honoured on one emitter,
      silently dropped on the other.
      *Done (#103):* the generated handler enforces `@Validation` server-side from `ValidationMetadata`
      (reject → `400`), restoring Java/TS parity on the request-validation contract.
      *Done (DB half):* `KernelFlywayGenerator` now emits named `CHECK` constraints inside the
      `CREATE TABLE` — numeric `min`/`max` and string `minLength`/`maxLength` (via `char_length`) —
      as defense in depth for rows inserted around the handler (bulk load, another service, manual
      `psql`). `pattern` is intentionally **not** a DB `CHECK`: the handler validates it with Java
      `String.matches` (full-match), whereas Postgres `~` is a POSIX partial match — the dialects
      diverge, so it stays enforced at the handler + client (Zod) edges only.

- [ ] **T12 — Cross-app contract registry + generated remote dispatch (the mesh story).** The
      pipeline already emits the *seams* of a distributed system — a typed sync client, async domain
      events, saga orchestration *intent* — but flattens every cross-service edge to a local call:
      (a) each `*Client` wraps `KernelWebClient` with a **relative** `BASE_PATH` and only for *this*
      app's own entities — no way to import a peer app's `DomainMetadata` and generate a client/DTOs
      against *its* contract; (b) `@SagaStep(service=…, command=…)` is captured in `SagaStepMetadata`
      then dropped — the generated `*SagaFlow` wires **local** lambdas, no remote dispatch /
      await-on-peer-events; (c) the capability graph (`@Provides`/`@Requires`/`@CapabilityModule`)
      is inert end to end (**S5**, SDK-side).
      *Update (tooling, with SDK + kernel halves):* a contract-registry stage in
      `exeris-codegen-core` that resolves `@Requires` / `@SagaStep.service` against a set of peer
      `DomainMetadata` (multi-app reactor, or a published contract artifact), then (1) generates a
      typed remote client + shared DTOs against the *peer's* contract, (2) emits saga steps that
      dispatch `command` to the resolved service and park on its `@DomainEvent`s, (3) emits the
      capability wiring (depends on **0.5.0** capability pass + **S5**). Keep Java/TS parity — a TS
      app calling a Java service needs the same generated client. Runtime half is **K4** (logical
      service-name → endpoint discovery; kernel `KernelWebClient` is single-host).
      *Note:* generating N independent apps already works — a second app runs the same pipeline and
      emits its complete `Application`/`RuntimeLifecycle`/handler/repository/service/client/Flyway/
      OpenAPI in its own base package with no tooling change. The missing piece is strictly the
      *cross-app* edge: importing another app's contract and turning a saga `service`/`command` into
      a remote dispatch instead of a local no-op.

- [ ] **T17 — Make capability resolution mesh-aware (the capability-axis twin of T12).** The **0.5.0**
      capability pass resolves `@Requires`→`@Provides` within a *single app's* closed world, so the one
      legitimate cross-service edge — a consumer app's `@Requires(SomeService)` satisfied by a *peer*
      app's `@Provides(SomeService)` — looks unprovided and **hard-fails the build**
      (`no @CapabilityModule provides it`). The provider lives in a *different* generated app. This is
      the same closed-world-per-app limitation that breaks generated saga dispatch (**T12**), seen on
      the capability axis. Surfaced by a multi-service trial; worked around by marking the edge
      `@Requires(… optional = true)` so the validator warns (`optional → skipped`) instead of failing —
      but that misrepresents a hard cross-service requirement as optional.
      *Update:* feed the resolver a **union of the per-service `cap-manifest.json`s** (the artifact the
      0.5.0 pass already emits is the natural carrier), or let `@Requires` declare an external/
      mesh-provided provider, so a cross-service edge resolves against the *other* service's `@Provides`
      instead of failing. Pairs with **T12** (the contract registry that union feeds) — same input,
      same milestone.

### Medium severity

- [~] **T2 — Generate tests for the generated surface (opt-in flag).** *In progress (0.7.0) — the
      current slice status lives in the 0.7.0 milestone entry above; this is the finding as first
      recorded.* The pipeline emits handlers,
      services, repositories, clients, sagas, events, Flyway, OpenAPI — and **zero tests**. Mirror the
      existing `*Generator` / scaffold structure + determinism + parity rules:
      Java — `Kernel*TestGenerator` per entity (repository CRUD round-trip, handler request/response
      shape, service delegation; saga step-wiring for `@Saga` entities; reuse the kernel TCK patterns).
      TS — `*.service.spec.ts` (HTTP) + `*.schema.spec.ts` (Zod) under the generated workspace's
      `ng test` runner (the emitted Angular 21 `package.json` uses `"test": "ng test"` — **not** the
      Vitest the `exeris-codegen-ts` package itself runs). Output stays deterministic and committed
      alongside the code it covers.

- [x] **T3 — Use `@Action(name=…)` as action identity.** `extractActionMetadata` set
      `name = method.getSimpleName()` and ignored the (required) `name` attribute, so a
      `@Action(name="…")` on a bean-setter-shaped method (e.g. `void setFormation(Formation)`)
      collided with the generated setter (`method … is already defined`).
      *Done (0.5.x):* `extractActionMetadata` now prefers `@Action(name=…)` (required, always
      present), falling back to the method name only defensively for a blank value.
      Pairs with **S3** (SDK-side: the attribute is otherwise inert).

- [x] **T4 — Honour `@Relationship.targetEntity`.** `extractRelationshipsMetadata` called
      `extractTargetEntityFromType(field.asType())` and never read `targetEntity`, so a
      `@Relationship private UUID ownerId` recorded its target as `UUID` — the annotation only worked on
      entity-typed fields, a poor fit for the explicit-UUID-FK style.
      *Done (0.5.x):* a new `resolveTargetEntity` prefers the explicit `targetEntity` (required;
      a `TypeMirror`), falling back to the field type only when it is absent or `void.class`.
      Pairs with **S2** and feeds **T9**'s relationship graph.

- [x] **T5 — Honour system-field override attributes in the repository (+ Flyway) generators.**
      `KernelRepositoryGenerator` hard-coded `getTenantId()/getCreatedAt()/getUpdatedAt()/
      getVersion()`, so renaming or omitting any of those fields failed compile
      (`cannot find symbol method getUpdatedAt()`).
      *Done (0.5.x):* the processor now extracts `SystemFieldsMetadata` from the `@ExerisDomain`
      `*Field` overrides (only when explicitly set — default-case JSON unchanged). The repository
      derives column/accessor names (`get/set/is`+`<Field>`, snake-cased columns) from those names,
      and Flyway derives the matching SQL columns + RLS predicate. Default case is byte-identical
      (`tenantId`→`tenant_id`/`getTenantId`, …) so determinism holds. Pairs with **S1**.

- [x] **T9 — Cross-entity relationship pass → `FOREIGN KEY` constraints. DONE (0.6.0 constraints +
      0.7.0 extraction).** Each entity's Flyway used to be generated in isolation; the only
      `REFERENCES` emitted were the tenant FKs, so an `owner_id` column was a bare `UUID NOT NULL` —
      no referential integrity, no cascade, no cross-entity awareness. Same blind spot as **T4**: the
      pipeline had no relationship graph.
      *Done (0.6.0, with T8):* a cross-entity pass over `@Relationship` emitting `FOREIGN KEY`
      constraints + an `ON DELETE` policy as one trailing `V3000000__foreign_keys` migration (which
      sorts after every `CREATE TABLE`, so no ordering hazard), feeding the join-aware finders.
      *Done (0.7.0):* the pass could not actually see relationship kind or cascade until the
      processor read `relationshipType`/`cascadeDelete` — see the T4-follow-up entry in the 0.7.0
      milestone.

- [~] **T11 — Strict mode / generation report for inert annotation attributes.** The systemic root
      behind T1/T3/T4/T5 (and S1–S5): an attribute set from the rich annotation Javadoc silently does
      nothing because no generator consumes it; the only way to learn it's inert is to read
      processor/generator source. The processor knows what it read; the generators know what they
      emit; the difference is computable.
      *Done (0.5.x):* an opt-in `-Aexeris.strict=true` processor flag (parallel to `-Aexeris.verbose`)
      emits a `javac` WARNING whenever an author sets an annotation attribute — or applies a whole
      annotation — that no generator consumes, turning silent no-ops into actionable diagnostics. This is
      well-defined because **every SDK annotation is `@Retention(SOURCE)`**: it is erased by the compiler
      and absent from bytecode, so the kernel runtime / SPI / Core *cannot* read any of them — the
      build-time pipeline is the only possible consumer, and an unconsumed attribute has literally zero
      effect (no runtime escape hatch). Two hand-maintained, conservative registries in
      `ExerisDomainProcessor`:
      - `INERT_ATTRIBUTES` (per-attribute): each entry verified (a) to be a real annotation attribute
        (not merely an AST record accessor with no matching element — e.g. `RelationshipMetadata.valueField()`
        has no `@Relationship.valueField`) and (b) unconsumed by **both** Java and TS emitters (consumption
        is their union). Seeded with `@Field.dataType`, `@ActionParam.description`, `@ActionParam.required`.
      - `INERT_ANNOTATIONS` (whole-annotation, reported once per entity): seeded with `@EventSourced` —
        the processor extracts `EventSourcedMetadata` into the JSON but **no generator emits it yet** (a
        build gap: the event-sourcing generator is unbuilt). `@Saga` and `@Graph` are NOT here — their
        generators (`KernelSagaGenerator`, `KernelGraphSyncGenerator`) do consume them.

      Default builds stay quiet (flag opt-in). When the event-sourcing generator lands, delete the
      `@EventSourced` registry entry in the same change.
      *Deferred:* broadening the registry to the `@UI` surface (a prime offender) rides with **U4** (UI
      fidelity end-to-end) in the **UI fidelity & theming** cluster — it needs the processor to emit the
      full `uiMetadata` first, otherwise the warning would fire on attributes that are dropped upstream
      rather than merely unconsumed.

- [x] **T13 — Generation must own its output tree (prune orphans).** Codegen *wrote* per-entity files
      but never *deleted* them: removing or re-homing an entity left its stale
      `Repository/Handler/Service/Client` + OpenAPI/Flyway referencing the deleted type
      (`cannot find symbol: class …`) until removed by hand. Worse, the *app-wide* files
      (`RuntimeLifecycle`, `Application`) **are** regenerated and drop the entity, leaving the tree
      internally inconsistent and un-compilable. Renaming/re-homing an entity is a normal refactor.
      *Done (0.5.x):* each run records the relative path of every file it writes; a manifest
      (`.exeris-codegen-manifest`) under the output root persists that set. The next run deletes any
      path that was in the previous manifest but is not re-emitted (the orphan), prunes the now-empty
      directories, and rewrites the (sorted, deterministic) manifest. Only previously-emitted files
      are ever deleted — a user-authored file is never in the manifest, so it is never touched.
      Implemented in both emitters: Java `OutputWriter.pruneOrphansAndWriteManifest()` (wired into
      `CodegenPipeline`) and TS `src/output/manifest.ts` (wired into the CLI). Pairs with the
      committed-L1 model (**D3**) — a stale orphan removal is a real, reviewable diff. (Distinct from
      the `exeris:detach` prune in **0.3.0**, which prunes the *emptied* source tree, not the
      *generate* mojo's per-entity output.)

- [x] **T18 — Two-pass build hazards from the capability pass (deadlock + clean-wipes-L1). FIXED (0.6.0).** Adopting
      the **0.5.0** capability pass surfaced two coupled interactions with the `generate-sources`-before-
      `compile` ordering (**D2**) on L1-committed-output repos:
      (a) **Validation deadlock.** Graph validation runs in `generate-sources`, *before* the `compile`
      phase where the processor (re)emits `capability_*.json`. So a *stale* capability metadata file is
      validated first and **hard-fails before the processor can refresh it** — an edit to a `@Requires`
      (e.g. adding `optional=true`) can't take effect because the build dies on the old metadata; the
      only way through is a manual `rm` of the stale `capability_*.json`. (The domain two-pass merely
      emits stale *code*; the capability two-pass *aborts* — worse.)
      (b) **`clean` + T13 prune wipes the committed tree.** After `mvn clean`, the metadata dir is empty
      at `generate-sources` time, so codegen emits zero files and the **T13 orphan-pruner** deletes the
      entire prior `.exeris-codegen-manifest` set — i.e. the committed `src/main/generated` tree —
      leaving hand-written subclasses uncompilable. A plain `mvn clean compile` is no longer safe here.
      *Update:* (a) capability validation must tolerate/refresh its own input within one build, or run
      *after* the processor (it already depends on processor output) — a phase-ordering fix or a
      "no metadata yet → skip, don't fail" guard; (b) the T13 pruner must distinguish "no inputs this
      run" (a clean two-pass first build) from "entity genuinely deleted" — gate pruning on a non-empty
      generation. Both want a documented safe-build recipe (don't `clean` then `compile` in one shot),
      or an `exeris:bootstrap` mojo that seeds metadata first.
      *Done (0.6.0), both halves.* **(b)** in #129: a run that loads zero `@ExerisDomain` entities while
      a prior manifest owns files the prune would delete now refuses (`EmptyMetadataException`, actionable
      message with the metadata-seed recipe `mvn compile -Dexeris.codegen.skip=true`); the genuine
      teardown opts in via `-Dexeris.codegen.allowEmpty=true`. **(a)** deferred validation + a fresh-input
      gate: the new `exeris:verify-capabilities` goal (default phase `process-classes`, i.e. right after
      the `compile` phase in which the processor re-emits `capability_*.json`) re-validates the graph
      against FRESH metadata and hard-fails on a genuine problem. When — and only when — that gate is
      bound in the same project, `exeris:generate` degrades a capability-graph failure at
      `generate-sources` to a WARNING and preserves the prior `cap-manifest.json` (T13 ownership intact,
      refreshed on the next successful generate — the same freshness contract the domain two-pass has),
      so the stale-input hard-fail no longer deadlocks the build. Without the gate bound, behaviour is
      unchanged (fail-closed): leniency exists exactly where the authoritative re-validation is
      guaranteed. Safe-build recipe documented on `GenerateMojo` (bind both goals; seed metadata after a
      `clean` before generating).

- [x] **T19 — Repository binds `Instant` as an ISO string against a `TIMESTAMPTZ` column (latent). FIXED (0.6.0).**
      The generated `*Repository` writes timestamps with `bindString(…, instant.toString())` and reads
      them back with `getString(…)` + `Instant.parse(…)`, but the generated Flyway DDL declares those
      columns (`created_at`/`updated_at` + any `Instant` `@Field`) as `TIMESTAMPTZ`. On a `VARCHAR` the
      string round-trips, but on the **real Postgres DDL** the read does `getString` on a `TIMESTAMPTZ`,
      which returns Postgres timestamp text (`2026-06-16 00:00:00+00`) that `Instant.parse` (expecting
      `…T…Z`) rejects — the round-trip is latent-broken on the very database the DDL targets. Same class
      as **T14**: hidden because the generated repositories are *compiled but never executed* (the unit
      suite uses hand-written in-memory stores). Surfaced downstream by the first integration harness to
      run a generated repository against a real DB (H2 via a kernel-SPI→JDBC double) — which is exactly
      the generated-repo-against-a-DB coverage **T2** would add.
      *Done (0.6.0):* the kernel-SPI gate cleared — kernel 0.10 added `PersistenceStatement.bindInstant` +
      `RowCursor.getInstant`. The repository generator now binds/reads `Instant` columns (audited
      `created_at`/`updated_at` + any `Instant` `@Field`) natively (`bindInstant`/`bindNull` write,
      `getInstant` read), so the `TIMESTAMPTZ` column round-trips through the driver — no ISO-String
      format mismatch. `LocalDate`/enum columns still String-round-trip (no typed SPI for those).
      *Follow-up — T19b (0.6.0):* `LocalDateTime` was classified `INSTANT_LIKE` too, so after the native
      switch it emitted `setX(row.getInstant(i))` into a `LocalDateTime` setter — uncompilable (and the
      pre-T19 `Instant.parse` path had the same mismatch: a latent parity hole, not a T19 regression).
      The SPI has no typed `LocalDateTime`, so it now bridges through the native `Instant` at the UTC
      offset (`ofInstant(…, UTC)` read / `toInstant(UTC)` write, null-guarded). The compile-gate gained a
      `LocalDateTime` field so javac guards it permanently. (PR #115.)
      `KernelCodegenCompileTest` compiles the new repo against the real 0.10 SPI (the validated `id` +
      audited-timestamp columns exercise the path). *Still tracked:* a generated-SQL-against-a-DB
      round-trip in the e2e suite (pairs with **T2**) — the strongest catch for this class; the codegen
      e2e still asserts on emitted *text* + compile-only.

- [~] **T7 — TS app-structure seams (`exeris-codegen-ts`).** *Route resolution — done:* per-entity files
      now emit under `src/app/{components,services,types}` and `app.routes.ts` imports `./components/…`
      consistently (`app-structure-gen.ts:92-104,257`), so the route-import mismatch no longer breaks the
      build. *Still open:* the app title is hardcoded (`'… - Exeris Foundation'`, `app-structure-gen.ts:257`)
      and the default `redirectTo` (first entity alphabetically) is not metadata-driven; and this same
      `generateAppStructure` path is half of **T20**'s duplicate emission.
      *Update:* make the default route + app title configurable (CLI flag / config); collapse the duplicate
      tree together with **T20**. Extended by **U5** (configurable detail/branding) in the **UI fidelity &
      theming** cluster below.

### Low severity

- [~] **T6 — Real pluralization (or honour overrides).** `colony → colonys` in both
      `V*__create_colonys.sql` and the Angular route `path: 'colonys'`; `construction_order` works only
      by luck.
      *Java half — done (0.5.x):* a shared `KernelTableNaming.effectiveTable` honours the
      `DomainMetadata.tableName` override and is the single source for the repository `TABLE`, the
      Flyway `CREATE TABLE`, and the migration filename (previously each generator pluralised
      independently — they could drift). Default case is unchanged (`toSnakeCase(name)+"s"`); real
      irregular pluralisation (`colony→colonies`) lives in the SDK `DomainMetadata.pluralName()` and
      is SDK-side.
      *TS half — deferred to **T7**:* the Angular route/label pluralisation lives in the
      app-structure generator T7 is already reworking, and there is no serialized route override on
      the TS side yet, so the TS half rides with that 0.6.0 change.

### UI fidelity & theming (`exeris-codegen-ts`)

Three layers diverge: the SDK *declares* a rich UI contract, the pipeline *carries* only a thin
slice of it, and the tokenized ui-kit theme is *not wired* into the generated app. This is a
fidelity/wiring gap — kin to **T11** (set-but-unconsumed) and **T7** (frontend seams) — **not** an
SDK gap.

- **SDK (declaration) — rich.** `@UI` with 21 control types (`TEXT_AREA`, `SELECT`, `DATE_PICKER`,
  `AUTOCOMPLETE`, `SLIDER`, `TOGGLE`, `RICH_TEXT`, `FILE_UPLOAD`, `COLOR`, …), `@UIGroup`
  (sections, columns), `@Tab`, `@NavMenu` (badge/role/icons), `@Relationship`
  (`displayField`/`displayTemplate` → picker), plus per-field `format`, `gridSpan`, `width`,
  `placeholder`, `helpText`, `dataType` (currency/percent/url…). All declarable today.
- **Pipeline (processor → JSON → emitter) — carries only a shallow, entity-level slice.** The TS
  `UIMetadataSchema` (`domain-model.ts`) models exactly `icon/color/listColumns/searchFields/
  filterFields/formLayout` — and is `.optional()`, so when the processor emits no `uiMetadata` the
  whole block is absent. Decisive: there is **no per-field UI surface** on the TS side —
  `componentType` / `@UIGroup` / `@Tab` / `gridSpan` / `fieldOverrides` are modelled nowhere
  (`grep componentType src/` = 0). So even if the processor emitted the rich attributes, the TS
  Zod schema would drop them on deserialization.
- **ui-kit (theme) — tokenized but unwired.** `exeris-sdk/exeris-sdk-ui-kit` has a real token
  system — `tailwind.preset.js` exporting `exerisPreset`, `--exeris-primary` (+ spacing/radius/
  shadow), `.exeris-btn`/`.exeris-card`/`.exeris-table`, dark mode, re-skin by overriding CSS vars.
  But the generated app doesn't use it: the emitted `tailwind.config.js` ships the default
  (`theme.extend:{}`, `plugins:[]`, **no `exerisPreset`**), `styles.css` hardcodes
  `bg-gray-100 text-gray-900`, and component templates hardcode `bg-indigo-600`/`text-gray-900`
  (~32 occurrences). So a re-skin means editing generated code — which the next regen overwrites
  (against the committed-L1 model, hard-constraint #6).

Proposals, highest return-on-effort first:

| # | Proposal | Where the fix lives | Effort | Target |
|---|---|---|:---:|---|
| U1 | **Wire ui-kit into the generated app** — ✅ **DONE 2026-06-28.** Emitted `styles.css` now `@import "@exeris-systems/ui-kit/theme"` (the v4 `@theme` token entry); hardcoded `bg-indigo-600`/`hover:bg-indigo-700` etc. across the emitted templates → `bg-exeris-primary` token utilities (evidence-checked against `theme.css`); `@exeris-systems/ui-kit` added to the emitted `package.json`; `presets:[exerisPreset]` added to the (v4-vestigial) `tailwind.config.js` for v3 consumers; the boilerplate `.btn-primary`/`.input-field` + `bg-gray-100 text-gray-900` body removed. **Finding (B1 twin):** the generated app is Tailwind **v4**, whose ui-kit `@theme` entry ships **tokens only, not the `.exeris-*` component classes** (those are v3 `index.css`), and has **no neutral surface/text token** — so token utilities were used (not component classes) and neutral `gray-*` were left as standard Tailwind (no token to map to). Also added a configurable `appName` (CLI `--app-name`) replacing the hardcoded `'Exeris Foundation'` (closes the **T7/U5** title remainder). The tooling-side fix for dog-food finding **T25**. | codegen-ts (ui-kit is ready) | small | ✅ 2026-06-28 |
| U2 | **Universal lists** — column types from metadata (enum→badge w/ `@UI.color`, number→`format`+align, bool→icon, date, FK→link/`displayField`, currency/percent from `dataType`); wire sort to headers (logic exists, only the `(click)` is missing); real filters for `filterable` fields (string/enum/date-range — today only bool + 2 fields); configurable `pageSize`; row actions | codegen-ts (+ processor emits `format`/`dataType`/`sortable`/`filterable`) | medium | 0.6.0 |
| U3 | **Forms from metadata, not the Java type** — read `@UI.componentType` (textarea/select/date/slider/toggle/rich-text/file/color), `@UIGroup`→sections, `@Tab`→tabs, `gridSpan`→multi-column, `placeholder`/`helpText`, `@Relationship`→autocomplete picker (today a UUID FK = `type="text"`); fix type mapping (`long→number`, `UUID→picker`) | codegen-ts (+ processor + TS schema) | med–large | 0.6.0 |
| U4 | **Fidelity end-to-end** — processor emits the full `uiMetadata` / per-field `UIFieldMetadata`, the TS Zod schema models it, and strict-mode (**T11**) warns when a `@UI` attribute is declared but dropped | processor + codegen-ts | medium | 0.6.0 (with T11) |
| U5 | **Configurable detail / branding** — sections/tabs in the detail view, related-entity panels; app name/titles/icons from metadata (today a hardcoded `"Exeris Foundation"` + an emoji-by-entity-name map) | codegen-ts | small–med | 0.6.0 (extends **T7**) |
| U6 | **New view shapes** — dashboard/cards/kanban/calendar from `@Projection`, charts from `@Graph` (deferred), master-detail, inline-edit, bulk-actions | SDK (light) + codegen-ts | large | 0.7.0–0.9.0 |
| U7 | **Live-view** (e.g. a battle preview) — a round stream pushed to the client. Transport SHIPPED (ADR-043/044 — Slice 1 #104 + Slice 2 #106: `HttpStreamHandler` + `streamRoute` + `EventSource`/RxJS clients); the **entity-level** producer now binds the `@DomainEvent` bus (real feed, #125) — the **per-action** driver stays a keep-alive scaffold (open slice) | ~~kernel (K2, done)~~ → codegen-java/-ts (**EV1-stream**) + kernel (stream-route `{id}` templates, v0.11 ask) | large | entity-level feed shipped 0.6.0 (#125); per-action driver still scaffold → 0.7.0 (kernel-gated, see EV1-stream) |
| U8 | **Genuinely missing in the SDK** — custom-component registration (plugin), per-role field visibility (RLS-aware), i18n labels, icon-set abstraction | SDK + codegen-ts | large | SDK-led |

> **Recommendation:** highest return for least motion is **U1** (wire ui-kit) + **U2** (universal
> lists) — the theme already exists tokenized, and lists already know more than they show
> (sort/filter are half-there). Together: re-skin the whole UI via one set of CSS tokens without
> touching generated code, and get lists with real column types — inventing nothing in the SDK.
> **U7's kernel blocker (K2) is now cleared** — the SSE transport landed (kernel 0.10, ADR-043) and the
> emitter ships it (Slice 1 #104 + Slice 2 #106); the **entity-level** remainder was generator-side and
> shipped (#125). The **per-action** path is kernel-gated again (2026-07-02 finding): the kernel
> stream-route table is exact-path only — no `{id}` template match (W7 #224 covers `route(...)` only) —
> so the emitted `streamRoute(POST, "<base>/{id}/actions/…")` cannot match a concrete request. Template
> matching on the stream table is filed as a kernel ask (v0.11 plan); see **EV1-stream** for sequencing.
> U6/U8 have SDK halves owned in `exeris-sdk`.

### Presentation views (`@View`) — first slice (NEW, 2026-06-28)

> The framework-neutral presentation IR (`@View` / `ViewMetadata`, SDK [RFC-2026-06-25](https://github.com/exeris-systems/exeris-sdk/blob/main/docs/rfc/RFC-2026-06-25-presentation-front-model.md), ACCEPTED) shipped **reserved** in the SDK with generation **gated** on (1) the Angular 22 emitter being authored in tooling and (2) a page/composition corpus. This entry **opens condition (1)**.

- [~] **`@View` → Angular 22 signal-first page emitter — first slice DONE (2026-06-28); full emitter gated.**
      Shape fixed in **[RFC-2026-06-28 — Presentation View Emitter (tooling)](docs/rfc/RFC-2026-06-28-presentation-view-emitter-tooling.md)** (DRAFT, sibling to the SSE emitter RFC-2026-06-22; the RFC the SDK RFC's build gate names).
      **Processor (DONE):** `ExerisDomainProcessor` extracts `@View` types via the RFC-fixed **class-structure-derived walk** (`@Region` members in declaration order → regions; a region class's `@Block`+`@Bind` members → components; nested block classes → recursive `children`; path-scoped cycle guard) into `view_<Name>.json` — app-wide, parallel to `DomainMetadata`, mirroring the `capability_*.json` precedent. Covered by `ViewExtractionTests`.
      **codegen-ts (DONE):** a `ViewMetadataSchema` (recursive, mirrors the SDK records), `view_*.json` discovery in `index.ts` (mirrors `enum_*`), and a `ViewGenerator` (`generators/angular/view-gen.ts`) emitting one **standalone, OnPush, signal-first** component per view under `pages/<kebab>.component.ts` + a lazy route. `BlockType`→element mapping (HERO/CARD/GRID/LIST/CONTAINER/RICH_TEXT/NAV/IMAGE/SLOT/CUSTOM/FORM), ui-kit token utilities (U1). Bindings honoured: `STATIC`/`NONE` (authored), `ENTITY` (`inject(<Ref>Service)` + signal read), `ACTION` (click handler stub). Route assembly threads views into `app.routes.ts` (PAGE-first default redirect + sidebar links). Verified end-to-end (real CLI on a `view_*.json` → faithful component).
      **Inert-honesty:** `@View` is consumed via the Java∪TS union (the codegen-ts ViewGenerator), so it is **not** registered in `INERT_ANNOTATIONS` — the processor extraction + the generator land together (the strict test asserts `@View` is not flagged).
      **Gated / follow-ups (the full emitter):** **G1** parameterised/relational binding (the corpus's defining "X of the current Y" — currently `expression` is a TODO passthrough) · **G2** `STREAM` source (pairs with ADR-044) · **G3** mesh binding (T12) · **G6** token/theme binding · leaf-field `FORM` emission ([ADR-047 — the `@UI`→`@View` leaf-facet migration](docs/adr/ADR-047-view-leaf-field-facet-and-ui-subsumption.md)) · the `@UI`→`@View` unification. Build of the *full* emitter stays gated on the Headless CMS SKU corpus (RFC condition 2); a downstream dog-food app's view-IR corpus is the early validating stand-in.

### Events & event sourcing

> Grounded in a kernel-side audit (2026-06-16). The open-core kernel already ships a **mature
> transactional-outbox pipeline** with **two swappable broker backends behind one Core port**
> (`OutboxBrokerPort`; impls `KafkaEventBrokerPort` + `CommunityEventBusOutboxBrokerPort`, **both
> open-core** in `exeris-kernel`, selected at bootstrap). The "Kafka vs internal" choice the founder
> recalls is real, but it is a **kernel bootstrap concern, already swappable** — not something codegen
> branches on.

- [x] **EV1 — `@DomainEvent` payload realization (codec-resolved, ships real data).**
      `KernelEventGenerator` emits a per-entity `*EventPublisher` whose
      `publish<Event>(UUID streamId)` calls `eventEngine.bus().publish(descriptor, …)` with
      `FLAG_PERSISTENT`, so events flow through the kernel's transactional outbox transparently.
      **Swappability is already solved and must stay codegen-invisible:** generated code binds to the
      backend-agnostic EventEngine SPI and **never names Kafka** — the broker is chosen below the SPI at
      bootstrap. A Kafka-specific (or RabbitMQ-specific) generator would violate single-target discipline
      (hard-constraint #1); do **not** add one.
      **DONE (#123, [ADR-046](docs/adr/ADR-046.link.md)):** the payload is no longer
      `EventPayload.empty()` — the generator emits **codec-resolved field-projection** into the payload,
      honouring `@DomainEvent.includeFields/excludeFields/includeComputed/sensitiveFields`.
      **DONE (topic, [ADR-050](docs/adr/ADR-050.link.md)):** `@DomainEvent.topic` now has a kernel
      counterpart — the generator lands it on the per-type `EventTypeSpec` via
      `ofPersistent(name, ordinal, topic)` (binding-agnostic; broker bindings honour it, the in-memory bus
      treats it as advisory), so it is no longer dropped to a Javadoc-only reference. The remaining knobs
      with no kernel counterpart (partitionKey, schema/Avro, headers, exchange/routingKey, retention)
      stay inert — `-Aexeris.strict` (**T11**) is the right surface to flag them, once each is verified
      per-attribute against the union of Java+TS emitters.

- [~] **EV1-stream — replace the SSE keep-alive scaffold with a real `@DomainEvent` → `StreamEvent`
      projection.** ADR-043/044 Slice 1 (entity-level `@ExerisDomain(realTimeApi)`, #104) and Slice 2
      (per-action `@Action(streaming)`, #106) ship the streaming **transport** end-to-end — the kernel
      `HttpStreamHandler` + `streamRoute` (Java) and the `EventSource` / RxJS-over-fetch clients (TS).
      ADR-044 obligation 3 names the `@DomainEvent` bus as the producer seam.
      **DONE — entity-level (Slice 1, #125):** `KernelStreamHandlerGenerator` now emits a real producer
      (`KernelStreamScaffold.eventProducerScaffold(...)`) — a long-lived subscription to the entity's
      `@DomainEvent` bus that projects each event into `StreamEvent` (reusing the **EV1** codec-resolved
      payload), with a bounded drop-on-full hand-off queue; it falls back to the keep-alive scaffold only
      when the entity declares no `@DomainEvent`. The TS clients already parse NAMED SSE frames, so named
      domain events flow with **no client reshape** (strong-default #4 parity holds for free).
      *Remaining gap:* the **per-action** driver (`KernelActionStreamHandlerGenerator`) still emits
      `KernelStreamScaffold.keepAliveScaffold(...)` — porting it to the same producer seam is the open
      slice (pairs with the planned per-action GET **spectate** route — `EventSource` is GET-only).
      **Re-gated on the kernel (2026-07-02):** the kernel stream-route table is exact-path only
      (`HttpRouter.Builder.streamRoute` documents "exact request path"; W7 #224 added `{id}` template
      matching to `route(...)` only), so the emitted per-action
      `streamRoute(POST, "<base>/{id}/actions/<kebab>")` can never match a concrete request — the route
      404s on a real boot (same defect class as T20/T23: advertised-but-dead). Stream-route template
      matching is filed as a kernel ask (kernel v0.11 plan); the per-action slice lands in **0.7.0**
      against the shipped kernel surface, with the GET spectate route shape as an **ADR-044 amendment**
      (the route shape is an ADR-044 obligation-1 change, not silent drift).
      Pairs with `@Projection` as the natural event→DTO shape. **Closes U7** on the entity-level path.

- [ ] **EV2 — `@EventSourced` aggregate generator — log substrate delivered (kernel 0.10, ADR-049);
      aggregate surface still missing.** No generator emits event-sourced aggregates today; **T11 strict
      mode surfaces `@EventSourced` as inert** (extracted into the JSON, consumed by nobody — and
      SOURCE-retained, so no runtime reader either).
      *Delivered by kernel 0.10 (ADR-049):* the log-level seam is real now —
      `EventStreamAppender.append(streamId, expectedVersion, descriptor, payload)` (fail-closed
      `EX-EVENT-6008` on version conflict, `ANY_VERSION` opt-out) + `EventStreamReader.replayFromVersion`
      with contractually ascending replay, **implemented and bound on two Community bindings**
      (JDBC/Postgres + Kafka, both TCK'd), resolvable via
      `KernelProviders.eventStreamAppender()/eventStreamReader()`.
      *Still missing at the aggregate level:* no `EventSourcedAggregate` base class, no snapshot store,
      no typed read surface (`EventStream` yields raw `EventPayload` bytes; decode-side codec wiring is
      deferred per ADR-046), and the Kafka binding's OCC is single-writer best-effort (strict OCC =
      JDBC binding only). Codegen emitted today would have to hand-roll load-fold-rehydrate +
      snapshotting against raw bytes.
      *Sequencing for 1.0.0:* (1) ~~kernel implements + binds the aggregate-event-store SPI~~ — **done
      at the log level (0.10, ADR-049)**; whether the aggregate-level remainder (base class / snapshot
      store / typed read) is kernel-owned or codegen-emitted is exactly the step-2 question; (2) an
      RFC/ADR fixes the codegen target shape — **now actionable** (touches the processor↔generator
      contract and the kernel-target surface → ADR-triggering per this repo's CLAUDE.md); (3) build
      `KernelEventSourcedGenerator` and **delete the `@EventSourced` entry from `INERT_ANNOTATIONS`**
      in the processor in the same change. Until (3), the strict-mode warning is the honest signal.

### Build & DX — tooling-owned halves

> The full D1–D3 findings are DX-tracked; the parts with a tooling fix are captured here.

- [x] **D1 — `requireJavaVersion` enforcer + README up-front. DONE (0.6.0).** `exeris-codegen-maven-plugin`
      classes are class v70 and load into Maven's JVM, so on JDK 21/25 the build dies at *plugin load*
      with an opaque classworlds `UnsupportedClassVersionError` realm dump — before
      `maven.compiler.release` matters.
      *Done (0.6.0):* root-POM `maven-enforcer` execution (`requireJavaVersion [26,27)` — exactly 26,
      preview compilation rejects a non-current release — plus `requireMavenVersion [3.9,)`), failing
      with one clear line at `validate`; README **Requirements** section moved up-front and rewritten
      ("Maven on JDK 26 — exactly", Node floors for generator vs generated app, released-pin resolution
      via GitHub Packages or the `v0.8.0` / 0.10.0 release tags).
      *Superseded in 0.7.0 by U1:* the range widened to `[25,)` and the plugin's classes are v69. The
      failure mode D1 exists to catch is unchanged — only the floor moved, and it moved down.

- [ ] **D2 — Document the two-pass first build.** The processor writes
      `target/classes/exeris-metadata/*.json` during `compile`, which runs *after* the plugin's
      `generate-sources`, so a from-scratch build needs two passes (already noted in `GenerateMojo`;
      `build.sh` encodes it). Worth a line in the plugin quick-start / an archetype.

- [ ] **D3 — Document the committed-L1 expectation for hand-written glue.** A hand-written class that
      `extends` a generated `*SagaFlow` references generated types that only exist *after* generation,
      so `rm -rf src/main/generated && mvn compile` fails on the first pass. Committed-L1 resolves it;
      `exeris:detach` (L2) makes it moot.
      *Update:* document that "delete and regenerate from scratch" is not a safe loop once glue exists,
      until detach lands.

- [ ] **D4 — Stable diagnostic IDs. Inbound ask from `exeris-ai-bridge` 0.7.0** (its `build:explain_diagnostic`
      tool; recorded in that repo's cross-repo asks table). Processor diagnostics are prefixed free text:
      `DIAG_PREFIX = "[Exeris] "` (`ExerisDomainProcessor.java:90`) is applied at **all 9**
      `Messager.printMessage` sites, so a consumer can already tell an Exeris diagnostic apart from a plain
      `javac` one — it just cannot tell *which* one. The kernel's `KernelErrorCodes` single-source-of-truth
      is the precedent to copy. Without IDs the bridge tool degrades to substring matching on message text
      that no gate holds stable, and every message reworded here silently breaks it.
      *Two corrections to the ask as stated, measured 2026-08-26:*
      **(a) 9 sites is not 9 diagnostics.** Six sites carry one message each (`tenantScoped` deprecation,
      the T29 `UNIVERSE` refusal, unknown `@Validation.validateOn`, deprecated `@Validation` attribute, and
      the two `-Aexeris.strict` inert warnings). The other three are the helpers `note` / `error` /
      `reportProcessingFailure`, which fan out to 11 / 5 / 5 call sites. The error+warning path therefore
      needs ~16 identifiers, not 9 — and giving those three helpers a required ID parameter is what forces
      each call site to name one, so the count is the design input, not a detail.
      **(b) The prefix covers the `javac` half only.** `exeris-codegen-maven-plugin` raises 14 further
      user-facing diagnostics (`VerifyCapabilitiesMojo`, `GenerateMojo`, `DetachMojo`) and **none** carries
      the prefix — several are bare `e.getMessage()` pass-throughs of a `CapabilityGraphException` or an
      ADR-055 Wall violation. A developer pasting "my build failed" is at least as likely to paste one of
      those as a processor warning, so scoping the registry to `ExerisDomainProcessor` would leave the more
      opaque half of the surface dark.
- [x] **D5 — half the `-Aexeris.strict` inert registry could never fire.** *Fixed 2026-08-27.* The audit
      is driven by `warnInertAttributes(...)` call sites, not by `INERT_ATTRIBUTES` itself, and only two
      call sites existed: `@Field` and `@ActionParam`. Two of the four registered entries —
      `@Action.path` and `@ExerisDomain.apiVersion` — were therefore unreachable. A strict build never
      reported either, and nothing noticed, because no test had ever asserted either warning. Both call
      sites are added. The gate against a repeat is a reachability test that sets all four registered
      attributes in one compilation unit and asserts four warnings by name, so an entry added without its
      call site fails there rather than going quiet; the registry javadoc gains criterion (3) —
      *the annotation's extraction path must call `warnInertAttributes` with the same simple name* —
      alongside the two it already stated.

      Surfaced while pinning SDK 0.11.0 (U3). `@Action.path` gained a default there, which made its
      registry note ("it has no default, so every author is required to write a path the server will not
      answer on") stale; writing the correction is what exposed that the warning carrying it could not
      fire. Worth naming as a class: this is D4's problem from the other end — D4 is about diagnostics a
      consumer cannot identify, D5 about diagnostics a consumer never receives.
- [x] **D6 — D5 fixed the instance; the class is one registry wider.** *Shipped 2026-08-27.*
      `warnInertAnnotations` takes an `Element`, and the field and method traversals that already
      hosted the twin `warnInertAttributes` sweep now host this one too — in the *loops*, so a
      warning's reachability does not depend on whether `@Field` or `@Action` is also present. The
      gate is a second reachability test, over `INERT_ANNOTATIONS`: one fixture places all three
      registered annotations at their declared targets and asserts three warnings by name. Proven by
      deleting the two new call sites — exactly the three new tests fail, nothing else.

      `@Blob` (`FIELD`) and `@Schedule` (`METHOD`) are registered in the same change, which is what
      makes the two new sweeps non-vacuous: before them neither registry held an entry outside type
      level, so a gate written without them would have covered code nothing exercised. Registration
      is **not** extraction — nothing is written to the AST, so the ADR-042 lockstep stays unarmed
      and the `-io` reader owes nothing. What changes is that an author who writes either annotation
      and runs `-Aexeris.strict` is told it has no effect, instead of hearing nothing. Original
      finding below.

      **D6 — D5 fixed the instance; the class is one registry wider.** Opened 2026-08-27, from a
      kernel-side reading of the `@Blob` disposition. The failure class D5 closed is *a registry whose
      entries are reachable only through call sites, with no gate proving reachability*. There are
      **two** such registries and only one has a gate: D5's test is literally named "reaches every
      registered inert attribute" and asserts four `@X.y` warnings, all from `INERT_ATTRIBUTES`.
      `INERT_ANNOTATIONS` has no equivalent, and is correct today only because it holds a single entry
      (`@EventSourced`) that happens to be `@Target(TYPE)` — which is what `warnInertAnnotations`
      inspects, taking a `TypeElement` at both call sites. `@Blob` is `@Target(FIELD)` and `@Schedule`
      is `@Target(METHOD)`; either added today would be a dead entry.

      **This is due independently of whether `@Blob` extraction happens**, because the registry is
      currently right by accident rather than by construction. The work: extend criterion (3) and a
      reachability gate to `INERT_ANNOTATIONS`, and widen `warnInertAnnotations` from `TypeElement` to
      `Element`. That last part is cheap — `findAnnotation` already takes `Element`, and the
      per-element traversals already exist and already host the twin `warnInertAttributes` sweep, so
      no new traversal is added.

      **One placement trap, recorded before anyone hits it:** the field-level sweep belongs in the
      field *loop*, not inside `extractFieldMetadata`, which sits behind a `@Field` gate. A `@Blob`
      field with no `@Field` is a real shape — `@Blob` describes a byte carrier, not a column — and
      the loop's `else` branch already admits such a field to the AST via `FieldMetadata.simple(...)`.
      Gating the sweep on `@Field` would inherit somebody else's condition, which is D5 again, shifted
      by one call.
- [x] **D7 — a write against a row that is not there answers 404 (ADR-076).** Shipped 0.8.0. What
      the finding named — an emitted test asserting `204` where production answers `500` — turned
      out to be one of *three* answers the emitted app gave to one question, and fixing only the
      double would have made the test agree with a wrong handler. The measured spread:
      `KernelRepositoryGenerator` threw `RuntimeException("<Entity> not found: " + id)`, so the fact
      existed as a message substring; `KernelHandlerGenerator#appendServerErrorCatch` closed every
      route with one `catch (RuntimeException)` → **500**; `OpenApiPathsBuilder#buildResponses`
      declared **404** on every operation and **500** on none; the emitted handler test asserted
      **204**. Meanwhile `GET /{id}` and every action route already answered **404** for the same
      fact, so the resource's by-id surface disagreed with itself.

      The fix is the smallest thing a `catch` can act on: a type. `KernelErrorGenerator` emits
      `<Entity>NotFoundException` per entity and `<Entity>VersionConflictException` for a versioned
      one, into the generated repository package — per entity because a shared type would live under
      `basePackage`, which a per-domain emitter cannot resolve (it is a pipeline input, explicit or
      auto-detected), and in the *repository* package because the domain package is the consumer's
      own and `OrderNotFoundException` is a name they may already have written there. `DELETE` and
      an unversioned `PUT` answer `404`; a versioned `PUT` and every action route answer `409`,
      because the update matches on `id` **and** version in one statement and `409` is true of both
      outcomes where `404` would lie about one. The spec now declares `500` everywhere and `409`
      where it can occur. The stub service gained `rowExists`, so the emitted test says which side
      of the branch it is on.

      Perturbation found a defect in the *new tests* before it found anything else: a
      `containsSubsequence` over the whole emitted file matched catch clauses belonging to a
      different handler method, so deleting the delete route's catch still passed. Rewritten as
      contiguous indentation-normalised blocks. Two follow-ups fell out and are recorded as **D8**.
      Original finding below.

      **D7 — the emitted handler test asserts a delete status production does not give.** Found
      2026-08-27 while checking a review challenge on T48, and it is the same class as the
      stub-service `id` gap that T48 itself closed. The emitted `<Entity>HandlerTest`'s
      `Stub<Entity>Service.delete` records the id and returns; the real service delegates to
      `deleteById`, which **throws** when `rowsAffected == 0`
      (`KernelRepositoryGenerator#buildDeleteById`). So the emitted `handleDelete` test deletes an id
      the stub has never seen and asserts `204`, while the same call against a real repository
      answers `500`. The test is not wrong about the handler's routing — that is what it is for — but
      it is the only place a reader can look for "what does DELETE do on an unknown id", and it
      answers the opposite of production.

      Two things to settle in the slice, not here: whether the double should track existence and
      throw (making the emitted test assert 500, which documents the real behaviour), and whether a
      `DELETE` on an absent id **should** be a 500 at all — an idempotent-delete endpoint answering
      `404`, or `204`, are both defensible, and the current answer was never chosen so much as
      inherited from a `rowsAffected == 0` guard written for a different reason.
- [x] **D8 — the emitted OpenAPI promises an authentication the emitted app does not have
      (ADR-079).** Shipped 0.8.0. The `grep` that found it — no emitter answers `401` — is not by
      itself proof, because in this architecture the *kernel* answers `401`, not the handler. So the
      claim was measured against kernel 0.11, and the measurement is what settled both open
      questions. `CommunityHttpRequestProcessor` reads `HttpKernelProviders.httpRoutePolicy()`, an
      `Optional` over a `ScopedValue` the application binds; with nothing bound the dispatcher
      resolves every route to `RouteRequirement.permitAll()` ("an application that declares nothing
      carries no edge authorization at all"), and a `PERMIT_ALL` route is admitted **without running
      the `SecurityInterceptor`** — no token read, no `PRINCIPAL_CONTEXT`, and the `401` the
      dispatcher can otherwise write is unreachable. No emitter binds `HTTP_ROUTE_POLICY`; the
      emitted `Application` binds exactly one HTTP slot, `HTTP_SERVER_HANDLER`.

      **Removed, not gated**, because gating fails twice: the declaration exists but is inert
      end-to-end (corrected 2026-08-28 — the first write-up said no declaration existed, having
      searched annotation *type* names and missed attributes: `@ExerisDomain` and `@Action` both
      carry `roles` and `permissions`, `DomainMetadata` has fields for them, and the processor
      extracts neither, so every one of those lists is empty in every build), and the one case that
      looked like it justified a gate — a tenant-partitioned entity, which truly cannot serve
      without a bound context — answers `500` from the tenant guard, not `401`. A gated claim would
      have named the wrong status for its own best case. The cheap half landed with it: response
      sets are now per route (`GET` collection `200/500`; `POST` `201/400/500`; by-id `GET`,
      unversioned `PUT`, `DELETE` `…/400/404/500`; versioned `PUT` `200/400/409/500`, agreeing with
      ADR-076's emitted catch instead of declaring both). Carried in the same change: the emitted
      tenant-guard message told the consumer to "install the kernel SecurityInterceptor ahead of
      this router", inoperative at 0.11 — the interceptor is already in the dispatcher and runs only
      for a route that demands identity. Two follow-ups recorded as **T51** and **D9**.
      Original finding below.

      **D8 — the emitted OpenAPI promises an authentication the emitted app does not have.** Found
      2026-08-27 while auditing `buildResponses` for D7, and left out of ADR-076 deliberately: it is
      the same *family* of defect — a spec claiming what no emitted code can do — but it is a claim
      about authentication rather than about write rejection, and merging them would have made
      ADR-076 decide something it never measured.

      `OpenApiSecurityBuilder.buildSecurity` attaches a `bearerAuth` requirement to **every**
      operation of **every** entity, unconditionally, and `buildSecuritySchemes` describes it as
      JWT bearer. `grep -rn UNAUTHORIZED exeris-codegen-java/src/main/java/` returns nothing: no
      emitter answers `401`, and no emitter enforces any scheme. So a consumer who hands the spec to
      a client generator, or to a reviewer, gets an API that documents authentication it does not
      perform — the most consequential shape of the "spec over-promises" defect, because the reader
      most likely to trust it is the one deciding whether the endpoint is safe to expose.

      Second, smaller half: `buildResponses` puts `404` on routes that have no id to miss — the
      collection `GET` and the create `POST`. Harmless next to the first half, same root cause (one
      response set for every operation shape), and worth fixing in the same pass.

      Two things to settle in the slice: whether the security block should be **removed** until an
      auth story exists, or **gated** on something the author declares (there is no annotation for
      it today — which is itself the answer to whether it can be gated); and whether the response
      set should become per-operation-accurate while it is being touched, which is the cheap half.

- [x] **D9 — the emitted OpenAPI document is mostly `null`.** Shipped 0.8.0. Measured before
      touching anything: one entity with two fields, one action and `versioned` emitted **1664
      lines, 1479 of them `: null`** — 89% of the document. The one-line `NON_NULL` fix the finding
      predicted works for those, and is not enough: `exampleSetFlag` survives it. That field is
      swagger-model bookkeeping, not an OpenAPI field, and the 3.1 schema's
      `unevaluatedProperties: false` rejects it — so the hand-configured mapper was emitting an
      invalid document, not merely a noisy one. The fix is to stop hand-configuring: swagger ships
      `Yaml31.mapper()` for exactly this model, and it drops both. 1664 → 167 lines, and array
      indentation moves to swagger's canonical style, which is a visible diff in every regenerated
      spec.

      The gate is a **round-trip**, not a text assertion: dropping fields from the writer is only
      safe if the reader still gets a whole document, so the emitted YAML is parsed back with
      `OpenAPIV3Parser` and asserted to yield zero messages with its paths and schemas intact.
      `swagger-core` and `swagger-models` are now declared rather than used as swagger-parser
      transitives — this module imports their types directly, and an undeclared compile dependency
      breaks the moment a transitive is reshuffled. No ADR: the document's *content* is unchanged
      and the round-trip proves it; what changed is the serializer. Original finding below.

      **D9 — the emitted OpenAPI document is mostly `null`.** Found 2026-08-28 while asserting on
      the emitted YAML for D8. `OpenApiGenerator`'s YAML mapper writes every unset field of the
      swagger model: a single-entity spec carries `termsOfService: null`, `contact: null`,
      `externalDocs: null`, `security: null`, and per-operation `callbacks: null`, `deprecated: null`,
      `servers: null`, plus roughly thirty `null` keys inside every schema (`multipleOf`,
      `exclusiveMaximum`, `uniqueItems`, …). The document is valid YAML and parses, so nothing has
      broken; what it costs is a spec no human reads by choice and a diff nobody can review. The
      fix is one mapper configuration (`setSerializationInclusion(NON_NULL)`), which makes it a
      one-line change with a large emitted-output diff — hence a slice of its own rather than a
      rider on D8. Worth checking while there: whether the emitted spec is byte-identical across
      runs once the nulls are gone, and whether any downstream consumer (the TS client, a
      generator a user points at it) reads a field that is currently emitted as explicit `null`.

- [ ] **T51 — five layers of an authorization story, none of them joined.** Split out of D8, then
      re-measured on 2026-08-28 because D8's own account of it was wrong. The declaration is not
      missing; almost everything else is. What exists, and what each layer actually does:

      | Layer | State |
      |---|---|
      | `@ExerisDomain(roles, permissions)`, `@Action(roles, permissions)` | declared in the SDK, with Javadoc saying "**declared but not extracted**" (kernel v0.11, ADR-061) |
      | `DomainMetadata.roles/permissions`, `ActionMetadata.permissions`, and the TS Zod schema | fields exist, `@JsonProperty` and all — always empty |
      | processor extraction | **absent**: `grep -rn "roles\|permissions" exeris-processor/src/main` returns nothing |
      | `-Aexeris.strict` completeness audit | **silent** — no `INERT_ATTRIBUTES` entry, so an author sets a permission and is told nothing (fixed alongside this entry) |
      | backend enforcement | nothing binds `HTTP_ROUTE_POLICY`, so every emitted route is `PERMIT_ALL` (ADR-079) |
      | one generator that *would* read it | `DomainMetadataGenerator` copies `action.permissions()` into the metadata JSON the frontend generators consume — a consumer that is there and never receives a value |
      | frontend enforcement | `guard-gen` emits `canView<Entity>` etc. checking `auth.hasPermission(<ENTITY>_PERMISSIONS.READ)` against **invented** constant names, and `app-structure-gen` attaches them to **no route** |

      The last row is the sharpest: the generated frontend guards on permissions the generated
      backend does not check and the author did not declare, and then does not install the guards.

      **The vocabulary question is already settled upstream, and not by us.** The kernel's
      `RouteRequirement` decides on **scopes** (`PERMIT_ALL` / `AUTHENTICATED` / `ANY_SCOPE` /
      `ALL_SCOPES`); `RouteAuthorizationEnforcer` consults `PrincipalContext.scopes()` and never
      `roles()`. The SDK's Javadoc rules out mapping `roles` onto scopes by a `ROLE_x` convention —
      it would stand up a second, silently diverging authority model at the edge — and points roles
      at the kernel's method-level `@RequiresRole` (kernel ADR-014, its own build-config processor)
      instead. So **`permissions` is the half with a destination** and `roles` is deliberately not.

      What is left for us is a design with real forks, hence an RFC before an ADR: how a path
      template matches the concrete path the dispatcher passes; what an *undeclared* route gets
      (today's `PERMIT_ALL`, or `HttpRoutePolicy.unmatched()`'s `AUTHENTICATED`, which would lock
      down every route of every existing app); whether an entity's permissions are `ANY_SCOPE` or
      `ALL_SCOPES`; how `@Action` permissions compose with the entity's; where the policy binds,
      given that `HTTP_ROUTE_POLICY` is a `ScopedValue` bound around boot and ADR-070's
      `RuntimeComponents` reaches neither; and what the FE guards should read once the names are
      declared rather than invented. Restoring ADR-079's security block is the last step, not the
      first.

- [ ] **D10 — the TS side has a bearer-token code path that reaches no emitted output.** Surfaced by
      the review of the D8 PR and verified: `KernelStrategy.getDefaultHeaders`
      (`exeris-codegen-ts/src/core/backend-strategy.ts:231`) sets
      `` headers['Authorization'] = `Bearer ${context.accessToken}` `` at `:251`, and the method is
      declared on the `BackendStrategy` interface at `:111` — but its only caller in the repository
      is its own spec (`test/core/backend-strategy.spec.ts`). No `*-gen.ts` generator invokes it, so
      no emitted Angular service ever sends the header. It is the same defect ADR-079 removed from
      the OpenAPI document, in the other emitter: a code path that describes an authentication the
      generated app does not perform, kept honest only by a test that calls it directly. The
      question the slice settles is which way it resolves — delete it as dead (the `@Retention`-style
      argument: nothing reads it, so it has no effect), or wire it, which is a T51 question because
      a header is worth sending only once a route requires one. Related to the standing
      "emitters wired by nobody" pattern; do not fix it in isolation from T51.

- [x] **D11 — three Handlebars templates and the `templatesDir` config option are wired to nothing.**
      Shipped 0.8.0, deleted rather than wired. The measurement made the choice easy: nothing calls
      `Handlebars.compile` anywhere in the package, so the three `.hbs` files were never rendered by
      anything, and the four helpers `service-gen` registered at module load existed for templates
      that no code loads. The templates had also drifted — the list template still emitted the
      pre-T40 `{{entityName}}Filter` — which is what a parallel implementation nobody runs always
      does.

      Removed with them: `resolveTemplatesPath` (no callers), the `templatesDir` config key (read
      only by that function), the `handlebars` runtime dependency, and the two test blocks that
      existed to cover the dead code — one of which said so in its own comment ("the inline renderer
      doesn't invoke them today"). Emitted output is byte-identical; the schema strips unknown keys,
      so a consumer config that still sets `templatesDir` keeps building, exactly as silently as it
      did when the key existed and did nothing.

      One consequence worth recording, because it looks like a coverage dodge and is not: deleting
      well-covered dead code *lowered* `service-gen.ts` branch coverage below its gate, since the
      file's remaining uncovered arms became a larger share. Two real tests closed it — a
      fully-qualified known Java type and a PascalCase domain type with no enum suffix, both of
      which must **not** produce an enum import. The one arm still uncovered is unreachable while
      every non-primitive entry in `KNOWN_JAVA_TYPES` carries both its simple name and its FQN; it
      now carries a comment saying so, rather than inviting the next reader to write a test that
      cannot pass. Original finding below.

      **D11 — three Handlebars templates and the `templatesDir` config option are wired to nothing.**
      Found 2026-08-28 while tracing the T40 barrel failure. `src/templates/angular/` holds
      `entity.service.ts.hbs`, `form.component.ts.hbs` and `list.component.ts.hbs` — a second,
      parallel implementation of three generators — and `config.ts` exposes `templatesDir` plus
      `resolveTemplatesPath` to locate them. `grep` for `.hbs` outside that directory returns
      nothing, and `resolveTemplatesPath` has **no callers**: the emitters build their output with
      `lines.push(...)`, and the templates have drifted from them (the list template still emits
      `{{entityName}}Filter`, which T40 has just corrected in the live path). The config option is
      the worse half — it invites a consumer to point at a template directory that nothing reads.
      Same family as the `generateDetails` / `generateSagas` / `generateEvents` flags and **D10**:
      code that describes a capability the pipeline does not have. Decide per artefact — delete, or
      wire and keep in step — but do not leave a documented knob attached to nothing.

---

## Versioning policy

- **0.x** — generated code shape may change in any release; consumers regenerate after every tooling bump
- **1.x** — generated code shape changes only via additive minors; deprecation cycle for breaking changes
- Output artifact compat is the headline contract — Maven plugin API is secondary
- **A release tag carries a final version in the POM.** `v0.7.0` is the first one that does: `v0.5.0`
  and `v0.6.0` were both tagged with the reactor still at `X-SNAPSHOT`, which no sibling repo does
  (`exeris-sdk` `v0.10.0` → `0.10.0`, `exeris-kernel` `v0.11.0` → `0.11.0`). A tag pointing at a
  mutable coordinate is a tag nobody can resolve. The cut is: release PR sets the final version →
  tag that commit → a follow-up PR opens the next cycle at `X+1-SNAPSHOT`. Separately and still
  binding: no cross-repo dependency may be a SNAPSHOT at a cut — release upstream first, pin the
  final, then tag.

## Tracking

- Per-milestone follow-ups: see open issues with `milestone: 0.X.0` label
- Round-1/round-2 review deferrals: [issue #2](https://github.com/exeris-systems/exeris-tooling/issues/2)
