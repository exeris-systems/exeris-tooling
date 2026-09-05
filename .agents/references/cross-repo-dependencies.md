# Reference: Cross-Repo Dependencies

`exeris-tooling` sits in the middle of the Exeris build-time pipeline.

## Dependency Graph

```text
exeris-sdk (upstream: annotations & AST records)
     │
     ▼
exeris-tooling (processor & generators) ──► exeris-kernel (target: SPI & Core)
     │
     ▼
downstream consumer applications (budgetHQ, user microservices)
```

## Upstream: `exeris-sdk`

- **Reads from:** `eu.exeris:exeris-sdk-annotations` and `eu.exeris:exeris-sdk-source-model`.
- **Contract:** The SDK's `@ExerisDomain` domain class is the single source of truth. Tooling consumes the AST records published by `exeris-sdk-source-model`.
- **Local installation:** During local cross-repo development, `mvn install` in `exeris-sdk` provides the required SNAPSHOT artifacts.

## Target: `exeris-kernel`

- **Targets:** `eu.exeris:exeris-kernel-spi` and `eu.exeris:exeris-kernel-core`.
- **Contract:** Emitted Java code imports kernel SPI interfaces and types (`TransactionCoordinator`, `EntityRepository`, `KernelContext`, `System.Logger`). Emitted code must compile and pass tests against the active kernel release.
- **Asymmetry:** When kernel SPI evolves, generators are updated to match. Generators never drive kernel SPI shape.

## Downstream Consumers

- **Read by:** Any downstream user application (e.g. `budgetHQ`, customer applications) that runs `exeris-processor` at compile time and executes `exeris-codegen-maven-plugin`.
- **Emitted artifacts:** Emitted handlers, queries, commands, sagas, OpenAPI specs, Flyway migrations, and Angular/TypeScript services are committed into the downstream codebase (`src/main/generated/`).
