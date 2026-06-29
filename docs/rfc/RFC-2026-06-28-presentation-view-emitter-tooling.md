# RFC-2026-06-28: What shape should the tooling `@View` presentation emitter take, now that the SDK ships the reserved presentation IR?

| Field             | Value                                                                 |
|:------------------|:----------------------------------------------------------------------|
| **Status**        | **DRAFT** — first-slice proposal pending founder review. The **first slice has LANDED** under this DRAFT (PR #121, off `main`) per the founder go-ahead, mirroring how the SSE RFC-2026-06-22 slice 1 shipped before ratification. Opens build-gate condition (1) of [SDK RFC-2026-06-25](https://github.com/exeris-systems/exeris-sdk/blob/main/docs/rfc/RFC-2026-06-25-presentation-front-model.md). |
| **Author(s)**     | arkstack-dev                                                          |
| **Date Opened**   | 2026-06-28                                                           |
| **Date Closed**   | —                                                                    |
| **Target ADR(s)** | TBD — the "presentation view emitter shape" ADR (sibling to [ADR-044](../adr/ADR-044-tooling-sse-stream-emitter-shape.md)) that ratifies this RFC's recommendation, reserved in `exeris-docs/adr-index.md` on acceptance. The leaf-field-facet follow-up has its own decision: [ADR-047 (DRAFT)](../adr/ADR-047-view-leaf-field-facet-and-ui-subsumption.DRAFT.md). |
| **Affected Repos**| `exeris-tooling` (processor extraction → `view_*.json`; the framework-neutral-IR → **Angular 22 signal-first emitter** in `exeris-codegen-ts`); `exeris-sdk` (consumes the IR shape it already owns — no change); `exeris-sdk-ui-kit` (a downstream consumer of the emitted front, not an emitter target owner) |
| **Reviewers**     | —                                                                    |

## Question

[SDK RFC-2026-06-25](https://github.com/exeris-systems/exeris-sdk/blob/main/docs/rfc/RFC-2026-06-25-presentation-front-model.md) (ACCEPTED) shipped the **reserved** presentation IR — the annotations (`@View` / `@Region` / `@Block` / `@Bind`) and the AST records (`ViewMetadata` / `RegionMetadata` / `ComponentNodeMetadata` / `BindingMetadata` + `ViewKind` / `BlockType` / `BindSource`) — and resolved its hard forks: composition authoring is **class-structure-derived**, the annotation is a single `@View` + `kind`, and the component taxonomy is the coarse `BlockType` enum. It deliberately ships **no generation**; that is gated on **(1) the Angular 22 emitter being authored in `exeris-tooling`** and **(2) a concrete page/composition corpus** (the Headless CMS SKU, named).

**What shape should the tooling emitter take — the processor extraction of the class-structure-derived `@View` into a metadata artifact, the artifact's place in the pipeline, the IR→Angular-22-signal-first component mapping, the binding subset the first slice honours, and the inert-honesty handling — such that the first slice ships without breaking determinism (hard-constraint #3), kernel-target discipline (hard-constraint #1), or the inert-attribute rule, and so that later corpus-driven growth *extends* rather than reshapes it?**

## Context

This RFC opens gate condition (1): it authors the emitter, so the SDK IR ships behind a real consumer rather than inert at family scale. For condition (2) the canonical informing usage is the Headless CMS SKU (H1-2028), which does not exist yet — but a **worked corpus already exists**: [`Stellar-Tactics/docs/view-ir-corpus.md`](https://github.com/exeris-systems/Stellar-Tactics/blob/main/docs/view-ir-corpus.md) maps 8 hand-written game screens onto the IR and enumerates the binding-model gaps (G1–G6). Stellar is a dog-food, not the SKU, so this RFC treats it as the **early validating corpus** for the *first slice* and explicitly defers the binding-model depth (G1–G3, G6) to the SKU corpus, exactly as the SDK RFC intends. The first slice is the smallest end-to-end vertical that proves the pipeline; it is **not** the full emitter.

Precedent this emitter mirrors, one-for-one:
- **Capability extraction** (`@CapabilityModule` → `capability_*.json`, PR #85) is the template for a **separate, app-wide metadata artifact parallel to `DomainMetadata`, never nested in it.** `@View` → `view_*.json` is the same move.
- **The SSE emitter** (RFC-2026-06-22 / ADR-044) is the sibling: a framework-neutral SDK driver, an Angular/TS emitter that owns the framework specifics, parity + determinism gates.
- **The inert-attribute rule + `-Aexeris.strict`** (T11): the processor must register `@View` in `INERT_ANNOTATIONS` for any window in which it is extracted but unconsumed, and **delete that entry in the same change that wires the generator** — the same discipline `@EventSourced` is held to.

## Investigation

### What already exists (greenfield except the SDK IR)
- SDK annotations `@View(name, kind, route, title, titleKey, layout)` (`@Target(TYPE)`), `@Region(slot)` / `@Block(type, customType, props)` / `@Bind(source, ref, path, expression, language)` (`@Target({FIELD, RECORD_COMPONENT})`) — all `@Retention(SOURCE)`.
- SDK records `ViewMetadata` (`name/kind/route/title/titleKey/layout/regions`), `RegionMetadata` (`slot/components`), `ComponentNodeMetadata` (`type/customType/binding/props/children`(recursive)/`field`:`UIFieldMetadata`), `BindingMetadata` (`source/ref/path/expression/language`); enums `ViewKind` / `BlockType` / `BindSource`; `@JsonInclude(NON_NULL)` (deliberate, the boxed-zero trap).
- Tooling: no `@View` in `@SupportedAnnotationTypes`; no `view_*.json`; no `ViewGenerator`; `ViewMetadataSchema` absent from codegen-ts `domain-model.ts`. `index.ts` already separates `enum_*` files by basename — `view_*` follows the same pattern.

## Recommendation (first slice — DRAFT, provisional)

### 1. Class-structure-derived extraction (the authoring convention this slice fixes)
The SDK resolved "class-structure-derived" but left the exact walk to the emitter RFC. This slice fixes it as:

- A **`@View` class** is the view root. Its declared **fields / record-components** that carry **`@Region`** become `RegionMetadata` (slot = `@Region.slot`, or the field name when blank). Region order = source declaration order (deterministic).
- A region field's **type is a plain class/record** ("a region class") whose fields/components, each carrying **`@Block`** (+ optional **`@Bind`** on the same member), become that region's `ComponentNodeMetadata` list. Block order = declaration order.
- **Recursion:** a `@Block` member whose type is itself a region-shaped class yields `children` — the recursive `ComponentNodeMetadata` tree the SDK records model. Depth is bounded only by the class graph; a visited-set guards cycles.
- A member with **`@Bind`** but no `@Block` is a leaf binding node; a member with **`@UI`/`@Field`-style** render hints contributes the leaf `field : UIFieldMetadata` facet (reusing the existing record — DRY, the SDK's "`@UI` is subsumed" direction). *(Leaf-field facet is modelled but its emission is minimal in slice 1.)*
- Blank annotation strings normalise to `null` (matches the SDK compact-constructor contract); enums come straight from the annotation.

This is the smallest rule set that produces the recursive tree from non-recursive annotations, and it is **additive** — richer member kinds extend the walk without reshaping `view_*.json`.

### 2. Pipeline placement — `view_*.json`, parallel artifact
The processor adds `eu.exeris.sdk.annotation.View` to `@SupportedAnnotationTypes`, processes `@View` types in their own round (mirroring `processCapabilityModuleAnnotations`), and writes `view_<Name>.json` via the existing `writeMetadata(...)` — a wrapper carrying `{name, packageName, qualifiedName, view: ViewMetadata}` (same shape as `CapabilityModuleJson`). App-wide, parallel to `DomainMetadata`, never nested.

### 3. IR → Angular 22 signal-first component (the emitter, `exeris-codegen-ts`)
A new `ViewGenerator` (a new `PAGE` `ArtifactType`) emits one **standalone, signal-first** Angular component per `view_*.json`, under `src/app/pages/<kebab>.component.ts`, routed by `@View.route`:
- `ViewMetadata` → `@Component({ standalone: true, … })` with a template assembled region-by-region; `title` → the route title.
- `RegionMetadata.slot` → a `<section data-region="slot">` wrapper; `ComponentNodeMetadata` → an element chosen by `BlockType` (HERO/CARD/GRID/LIST/CONTAINER/RICH_TEXT/NAV/IMAGE → semantic wrappers; `CUSTOM` → the named `customComponent` selector; `FORM` → defers to the existing form vocabulary), recursing through `children`.
- **Bindings honoured in slice 1:** `STATIC` (literal/`props` text), `NONE` (authored structure), `ENTITY` (read via the generated service signal — `inject(<Entity>Service)` + `toSignal`/`httpResource`, the v22 idiom), `ACTION` (a click handler calling the typed action method). **OUT of slice 1:** `PROJECTION` beyond a named read, `G1` parameterised/relational binding (opaque `expression` only, emitted as a TODO-marked passthrough), `G2` `STREAM` source, `G3` mesh binding, `G6` token/theme binding — each documented as a known limit referencing the corpus gap.
- Framework-neutral IR in, Angular out: the SDK names no Angular type; the ui-kit stays a consumer (its token utilities skin the emitted markup, per U1/T25).

### 4. Inert honesty + parity + determinism
- The processor registers `@View` in `INERT_ANNOTATIONS` **only** for the interim where extraction exists without the generator; the slice lands extraction **and** the generator together, so the end state carries **no** `@View` inert entry (and `-Aexeris.strict` stays honest). If split across PRs, the entry is added then removed in lock-step.
- Emitter is codegen-ts only; views are a **front-only** facet, so there is no Java emitter counterpart — parity (strong-default #4) is satisfied by construction (no shared backend surface). `-io` reads `view_*.json` only once the processor writes it (ADR-042 parity), tracked SDK-side.
- Deterministic: declaration-order regions/blocks, sorted where order is not source-given, no timestamps/hash-iteration leakage. A `view_*.json` round-trip + an emitted-component snapshot pin it; the FE build gate (`verify:generated` / `tsc --noEmit`) compiles the emitted page.

### 5. Build-gate honesty
This slice satisfies condition (1) (the emitter is authored) and uses Stellar's corpus as early condition-(2) validation. The **full** emitter (G1–G3/G6, the leaf-field forms, the `@UI` unification) remains gated on the Headless CMS SKU corpus — this RFC ships the *first slice* and the design, not the finished surface.

## Open questions / follow-ups
- **G1 parameterised/relational binding** — the corpus's defining trait ("X of the current Y"); the highest-leverage post-slice fork (`@Bind(via=…, from=PARENT)` shape). Owner: this RFC's successor, gated on the SKU corpus.
- **G2 `STREAM` binding** — pairs with the SSE emitter (ADR-044); cheap once added to `BindSource` (SDK).
- **G3 mesh binding** — the T12 cross-service epic meeting the presentation lane.
- **G6 token/theme binding** — the presentation counterpart of U1/T25; how a view names its design system + theme-variant axis.
- **`@UI` unification** — generated entity views as default-projected `ViewMetadata` (one presentation model). Owner: SDK roadmap; the tooling-side first step is [ADR-047 (DRAFT)](../adr/ADR-047-view-leaf-field-facet-and-ui-subsumption.DRAFT.md).
- **Leaf-field form emission** — fuller `UIFieldMetadata`-facet rendering inside `FORM` blocks. The slice-1 emitter deliberately left `ComponentNodeMetadata.field` `null`; populating + emitting it is the subject of [ADR-047 (DRAFT)](../adr/ADR-047-view-leaf-field-facet-and-ui-subsumption.DRAFT.md), Slice 2.

## Next action

The **first slice has landed** behind the gates above (`feat/view-emitter-slice1` → PR #121, stacked on the ui-kit/appName scaffold PR #120, both off `main`), under this DRAFT per the founder's go-ahead — the same path the SSE RFC-2026-06-22 slice 1 took before ADR-044 ratified it. On **ACCEPT**: reserve the tooling "presentation view emitter shape" ADR number in `exeris-docs/adr-index.md` and author the emitter-shape ADR (sibling to ADR-044) fixing the now-shipped slice-1 shape as the contract and scoping the gated follow-ups (G1–G3/G6, the leaf-field facet via [ADR-047](../adr/ADR-047-view-leaf-field-facet-and-ui-subsumption.DRAFT.md), the `@UI` unification). The full emitter stays gated on the Headless CMS SKU corpus (condition 2).
