# Policy: DomainMetadata is the Sole Contract

The intermediate JSON model `DomainMetadata` is the single contract boundary separating annotation processing from code emission.

## Hard Rules

1. **Strict separation of concerns.**
   - `exeris-processor` reads `javax.lang.model` and writes `DomainMetadata` JSON.
   - `exeris-codegen-java` and `exeris-codegen-ts` read `DomainMetadata` JSON via `MetadataLoader` and emit code.
2. **No Element / TypeMirror leakage.**
   Generators MUST NOT import or read `javax.lang.model.element.Element`, `TypeMirror`, or any compiler-internal AST types directly.
3. **Polyglot decoupling.**
   By keeping `DomainMetadata` as the sole contract, Java and TypeScript/Angular emitters remain completely independent, operating in different toolchains while executing against a single source-model specification.
4. **No shadowing SDK records.**
   Always depend on and deserialize into the canonical records provided by `eu.exeris:exeris-sdk-source-model`. Never copy or shadow AST records inside tooling.

## Verification

- Maven module dependency boundaries (`exeris-codegen-*` do not depend on compiler APIs).
- Processor and generator unit tests verifying clean JSON round-trip through `MetadataLoader`.
