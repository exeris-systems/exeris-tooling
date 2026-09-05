---
title: "Contributing to Exeris Tooling"
type: reference
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-09-05
---

# Contributing to Exeris Tooling

This document describes how to build, test, and contribute to `exeris-tooling`, the build-time
code-generation pipeline of the Exeris platform.

## Build & test

```bash
# Full reactor build and verification
mvn clean install

# Compile gate: verifies emitted code compiles against kernel SPI via JavaCompiler
mvn -pl exeris-e2e-tests -am test -Dtest=KernelCodegenCompileTest -Dsurefire.failIfNoSpecifiedTests=false

# E2E snapshot suite: asserts exact emitted code structures
mvn -pl exeris-e2e-tests -am test -Dtest=KernelCodegenE2ETest -Dsurefire.failIfNoSpecifiedTests=false

# Standalone TypeScript emitter build and test suite
cd exeris-codegen-ts && npm install && npm test
```

**JDK 25 LTS is the baseline** across the reactor (`maven.compiler.release=25`, per kernel ADR-066 / SDK ADR-069).
Maven 3.9+ for Java modules; Node 18+ for `exeris-codegen-ts`.

## Architectural invariants

`exeris-tooling` is published under **Apache-2.0**. Non-negotiable architectural principles maintain
the pipeline's integrity:

1. **Single target: Exeris kernel:** Spring, Quarkus, Micronaut, and Vanilla generators were removed
   in 0.1.0. Spring hosting belongs downstream in `exeris-spring-runtime`.
2. **Build-time only processor:** `exeris-processor` depends strictly on `javax.lang.model`,
   `exeris-sdk-source-model`, and standard JDK. Zero runtime dependencies.
3. **DomainMetadata is the sole contract:** Generators consume JSON via `MetadataLoader`; they never
   inspect compiler AST elements directly.
4. **Codegen determinism:** Identical input produces byte-identical output across runs and locales.
   No timestamps, random UUIDs, or uncontrolled iteration orders in emitted text.
5. **Committed generated code:** Emitted code lands in `src/main/generated/` and supports the detachment
   lifecycle (`exeris:detach`). Never assume "always regenerate".
6. **Consumer-build contracts:** Emitted code logs through `System.Logger` with `{0}` placeholders
   (ADR-060); emitted tests in `src/test/generated/java` import only JUnit 5 + AssertJ (ADR-058);
   `CapTierWall` uses dependency-free Class-File API (ADR-055).

## Contributor terms & DCO sign-off

External contributions require a Developer Certificate of Origin sign-off via the `Signed-off-by:`
trailer (`git commit -s`), per [ADR-085 §K](docs/adr/ADR-085.link.md).

The sign-off certifies that you have the right to submit the contribution under the Apache-2.0 licence
published in `LICENSE`. Organisation members are exempt from the trailer, but remain accountable for
every merged change.

## AI provenance

Exeris is developed with AI assistance and states the terms openly per
[`ai-provenance.md`](https://github.com/exeris-systems/exeris-docs/blob/main/standards/ai-provenance.md):

- **Provenance is kept:** An AI-assisted commit carries `Co-authored-by: <model name> <noreply@...>`
  (or equivalent). Stripping it is a defect; adding it where no AI was involved is prohibited.
- **A named human is accountable for every line:** The PR author must be able to explain and defend any part
  of the change in review. "The agent produced it" is never an acceptable explanation.
- **Agents do not open pull requests, file issues or post comments unattended:** Automated review comments
  are allowed; automated contributions without human review are not.
- **Verification is stated, not assumed:** PR descriptions must name the exact test commands executed.
- **Reject hollow tests:** AI-generated tests that assert nothing observable or only restate mocks are rejected.

## Conventions

Development standards are binding per [ADR-085](docs/adr/ADR-085.link.md) and hosted in
[`exeris-docs/standards/`](https://github.com/exeris-systems/exeris-docs/tree/main/standards):

- [`commit-conventions.md`](https://github.com/exeris-systems/exeris-docs/blob/main/standards/commit-conventions.md) — Conventional Commits with Netty-form body (`Motivation:`, `Modification:`, `Result:`).
- [`pr-conventions.md`](https://github.com/exeris-systems/exeris-docs/blob/main/standards/pr-conventions.md) — structured PR bodies and scope classification.
- [`javadoc-conventions.md`](https://github.com/exeris-systems/exeris-docs/blob/main/standards/javadoc-conventions.md) — Oracle doc-comment standards.
- [`docs-style-guide.md`](https://github.com/exeris-systems/exeris-docs/blob/main/standards/docs-style-guide.md) — validated frontmatter and naming conventions.
- [`agents-md-schema.md`](https://github.com/exeris-systems/exeris-docs/blob/main/standards/agents-md-schema.md) — [`AGENTS.md`](AGENTS.md) as the canonical entry point and [`.agents/`](.agents) as the semantic source.
- Language: English everywhere (code, identifiers, comments, commit messages, PR titles/bodies, documentation).
