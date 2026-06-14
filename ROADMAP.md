# Exeris Tooling — Roadmap to 1.0.0 GA

The tooling layer is the **build-time pipeline**: annotation processor reads
`@ExerisDomain` user code → emits `DomainMetadata` JSON → kernel-target Java
generators consume it → emit handlers, services, repositories, OpenAPI specs,
sagas. 1.0.0 GA means: **the codegen output is stable**, the Maven plugin API
is stable, and downstream user apps can pin to it with semver guarantees.

This file tracks scope per milestone. Items marked `[ ]` are open; `[x]` shipped.

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

- [x] `exeris-codegen-maven-plugin` module — `maven-plugin` packaging, reactor-wired; a thin Maven shell over `CodegenPipeline` (no emission logic). ASM override (9.9.x) on `maven-plugin-plugin` so the descriptor scanner reads Java 26 (class major 70) bytecode
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

> Goal: capability annotations (from SDK 0.4.0) drive additional generation paths.

- [ ] `KernelCapabilityClientGenerator` — emits typed clients for capability ports
- [ ] `KernelCapabilityHandlerGenerator` — wires capability event handlers
- [ ] OpenAPI spec aggregation for capability-exposed APIs

> This milestone's capability surface is the input the mesh story (**T12**) needs —
> `@Provides`/`@Requires`/`@CapabilityModule` are currently extracted by no processor
> pass and consumed by no generator (**S5**, SDK-side). Wiring the capability graph here
> is prerequisite (1) of T12's contract-registry. See the **Codegen completeness backlog
> → T12** below.

## 0.6.0 — codegen-ts hardening

> Goal: TS/Angular generator is on equal footing with Java (currently treated as preview-grade).

- [ ] Add `exeris-codegen-ts` to a top-level orchestration target (Makefile or `frontend-maven-plugin`)
- [ ] CI: separate npm-build job
- [ ] Round-trip tests against generated Angular workspace (compiles + `ng build` green)

## 0.7.0–0.9.0 — feedback-driven cleanups

- [ ] Generator output adjustments based on real budgetHQ + IDP-cap consumer feedback
- [ ] Remaining items from the **Codegen completeness backlog** below — High-severity items
      (T1, T8, T10, T12) are pulled forward into earlier milestones; the rest land here
- [ ] Performance: large-corpus generation profiling + fixes
- [ ] Memory: stream metadata loading instead of slurping all `*.json` upfront

## 1.0.0 GA — stable codegen + plugin

> Goal: any 1.x release produces source-compatible output; user apps' generated code keeps compiling across 1.x bumps.

- [ ] Generated-code golden snapshot suite (per generator, per scenario)
- [ ] `exeris-codegen-maven-plugin` API frozen (mojo parameters, lifecycle phase semantics)
- [ ] `KernelArtifactGenerator` SPI frozen (third-party generators can plug in)
- [ ] `MIGRATION-0.x-to-1.0.md`
- [ ] Maven Central release (processor + codegen-core + codegen-java + plugin)
- [ ] npm registry release for `@exeris/codegen-ts`

---

## Codegen completeness backlog

> Tooling gaps surfaced by exercising the full pipeline (processor + codegen + `javac` JDK 26 + the
> Angular emitter) against a larger, multi-aggregate, multi-service domain than the `Order` sample.
> Each item is the gap plus the concrete *needed update*; SDK/kernel/DX halves of these findings are
> owned and tracked in their own repos. Where a tooling fix has an SDK/kernel counterpart
> (T4↔S2, T5↔S1, T3↔S3, T12↔S5/K3/K4) the dependency is named but the cross-repo half is out of
> scope here. Stable handles (`T*`) are kept so cross-references resolve.

| # | Finding | Severity | Recommended target |
|---|---|:---:|---|
| T1  | `@Action` endpoints advertised (OpenAPI + Angular) but no kernel route serves them — 404 | **High** | 0.6.0 |
| T8  | No generated finders/indexes for FK + `filterable` fields → O(n) `findAll().filter()` everywhere | **High** | 0.6.0 |
| T10 | `@Validation` enforced client-side (Zod) but dropped server-side (handler/service/DB) | **High** | 0.6.0 |
| T12 | N generated apps can't form a mesh — client is own-app/relative-host, saga step is local, no cross-app contract | **High** | 0.5.0 → 0.6.0 |
| T2  | Zero tests generated for the generated surface | Medium | 0.6.0 |
| T3  | Action identity = method name, not `@Action(name=…)` → bean-setter collisions | Medium | 0.5.x |
| T4  | `@Relationship` target derived from field Java type, not `targetEntity` | Medium | 0.5.x |
| T5  | System-field overrides (`tenantIdField`, …) ignored by the repository generator | Medium | 0.5.x |
| T9  | Generated schema has no inter-entity foreign keys — zero referential integrity | Medium | 0.6.0 |
| T11 | No fidelity/strict mode — annotation attributes set but consumed by no generator fail silently | Medium | 0.5.x |
| T13 | Codegen emits per-entity output but never prunes it — a removed/renamed entity breaks the build | Medium | 0.5.x |
| T6  | Naive English pluralization (`colony → colonys`) in SQL tables + Angular routes | Low | 0.5.x |
| T7  | TS app-structure seams — per-entity path vs `app.routes` import mismatch; hardcoded title/redirect | Low–Med | 0.6.0 |

### High severity

- [ ] **T1 — Serve custom actions, or gate them out (restore Java/TS parity).** `@Action` methods
      reach the OpenAPI spec and the Angular service, but the generated `RuntimeLifecycle` wires
      **CRUD only** (entities × 5) — no route or handler method for any action, so a generated
      frontend `POST`s endpoints the generated backend answers with 404. Two emitters disagree across
      the one metadata contract.
      *Update:* add an action-dispatch path to `KernelHandlerGenerator` + matching routes in
      `KernelApplicationGenerator`'s `RuntimeLifecycle`, **or** gate actions out of the OpenAPI/TS
      emitters until the server side exists. Either way restore parity (emitter-parity skill should
      catch this). Pairs with **T3** (action identity) and the command-surface gap noted under T12.

- [ ] **T8 — Generate finders + FK/`filterable` indexes.** Repositories expose only
      `findById/findAll/save/update/deleteById/count`; every cross-aggregate lookup forces a
      `findAll().stream().filter(...)` — O(n) per call — and FK columns aren't indexed (only
      `tenant_id` + `searchable` fields get indexes). The intent is already in the annotations
      (`@Field(filterable=true)`, the FK relationships).
      *Update:* emit finder methods (e.g. `findByOwnerId`) for FK and `filterable` fields, plus the
      matching `CREATE INDEX`. Removes most hand-written filter glue; turns table scans into index
      lookups. Feeds, and feeds off, **T9**'s relationship graph.

- [ ] **T10 — Emit server-side validation (handler/service) + `CHECK` constraints.**
      `@Validation(min/max/pattern/minLength/…)` flows into the Angular Zod schemas but the generated
      server handler/service enforce nothing — a malformed request the UI rejects sails into the
      backend; the DB gets only `NOT NULL` + `VARCHAR(255)`. Same contract honoured on one emitter,
      silently dropped on the other.
      *Update:* emit server-side validation from `ValidationMetadata` (reject → map to `400`), and/or
      `CHECK` constraints in Flyway — restoring Java/TS parity on the validation contract.

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

### Medium severity

- [ ] **T2 — Generate tests for the generated surface (opt-in flag).** The pipeline emits handlers,
      services, repositories, clients, sagas, events, Flyway, OpenAPI — and **zero tests**. Mirror the
      existing `*Generator` / scaffold structure + determinism + parity rules:
      Java — `Kernel*TestGenerator` per entity (repository CRUD round-trip, handler request/response
      shape, service delegation; saga step-wiring for `@Saga` entities; reuse the kernel TCK patterns).
      TS — `*.service.spec.ts` (HTTP) + `*.schema.spec.ts` (Zod) under Vitest. Output stays
      deterministic and committed alongside the code it covers.

- [ ] **T3 — Use `@Action(name=…)` as action identity.** `extractActionMetadata` sets
      `name = method.getSimpleName()` and ignores the (required) `name` attribute, so a
      `@Action(name="…")` on a bean-setter-shaped method (e.g. `void setFormation(Formation)`)
      collides with the generated setter (`method … is already defined`).
      *Update:* prefer `@Action(name=…)` when present so method names can avoid accessor collisions.
      Pairs with **S3** (SDK-side: the attribute is otherwise inert).

- [ ] **T4 — Honour `@Relationship.targetEntity`.** `extractRelationshipsMetadata` calls
      `extractTargetEntityFromType(field.asType())` and never reads `targetEntity`, so a
      `@Relationship private UUID ownerId` records its target as `UUID` — the annotation only works on
      entity-typed fields, a poor fit for the explicit-UUID-FK style.
      *Update:* prefer explicit `targetEntity` when set; fall back to field type only when it is
      `void.class`. Pairs with **S2** and feeds **T9**'s relationship graph.

- [ ] **T5 — Honour system-field override attributes in the repository (+ Flyway) generators.**
      `KernelRepositoryGenerator` hard-codes `getTenantId()/getCreatedAt()/getUpdatedAt()/
      getVersion()`, so renaming or omitting any of those fields fails compile
      (`cannot find symbol method getUpdatedAt()`).
      *Update:* derive accessor names from the `*Field` overrides in `DomainMetadata`. Pairs with
      **S1** (alternative is dropping the attributes SDK-side).

- [ ] **T9 — Cross-entity relationship pass → `FOREIGN KEY` constraints.** Each entity's Flyway is
      generated in isolation; the only `REFERENCES` emitted are the tenant FKs. An `owner_id` column is
      a bare `UUID NOT NULL` — no referential integrity, no cascade, no cross-entity awareness. Same
      blind spot as **T4**: the pipeline has no relationship graph.
      *Update:* a cross-entity pass (from `@Relationship` and/or convention-named `*Id` FKs) emitting
      `FOREIGN KEY` constraints + `ON DELETE` policy in Flyway, feeding join-aware finders (**T8**).

- [ ] **T11 — Strict mode / generation report for inert annotation attributes.** The systemic root
      behind T1/T3/T4/T5 (and S1–S5): an attribute set from the rich annotation Javadoc silently does
      nothing because no generator consumes it; the only way to learn it's inert is to read
      processor/generator source. The processor knows what it read; the generators know what they
      emit; the difference is computable.
      *Update:* an opt-in `-Aexeris.strict` (or a generation report) that warns, per entity, when a
      metadata attribute is set but consumed by no generator — turning silent no-ops into actionable
      `javac` diagnostics. Highest-leverage trust fix for the annotation surface.

- [ ] **T13 — Generation must own its output tree (prune orphans).** Codegen *writes* per-entity files
      but never *deletes* them: removing or re-homing an entity leaves its stale
      `Repository/Handler/Service/Client` + OpenAPI/Flyway referencing the deleted type
      (`cannot find symbol: class …`) until removed by hand. Worse, the *app-wide* files
      (`RuntimeLifecycle`, `Application`) **are** regenerated and drop the entity, leaving the tree
      internally inconsistent and un-compilable. Renaming/re-homing an entity is a normal refactor.
      *Update:* track what was emitted last run (a manifest, or a "clean the managed tree" step) and
      delete files whose entity no longer exists. Pairs with the committed-L1 model (**D3**) — a stale
      orphan is a real, reviewable diff. (Note: distinct from the `exeris:detach` prune in **0.3.0**,
      which prunes the *emptied* source tree, not the *generate* mojo's per-entity output.)

### Low severity

- [ ] **T6 — Real pluralization (or honour overrides).** `colony → colonys` in both
      `V*__create_colonys.sql` and the Angular route `path: 'colonys'`; `construction_order` works only
      by luck.
      *Update:* an irregular-plural map, or honour the existing `DomainMetadata.tableName` (Java) and an
      analogous route/`pluralLabel` override (TS) in both emitters.

- [ ] **T7 — TS app-structure seams (`exeris-codegen-ts`).** Per-entity components emit to
      `<out>/components/…`, but generated `app.routes.ts` imports `./components/…` relative to
      `<out>/src/app/`, so routes don't resolve without a move; `redirectTo` (first entity
      alphabetically) and a hardcoded app title are baked in.
      *Update:* emit per-entity files under `src/app/...` to match the app shell; make the default route
      + app title configurable (CLI flag / config).

### Build & DX — tooling-owned halves

> The full D1–D3 findings are DX-tracked; the parts with a tooling fix are captured here.

- [ ] **D1 — `requireJavaVersion` enforcer + README up-front.** `exeris-codegen-maven-plugin` classes
      are class v70 and load into Maven's JVM, so on JDK 21/25 the build dies at *plugin load* with an
      opaque classworlds `UnsupportedClassVersionError` realm dump — before `maven.compiler.release`
      matters.
      *Update:* state "Maven on JDK 26" up front in the tooling README, and add a `requireJavaVersion`
      enforcer (or Mojo precondition) that fails with one clear line instead of the realm trace.

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

---

## Versioning policy

- **0.x** — generated code shape may change in any release; consumers regenerate after every tooling bump
- **1.x** — generated code shape changes only via additive minors; deprecation cycle for breaking changes
- Output artifact compat is the headline contract — Maven plugin API is secondary

## Tracking

- Per-milestone follow-ups: see open issues with `milestone: 0.X.0` label
- Round-1/round-2 review deferrals: [issue #2](https://github.com/exeris-systems/exeris-tooling/issues/2)
