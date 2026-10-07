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

## Verification

- `exeris-processor`: `ProcessorArchitectureTest` holds the class-loading ban (`never_loads_classes`), the
  host-framework ban (`no_host_framework`) and the closed dependency set
  (`depends_only_on_the_build_time_set`), which admits Jackson only under `com.fasterxml.jackson`.
- `exeris-codegen-java` and `exeris-codegen-core`: `CodegenJavaArchitectureTest` and
  `CodegenCoreArchitectureTest` hold the `javax.lang.model` ban (`reads_no_language_model`), the
  timestamp and random-value ban (`reads_no_clock_and_no_randomness`) and the host-framework ban
  (`no_host_framework`).
- The `ban-host-and-kernel-runtime` enforcer execution holds the host-framework ban on the resolved
  dependency graph of all three modules.
- Locale formatting, `KernelScaffold` extraction and the `exeris-codegen-ts` bans are review-held:
  `exeris-tooling-codegen-determinism-review`, `exeris-tooling-strict-audit-review`.
