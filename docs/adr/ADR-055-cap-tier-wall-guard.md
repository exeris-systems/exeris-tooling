# ADR-055: Enforce the Cap-Tier Wall by Scanning Bytecode in the Tooling Pipeline

| Attribute       | Value                                                                                                                        |
|:----------------|:-----------------------------------------------------------------------------------------------------------------------------|
| **Status**      | **ACCEPTED**                                                                                                                 |
| **Deciders**    | Arkadiusz Przychocki (founder)                                                                                               |
| **Date**        | 2026-07-30                                                                                                                   |
| **Scope**       | per-repo (`tooling`)                                                                                                         |
| **Owning Repo** | `exeris-tooling`                                                                                                             |
| **Driven By**   | [ADR-024](https://github.com/exeris-systems/exeris-docs/blob/main/adr/ADR-024-capability-composition-model.md) validation predicate 4; the 2026-07-21 gateway-caps first-SKU implementation plan, item P1.3 (tooling 0.7.0 slice G1) |
| **Compliance**  | [ADR-006 The Wall](https://github.com/exeris-systems/exeris-docs/blob/main/adr/ADR-006-spring-free-kernel-boundary.md), [ADR-015 Codegen Emission Strategy](ADR-015-codegen-emission-strategy.md), [ADR-023 licensing/detachment](https://github.com/exeris-systems/exeris-docs/blob/main/adr/ADR-023-capability-licensing-model.md) |

## Context and Problem Statement

ADR-024 defines a composition as valid when four predicates hold. Three of them — every
`@Requires` edge resolves, no cycles, no version conflicts — have been implemented in
`exeris-codegen-core`'s `CapabilityGraph` since 0.5.0 and are gated by
`exeris:verify-capabilities` since 0.6.0. The fourth, **no Wall violations**, has never
been implemented. A composition can therefore carry a `validated:true` stamp today while
containing a cap that imports `org.springframework.context.ApplicationContext`.

That gap matters more than a missing check usually would, because the cap-tier Wall is not
hygiene — it is the mechanism behind two commercial guarantees. It is what keeps
`exeris-spring-runtime` an *independent* Tier 1 product whose absence cannot break any cap
(a cap that hard-wires a host runtime is no longer portable across SKUs). And it is what
makes ADR-023 Code Detachment mechanical: a customer can lift a detached cap into their own
fork precisely because no hidden classpath comes with it. A Wall breach silently converts
both guarantees into promises.

ADR-024 itself is ambivalent about *where* the check lives. Obligation 3 puts it in
"build-time ArchUnit-style guards in every cap repository", and concedes in the same
sentence that "modifying or disabling the guards is a registry violation reported through
periodic audits" — a social control, enforced by review attention that does not scale past
the first few caps. Yet the body's own §"The Wall, extended to capabilities" says the Wall
"is validated by the same `exeris-tooling` pipeline that performs `@Requires` resolution".
Phase 2 of the gateway-caps plan creates the first real `exeris-caps-*` repository, so the
ambiguity has to be resolved before there is a cap to resolve it against.

**This ADR answers: where does the cap-tier Wall get enforced, and against what input?**

## 🏁 The Decision

**The cap-tier Wall is enforced by the tooling pipeline, by scanning the cap's compiled
bytecode with the JDK-standard Class-File API, wired into `exeris:verify-capabilities` at
`process-classes`.**

Predicate 4 joins predicates 1–3 in the same goal that already delivers the fail-closed
composition verdict. Per-repo ArchUnit guards (ADR-024 obligation 3) remain welcome as
defence-in-depth, but they are no longer the primary gate: a cap cannot ship past a
pipeline it must run to produce its own `cap-manifest.json`.

### Bytecode, not sources

Founder-ruled 2026-07-29. A source scan sees only the files the cap declares, which makes
it blind to the failure mode that actually occurs: a forbidden type arriving **transitively
when a dependency changes**. A cap that compiled clean last month can breach the Wall today
without a single line of its own source changing. Bytecode is the artefact that ships, so
bytecode is what the Wall must hold. It also keeps the guard cheap — no parser, no access to
user sources, just `target/classes`.

This is also why the check cannot live in `exeris-processor`: at annotation-processing time
no bytecode exists yet, and the processor is barred from loading the user's classpath at all
(a scoped ban in `CLAUDE.md`). `process-classes` is the earliest phase where the input
exists.

### The extraction surface is the load-bearing part

A constant-pool walk alone is **unsound**, and this is the single most important
implementation constraint in this ADR. The constant pool records the types a class
*touches* in code, not the types it *mentions*. Verified against real `javac` output on
JDK 26:

```java
public void configure(org.springframework.context.ApplicationContext ctx) { }  // pool: nothing
public java.util.List<org.springframework.context.ApplicationContext> all;     // pool: java/util/List
```

The first puts the forbidden type only in the method **descriptor**; the second only in a
generic **`Signature`** attribute. Neither yields a `ClassEntry`. A pool-only guard waves
both through — i.e. it would miss the most natural way to accept a Spring context.

The guard therefore unions five sources: constant-pool `ClassEntry`, field descriptors,
method descriptors, `Signature` attributes (class/field/method), and annotation types.
Every one of the five has its own isolating test in `CapTierWallTest`.

### No new dependency, and specifically not ASM

`exeris-tooling` enforces JDK exactly `[26,27)`, so the Class-File API (JEP 484, final in
JDK 24) is always available. ASM appears in this repo only as a build-plugin-scoped override
for `maven-plugin-plugin`'s descriptor scanner — it is *not* on the plugin's own classpath,
so using it would have meant adding a real dependency to the build-time path for something
the platform now ships.

**Concrete obligations:**

1. **`exeris:verify-capabilities` runs the Wall scan over `${project.build.outputDirectory}` after graph validation.** Order is contractual: a broken graph is the more urgent verdict and reports first.
2. **A violation fails the build** (`MojoFailureException`), listing every breach found, not the first — a cap author sees the whole boundary problem in one build. An unreadable class file is a `MojoExecutionException` instead: an environment fault, not a Wall verdict.
3. **The scan is gated on the module actually being a cap** — it runs only when this build emitted capability metadata. Without that gate the guard would fail every ordinary generated application the moment it touched Spring, and an application is a Tier 3 SKU consumer, not a Tier 2 cap. The Wall was never its contract.
4. **The forbidden set is exactly three boundaries.** Host-runtime packages (`org.springframework.*`, `io.netty.*`, `reactor.*`, `jakarta.servlet.*` — the ADR-024 named floor, extended in one place, `CapTierWall.HOST_RUNTIME_PREFIXES`); kernel private packages (`eu.exeris.kernel.**.internal.**`); and **sibling** cap internals (`eu.exeris.caps.<other>.internal.**`).
5. **A cap may read its own internals.** Own cap names are derived from the `@CapabilityModule` package (`eu.exeris.caps.<name>.…`), never configured. A cap whose module sits outside that namespace claims no name and therefore gets no licence over any `eu.exeris.caps` internal package — the same rule with an empty own-set, not a special case.
6. **`internal` matches as a whole package segment.** A package named `internals` or a class named `InternalCache` is not a private package; a substring test would flag both.
7. **Violations are emitted in deterministic order.** Hard-constraint #3 (determinism) covers diagnostics: a build must not reorder its own error list between runs.
8. **`exeris.wall.skip` disables the Wall alone, loudly.** It logs a WARNING on every build and is deliberately separate from `exeris.codegen.skip`, so a knowing migration breach stays visible to the ADR-024 obligation 3 audit rather than hiding behind a general skip flag.

## Consequences

### ✅ Positive Outcomes

- **[+] Predicate 4 stops being aspirational.** `validated:true` now means all four predicates passed, which is what the stamp has always claimed and the platform composition runtime asserts.
- **[+] Enforcement becomes mechanical rather than social.** ADR-024 obligation 3's "periodic audits" for disabled guards no longer carry the load; the gate is in the pipeline every cap must run.
- **[+] Catches transitive breaches.** The bytecode substrate flags a forbidden type that arrived via a dependency bump, which no source-level or per-repo-source guard can see.
- **[+] Zero dependency cost.** JDK-standard API on a JDK the repo already pins exactly.
- **[+] Detachability stays mechanical.** The ADR-023 guarantee that a detached cap carries no hidden classpath is now checked rather than asserted.

### ⚠️ Trade-offs

- **[-] Reflection is invisible.** `Class.forName("org.springframework…")` cannot be caught by any static scan — the type name is a string constant, indistinguishable from a log message. String constants are deliberately *not* scanned, because doing so would flag a cap for logging the word "springframework". The Wall is an import-boundary guard, not a sandbox.
- **[-] A second input class enters the pipeline.** Until now the pipeline consumed exactly one input, the processor-emitted metadata corpus. It now also reads compiled classes. That is the pipeline-shape change that made this ADR-triggering rather than a refactor, and it means `exeris:verify-capabilities` is no longer runnable from metadata alone.
- **[-] The host-runtime list is a denylist.** ADR-024's "or any host-runtime-specific package" is open-ended; a denylist cannot be complete. A new host runtime entering the ecosystem needs an entry, and until it gets one it is unguarded. An allowlist was rejected as unworkable: caps legitimately use arbitrary third-party libraries.
- **[-] A scan can be vacuous, so it is reported rather than counted as a pass.** Zero violations has two causes: a clean cap, and a `classesDir` the compiler never wrote to (a relocated or overridden output root). The verdict alone cannot tell them apart, so the guard counts the class files it read and, for a cap with none, gates *nothing* and says so — WARNING in both the pipeline log and the Maven log, and a module count of `0`. Inside the normal lifecycle `compile` always populates `target/classes` before `process-classes`, so this is a misconfiguration signal, not an expected state. It stays a warning rather than a failure because a *non*-cap module legitimately reaches the same zero.
- **[-] Fixture tests need a real compiler.** `CapTierWallTest` invokes `javac` at test time, because the guard's whole subject is what javac chooses to emit; hand-assembled class files would test the implementation's assumptions instead of real bytecode. The suite skips on a JRE.

### 📋 What is NOT in scope

- **Whether caps may use `eu.exeris.kernel.core.*` at all.** ADR-024 line 77 is internally ambiguous: its heading forbids "kernel private packages" (internals only), while its closing clause says "only the SPI surface (`eu.exeris.kernel.spi.*`) is callable from cap code" — which would ban all of `core.*`, including the tier-neutral `KernelWebClient` facade that ADR-034 deliberately placed there for generated code to bind. This ADR implements the **unambiguous intersection** (internals are forbidden) and leaves the stricter reading open, to be settled against the first real cap in Phase 2 rather than guessed at now. Choosing the strict reading today risks false-failing the first cap that touches `KernelWebClient`.
- **Enforcing the Wall across a cap's *dependencies*.** The scan covers the cap's own compiled output. Auditing a dependency's bytecode is a supply-chain concern with a different failure model.
- **The per-repo ArchUnit guard set** shipped by the `exeris-caps-*` scaffold (ADR-024 obligation 3). It survives as defence-in-depth; this ADR only demotes it from primary gate.
- **Boot-time enforcement.** ADR-024's body says "an SKU manifest that contains a cap with disabled or stale Wall guards is rejected by the kernel at boot". That clause is **stale** after the 2026-06-17 "Validation Stamp Lifecycle" amendment made the kernel cap-blind. The correct split — build-time Wall gate here, boot-time stamp assertion in the platform composition runtime — is what this ADR implements; it does not reopen that amendment.

## Cross-references

- ADR-024 (Capability Composition Model) — the parent decision; this implements its validation predicate 4 and supplies the enforcement locus its obligation 3 and body section disagreed on.
- ADR-038 (SDK capability annotation surface) — the SDK-side realization of ADR-024; this ADR is its tooling-side counterpart.
- ADR-006 (Spring-Free Kernel Boundary — The Wall) — the substrate-tier Wall whose discipline this extends to Tier 2.
- ADR-023 (Capability Licensing Model) — the detachment guarantee this guard makes mechanical.
- ADR-034 (tier-neutral `KernelWebClient` in `kernel-core`) — the concrete reason the `core.*` question above is left open.
- ADR-015 (Codegen Emission Strategy) — the single-input pipeline contract this decision widens.
- [JEP 484: Class-File API](https://openjdk.org/jeps/484) — the JDK 24 API this guard is built on.

## Engineering Protocol

1. **`CapTierWallTest` (codegen-core)** pins all five extraction sources with one isolating fixture each, plus the three boundaries, segment-exactness, own-vs-sibling internals, and determinism. The parameter-only and generic-argument fixtures are the regression tests against a pool-only implementation.
2. **`VerifyCapabilitiesMojoTest.CapTierWallGuard`** pins the mojo control flow: run order versus graph validation, both skip toggles, the failure-type mapping, and the two readings of a gated-nothing outcome (a cap that was never scanned warns; a non-cap module stays silent).
3. **G3 (the 0.7.0 e2e composition proof)** adds the end-to-end half: a Wall-violating sample cap that must fail a real build. Predicate 4's negative path is not considered fully covered until that lands.
4. **Migration:** nothing to migrate. No `exeris-caps-*` repository exists yet — this guard ships *before* the first cap, which is the only moment it can be introduced without a grandfathering window.
