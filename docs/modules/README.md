---
title: Module pages
type: reference
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-11-03
---

# Module pages

One page per tooling module, numbered in dependency order. Each page states the module's contract, what runs on its hot path, how it fails, and which ADRs own it. Rules live in their own homes: the policies in [`.agents/policies/`](../../.agents/policies), the decisions in [`docs/adr/`](../adr), the identifiers in [diagnostics.md](../diagnostics.md) and the emitted paths in [generators.md](../generators.md).

| Page | Module | In one line |
|---|---|---|
| [01-diagnostics.md](01-diagnostics.md) | `exeris-diagnostics` | The registry of every `EXT-` diagnostic identifier; no dependencies. |
| [02-processor.md](02-processor.md) | `exeris-processor` | The annotation processor: source to `exeris-metadata/*.json`, and the declaration refusals. |
| [03-codegen-core.md](03-codegen-core.md) | `exeris-codegen-core` | Generator interface and registry, output-tree ownership, subsystem, driver and route-requirement derivations, capability graph and Wall. |
| [04-codegen-java.md](04-codegen-java.md) | `exeris-codegen-java` | `CodegenPipeline` and the `Kernel*Generator`s: the kernel application, its tests, its OpenAPI and migrations. |
| [05-codegen-ts.md](05-codegen-ts.md) | `@exeris/codegen-ts` | The Angular 22 generator, `exeris-gen`; an npm package outside the Maven reactor. |
| [06-codegen-maven-plugin.md](06-codegen-maven-plugin.md) | `exeris-codegen-maven-plugin` | `exeris:generate`, `verify-capabilities`, `verify-runtime` and `detach`. |
| [07-codegen-cli.md](07-codegen-cli.md) | `exeris-codegen-cli` | The Java generator as one shaded jar, for builds outside Maven. |
| [08-initializr.md](08-initializr.md) | `exeris-initializr` | Writes a new project on the starter, as a directory or a ZIP. |
| [09-app-starter.md](09-app-starter.md) | `exeris-app-bom`, `exeris-app-parent`, `exeris-app-starter` | The tested version triple, the application build and the dependency set generated code needs. |
| [10-e2e-tests.md](10-e2e-tests.md) | `exeris-e2e-tests`, `exeris-app-starter-it`, `exeris-coverage-aggregate` | The compile, boot, contract and conformance gates; not published. |

`exeris-tooling-bom` and `exeris-tooling-parent` carry the reactor's own dependency pins and build configuration, and no code, so they have no module page. An application builds on the [application POMs](09-app-starter.md) instead.

Of these modules, only the application POMs shape anything a deployed application carries, and they carry no code. Every other module runs at build time and is absent from a generated application's runtime classpath.

## Where this does not apply

This index covers the modules of `exeris-tooling`. The SDK records the processor writes, and the kernel the generated code runs on, are documented in [exeris-sdk](https://github.com/exeris-systems/exeris-sdk/blob/main/docs/modules/README.md) and [exeris-kernel](https://github.com/exeris-systems/exeris-kernel/tree/main/docs/modules).
