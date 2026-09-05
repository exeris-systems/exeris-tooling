---
# DO NOT EDIT — generated from .agents/skills/exeris-tooling-consumer-build-contracts/SKILL.md (agents-md-schema.md rule 7). Edit the source.
name: exeris-tooling-consumer-build-contracts
description: Non-obvious contracts between exeris-tooling and the consumer's build. Invoke before editing any emitted log call, any Kernel*TestGenerator, KernelScaffold, CapTierWall, VerifyCapabilitiesMojo, or GenerateMojo — and when reviewing or addressing a review on such a change. Tooling emits no pom.xml, so every import and every API the emitted code touches is a silent requirement on the downstream app's build. Covers ADR-055 (cap-tier Wall scan), ADR-058 (generated-test channel), ADR-060 (System.Logger).
---
<!-- DO NOT EDIT. Generated from .agents/skills/exeris-tooling-consumer-build-contracts/SKILL.md by the AGENTS.md adapter step
     (agents-md-schema.md rule 7). Edit the source, not this file. -->
# Consumer-Build Contracts

## Purpose
`exeris-tooling` emits code into somebody else's project and emits **no `pom.xml`**. Every import
in emitted output, and every input the verify step reads, is therefore a requirement on the
consumer's build that no dependency declaration carries. These three contracts are the ones that
have already been paid for once — do not rediscover them.

## When to Use
- Editing or adding an emitted log statement in any `exeris-codegen-java/**/Kernel*Generator.java`.
- Touching `exeris-codegen-java/src/main/java/eu/exeris/tooling/codegen/java/support/KernelScaffold.java`.
- Touching any `Kernel*TestGenerator.java` or the test-emission channel.
- Touching `exeris-codegen-core/src/main/java/eu/exeris/tooling/codegen/core/capability/CapTierWall.java`,
  `VerifyCapabilitiesMojo`, or `GenerateMojo`.
- Reviewing, or addressing a review on, any of the above.

## ADR-060 — Generated code logs through `System.Logger`

The emitters bind **no logging facade**. `slf4j-api` is not a dependency of `exeris-kernel-spi` or
`-core` — it reached an app only through the driver tier — so it was a requirement on the
consumer's build that no document carried.

Three API differences are load-bearing when touching an emitted log call:

1. Placeholders are `{0}` / `{1}` (`MessageFormat`), **not** `{}`.
2. Single quotes in a *parameterised* message must be doubled via `KernelScaffold.escapeQuotes`.
   A placeholder inside single quotes is emitted verbatim and its argument is **silently dropped**.
3. There is no trailing-`Throwable` convention. Concatenate the value and use
   `log(Level, String, Throwable)`.

Enforcement: the ADR-058 gate compiles with **no** slf4j on the classpath at all.

## ADR-058 — Generated-test emission channel

Generated tests go to a **second output root** (`src/test/generated/java`, its own `OutputWriter`
+ T13 manifest, `addTestCompileSourceRoot`), behind the opt-in `exeris.tests` flag.

Two constraints before touching `Kernel*TestGenerator`:

1. Emitted tests may import **only JUnit 5 + AssertJ**. Tooling emits no `pom.xml`, so every import
   is a requirement on the consumer's build — doubles are emitted, never mocked.
2. The gate **runs** the emitted tests rather than compiling them, so a new test emitter is not
   proven until `GeneratedTestsE2ETest` executes it.

Consequence: `public` + non-final + assignment-only constructors are a **contract of generated
code** — the emitted service stub subclasses it.

## ADR-055 — Cap-tier Wall guard

The pipeline reads a **second input class** — compiled bytecode from `target/classes`, not just
processor-emitted metadata — to enforce ADR-024 validation predicate 4 in
`exeris:verify-capabilities`.

Two constraints before touching `CapTierWall`:

1. The scan uses the JDK-standard Class-File API (JEP 484) and **must stay dependency-free**.
2. A constant-pool-only walk is **unsound** — it misses types that appear only in descriptors or in
   generic `Signature` attributes. The five-source extraction union is load-bearing; do not
   "simplify" it.

## Review Output
1. **Contract(s) in scope** (which of ADR-055 / 058 / 060 the change touches)
2. **Findings** (concrete violations, with `file:line`)
3. **Verdict** (`APPROVE` / `CONDITIONAL` / `REJECT`)
4. **Required actions** (precise and minimal)

## Non-Negotiable Rules
- Never add an import to emitted code without naming the consumer-build requirement it creates.
- Never assume slf4j, a mocking framework, or any non-JDK logging API is available downstream.
- Never reduce the `CapTierWall` extraction union to a constant-pool walk.
