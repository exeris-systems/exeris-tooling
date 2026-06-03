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

If you see SQL diffs in the migration **body**, please open an issue — that is a regression, not the expected migration shape.

### Flyway migration filenames are now deterministic (one-time rename)

The Flyway migration **filename** changed from a wall-clock version
(`V<System.currentTimeMillis()>__create_<table>.sql`) to a deterministic one
(`V<tier><fqn-hash>__create_<table>.sql`) so regeneration is byte-identical
(hard-constraint #3). Tenant-scoped tables tier above unscoped ones, so the
`tenants` table is always created before tables that `REFERENCES tenants(id)`.

**One-time action for apps that committed generated migrations:** the first
regen against this train writes the new deterministic filename and leaves the
old timestamped file orphaned. Delete the old `V<digits>__create_*.sql` files in
the same commit — otherwise Flyway sees two migrations for the same table. The
SQL *content* is unchanged, so this is a rename, not a schema change.

---

## What does NOT change

| Surface | Status |
|---|---|
| `@ExerisDomain`, `@DomainEvent`, `@Saga`, etc. annotation contracts | Unchanged |
| `DomainMetadata` AST (input to generators) | Unchanged |
| Annotation processor (`exeris-processor`) output | Unchanged |
| Runtime SPIs (`KernelBootstrap`, `EventStore`, `SagaEngine`, `Http3Router`) | Unchanged |
| Generated SQL migration **body** | Byte-equivalent (the **filename** is now deterministic — see above) |
| Generated OpenAPI YAML | Byte-equivalent (`KernelOpenApiGenerator` already used Swagger model objects + Jackson YAMLMapper — no migration needed) |
| Generated TypeScript / Angular (`exeris-codegen-ts`) | Out of scope for ADR-015 |

---

## Recommended migration step

Run regen once against the new tooling. Expect a single large diff in your generated-source tree that touches every `Kernel*` artifact. Skim it for the buckets above. After landing the regen commit, future regens are stable again — JavaPoet's output is deterministic.

If your repository commits generated sources, add the regen as its own commit so the formatting diff stays cleanly separable from feature work.

---

## ADR-034 — `KernelWebClient` facade rename

> **Out-of-band:** this is a runtime FQN change in the kernel side (broader scope than ADR-015's output-formatting story). It is noted here because the lockstep update on the tooling side lives in this repo (`KernelClientGenerator`'s `WEB_CLIENT` / `WEB_CLIENT_EXCEPTION` constants — see [ADR-034 link stub](adr/ADR-034.link.md)).

Effective at the ADR-034 kernel landing (kernel-side PRs A and B), the tier-neutral HTTP client facade moves:

| Surface | Before | After |
|---|---|---|
| Class | `ExerisWebClient` | `KernelWebClient` |
| Package | `eu.exeris.kernel.transport.http3.client` | `eu.exeris.kernel.core.http.client` |
| Nested exception | `ExerisWebClient.WebClientException` | `KernelWebClient.WebClientException` |

The facade is **tier-neutral by design** — the name no longer encodes a Community / Enterprise (or H1 / H2 / H3 transport) decision, which is now an internal Kernel-runtime detail. ADR-034 supersedes ADR-026.

### Who is affected

- **Manually-written HTTP clients in downstream user code** that imported the old FQN: update the import to `eu.exeris.kernel.core.http.client.KernelWebClient` (and `KernelWebClient.WebClientException` for the nested exception). No method signatures change.
- **Generated `*Client.java`** under `src/main/generated/java/…`: `KernelClientGenerator` is **parked** in this `exeris-tooling` train — no released artifact emits client code yet. When the generator unparks (blocked on a higher-level convenience SPI OR an `HttpEntityCodec<T>` collaborator — see `KernelGeneratorStrategy` parked-section Javadoc), the emitted FQN is already correct for ADR-034.
- **Bypass callers** that drive `HttpClientEngine.send(HttpRequest) → HttpResponse` directly: no migration needed (the SPI surface is unchanged; ADR-034 §Alternatives A documents why the typed API does not live on the engine SPI itself).

### What does NOT change in this train

| Surface | Status |
|---|---|
| `HttpClientEngine` SPI (`send(HttpRequest) → HttpResponse`) | Unchanged |
| `HttpRequest` / `HttpResponse` records (incl. `LoanedBuffer` body) | Unchanged |
| Generator registration (`KernelClientGenerator` still parked) | Unchanged |
| Generated SQL / OpenAPI / Angular / TypeScript | Unaffected |

---

## `exeris-codegen-ts` — single-target collapse (kernel-only)

The TypeScript emitter dropped the residual multi-backend abstraction so it
matches the Java side's single-target story (hard-constraint #1: Exeris kernel
only). This narrows the package's published surface (`exeris-codegen-ts`
re-exports `./core/backend-strategy` via `src/core/index.ts`).

**Breaking (pre-1.0, semver-permitted at 0.x):**

| Removed / narrowed export | Change |
|---|---|
| `BackendType` | Narrowed from `'KERNEL' \| 'SPRING' \| 'QUARKUS' \| 'MICRONAUT' \| 'VANILLA'` to `'KERNEL'` |
| `SpringStrategy` | Class removed |
| `QuarkusStrategy` | Class removed |
| `MicronautStrategy` | Class removed |
| `VanillaStrategy` | Class removed |

**Who is affected**

- **Config files / scripts** that set `"backend": "SPRING"` (or another
  non-kernel value) in `exeris-codegen.json`, or pass `--backend SPRING` on the
  CLI: the zod config parse now **throws** on the next run
  (`Invalid enum value. Expected 'KERNEL', received 'SPRING'`) rather than
  silently ignoring it. Remove the `backend` key (the default is `'KERNEL'`) or
  set it explicitly to `'KERNEL'`.
- **Code importing the removed strategy classes / non-kernel `BackendType`
  members** from `@exeris/codegen-ts`: drop the import — only `KernelStrategy`
  remains, and it is auto-registered.

**What does NOT change**

The `BackendStrategy` interface, the strategy registry, and the
`backend` / `supportedBackends` plumbing remain (now single-valued); generated
Angular/TypeScript output is byte-identical for the kernel target.

---

## Reference

- [ADR-015 — Codegen emission strategy](adr/ADR-015-codegen-emission-strategy.md)
- ADR-015 Amendment 1 — switch to Palantir's JavaPoet fork (same document)
- [ADR-034 link stub — `KernelWebClient` facade rename](adr/ADR-034.link.md) (authoritative copy kernel-side)
