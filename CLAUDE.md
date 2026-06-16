# CLAUDE.md — exeris-tooling

Guardrails for AI assistants working inside `~/exeris-systems/exeris-tooling/`. Human-facing description lives in [`README.md`](README.md); this file captures the constraints, conventions, and "what to do when" rules a Claude Code session must respect.

## What this repo is — load-bearing facts

`exeris-tooling` is the **build-time pipeline** of the Exeris ecosystem: it turns annotated Entity-First Java sources (`@ExerisDomain`, `@Action`, `@Field`, …) into kernel-target code (handlers, services, repositories, OpenAPI, Flyway, sagas) and matching Angular/TypeScript artefacts. It is not a runtime, not a framework, and not a host-application — it runs at `javac` time and at `npm run build` time, and its output is committed into downstream user apps.

Two builds live here:

- **Java reactor** (`mvn install`) — `exeris-processor`, `exeris-codegen-core`, `exeris-codegen-java`, `exeris-e2e-tests`, `exeris-tooling-bom`, `exeris-tooling-parent`.
- **TypeScript package** (`exeris-codegen-ts` — npm) — **not part of the Maven reactor**. Built independently with `npm install && npm test`. Cross-build coordination is intentional and must stay explicit.

The founding decision for the emission strategy is [`docs/adr/ADR-015-codegen-emission-strategy.md`](docs/adr/ADR-015-codegen-emission-strategy.md). Read it before any work in `exeris-codegen-java/*Generator.java`.

Pipeline diagram (canonical, from [`README.md`](README.md)):

```
@ExerisDomain Order.java
        ↓
[ exeris-processor ]               ← javax.lang.model at compile time
        ↓
exeris-metadata/Order.json         ← canonical DomainMetadata (records in exeris-sdk-source-model)
        ↓
[ exeris-codegen-core ]            ← MetadataLoader + GeneratorRegistry
   ├── exeris-codegen-java         ← kernel-target Java emission
   └── exeris-codegen-ts           ← Angular components/services/stores (npm, separate build)
        ↓
src/main/generated/{java,typescript}/...  ← committed to repo, regenerated on demand
```

## Hard constraints (always enforce)

These are not negotiable. Re-derive from ADR-015 + the open-core/kernel-target story when challenged.

1. **Single target: Exeris kernel.** Spring/Quarkus/Micronaut/Vanilla generators were deliberately removed (0.1.0 milestone). Do NOT reintroduce a multi-backend abstraction. If a downstream wants Spring hosting, that is `exeris-spring-runtime`'s job — not a `SpringHandlerGenerator` here.
2. **Annotation processor is build-time only.** `exeris-processor` may depend on `javax.lang.model`, the SDK source model, and standard JDK only. NO runtime libraries (no Jackson on the processor classpath for serialization choices that leak runtime types; no Spring; no kernel runtime). Self-registration via `@AutoService(Processor.class)` stays.
3. **Codegen output must be deterministic.** Same `DomainMetadata` → byte-identical output. No timestamps, no random UUIDs, no `HashMap` iteration order leaking into emitted code. The `OutputWriter timestamp drop` (0.1.0 round-2 fix) is canonical — do not regress it.
4. **DomainMetadata is the only contract between processor and generators.** Generators MUST NOT read `Element`/`TypeMirror` directly. The processor produces JSON; generators consume JSON via `MetadataLoader`. This is what allows Java + TS emitters to live in different language ecosystems against one contract.
5. **Java/TS emitter parity.** A field/action/event visible to `exeris-codegen-java` is also visible to `exeris-codegen-ts` (and vice versa, when the surface is shared). Adding a metadata field on one side without a matching emitter consideration on the other is a contract bug, not a feature gap.
6. **Generated code is committed.** Emit into `src/main/generated/` (L1) until a user app runs `exeris:detach` (L2, planned 0.3.0 Maven plugin). Do NOT design the pipeline assuming "always regenerate" — the detachment story is load-bearing.

## Strong defaults (justified exceptions allowed)

1. **Emit Java with JavaPoet, emit text (SQL/YAML/OpenAPI) with text blocks.** This is the 0.4.0 direction in ADR-015 — string concatenation in `StringBuilder.append(...)` is being actively reduced. New `*Generator` classes start with the modern shape unless the surface is genuinely text-shaped (SQL/YAML/JSON).
2. **Each `Kernel*Generator` extracts shared scaffold** — package decl, imports, Javadoc header, class header. The 0.4.0 milestone targets duplication (Sonar flagged ~3.8% on new code; `KernelHandlerGenerator` 59.8%; `KernelClientGenerator` 40.6%). Don't replicate by copy-paste.
3. **Processor diagnostics are user-facing.** Errors land in real `javac` output — keep them actionable. `-Aexeris.verbose` opt-in flag (0.2.0) gates per-entity chatter. Use `e.toString()`, not `e.getMessage()` (which is often `null` for JDK exceptions).
4. **TS generators emit Angular shapes (component / service / store / guard / form / list / detail / app structure / sagas).** Adding a new shape requires a parity story on the Java side — name it explicitly in the PR.
5. **Dependency on `exeris-sdk-source-model`** is via SNAPSHOT install (see Requirements). Don't shadow SDK records here — read them.

## Scoped bans

Banned in `exeris-processor/*` (build-time path):
- Jackson runtime serialisation choices that would couple the processor to a specific JSON library at runtime (DomainMetadata write-out is one well-scoped exception; everything else stays SDK-typed).
- Spring, IoC containers, runtime DI.
- Anything that would force the processor to load classes from the user's project classpath (only the annotation surface and `javax.lang.model` are legitimate inputs).

Banned in `exeris-codegen-java` / `exeris-codegen-ts` (emitters):
- Reading `Element` / `TypeMirror` / `javax.lang.model` directly. Go through `MetadataLoader`.
- Timestamps, random IDs, OS-locale-dependent string ops in emitted output.
- A second backend target (Spring/Quarkus/Micronaut/Vanilla). If proposed, point at the 0.1.0 single-target decision and the cross-repo split (`exeris-spring-runtime`).

Banned in `exeris-codegen-ts` specifically:
- Adding a Maven module wrapper that would pull TS into the reactor. The split is intentional (different toolchains, different release cadence, different consumers).

## Cross-repo dependencies

This repo sits in the middle of the build pipeline:

- **Reads from:** `exeris-sdk` (annotation surface + source model records). Local `mvn install` of `eu.exeris:exeris-sdk-*` is required.
- **Read by:** any downstream user app that runs `exeris-processor` at `javac` time. The 0.3.0 `exeris-codegen-maven-plugin` becomes the canonical invocation point.
- **Targets:** the Exeris kernel SPI surface (`exeris-kernel-spi`, `exeris-kernel-core`). Emitted Java imports these types and binds against them. Generated code must compile against current kernel — `KernelCodegenCompileTest` enforces this for the CRUD path.

When a kernel SPI change lands in `exeris-kernel`, the corresponding generator update lives here. The reverse is not symmetric — generators don't drive SPI shape.

## ADR registry

Tooling-specific ADRs live in `docs/adr/`. The one currently load-bearing:

- **ADR-015 — Codegen Emission Strategy** (text blocks for text artefacts, JavaPoet for Java, shared scaffold extraction; 0.4.0 implementation target).

The single-numbering namespace is owned at `~/exeris-systems/exeris-docs/adr-index.md` (per top-level `CLAUDE.md`). Reserve numbers there before drafting. Refactor-only changes do NOT get an ADR — they go in PR descriptions / commit history.

If a change affects pipeline shape, contract between processor and generators, emitter parity, or kernel-target discipline → **trigger an ADR**, don't just edit code.

## Build & test

```bash
mvn -s ~/exeris-systems/.github/maven-settings.xml clean install   # full Java reactor (if settings present)
mvn clean install                                                  # vanilla local build (settings optional)
mvn -pl exeris-codegen-java -am test                               # one module + deps
mvn -pl exeris-codegen-java test -Dtest=KernelCodegenCompileTest   # compile-gate (catches removed-symbol regressions)
mvn -pl exeris-codegen-java test -Dtest=KernelCodegenE2ETest       # substring assertions on emitted output

cd exeris-codegen-ts && npm install && npm test                    # TS side (separate toolchain)
```

JDK 26 is required (the processor processes Java 26 preview sources). Maven 3.9+ for Java modules. Node 18+ for `exeris-codegen-ts`.

`-Aexeris.verbose` (added 0.2.0) controls per-entity processor chatter. Use it locally; leave default-quiet in CI.

`-Aexeris.strict` (added 0.5.x, T11) opts into a completeness audit: a `javac` WARNING when an annotation attribute — or a whole annotation — is set but no generator consumes it. Well-defined because all SDK annotations are `@Retention(SOURCE)` (erased from bytecode → runtime/SPI/Core cannot read them → the build-time pipeline is the only possible consumer; an unconsumed attribute has zero effect). Backed by two conservative registries in `ExerisDomainProcessor`: `INERT_ATTRIBUTES` (per-attribute) and `INERT_ANNOTATIONS` (whole-annotation, e.g. `@EventSourced` until its generator exists). When a generator starts consuming one of those, delete its registry entry in the same change (a stale entry produces a false "no effect" warning).

## Documentation precedence

When sources disagree, the source-of-truth order is:

1. `docs/adr/ADR-015-codegen-emission-strategy.md` (and any later ADR in `docs/adr/`).
2. `docs/MIGRATION-0.x-to-1.0.md` for breaking changes between trains.
3. `README.md` pipeline diagram + module table.
4. `ROADMAP.md` for milestone scope (0.1.0 shipped → 1.0.0 GA).
5. This file.
6. Top-level `~/exeris-systems/CLAUDE.md` for cross-repo routing and ADR registry rules.

Higher source wins; lower source is a doc-drift task.

## Language

English everywhere — source, comments, commit messages, PR titles, ADRs, this file. Conversation with the founder happens in Polish; persisted artefacts are English.

## Agents, commands, skills

- Functional sub-agents live in `.claude/agents/` (Router, Architect, Implementer, Codegen Verification, Docs/ADR).
- Reusable slash commands live in `.claude/commands/` (codegen-determinism-check, processor-purity, emitter-parity, kernel-target-discipline).
- Skill packs live in `.claude/skills/` (task classifier, routing planner, codegen-determinism review, emitter-parity review, processor-discipline review, kernel-target discipline).

See [`.claude/README.md`](.claude/README.md) for layout, and individual `agents/*.md` / `skills/*/SKILL.md` for invocation contracts.

## Auto-memory

Persistent memory for this workspace lives at `~/.claude/projects/-home-arkstack-exeris-systems-exeris-tooling/memory/` (created lazily when first used). When a session is opened *inside* this repo, that memory directory overrides the parent `~/.claude/projects/-home-arkstack-exeris-systems/memory/`.
