# ADR-001 — Codegen Emission Strategy: JavaPoet for Java, Text Blocks for SQL/YAML

**Status:** Proposed *(flips to Accepted at merge)*
**Date:** 2026-05-06
**Deciders:** @arkstack-dev
**Scope:** `exeris-tooling` (codegen-core + codegen-java)
**Related:** ROADMAP 0.4.0 (codegen quality refactor), [issue #2](https://github.com/exeris-systems/exeris-tooling/issues/2)

---

## Context

The Kernel generator suite (12 `Kernel*Generator` classes in `exeris-codegen-java`) currently emits all output — Java, SQL, YAML — by hand via `StringBuilder.append(...)`. This produces three concrete problems that the 0.4.0 milestone is meant to fix:

**1. Duplication.** Sonar flags 3.8% duplication on new-code overall; `KernelHandlerGenerator` 59.8% and `KernelClientGenerator` 40.6%. The pattern is the same every time: package declaration, imports, Javadoc, class header, methods, footer — concatenated as raw strings. Each new generator copies the scaffold from a sibling.

**2. Output stability is a hand-graded contract.** A misplaced semicolon, a missing `import`, or a `\n` in the wrong column produces broken output that the e2e substring tests still pass. The 0.2.0 `KernelCodegenCompileTest` gate now catches the worst cases (anything that fails `javac`), but it doesn't catch — and isn't meant to catch — cosmetic regressions, modifier ordering changes, or formatting drift that downstream consumers will see in their VCS as enormous "no-op" diffs after a tooling bump. The 1.0 contract is **source-compatible generated output across 1.x bumps**; we cannot defend that without a layer that owns the output's structure.

**3. Adding a generator is a copy-paste exercise.** New `Kernel*Generator` classes start by duplicating the scaffold from the closest sibling, then mutating. There is no shared scaffold. ROADMAP 0.5.0 (`@Capability`-aware codegen) plans to add at least two more generators — every additional one compounds the duplication and the migration cost.

This ADR exists because the standing rule is *"architecture-shaping refactors need an ADR first"* — picking an emission strategy is exactly that. It locks in a third-party dependency (JavaPoet), a Java language feature for non-Java paths (text blocks), and a migration shape that ripples through every generator. Once chosen, reversing it inside 0.x is feasible but expensive; reversing it in 1.x would be a breaking output change.

---

## Decision drivers

- **Output stability across 1.x.** We need a layer that knows what a method looks like, not what string a method renders to.
- **Type and symbol safety.** A missing import or a fat-fingered modifier should fail at codegen-time, not at the user's `mvn compile`.
- **Zero new logging-style hidden deps.** Per the project's standing rule, `exeris-tooling` does not pull in slf4j or any third-party logging stack. JavaPoet is a different category (a codegen library, not runtime infra), but the rule informs the bar: any new dep must earn its place.
- **JDK floor is 26.** Text blocks (JEP 378, stable JDK 15) are unconditionally available. Stable through 26.
- **Sonar coverage gates the 0.4.0 release.** Whatever we pick must measurably reduce duplication, not just relocate it.
- **Existing dep surface.** We already pull Square's AutoService (`@AutoService`) via the processor module; adding JavaPoet (also Square / Apache 2.0) is a marginal increase in supply-chain surface, not a new vendor.

---

## Options considered

### Option A — Status quo: pure `StringBuilder`

Keep everything as-is.

- ✅ Zero migration cost.
- ✅ Zero new dep.
- ❌ Sonar duplication stays at 60% on the worst generators; 1.0 GA goal is unreachable without it.
- ❌ Output stability remains hand-graded — `KernelCodegenCompileTest` is necessary but insufficient.
- ❌ Adding capability generators (0.5.0) compounds the duplication 2×.

**Rejected** — does not solve the problem the milestone exists to solve.

### Option B — Text blocks everywhere (no JavaPoet)

Replace `StringBuilder.append(...)` with Java 15+ text blocks for both Java emission and SQL/YAML, parameterizing via `String.formatted(...)` or `replace("{{NAME}}", ...)`.

- ✅ Zero new dep.
- ✅ Massive readability win on the SQL/YAML paths (multi-line interpolation is the default case).
- ✅ Already supported by JDK 26 floor.
- ❌ Java emission becomes a templating exercise: imports must be tracked manually, modifiers concatenated as strings, type references string-keyed. The "missing import" class of bug is unchanged.
- ❌ Shared scaffold extraction is awkward: text blocks are not first-class composable values; you end up with a `String` constant pool keyed by template name, which is just `StringBuilder` with prettier syntax.
- ⚠️ JEP 459 (String Templates) was the next-generation answer here, but it was withdrawn in JDK 23 and no replacement is on the JEP roadmap as of 26. Cannot rely on it.

**Rejected for Java emission**, **accepted for SQL/YAML emission** — the failure modes differ. Java emission needs symbol-level type safety; SQL/YAML emission needs interpolation, which text blocks do well.

### Option C — JavaPoet for Java, text blocks for SQL/YAML *(chosen)*

Use [JavaPoet](https://github.com/square/javapoet) (Square, Apache 2.0) for every Java-emitting generator. Use text blocks for SQL (`KernelFlywayGenerator`) and YAML (`KernelOpenApiGenerator`).

- ✅ JavaPoet is the de-facto JVM library for type-safe Java emission. Used by Dagger, Auto, Hilt, gRPC-Java, Lombok-equivalents — proven across kernels of significantly larger scale.
- ✅ Type-safe imports (`ClassName.get(...)`), modifier sets, parameter lists. Missing-import bugs become impossible by construction.
- ✅ Shared scaffold extraction is a natural drop-out: `TypeSpec.Builder` is a composable value; "package + imports + Javadoc + class header" becomes a helper that returns a `TypeSpec.Builder`.
- ✅ Output formatting is owned by JavaPoet's pretty-printer — deterministic across runs, stable across Java versions.
- ✅ Compatible with our 1.0 GA "golden snapshot suite" plan: snapshots become reliable diffs instead of cosmetic noise.
- ⚠️ Adds one runtime dep (`com.squareup:javapoet:1.13.0` — small, no transitives we don't already pull). Inside the codegen-java module only; does not leak to `exeris-sdk` or `exeris-codegen-core`.
- ❌ Migration is a per-generator rewrite — there is no mechanical translation. ~12 generators × O(few hundred lines each).
- ❌ JavaPoet's output style differs from current cosmetic conventions (trailing newlines, blank-line placement, parameter wrapping). Downstream consumers will see a one-time large diff on first regen.

**Chosen** — only option that hits both the duplication target and the type-safety target. The migration cost is paid once; the output-stability dividend repeats for every 1.x release.

### Option D — External template engine (Mustache, FreeMarker, StringTemplate)

Pull a template engine, externalize the templates, parameterize them.

- ✅ Templates live as data, can be swapped without code changes.
- ❌ Templates are stringly-typed — same "missing import" bug class as text blocks.
- ❌ Adds a heavyweight dep with significant transitive surface (FreeMarker pulls Apache Commons, Mustache.java pulls compiler tooling).
- ❌ Debugging template errors is significantly worse than debugging Java code.
- ❌ Externalizing the templates means template files need to ship alongside the codegen JAR, complicating the future Maven plugin (0.3.0).

**Rejected** — solves no problem JavaPoet doesn't solve, costs more to integrate, and pushes errors out of compile-time.

### Option E — JEP 459 String Templates

Use the (preview) Java language feature.

- ✅ Would have been the cleanest solution if it had landed.
- ❌ **Withdrawn in JDK 23.** No replacement on the JEP roadmap as of JDK 26. Cannot bet 1.0 GA on a withdrawn proposal.

**Rejected** — does not exist as a stable feature.

---

## Decision

Adopt **Option C**:

1. **JavaPoet for every Java-emitting generator** (`Kernel*Generator` that produces `.java` output).
2. **Text blocks for SQL emission** (`KernelFlywayGenerator`).
3. **Text blocks for YAML emission** (`KernelOpenApiGenerator`).
4. **Shared scaffold helper** (`TypeSpec.Builder kernelScaffold(String packageName, String className, String javadoc, ...)`) lives in `exeris-codegen-java` (e.g., `eu.exeris.tooling.codegen.java.support.KernelScaffold`). It cannot live in `exeris-codegen-core` because its signature exposes JavaPoet's `TypeSpec.Builder` — that would force JavaPoet into `codegen-core` and violate the pure-AST scope below. All `Kernel*Generator` classes already live in `codegen-java`, so no cross-module dependency is needed. Generators compose the helper; they do not duplicate it.

JavaPoet dependency:

```xml
<dependency>
  <groupId>com.squareup</groupId>
  <artifactId>javapoet</artifactId>
  <version>1.13.0</version>
</dependency>
```

Scoped to `exeris-codegen-java` only. Does not appear in `exeris-codegen-core` (which stays pure-AST), `exeris-processor`, or any `exeris-sdk` module.

---

## Migration plan

The migration ships as a phased sequence, each phase one PR. Each PR keeps `KernelCodegenCompileTest` green and updates affected `KernelCodegenE2ETest` substring assertions to JavaPoet-structural assertions where the substrings drift.

| Phase | Scope | Why this order |
|---|---|---|
| 1 | Pilot: `KernelHandlerGenerator` (Sonar 59.8%) | Highest-duplication target — first to validate the approach gives the duplication numbers we expect. Also exercises imports, methods, and modifier sets in one go. |
| 2 | `KernelClientGenerator` (Sonar 40.6%) | Second-highest duplication. After two generators, the shared-scaffold shape is settled and can be extracted. |
| 3 | Extract `kernelScaffold(...)` into `exeris-codegen-java` (under `eu.exeris.tooling.codegen.java.support`) | Codifies the pattern that emerged from Phase 1+2. Refactor — no behavior change. Stays in `codegen-java` because the helper's signature uses JavaPoet's `TypeSpec.Builder`. |
| 4 | Remaining 7 Java-emitting generators: `KernelRepositoryGenerator`, `KernelServiceGenerator`, `KernelSagaGenerator`, `KernelEventGenerator`, `KernelEventHandlerGenerator`, `KernelGraphSyncGenerator`, `KernelApplicationGenerator` (the last also emits Application + CompositionRoot + RouterConfig output files) | Mechanical at this point — each generator becomes ~30% of its current LOC. Total tally: 11 generator classes − Handler (P1) − Client (P2) − Flyway (P5) − OpenApi (P6) = 7. |
| 5 | `KernelFlywayGenerator` → text blocks | Independent of JavaPoet phases. Can land anytime after Phase 1. |
| 6 | `KernelOpenApiGenerator` → text blocks | Same. |
| 7 | `MIGRATION-0.x-to-1.0.md` entry *(doc-only — no code change)* | Document the one-time output-style diff downstream consumers will see. |

Each phase is independently reversible (revert that PR; remaining generators stay on JavaPoet or StringBuilder per their phase). The dep is added in Phase 1 and never removed. **Phases 5 and 6 (text blocks for SQL/YAML) are independent of the JavaPoet phases** — the table orders them after for readability, but they can land any time after Phase 1 ships.

---

## Consequences

### Positive

- Sonar duplication on the two worst generators drops from 59.8% / 40.6% into single digits (target: under 10%). Verified per-phase.
- Missing-import / wrong-symbol class of bug becomes a `javapoet` compile-time error in the generator, not a `javac` error in the user's app.
- The 1.0 GA "golden snapshot suite" target becomes feasible — snapshots of JavaPoet-formatted output are deterministic. Snapshots of `StringBuilder.append(...)` output are not (whitespace drift across refactors).
- Adding a 0.5.0 capability generator becomes a `TypeSpec.Builder` exercise on top of `kernelScaffold(...)`, not a copy-paste.

### Negative

- One-time large diff for every downstream consumer on first regen after the tooling bump that lands Phase 4. Documented in `MIGRATION-0.x-to-1.0.md`. Can be partially mitigated by lining the migration up against an existing generator-output rewrite (no extra disruption).
- One additional runtime dep (`javapoet:1.13.0`) — 200kB JAR, Apache 2.0, no transitives outside what `auto-service` already pulls. Same vendor as our existing AutoService usage.
- ~2–3 weeks of focused per-generator rewrites across Phase 1–4.

### Neutral

- `exeris-codegen-ts` (Angular generator, separate npm package) is unaffected. It uses its own emission style (TypeScript template literals); this ADR is Java/SQL/YAML scoped.

---

## Validation

A phase-1 PR is considered successful when:

1. `KernelCodegenCompileTest` stays green (output compiles against kernel SPI stubs).
2. `KernelCodegenE2ETest` either passes unchanged or its substring assertions are replaced with structural assertions on the JavaPoet output (`TypeSpec` structure, modifier set, import set).
3. Sonar duplication on the migrated generator drops below 10% (Phase 1 target for `KernelHandlerGenerator`, baseline 59.8%; Phase 2 target for `KernelClientGenerator`, baseline 40.6%).
4. Manual spot-check: regenerate a known consumer (budgetHQ if reachable, otherwise the e2e test fixture) and confirm the output diff is JavaPoet style differences and *not* semantic differences.

A regression on any of (1)–(4) blocks merge.

---

## Open questions

- **Output cosmetic alignment.** Does JavaPoet's default formatting match what we want, or should we wrap it with a post-processor (e.g., `google-java-format`)? Resolved in Phase 1 by inspection of the migrated `KernelHandlerGenerator` output. If JavaPoet's default is acceptable, no post-processor is added.
- **`@Capability` generators (0.5.0).** Should be born JavaPoet-native — no StringBuilder phase. Not a question this ADR resolves; flagged here as the natural follow-on.
- **Streaming metadata loading (0.7.0).** Independent of this ADR. The metadata read path (`MetadataLoader`) is not affected by emission strategy.

---

## Notes

- JavaPoet's last release is 1.13.0 (May 2022). The library is mature and stable; lack of recent releases reflects feature-completeness, not abandonment. Active community fork ([Palantir's JavaPoet](https://github.com/palantir/javapoet)) exists if upstream stagnates further; both are wire-compatible.
- This ADR does not commit to a JavaPoet successor (Kotlin's KotlinPoet is similar but Kotlin-target). If we ever generate Kotlin output, that is a separate ADR.
