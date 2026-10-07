# Reference: Build & Testing Model

This document summarizes the build commands, compiler requirements, and test suites for `exeris-tooling`.

## Primary Build Commands

```bash
# Full Maven reactor build and test execution. Every dependency, the kernel and the SDK
# included, resolves from Maven Central; no settings file is needed.
mvn clean install

# Targeted compile gate: verifies generated code compiles against current kernel SPI
mvn -pl exeris-e2e-tests -am test -Dtest=KernelCodegenCompileTest -Dsurefire.failIfNoSpecifiedTests=false

# Targeted E2E substring snapshot suite: verifies exact emission shapes
mvn -pl exeris-e2e-tests -am test -Dtest=KernelCodegenE2ETest -Dsurefire.failIfNoSpecifiedTests=false

# Architecture rules (ArchUnit) across the tooling modules
mvn -pl exeris-processor,exeris-codegen-core,exeris-codegen-java,exeris-codegen-maven-plugin,exeris-diagnostics -am test -Dtest='*ArchitectureTest' -Dsurefire.failIfNoSpecifiedTests=false

# TypeScript codegen build and tests (standalone npm project)
cd exeris-codegen-ts && npm install && npm test
```

## Toolchain & Baselines

- **JDK Baseline:** JDK 25 LTS or newer is required across the Maven reactor (`maven.compiler.release=25`; kernel ADR-066 / SDK ADR-069).
- **Maven Baseline:** Maven 3.9+.
- **Node Baseline:** Node 18+ for `exeris-codegen-ts`.

## Processor Flags

1. `-Aexeris.verbose` (added 0.2.0):
   Controls per-entity processor diagnostic chatter. Recommended during local development and debugging; default-quiet in CI builds.
2. `-Aexeris.strict` (added 0.5.x, T11):
   Enables completeness audit in `javac`. Warns when an annotation attribute or whole annotation is set in user domain code but no generator consumes it:
   - **extracted-but-unconsumed:** Checks against conservative denylists (`INERT_ATTRIBUTES` and `INERT_ANNOTATIONS`) in `ExerisDomainProcessor`.
   - **never-read:** Allowlist check (`EXTRACTED_ANNOTATIONS`). Reports any annotation present on a visited domain element that is not extracted.

## Testing Layers

| Layer | Module / Test Class | Verification Target |
|:---|:---|:---|
| Processor Unit | `exeris-processor/src/test/` | Element parsing, AST record extraction, diagnostic reporting |
| Architecture | `*ArchitectureTest` in the processor, codegen-core, codegen-java, Maven plugin and diagnostics modules | ArchUnit rules over each module's main bytecode: dependency sets, banned calls, no host framework |
| Dependency ban | `ban-host-and-kernel-runtime` enforcer execution | No host framework or kernel runtime on the processor's or the generators' resolved graph |
| Codegen Compile Gate | `KernelCodegenCompileTest` | In-memory `JavaCompiler` compiles emitted code against kernel SPI |
| Codegen Snapshot E2E | `KernelCodegenE2ETest` | Substring and structural assertions on emitted Java artefacts |
| Generated Tests E2E | `GeneratedTestsE2ETest` | Executes emitted JUnit 5 + AssertJ tests against emitted services |
| TypeScript Suite | `exeris-codegen-ts/` | Vitest / Jest assertions on Angular services, components, and sagas |
