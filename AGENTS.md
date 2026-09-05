---
title: "exeris-tooling: build-time code-generation pipeline of the Exeris ecosystem"
type: reference
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-09-05
---

# exeris-tooling

Guardrails for AI assistants working inside this repository — the contract a session must respect,
and an index to where each rule lives. Human onboarding is [`README.md`](README.md) and
[`CONTRIBUTING.md`](CONTRIBUTING.md).

## Mission and scope

`exeris-tooling` is the **build-time pipeline** of the Exeris ecosystem: it turns annotated Entity-First
Java sources (`@ExerisDomain`, `@Action`, `@Field`, …) into kernel-target code (handlers, services,
repositories, OpenAPI, Flyway, sagas) and matching Angular/TypeScript artefacts. It is not a runtime,
not a framework, and not a host application — it runs at `javac` time and `npm run build` time, and its
output is committed into downstream user apps.

Two builds live here: the Java Maven reactor, and **`exeris-codegen-ts` (npm, standalone, not in reactor)**.
Cross-build coordination is intentional and must stay explicit.

Coordinates: groupId `eu.exeris`, packages `eu.exeris.tooling.*`.

## Operating contract

**Non-negotiable invariants, whatever the task:**

- **Single target: Exeris kernel:** Multi-backend abstractions (Spring/Quarkus/Micronaut/Vanilla) were
  removed in 0.1.0. Do NOT reintroduce them. Spring hosting belongs downstream in `exeris-spring-runtime`
  ([policy](.agents/policies/kernel-target-only.md)).
- **Annotation processor is build-time only:** `exeris-processor` depends strictly on `javax.lang.model`,
  `exeris-sdk-source-model`, and JDK. Zero runtime libraries (no Jackson databind leaking runtime types,
  no Spring, no kernel runtime). Diagnostics use `e.toString()`, not `e.getMessage()`
  ([policy](.agents/policies/processor-build-time-only.md)).
- **Codegen determinism:** Same `DomainMetadata` → byte-identical output across runs, machines, and
  locales. No timestamps, no random UUIDs, no `HashMap`/`HashSet` iteration order leaking into emitted code.
  Use `Locale.ROOT` ([policy](.agents/policies/codegen-determinism.md)).
- **DomainMetadata is the sole contract:** Generators MUST NOT read `Element` or `TypeMirror` directly.
  Processor produces JSON; generators consume JSON via `MetadataLoader`
  ([policy](.agents/policies/domain-metadata-contract.md)).
- **Java/TS emitter parity:** Shared metadata surfaces visible to Java emitters must have matching
  consideration in TS emitters (and vice versa) ([policy](.agents/policies/emitter-parity.md)).
- **Generated code is committed:** Emit into `src/main/generated/` (L1) until consumer runs `exeris:detach`
  (L2, 0.3.0 plugin). Never assume "always regenerate" ([policy](.agents/policies/committed-generated-code.md)).
- **Consumer-build contracts:** Emitted code binds `System.Logger` with `{0}` placeholders and quote
  escaping (ADR-060, no slf4j). Emitted tests in `src/test/generated/java` import only JUnit 5 +
  AssertJ (ADR-058). `CapTierWall` uses dependency-free Class-File API (ADR-055)
  ([policy](.agents/policies/consumer-build-contracts.md)).
- **Scoped bans:** Hard boundaries across processor, emitters, and TypeScript generator
  ([policy](.agents/policies/scoped-bans.md)).

## Architecture and documentation entry points

1. [`docs/adr/`](docs/adr/): [ADR-015](docs/adr/ADR-015-codegen-emission-strategy.md) (emission strategy),
   [ADR-055](docs/adr/ADR-055-cap-tier-wall-guard.md) (CapTierWall),
   [ADR-058](docs/adr/ADR-058-generated-test-emission-channel.md) (test channel),
   [ADR-060](docs/adr/ADR-060-generated-code-logging-facade.md) (logging facade),
   [ADR-070](docs/adr/ADR-070-generated-composition-root-seam.md) (RuntimeComponents seam),
   [ADR-085](docs/adr/ADR-085.link.md) (federated documentation and hygiene).
2. [`docs/MIGRATION-0.x-to-1.0.md`](docs/MIGRATION-0.x-to-1.0.md) and [`ROADMAP.md`](ROADMAP.md) for breaking
   changes and milestone scope.
3. Upstream & downstream references: `exeris-sdk/exeris-sdk-source-model/`, `exeris-kernel/exeris-kernel-spi/`,
   downstream consumer applications ([reference](.agents/references/cross-repo-dependencies.md)).

## `.agents/` — the canonical semantic source

Detailed rules are authored once under [`.agents/`](.agents) and nowhere else.

| Path | What it holds |
|:--|:--|
| [`.agents/policies/`](.agents/policies) | Non-negotiable boundaries: kernel target, build-time processor, determinism, DomainMetadata, emitter parity, committed code, consumer contracts, scoped bans. |
| [`.agents/references/`](.agents/references) | Authoritative summaries: build & testing, cross-repo dependencies, pipeline architecture & emission strategy. |
| [`.agents/skills/`](.agents/skills) | Bounded review capabilities: ADR shape gate, Angular emission, determinism review, consumer build contracts, detach output discipline, emitter parity, kernel target discipline, processor discipline, strict audit, triage. |
| [`.agents/agents/`](.agents/agents) | Role profiles: router, architect, implementer, codegen verification, docs ADR. |
| [`.agents/workflows/`](.agents/workflows) | Repeatable sequences: determinism audit, consumer build contract audit, emitter parity audit, processor discipline audit. |
| [`.agents/manifest.yaml`](.agents/manifest.yaml) | Composition metadata. Imports none. |

Instruction sources resolve broad to narrow: organisation bundle → repository → subtree → workflow.
A narrower file may restrict behaviour; it may never relax a higher-order rule.

## Verification and reporting

- `mvn clean install` runs the full reactor build.
- Compile gate: `mvn -pl exeris-e2e-tests -am test -Dtest=KernelCodegenCompileTest` verifies generated code compiles against kernel SPI via in-memory `JavaCompiler`.
- E2E snapshot: `mvn -pl exeris-e2e-tests -am test -Dtest=KernelCodegenE2ETest` verifies exact emitted shapes.
- Processor flags: `-Aexeris.verbose` (opt-in chatter), `-Aexeris.strict` (completeness audit: extracted-but-unconsumed vs never-read).
- Determinism check: regenerate twice, `diff -r`, expect byte-identical output.
- TypeScript suite: `cd exeris-codegen-ts && npm test`.

Report outcomes first. A claim names the command that proves it, verified against effective source.

## Conventions and contribution terms

Binding standards live in [`exeris-docs/standards/`](https://github.com/exeris-systems/exeris-docs/tree/main/standards):
commit conventions, PR conventions, javadoc conventions, docs style guide, ADR conventions, the
[agent-file schema](https://github.com/exeris-systems/exeris-docs/blob/main/standards/agents-md-schema.md),
and [AI provenance](https://github.com/exeris-systems/exeris-docs/blob/main/standards/ai-provenance.md).

An AI-assisted commit keeps its `Co-authored-by:` trailer, a named human is accountable for every
line, and an agent does not open pull requests or file issues unattended. Contribution terms:
[`CONTRIBUTING.md`](CONTRIBUTING.md).

## Provider adapters

[`.claude/`](.claude) holds Claude Code adapters generated from `.agents/`, each carrying a
do-not-edit marker naming its source. Rewrite them with `tools/agent-adapter-check/agent-adapter-render.sh`
and verify with `tools/agent-adapter-check/agent-adapter-check.sh`; never edit an adapter directly.
[`CLAUDE.md`](CLAUDE.md) points here.

## Auto-memory

Persistent memory for this workspace lives at `~/.claude/projects/-home-arkstack-exeris-systems-exeris-tooling/memory/`.
Use it for process feedback and user preferences, never project facts.
