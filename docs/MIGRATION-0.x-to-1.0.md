# Migration: 0.x → 1.0

This document describes one-time differences downstream consumers will see when they regenerate against the `exeris-tooling` 0.x lineage that includes the [ADR-015](adr/ADR-015-codegen-emission-strategy.md) emission-strategy migration. Skim this once per consumer; nothing here is recurring.

> **Scope:** generated code shape only. Domain metadata, annotation processor input, and runtime SPI surfaces are unchanged. This is purely an output-formatting note.

---

## Why the diff exists

The Kernel generator suite (11 `Kernel*Generator` classes in `exeris-codegen-java`) used to emit all output by hand via `StringBuilder.append(...)`. ADR-015 migrated:

- the 9 Java-emitting generators to **JavaPoet** (Palantir's fork, `com.palantir.javapoet:javapoet:0.15.0`)
- `KernelFlywayGenerator` to **Java text blocks + `String.join`** for SQL emission

JavaPoet is a deterministic pretty-printer; the prior emission was ad-hoc. Regenerating the same domain entity against the new tooling produces semantically identical Java that is **formatted differently**. The compile-gate (`KernelCodegenCompileTest`) and the JMH/runtime contracts are unchanged.

---

## What changes in regenerated Java sources

The first regen against ADR-015-migrated tooling will produce a one-time large diff. Every delta below falls into one of these buckets — none of them are semantic regressions.

### Imports
- **Sorted alphabetically.** Square's JavaPoet (and Palantir's fork) emit imports in alphabetical order. The prior emission order matched the order each `import` line was hand-written.
- **Dead imports dropped.** JavaPoet only emits an import when a `$T` substitution actually references that type. Imports that the StringBuilder code wrote but never used (e.g., `SagaDefinition`, `StepAction`, `Function` in the saga generator) disappear from the output.

### Banner / section comments
- **Section banners (`// ═══...`) dropped.** JavaPoet has no comment passthrough at the field-/method-separator level. Sections that used to be visually divided are now identifiable by member ordering only.

### Whitespace
- **Blank lines between fields.** JavaPoet inserts a blank line between consecutive fields. Hand-rolled output sometimes packed them.
- **Blank line before nested types.** JavaPoet emits all nested types after methods, separated by a blank line.
- **One-liner getter expansion.** Hand-rolled `public X getX() { return x; }` becomes
  ```java
  public X getX() {
      return x;
  }
  ```
  This affects, in particular, the `CompositionRoot.java` getters (≈18 of them).

### Records
- **Record components on a wrapped line.** JavaPoet emits record components on a wrapped multi-component line:
  ```java
  public record State(UUID sagaId, UUID entityId, UUID tenantId, String currentStep,
          Instant startedAt, Map<String, Object> stepData) { ... }
  ```
  Hand-rolled output put each component on its own line.

### Nested-type position
- **Nested types emitted after methods.** Hand-rolled output sometimes placed the nested `record State` before methods. JavaPoet emits all nested types after methods.

### File ordering
- **Generated file count is unchanged.** Each generator still emits the same set of files (`OrderHandler.java`, `OrderRepository.java`, etc.).

---

## What changes in regenerated SQL

`KernelFlywayGenerator` was migrated to text blocks **with byte-equivalence preservation as an explicit requirement**. The golden-snapshot test (`KernelFlywayGeneratorTest`) pins this — for any of the eight metadata-flag combinations the tests cover, the SQL output is bit-for-bit identical to what 0.x emitted.

If you see SQL diffs, please open an issue — that is a regression, not the expected migration shape.

---

## What does NOT change

| Surface | Status |
|---|---|
| `@ExerisDomain`, `@DomainEvent`, `@Saga`, etc. annotation contracts | Unchanged |
| `DomainMetadata` AST (input to generators) | Unchanged |
| Annotation processor (`exeris-processor`) output | Unchanged |
| Runtime SPIs (`KernelBootstrap`, `EventStore`, `SagaEngine`, `Http3Router`) | Unchanged |
| Generated SQL migrations | Byte-equivalent |
| Generated OpenAPI YAML | Byte-equivalent (`KernelOpenApiGenerator` already used Swagger model objects + Jackson YAMLMapper — no migration needed) |
| Generated TypeScript / Angular (`exeris-codegen-ts`) | Out of scope for ADR-015 |

---

## Recommended migration step

Run regen once against the new tooling. Expect a single large diff in your generated-source tree that touches every `Kernel*` artifact. Skim it for the buckets above. After landing the regen commit, future regens are stable again — JavaPoet's output is deterministic.

If your repository commits generated sources, add the regen as its own commit so the formatting diff stays cleanly separable from feature work.

---

## Reference

- [ADR-015 — Codegen emission strategy](adr/ADR-015-codegen-emission-strategy.md)
- ADR-015 Amendment 1 — switch to Palantir's JavaPoet fork (same document)
