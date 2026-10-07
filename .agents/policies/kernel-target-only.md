# Policy: Single Target — Exeris Kernel Only

`exeris-tooling` has exactly one target backend: the **Exeris kernel** (`exeris-kernel-spi`, `exeris-kernel-core`).

## Hard Rules

1. **No multi-backend abstractions.**
   Spring, Quarkus, Micronaut, and Vanilla backend generators were deliberately removed in the 0.1.0 milestone. Never reintroduce a multi-backend abstraction or generator family in this repository.
2. **Spring hosting belongs downstream.**
   If a consumer requires Spring hosting, that is the sole responsibility of `exeris-spring-runtime`, which consumes kernel-target output and wraps it. Never create a `SpringHandlerGenerator` or Spring-specific emitter in `exeris-tooling`.
3. **SPI alignment.**
   When a kernel SPI change lands in `exeris-kernel`, the matching generator update belongs here. The reverse is not symmetric — generators never dictate SPI design.

## Verification

- `KernelCodegenCompileTest` in `exeris-e2e-tests` compiles generated handler, service, and repository code against the Exeris kernel SPI.
- Architecture review via `exeris-tooling-kernel-target-discipline` skill.
- `no_host_framework` in `ProcessorArchitectureTest`, `CodegenCoreArchitectureTest`,
  `CodegenJavaArchitectureTest`, `MavenPluginArchitectureTest` and `DiagnosticsArchitectureTest`: no
  main class depends on `org.springframework..`, `io.quarkus..`, `io.micronaut..`, `jakarta.inject..` or
  `javax.inject..`.
- The `ban-host-and-kernel-runtime` enforcer execution (`exeris-tooling-parent`, enabled in
  `exeris-processor`, `exeris-codegen-core` and `exeris-codegen-java`) fails the build when
  `org.springframework*`, `io.quarkus*`, `io.micronaut*`, `eu.exeris:exeris-kernel-core` or
  `eu.exeris:exeris-kernel-community*` appears anywhere on the resolved dependency graph, transitive
  and test scope included. `exeris-kernel-spi` is the target and is not banned.
