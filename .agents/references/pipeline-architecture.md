# Reference: Pipeline Architecture and Emission Strategy

`exeris-tooling` implements the Entity-First code generation pipeline for the Exeris platform.

## Architecture

The pipeline consists of three core stages:

1. **Extraction (`exeris-processor`):**
   - JSR-269 annotation processor executing during `javac`.
   - Traverses Java source elements annotated with `@ExerisDomain`, `@Action`, `@Field`, `@RouteAccess`, etc.
   - Validates entity definitions against SDK contracts.
   - Serializes the validated model into intermediate `DomainMetadata` JSON.
2. **Loading & Intermediate Model (`exeris-codegen-core`):**
   - Provides `DomainMetadata` definitions and `MetadataLoader`.
   - Houses common generation abstractions, verification mojo logic, and the `CapTierWall` verifier.
3. **Emission (`exeris-codegen-java` & `exeris-codegen-ts`):**
   - Java generators consume `DomainMetadata` and emit kernel-target Java code.
   - TypeScript generators consume `DomainMetadata` and emit Angular components, services, stores, and routes.
   - Maven plugin (`exeris-codegen-maven-plugin`) coordinates generation and capability verification within consumer Maven builds.

## Emission Strategy (ADR-015)

- **Java code emission:** Emitted using **JavaPoet** for structural safety, type importing, and formatting. String concatenation via `StringBuilder` is actively minimized.
- **Text artifact emission:** Structured text files (Flyway SQL migrations, YAML OpenAPI specifications) are emitted using Java **text blocks**.
- **Shared scaffolding:** Emitters reuse common scaffold definitions (`KernelScaffold`) for class headers, package declarations, and Javadoc headers to avoid boilerplate duplication.

## Composition Root Seam (ADR-070)

- `RuntimeComponents` provides the integration seam where consumer business logic interfaces with generated infrastructure.
- One `protected create*` factory method per emitted component, wired through `Application#components(TransactionalExecutor)` and route configuration.
- Any new component type emitted by tooling must update this composition root seam in the same change.
