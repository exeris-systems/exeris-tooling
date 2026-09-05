# Policy: Scoped Bans

Consolidated hard bans across `exeris-tooling` modules.

## In `exeris-processor` (Build-Time Path)

- **Banned:** Jackson runtime serialization choices that couple the processor to a specific runtime JSON implementation (`DomainMetadata` write-out is the sole, isolated exception).
- **Banned:** Spring Framework, runtime IoC containers, runtime DI frameworks, or servlet APIs.
- **Banned:** Loading classes from the consumer project's compilation classpath via reflection. Only `javax.lang.model` AST and annotations are valid inputs.
- **Banned:** Silent compilation failures. Diagnostic errors must always be emitted through `Messager` using `e.toString()`.

## In `exeris-codegen-java` and `exeris-codegen-ts` (Emitters)

- **Banned:** Direct reading of `Element`, `TypeMirror`, or `javax.lang.model`. All inputs must come through `DomainMetadata` and `MetadataLoader`.
- **Banned:** Timestamps (`Instant.now()`, `currentTimeMillis`), random values (`UUID.randomUUID()`), or platform-locale-dependent formatting in emitted text.
- **Banned:** Multi-backend generators. No Spring, Quarkus, Micronaut, or Vanilla emitters.
- **Banned:** Copy-pasting boilerplate across `Kernel*Generator` implementations. Common headers, imports, and scaffolding must be extracted to `KernelScaffold`.

## In `exeris-codegen-ts` Specifically

- **Banned:** Maven module wrappers that pull TypeScript/npm builds into the Maven reactor. Toolchain and release cadences are intentionally independent.
