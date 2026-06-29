# ADR-047: Consume the `@View` leaf field facet end-to-end — subsuming and removing `@UI`

| Attribute       | Value                                                                                                  |
|:----------------|:-------------------------------------------------------------------------------------------------------|
| **Status**      | **ACCEPTED**. Number **047** reserved in `exeris-docs/adr-index.md`; file renames to `ADR-047-view-leaf-field-facet-and-ui-subsumption.md`. |
| **Deciders**    | Arkadiusz Przychocki                                                                                    |
| **Date**        | 2026-06-28                                                                                              |
| **Scope**       | per-repo (`exeris-tooling`); `tooling/codegen`. Mandates a coordinated `exeris-sdk` record rename (`UIFieldMetadata` → `ViewFieldMetadata`) and the removal of `@UI` — see the Decision. |
| **Owning Repo** | `exeris-tooling` (`exeris-processor` + `exeris-codegen-ts`)                                             |
| **Driven By**   | [RFC-2026-06-28](../rfc/RFC-2026-06-28-presentation-view-emitter-tooling.md) (the `@View` emitter, whose first slice left the leaf facet `null`); [SDK RFC-2026-06-25](https://github.com/exeris-systems/exeris-sdk/blob/main/docs/rfc/RFC-2026-06-25-presentation-front-model.md) (the "`@UI` is subsumed by `@View`, not paralleled" decision) |
| **Compliance**  | [ADR-015](ADR-015-codegen-emission-strategy.md) (emission strategy); hard-constraint #1 (single Exeris-kernel target), #3 (deterministic codegen), strong-default #4 (Java/TS emitter parity); [ADR-042](ADR-042.link.md) (`-io` reader mirror) |

## Context and Problem Statement

[SDK RFC-2026-06-25](https://github.com/exeris-systems/exeris-sdk/blob/main/docs/rfc/RFC-2026-06-25-presentation-front-model.md) (ACCEPTED) resolved that **`@UI` is *subsumed* by `@View`/`ViewMetadata`, then removed — one presentation model, not two**: entity-level `@UI` (list/detail/create/edit selection) becomes a `@View` of the matching `kind`, and **field-level `@UI` render hints become the leaf field facet of the IR — `ComponentNodeMetadata.field`, one record reused DRY (not a parallel one).**

The **SDK already shipped its seed**: the `@View` model carries a field-render record (shipped as `UIFieldMetadata`) on leaf `ComponentNodeMetadata`, and `@UI` carries a successor note. The **`@View` first slice (RFC-2026-06-28, PR #121) deliberately left `ComponentNodeMetadata.field` `null`**. So the one missing piece is the **tooling-side migration: actually populating (processor) and emitting (codegen-ts) that leaf facet** — the transfer the SDK has been waiting on. Until it lands, `@View` cannot carry field-level render detail, so `@UI` cannot be replaced.

Per `exeris-tooling`'s CLAUDE.md this is ADR-triggering: it changes the processor↔generator contract (the processor populates a facet it did not; the emitter consumes a facet it did not) and is the concrete step of retiring a public surface (`@UI`).

**This ADR fixes the stable, enforceable shape of wiring the `@View` leaf field facet end-to-end, such that `@View` becomes the single carrier of field-level render detail — without breaking determinism, parity, or the additive guarantee — and the unification removes `@UI`.**

## 🏁 The Decision

**Wire the `@View` leaf field facet end-to-end — the processor populates `ComponentNodeMetadata.field`, and the codegen-ts `ViewGenerator` renders the field control from it — making `@View` the single carrier of field-level render detail. This unifies the presentation model on `@View` and removes `@UI`.**

The unification was the intent from the start; the following positions are settled, not open:

- **One record, renamed and expanded.** The leaf field facet is `ViewFieldMetadata` — the renamed, expanded successor of the seed's `UIFieldMetadata`. Reused DRY (the single leaf-facet record across processor, emitter, and `-io` reader), **not** a parallel field-render record. The `UI`-named records go away with `@UI`; nothing in the presentation model keeps a `UI` prefix.
- **Entity-driven expansion is the model.** A block bound to an entity resolves the bound entity's field-level render hints into the `@View` leaves (case b below) — this is *the* unification mechanism, not a deferred follow-up. It is not required that every field be an explicit authored leaf.
- **No dual-path.** There is no period where the legacy `@UI`-attached list/form/detail generators run alongside the `@View`-driven path. The `@View` path reaches functional parity (authored-leaf + entity-driven expansion + `FORM`-block control rendering) and replaces `@UI`; `@UI` is then removed. The additive guarantee is about the `@View` IR (a view with no field leaves is byte-identical to slice 1), **not** a parallel legacy runtime kept as a fallback.
- **U2/U3/U5 are `@View`-driven.** Lists (U2), forms (U3), and detail (U5) are built on the `@View` leaf-facet path, not legacy-`@UI` enrichment.

**Concrete obligations:**

1. **Processor populates `ComponentNodeMetadata.field`.** During the class-structure-derived `@View` walk, a leaf node that represents a field carries a `ViewFieldMetadata` built from field-level render hints (`componentType` / `format` / `gridSpan` / `displayOrder` / `placeholder` / `helpText` / `autocomplete` / `select` / …), from two sources: **(a) authored leaf** — a `@Block`/`@Bind` leaf member carrying the render hints, read directly; **(b) entity-driven block** — a `@Bind(ENTITY ref=X)` block expands to the bound entity's fields, each leaf's `field` resolved from that entity field's render hints. Both land together (no dual-path; case b is the model). *Testable:* a `@View` fixture with an authored leaf serializes a non-null `view_*.json` `field` carrying the declared `componentType`/`format`; an entity-driven `@Bind(ENTITY)` block serializes one populated leaf per bound entity field; a fixture with no field leaves serializes `field: null` (byte-identical to slice-1 output).
2. **codegen-ts `ViewGenerator` emits the control from the leaf facet.** When `ComponentNodeMetadata.field` is present, the field control is rendered from it (`componentType` → input/select/textarea/date/toggle/…; `format`/`dataType` → the L1 currency/percent/url formatting; `gridSpan` → layout), reusing the form/list control vocabulary now driven by the IR leaf facet — closing the slice-1 `FORM`-block placeholder. *Testable:* a `view-gen` spec asserts a `componentType: SELECT` leaf emits a `<select>` (not a bare `<input>`), and the emitted control matches the form-gen vocabulary for the same hint.
3. **TS schema grows additively.** The codegen-ts `ViewMetadata`/`ComponentNodeMetadata` Zod schema carries `field : ViewFieldMetadata` (+ a `ViewFieldMetadataSchema` / `ComponentType` union), all optional. *Testable:* a slice-1 `view_*.json` (no `field`) still parses; tsc green.
4. **ADR-042 `-io` parity + the rename, same train.** The SDK `SourceModelReader` reads the leaf `ViewFieldMetadata` in lock-step with the processor; the `UIFieldMetadata` → `ViewFieldMetadata` rename lands across SDK source-model + `-io` + tooling in one coordinated train; round-trip + parity tests; `schemaVersion` re-baseline coordinated. *Testable:* an AST round-trip test over a `@View` with a populated leaf facet; the `-io` PR ships in the same train (no facet extracted without its reader mirror).
5. **Determinism + additive guard (inherited).** Declaration / `displayOrder` ordering; blank annotation strings normalise to `null`; no timestamps. A `@View` with no field leaves is **byte-identical** to slice-1 output. *Testable:* generate-twice byte-equality; the zero-leaf fixture diffs empty against slice 1.
6. **Front-only parity by construction.** The field facet is a front-only concern (like `@View` itself): no Java emitter counterpart, so strong-default #4 is satisfied as the Java∪TS union. *Testable:* no kernel-target Java generator reads `ComponentNodeMetadata.field`.

## Consequences

### ✅ Positive Outcomes

- **[+] `@View` becomes the single carrier of field-level render detail.** The last structural gap between `@View` and `@UI` closes; `@UI` is removed, leaving one presentation model.
- **[+] U2/U3/U5 become `@View`-driven, not legacy-`@UI` enrichment.** Lists, forms, and detail views consume the leaf facet through one IR path instead of the entity-attached `@UI` generators.
- **[+] DRY, one record.** `ViewFieldMetadata` is the single leaf-facet record (renamed + expanded from the seed) across processor, emitter, and `-io` reader — not a parallel field-render record, exactly as RFC-2026-06-25 intended.
- **[+] Additive on the `@View` IR.** A view with no field leaves is byte-identical to slice 1, so the migration extends the IR without reshaping existing `@View` output.

### ⚠️ Trade-offs

- **[-] Per-facet `-io` reader cost (ADR-042).** Extracting the leaf facet re-arms the SDK reader obligation; the `SourceModelReader` mirror is a same-train second-repo cost, and a wrong mirror mis-attributes the facet as user drift in `applyMutation`.
- **[-] Entity-driven expansion is the larger commitment.** Resolving a bound entity's render hints into the `@View` leaves (case b) is the unification mechanism and the bigger build — accepted as the model, and it lands with case (a) rather than after it.
- **[-] Direct cutover, no parallel fallback.** Removing `@UI` without a dual-path means the `@View` path must reach functional parity (authored-leaf + entity-driven + `FORM` controls) before `@UI` is dropped — the parity bar is the gate, in place of a parallel-runtime safety net.

### 📋 What is NOT in scope

- **Entity-driven views as default `@View` compositions** — auto-projecting today's generated list/form/detail as default `@View`s for an entity (distinct from case-b leaf expansion inside an authored `@View`). Sequenced follow-up.
- **The `@UI` removal mechanics** — the SDK marking `@UI`/`@UIGroup`/`@Tab` for removal with `@View` as the replacement + migration note, tooling dropping the `@UI`/`UIMetadata` read path, and `exeris-sdk-ui-kit` absorbing the `ComponentType` classes. Cross-repo, coordinated; this ADR makes it possible by closing the parity gap.
- **G1–G3/G6 binding depth** — owned by RFC-2026-06-28's successor, gated on the SKU corpus; orthogonal to the leaf facet.

## Cross-references

- [RFC-2026-06-28](../rfc/RFC-2026-06-28-presentation-view-emitter-tooling.md) (Presentation View Emitter) — the emitter whose first slice left `ComponentNodeMetadata.field` `null`; this ADR fills it.
- [SDK RFC-2026-06-25](https://github.com/exeris-systems/exeris-sdk/blob/main/docs/rfc/RFC-2026-06-25-presentation-front-model.md) — the "`@UI` is subsumed, not paralleled" decision; the SDK shipped the leaf-facet seed.
- [ADR-044](ADR-044-tooling-sse-stream-emitter-shape.md) — sibling emitter-shape ADR; the template this decision follows.
- [ADR-042](ADR-042.link.md) (bidirectional mutation surface) — the `-io` reader-mirror obligation (obligation 4).
- [ADR-015](ADR-015-codegen-emission-strategy.md) — JavaPoet/text-block/shared-scaffold rules the generators follow.
- The kernel-free menu §U4 (`docs/generation-expansion-kernel-free-menu.md`) — frames this migration as the U-cluster keystone.

## Engineering Protocol

1. **Processor test** — a `@View` fixture with an authored leaf asserts a populated `view_*.json` `field`; an entity-driven `@Bind(ENTITY)` fixture asserts one populated leaf per bound field; a no-leaf fixture asserts `field: null` byte-identical to slice 1 (obligations 1, 5).
2. **codegen-ts spec** — `view-gen` asserts `componentType`→control mapping reuses the form/list vocabulary and that a slice-1 (no-`field`) `view_*.json` still parses + emits unchanged (obligations 2, 3, 5).
3. **ADR-042 `-io` round-trip + parity + rename** — lands in the same train; no leaf facet extracted without its `SourceModelReader` mirror + `schemaVersion` re-baseline; the `UIFieldMetadata` → `ViewFieldMetadata` rename is part of this train (obligation 4).
4. **Migration owner:** `exeris-tooling`. The implementation wave is the full leaf-facet migration in one cut — authored-leaf (case a) **and** entity-driven expansion (case b) **and** the `FORM`-block control rendering — since case b is the model and there is no dual-path; `@UI` removal follows once parity is demonstrated.
