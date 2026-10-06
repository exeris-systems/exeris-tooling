# Policy: Annotation Processor is Build-Time Only

`exeris-processor` executes exclusively at `javac` compile time. It must never leak into runtime execution or depend on runtime frameworks.

## Hard Rules

1. **Permitted dependencies only.**
   `exeris-processor` may depend strictly on:
   - `javax.lang.model` (standard JDK annotation processing model),
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
- Skill `exeris-tooling-processor-discipline-review`.
