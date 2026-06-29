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

## 0.6.0 — codegen-ts hardening

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
- [ ] Add `exeris-codegen-ts` to a top-level orchestration target (Makefile or `frontend-maven-plugin`)
- [ ] CI: separate npm-build job
- [~] **Angular v22 migration** (emitted scaffold + idioms; scope T-C, phased A→B→C). **Done:**
      Phase A compat bump — `@angular/* ^22`, TS `~6`, drop `withFetch()` (#95); Phase B1 scaffold cleanup —
      drop `@angular/platform-browser-dynamic`, Node-22 floor, `@angular/build` builder (#96); Phase B3
      data-fetch fix — `rxResource` in detail, `firstValueFrom` in store (#98). **Pending:** Phase C
      (Reactive Forms → Signal Forms, ADR-worthy → flag-gated WebMCP); small tidy (`@Service`, `?.` audit).
      `debounced()` rejected (experimental). Gated by the `ng build` round-trip above.
- [ ] Round-trip tests against generated Angular workspace (compiles + `ng build` / `tsc --noEmit` green)
      — the FE analog of `KernelCodegenCompileTest`; **this is the catch for T20** (the generated frontend
      currently doesn't build) and guards the Phase C reshape just as it would have caught T20

### 0.6.0 scope (agreed) — "codegen-ts on par with Java + parity/correctness debts closed + a gate that keeps it honest"

**Shipped:** **T1** action-serving (#92) · Angular v22 Phase A/B1/B3 (#95/#96/#98) · SDK pinned to released 0.7.0 (#100).

**In this cut:**
- **T20 + FE build gate** — fix the duplicate-emission/enum-stub *and* add the `ng build`/`tsc --noEmit` round-trip (the FE analog of `KernelCodegenCompileTest`; the permanent catch). One PR.
- **T7** remainder — configurable title/redirect; collapse the duplicate tree (rides with T20).
- **T10** — server-side `@Validation` (handler/service + `CHECK`), the other High parity break.
- **T8** — generate finders + FK/`filterable` indexes (kills O(n) `findAll().filter()`).
- **T2 (FE slice)** — emit `*.service.spec.ts` + `*.schema.spec.ts` into the generated app, run by the FE gate. (Full Java `Kernel*TestGenerator` → 0.7.0.)
- **U1** — wire the ui-kit (`exerisPreset`, semantic classes) into the emitted app.
- **T18** — build-safety: guard the T13 pruner on empty input + the capability-pass phase ordering.
- **D1** — `requireJavaVersion` enforcer + README up-front.

**Deferred to 0.7.0+:** **T12 + T17** (the cross-app mesh epic), **T9** (FK-constraint relationship graph), the full **T2** Java test-emitter, and the **U2–U5** UI-depth cluster (U2 universal lists is the lead 0.7.0 item). EV1/EV2 per their own section. (**T19** typed `Instant` bind — done in 0.6.0, the kernel persistence-SPI gate cleared in 0.10.)

See the **Codegen completeness backlog** below for per-item detail.

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
| T1  | `@Action` endpoints advertised (OpenAPI + Angular) but no kernel route serves them — 404 | **High** | ✅ 0.6.0 (#92) |
| T20 | Generated Angular frontend doesn't compile (`npm run build` fails) — two parallel TS emission paths; the `src/app` sourceRoot ships an empty enum stub that shadows the real `types/enums.ts`, so enum-typed code fails (TS2304/2305) | **High** (latent) | 0.6.0 |
| T8  | No generated finders/indexes for FK + `filterable` fields → O(n) `findAll().filter()` everywhere | **High** | 0.6.0 |
| T10 | `@Validation` enforced client-side (Zod) but dropped server-side (handler/service/DB) | **High** | 0.6.0 |
| T12 | N generated apps can't form a mesh — client is own-app/relative-host, saga step is local, no cross-app contract | **High** | 0.7.0 |
| T17 | Capability-graph validation is closed-world per app — a legitimate cross-service `@Requires` hard-fails the build | **High** | 0.7.0 |
| T2  | Zero tests generated for the generated surface | Medium | 0.6.0 (FE slice) / 0.7.0 (Java) |
| T3  | Action identity = method name, not `@Action(name=…)` → bean-setter collisions | Medium | 0.5.x |
| T4  | `@Relationship` target derived from field Java type, not `targetEntity` | Medium | 0.5.x |
| T5  | System-field overrides (`tenantIdField`, …) ignored by the repository generator | Medium | 0.5.x |
| T9  | Generated schema has no inter-entity foreign keys — zero referential integrity | Medium | 0.7.0 |
| T11 | No fidelity/strict mode — annotation attributes set but consumed by no generator fail silently | Medium | 0.5.x |
| T13 | Codegen emits per-entity output but never prunes it — a removed/renamed entity breaks the build | Medium | 0.5.x |
| T18 | Capability validation × two-pass build deadlock; `mvn clean` + T13 prune wipes the committed L1 tree | Medium | 0.6.0 |
| T19 | Repository binds `Instant` as ISO string but DDL declares `TIMESTAMPTZ` — round-trip latent-broken on real Postgres | Medium | **Done 0.6.0** (native `bindInstant`/`getInstant`, kernel 0.10 SPI) |
| T7  | TS app-structure seams — per-entity path vs `app.routes` import mismatch breaks the build; hardcoded title/redirect | Medium | 0.6.0 |
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

- [ ] **T20 — Generated Angular frontend doesn't compile (`npm run build` fails).** The FE analog of
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
      *Update (exeris-codegen-ts):* collapse to **one** emission path — make the `src/app` (sourceRoot)
      tree the canonical output of the real per-entity generators (drop the duplicate top-level loop, or
      vice-versa), and delete the `app-structure-gen` enum stub in favour of the real `generateEnumTypes` /
      `EnumGenerator`. Unifying the path fixes the enum-stub and the import-resolves-to-stub symptoms
      together. **The catch is the 0.6.0 "generated workspace compiles + `ng build` green" gate** — a
      `tsc --noEmit`/`ng build` over a generated sample, the FE analog of `KernelCodegenCompileTest`.
      (FE orphan-pruning — the other suspected FE-twin — is **already done**: the **T13** manifest pruner
      runs on the TS CLI path, `index.ts:302`.) Surfaced by a larger multi-entity, multi-service frontend trial.

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

- [ ] **T2 — Generate tests for the generated surface (opt-in flag).** The pipeline emits handlers,
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

- [ ] **T9 — Cross-entity relationship pass → `FOREIGN KEY` constraints.** Each entity's Flyway is
      generated in isolation; the only `REFERENCES` emitted are the tenant FKs. An `owner_id` column is
      a bare `UUID NOT NULL` — no referential integrity, no cascade, no cross-entity awareness. Same
      blind spot as **T4**: the pipeline has no relationship graph.
      *Update:* a cross-entity pass (from `@Relationship` and/or convention-named `*Id` FKs) emitting
      `FOREIGN KEY` constraints + `ON DELETE` policy in Flyway, feeding join-aware finders (**T8**).

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

- [ ] **T18 — Two-pass build hazards from the capability pass (deadlock + clean-wipes-L1).** Adopting
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
| U1 | **Wire ui-kit into the generated app** — ✅ **DONE 2026-06-28.** Emitted `styles.css` now `@import "@exeris/ui-kit/theme"` (the v4 `@theme` token entry); hardcoded `bg-indigo-600`/`hover:bg-indigo-700` etc. across the emitted templates → `bg-exeris-primary` token utilities (evidence-checked against `theme.css`); `@exeris/ui-kit` added to the emitted `package.json`; `presets:[exerisPreset]` added to the (v4-vestigial) `tailwind.config.js` for v3 consumers; the boilerplate `.btn-primary`/`.input-field` + `bg-gray-100 text-gray-900` body removed. **Finding (B1 twin):** the generated app is Tailwind **v4**, whose ui-kit `@theme` entry ships **tokens only, not the `.exeris-*` component classes** (those are v3 `index.css`), and has **no neutral surface/text token** — so token utilities were used (not component classes) and neutral `gray-*` were left as standard Tailwind (no token to map to). Also added a configurable `appName` (CLI `--app-name`) replacing the hardcoded `'Exeris Foundation'` (closes the **T7/U5** title remainder). The tooling-side fix for Stellar finding **T25**. | codegen-ts (ui-kit is ready) | small | ✅ 2026-06-28 |
| U2 | **Universal lists** — column types from metadata (enum→badge w/ `@UI.color`, number→`format`+align, bool→icon, date, FK→link/`displayField`, currency/percent from `dataType`); wire sort to headers (logic exists, only the `(click)` is missing); real filters for `filterable` fields (string/enum/date-range — today only bool + 2 fields); configurable `pageSize`; row actions | codegen-ts (+ processor emits `format`/`dataType`/`sortable`/`filterable`) | medium | 0.6.0 |
| U3 | **Forms from metadata, not the Java type** — read `@UI.componentType` (textarea/select/date/slider/toggle/rich-text/file/color), `@UIGroup`→sections, `@Tab`→tabs, `gridSpan`→multi-column, `placeholder`/`helpText`, `@Relationship`→autocomplete picker (today a UUID FK = `type="text"`); fix type mapping (`long→number`, `UUID→picker`) | codegen-ts (+ processor + TS schema) | med–large | 0.6.0 |
| U4 | **Fidelity end-to-end** — processor emits the full `uiMetadata` / per-field `UIFieldMetadata`, the TS Zod schema models it, and strict-mode (**T11**) warns when a `@UI` attribute is declared but dropped | processor + codegen-ts | medium | 0.6.0 (with T11) |
| U5 | **Configurable detail / branding** — sections/tabs in the detail view, related-entity panels; app name/titles/icons from metadata (today a hardcoded `"Exeris Foundation"` + an emoji-by-entity-name map) | codegen-ts | small–med | 0.6.0 (extends **T7**) |
| U6 | **New view shapes** — dashboard/cards/kanban/calendar from `@Projection`, charts from `@Graph` (deferred), master-detail, inline-edit, bulk-actions | SDK (light) + codegen-ts | large | 0.7.0–0.9.0 |
| U7 | **Live-view** (e.g. a battle preview) — a round stream pushed to the client. Transport SHIPPED (ADR-043/044 — Slice 1 #104 + Slice 2 #106: `HttpStreamHandler` + `streamRoute` + `EventSource`/RxJS clients); the generated producer is a keep-alive scaffold until the **EV1-stream** pass binds the `@DomainEvent` bus | ~~kernel (K2, done)~~ → codegen-java/-ts (**EV1-stream**) | large | scaffold shipped 0.6.0; real feed with **EV1** |
| U8 | **Genuinely missing in the SDK** — custom-component registration (plugin), per-role field visibility (RLS-aware), i18n labels, icon-set abstraction | SDK + codegen-ts | large | SDK-led |

> **Recommendation:** highest return for least motion is **U1** (wire ui-kit) + **U2** (universal
> lists) — the theme already exists tokenized, and lists already know more than they show
> (sort/filter are half-there). Together: re-skin the whole UI via one set of CSS tokens without
> touching generated code, and get lists with real column types — inventing nothing in the SDK.
> **U7's kernel blocker (K2) is now cleared** — the SSE transport landed (kernel 0.10, ADR-043) and the
> emitter ships it (Slice 1 #104 + Slice 2 #106); what remains is generator-side, the **EV1-stream**
> producer pass (see Events & event sourcing). U6/U8 have SDK halves owned in `exeris-sdk`.

### Events & event sourcing

> Grounded in a kernel-side audit (2026-06-16). The open-core kernel already ships a **mature
> transactional-outbox pipeline** with **two swappable broker backends behind one Core port**
> (`OutboxBrokerPort`; impls `KafkaEventBrokerPort` + `CommunityEventBusOutboxBrokerPort`, **both
> open-core** in `exeris-kernel`, selected at bootstrap). The "Kafka vs internal" choice the founder
> recalls is real, but it is a **kernel bootstrap concern, already swappable** — not something codegen
> branches on.

- [~] **EV1 — `@DomainEvent` payload realization (publisher exists, ships empty events).**
      `KernelEventGenerator` already emits a per-entity `*EventPublisher` whose
      `publish<Event>(UUID streamId)` calls `eventEngine.bus().publish(descriptor, …)` with
      `FLAG_PERSISTENT`, so events flow through the kernel's transactional outbox transparently.
      **Swappability is already solved and must stay codegen-invisible:** generated code binds to the
      backend-agnostic EventEngine SPI and **never names Kafka** — the broker is chosen below the SPI at
      bootstrap. A Kafka-specific (or RabbitMQ-specific) generator would violate single-target discipline
      (hard-constraint #1); do **not** add one.
      *Realizable gap (→ 1.0.0):* the payload is hardcoded `EventPayload.empty()`, so generated events
      carry **no data**. Emit field-projection into the payload, honouring
      `@DomainEvent.includeFields/excludeFields/includeComputed/sensitiveFields`. The many knobs with no
      kernel counterpart (topic, partitionKey, schema/Avro, headers, exchange/routingKey, retention) stay
      inert — `-Aexeris.strict` (**T11**) is the right surface to flag them, once each is verified
      per-attribute against the union of Java+TS emitters.

- [~] **EV1-stream — replace the SSE keep-alive scaffold with a real `@DomainEvent` → `StreamEvent`
      projection.** ADR-043/044 Slice 1 (entity-level `@ExerisDomain(realTimeApi)`, #104) and Slice 2
      (per-action `@Action(streaming)`, #106) ship the streaming **transport** end-to-end — the kernel
      `HttpStreamHandler` + `streamRoute` (Java) and the `EventSource` / RxJS-over-fetch clients (TS) — but
      the generated `handle(...)` body is a **deterministic keep-alive scaffold**. ADR-044 obligation 3
      names the `@DomainEvent` bus as the producer seam. *Realizable gap (→ with EV1):* emit a long-lived
      subscription to this entity's `@DomainEvent` stream that projects each event into
      `StreamEvent.of(eventType, json)` — reusing the **EV1** field-projection above for the `json` payload
      — and delete the keep-alive loop. The Slice-2 dedup put that loop behind **one** seam,
      `KernelStreamScaffold.keepAliveScaffold(...)`, so it is the sole replacement site for *both* drivers;
      and the TS clients already parse NAMED SSE frames, so named domain events flow with **no client
      reshape** the moment the producer emits them (strong-default #4 parity holds for free). **Interim,
      today:** the downstream fills the seam by subclassing the generated handler — proven in the Stellar
      dog-food (`EraLiveStream extends GalacticEraStreamHandler`, projecting `EraTurned` into a real
      `text/event-stream`). Gated on EV1 payload realization (above); pairs with `@Projection` as the
      natural event→DTO shape. **Closes U7.**

- [ ] **EV2 — `@EventSourced` aggregate generator — BLOCKED on a kernel SPI.** No generator emits
      event-sourced aggregates today; **T11 strict mode surfaces `@EventSourced` as inert** (extracted
      into the JSON, consumed by nobody — and SOURCE-retained, so no runtime reader either). The blocker
      is upstream: the kernel has **no aggregate-event-store SPI** (no append-with-expected-version /
      load-from-stream / snapshot store). The intended seam — `EventStreamAppender` / `EventStreamReader`
      (`exeris-kernel-spi`, `@since 0.7.0`) — exists as interfaces but has **zero implementations and zero
      binding sites**, and `@EventSourced`'s referenced `EventSourcedAggregate` base class has no kernel
      counterpart. Codegen cannot emit working append/load/snapshot calls until the kernel binds these.
      *Sequencing for 1.0.0:* (1) kernel implements + binds the aggregate-event-store SPI; (2) an RFC/ADR
      fixes the codegen target shape (touches the processor↔generator contract and the kernel-target
      surface → ADR-triggering per this repo's CLAUDE.md); (3) build `KernelEventSourcedGenerator` and
      **delete the `@EventSourced` entry from `INERT_ANNOTATIONS`** in the processor in the same change.
      Until (1), the strict-mode warning is the honest signal.

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
