# Policy: Annotation Processor is Build-Time Only

`exeris-processor` executes exclusively at `javac` compile time. It must never leak into runtime execution or depend on runtime frameworks.

## Hard Rules

1. **Permitted dependencies only.**
   `exeris-processor` may depend strictly on:
   - `javax.lang.model` (standard JDK annotation processing model),
   - the JDK compiler tree API `com.sun.source` (`Trees`, exported by `jdk.compiler`), read only for the source text behind the `SourceDigest`,
   - `eu.exeris:exeris-sdk-source-model` (SDK source model records),
   - `eu.exeris:exeris-diagnostics` (the stable diagnostic identifiers, ADR-095). It uses nothing beyond `java.lang`, has no dependencies of its own and registers no service, so it can never put a runtime type or a second processor on the `javac` path,
   - Standard Java runtime library.
2. **Zero runtime libraries.**
   - No Jackson on the processor classpath for serialization choices that leak runtime types (`DomainMetadata` write-out is the sole, well-scoped exception).
   - No Spring, IoC containers, or runtime DI libraries.
   - No Exeris kernel runtime dependencies (`exeris-kernel-core`).
3. **No loading classes from consumer classpath.**
   The processor must never attempt to load or reflect upon classes from the user's project classpath. Only the annotation surface and `javax.lang.model` `Element`/`TypeMirror` are valid inputs.
4. **Actionable diagnostics.**
   Diagnostic messages land in actual `javac` compiler output:
   - Use `e.toString()`, never `e.getMessage()` (which can be `null` for JDK exceptions).
   - Per-entity diagnostic chatter must be gated behind the `-Aexeris.verbose` opt-in flag. Default build must remain quiet.
   - Every diagnostic carries a stable identifier from `eu.exeris.tooling.diagnostics.DiagnosticId`, passed to the `note` / `warning` / `error` / `reportProcessingFailure` helpers. Never call `Messager.printMessage` outside the single printing method. A new diagnostic takes a new identifier and a row in `docs/diagnostics.md`; an existing identifier is never renumbered or reused (ADR-095).
5. **Self-registration.**
   Self-registration via `@AutoService(Processor.class)` is canonical and must be preserved.

## Verification

- `mvn -pl exeris-processor test` exercises processor isolation.
- `ProcessorArchitectureTest` holds rules 1, 3 and 4 on the processor's bytecode:
  `depends_only_on_the_build_time_set` (rule 1's set, with Jackson and `@AutoService` from rules 2 and 5),
  `never_loads_classes` (no `Class.forName`, `ClassLoader.loadClass` or `Thread.getContextClassLoader`),
  `never_calls_throwable_get_message`, and `prints_only_from_the_printing_method`
  (`Messager.printMessage` is called from `ExerisDomainProcessor.print` alone).
- `DiagnosticsArchitectureTest` (`depends_only_on_the_jdk`): `exeris-diagnostics` depends on `java..`
  alone.
- The `ban-host-and-kernel-runtime` enforcer execution bans `exeris-kernel-core`, the community driver
  and host frameworks from the processor's resolved dependency graph (see `kernel-target-only.md`).
- Skill `exeris-tooling-processor-discipline-review`.
