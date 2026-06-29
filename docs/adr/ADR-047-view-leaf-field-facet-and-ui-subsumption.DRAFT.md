# ADR-047: Consume the `@View` leaf field facet end-to-end — the prerequisite to deprecating `@UI`

| Attribute       | Value                                                                                                  |
|:----------------|:-------------------------------------------------------------------------------------------------------|
| **Status**      | **PROPOSED** — DRAFT pending founder go. Number **047** to reserve in `exeris-docs/adr-index.md` before acceptance (next free after ADR-046); file renames to `ADR-047-view-leaf-field-facet-and-ui-subsumption.md` on acceptance. |
| **Deciders**    | Arkadiusz Przychocki                                                                                    |
| **Date**        | 2026-06-28                                                                                              |
| **Scope**       | per-repo (`exeris-tooling`); `tooling/codegen`                                                          |
| **Owning Repo** | `exeris-tooling` (`exeris-processor` + `exeris-codegen-ts`)                                             |
| **Driven By**   | [RFC-2026-06-28](../rfc/RFC-2026-06-28-presentation-view-emitter-tooling.md) (the `@View` emitter, whose first slice left the leaf facet `null`); [SDK RFC-2026-06-25](https://github.com/exeris-systems/exeris-sdk/blob/main/docs/rfc/RFC-2026-06-25-presentation-front-model.md) (the "`@UI` is subsumed by `@View`, not paralleled" decision) |
| **Compliance**  | [ADR-015](ADR-015-codegen-emission-strategy.md) (emission strategy); hard-constraint #1 (single Exeris-kernel target), #3 (deterministic codegen), strong-default #4 (Java/TS emitter parity); [ADR-042](ADR-042.link.md) (`-io` reader mirror) |

## Context and Problem Statement

[SDK RFC-2026-06-25](https://github.com/exeris-systems/exeris-sdk/blob/main/docs/rfc/RFC-2026-06-25-presentation-front-model.md) (ACCEPTED) resolved that **`@UI` is *subsumed* by `@View`/`ViewMetadata`, then deprecated/removed — one presentation model, not two**: entity-level `@UI` (list/detail/create/edit selection) becomes a `@View` of the matching `kind`, and **field-level `@UI` render hints become the leaf field facet of the IR — `ComponentNodeMetadata.field : UIMetadata.UIFieldMetadata`, reused DRY (not a parallel record).**

The **SDK already shipped its half**: the `@View` seed carries `UIFieldMetadata` on leaf `ComponentNodeMetadata`, and `@UI` carries a successor note (no `@Deprecated` yet — it stays the functional path until the emitter can replace it). The **`@View` first slice (RFC-2026-06-28, PR #121) deliberately left `ComponentNodeMetadata.field` `null`**. So the one missing piece is the **tooling-side migration: actually populating (processor) and emitting (codegen-ts) that leaf facet** — the transfer the SDK has been waiting on. Until it lands, `@View` cannot carry field-level render detail, so `@UI` cannot be replaced, so the deprecation can never fire.

Per `exeris-tooling`'s CLAUDE.md this is ADR-triggering: it changes the processor↔generator contract (the processor populates a facet it did not; the emitter consumes a facet it did not) and is the first concrete step of retiring a public surface (`@UI`).

**This ADR answers: what is the stable, enforceable shape of wiring the `@View` leaf field facet end-to-end, such that `@View` becomes the carrier of field-level render detail without breaking determinism, parity, or the additive guarantee — and what is explicitly deferred to the follow-up `@UI`-deprecation arc?**

## 🏁 The Decision

**Wire the `@View` leaf field facet end-to-end — the processor populates `ComponentNodeMetadata.field` from field-level `@UI` render hints, and the codegen-ts `ViewGenerator` renders the field control from it — making `@View` the carrier of field-level render detail and the functional prerequisite to deprecating `@UI`.**

Scoped to the leaf-facet migration. The entity-driven-views-as-`@View` re-expression and the `@UI` deprecation pipeline are sequenced follow-ups (below), **not** this ADR's code.

**Concrete obligations:**

1. **Processor populates `ComponentNodeMetadata.field`.** During the class-structure-derived `@View` walk, a leaf node that represents a field carries a `UIFieldMetadata` built from field-level `@UI` render hints (`componentType` / `format` / `gridSpan` / `displayOrder` / `placeholder` / `helpText` / `autocomplete` / `select` / …), from two sources: **(a) authored leaf** — a `@Block`/`@Bind` leaf member carrying `@UI`, read directly; **(b) entity-driven block** — a `@Bind(ENTITY ref=X)` block expands to the bound entity's fields, each leaf's `field` resolved from that entity field's `@UI`. *Testable:* a `@View` fixture with an authored-`@UI` leaf serializes a non-null `view_*.json` `field` carrying the declared `componentType`/`format`; a fixture with no field leaves serializes `field: null` (byte-identical to slice-1 output).
2. **codegen-ts `ViewGenerator` emits the control from the leaf facet.** When `ComponentNodeMetadata.field` is present, the field control is rendered from it (`componentType` → input/select/textarea/date/toggle/…; `format`/`dataType` → the L1 currency/percent/url formatting; `gridSpan` → layout), reusing the form/list control vocabulary now driven by the IR leaf facet — closing the slice-1 `FORM`-block placeholder. *Testable:* a `view-gen` spec asserts a `componentType: SELECT` leaf emits a `<select>` (not a bare `<input>`), and the emitted control matches the form-gen vocabulary for the same hint.
3. **TS schema grows additively.** The codegen-ts `ViewMetadata`/`ComponentNodeMetadata` Zod schema carries `field : UIFieldMetadata` (+ a `UIFieldMetadataSchema` / `ComponentType` union), all optional. *Testable:* a slice-1 `view_*.json` (no `field`) still parses; tsc green.
4. **ADR-042 `-io` parity, same train.** The SDK `SourceModelReader` reads the leaf `UIFieldMetadata` in lock-step with the processor; round-trip + parity tests; `schemaVersion` re-baseline coordinated. *Testable:* an AST round-trip test over a `@View` with a populated leaf facet; the `-io` PR ships in the same train (no facet extracted without its reader mirror).
5. **Determinism + additive guard (inherited).** Declaration / `displayOrder` ordering; blank annotation strings normalise to `null`; no timestamps. A `@View` with no field leaves is **byte-identical** to slice-1 output, and the legacy `@UI`-attached path keeps working until the deprecation follow-up. *Testable:* generate-twice byte-equality; the zero-leaf fixture diffs empty against slice 1.
6. **Front-only parity by construction.** The field facet is a front-only concern (like `@View` itself): no Java emitter counterpart, so strong-default #4 is satisfied as the Java∪TS union. *Testable:* no kernel-target Java generator reads `ComponentNodeMetadata.field`.

## Consequences

### ✅ Positive Outcomes

- **[+] `@View` becomes the single carrier of field-level render detail.** The last structural gap between `@View` and `@UI` closes, unblocking the deprecation arc.
- **[+] U2/U3/U5 become `@View`-driven, not legacy-`@UI` enrichment.** Lists, forms, and detail views consume the leaf facet through one IR path instead of the entity-attached `@UI` generators.
- **[+] DRY with the SDK record.** The emitter reuses `UIFieldMetadata` rather than introducing a parallel field-render record, exactly as RFC-2026-06-25 intended.
- **[+] Additive and reversible during transition.** A view with no field leaves is byte-identical to slice 1; the legacy path stays the fallback the deprecation cycle requires.

### ⚠️ Trade-offs

- **[-] Per-facet `-io` reader cost (ADR-042).** Extracting the leaf facet re-arms the SDK reader obligation; the `SourceModelReader` mirror is a same-train second-repo cost, and a wrong mirror mis-attributes the facet as user drift in `applyMutation`.
- **[-] Entity-driven expansion (case b) is the bigger call.** Resolving a bound entity's `UIFieldMetadata` into the `@View` leaves (rather than requiring every field be an explicit authored leaf) is the unification mechanism but also the larger design commitment — see Open Questions.
- **[-] Two render paths coexist during transition.** The legacy `@UI`-attached list/form/detail generators run alongside the `@View`-driven path until the deprecation follow-up; this is deliberate (safe, additive) but is duplicated surface until cutover.

### 📋 What is NOT in scope

- **Entity-driven views as `@View` compositions** — re-expressing today's generated list/form/detail as default-projected `@View`s (the RFC's "`@UI` unification" open question). Sequenced follow-up; the big step that makes `@UI` fully redundant.
- **The `@UI` deprecation pipeline** — once `@View` demonstrably replaces `@UI`: the SDK fires `@Deprecated(forRemoval)` on `@UI`/`@UIGroup`/`@Tab` with `@View` as the replacement + a migration note; tooling drops the `@UI`/`UIMetadata` read path (≥1-minor fallback-with-warning); `exeris-sdk-ui-kit` absorbs the `ComponentType` classes. Cross-repo, coordinated, follow-up.
- **G1–G3/G6 binding depth** — owned by RFC-2026-06-28's successor, gated on the SKU corpus; orthogonal to the leaf facet.

## Cross-references

- [RFC-2026-06-28](../rfc/RFC-2026-06-28-presentation-view-emitter-tooling.md) (Presentation View Emitter) — the emitter whose first slice left `ComponentNodeMetadata.field` `null`; this ADR fills it.
- [SDK RFC-2026-06-25](https://github.com/exeris-systems/exeris-sdk/blob/main/docs/rfc/RFC-2026-06-25-presentation-front-model.md) — the "`@UI` is subsumed, not paralleled" decision; the SDK shipped the `UIFieldMetadata`-on-leaf half.
- [ADR-044](ADR-044-tooling-sse-stream-emitter-shape.md) — sibling emitter-shape ADR; the template this decision follows.
- [ADR-042](ADR-042.link.md) (bidirectional mutation surface) — the `-io` reader-mirror obligation (obligation 4).
- [ADR-015](ADR-015-codegen-emission-strategy.md) — JavaPoet/text-block/shared-scaffold rules the generators follow.
- The kernel-free menu §U4 (`docs/generation-expansion-kernel-free-menu.md`) — frames this migration as the U-cluster keystone.

## Engineering Protocol

1. **Processor test** — a `@View` fixture with an authored-`@UI` leaf asserts a populated `view_*.json` `field`; a no-leaf fixture asserts `field: null` byte-identical to slice 1 (obligations 1, 5).
2. **codegen-ts spec** — `view-gen` asserts `componentType`→control mapping reuses the form/list vocabulary and that a slice-1 (no-`field`) `view_*.json` still parses + emits unchanged (obligations 2, 3, 5).
3. **ADR-042 `-io` round-trip + parity** — lands in the same train; no leaf facet extracted without its `SourceModelReader` mirror + `schemaVersion` re-baseline (obligation 4).
4. **Migration owner:** `exeris-tooling` (founder). The code is **not yet written** — this ADR is the design gate. Recommended first implementation wave: leaf facet + authored-leaf (case a) + the `FORM`-block control rendering; entity-driven expansion (case b) as the immediate follow-up.

## Open questions (for founder review)

1. **Authoring model for the leaf facet (the crux).** Case (a) authored-leaf `@UI` is straightforward; case (b) entity-driven expansion (a `FORM` block bound to an entity → expand to the entity's field-level `@UI`) is the unification mechanism and the bigger call — confirm we resolve the bound entity's `UIFieldMetadata` into the `@View` leaves vs requiring every field be an explicit authored leaf.
2. **Dual-path during transition.** Keep the legacy `@UI`-attached list/form/detail generators running alongside the `@View`-driven path until the deprecation follow-up (leaning: yes — the legacy path is the fallback the deprecation cycle requires), or cut over per-view?
3. **U2/U3/U5 framing.** Confirm lists (U2) / forms (U3) / detail (U5) are built on the `@View` leaf-facet path (the menu now frames them this way), not legacy-`@UI` enrichment.
4. **Scope of the first wave.** Leaf facet + authored-leaf (case a) + `FORM`-block control rendering as the first cut, with entity-driven expansion (case b) as the immediate follow-up — or both at once?
