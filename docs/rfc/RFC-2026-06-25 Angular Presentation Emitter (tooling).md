# RFC-2026-06-25: What shape should the tooling Angular 22 presentation emitter take, now that the SDK seeds the `@View`/`ViewMetadata` presentation IR?

| Field             | Value                                                                                                                                  |
|:------------------|:--------------------------------------------------------------------------------------------------------------------------------------|
| **Status**        | **DRAFT**                                                                                                                              |
| **Author(s)**     | arkstack-dev                                                                                                                           |
| **Date Opened**   | 2026-06-25                                                                                                                             |
| **Date Closed**   | —                                                                                                                                      |
| **Target ADR(s)** | TBD — a tooling presentation-emitter-shape ADR (sibling of [ADR-044](../adr/ADR-044-tooling-sse-stream-emitter-shape.md), the SSE emitter shape). Reserved in `exeris-docs/adr-index.md` only once the build gate below opens (cross-repo, same protocol as ADR-043/044). Pairs with the SDK-side "presentation IR surface" ADR that annotates ADR-003's scope. |
| **Affected Repos**| `exeris-tooling` (processor extraction + `exeris-codegen-ts` emitter; `exeris-codegen-java` is **not** an emission target here — see Axis 2); `exeris-sdk` (owns the IR, [SDK PR #70](https://github.com/exeris-systems/exeris-sdk/pull/70) / RFC-2026-06-25 ACCEPTED); `exeris-platform` (Studio/LSP read the same IR); `exeris-sdk-ui-kit` (framework-agnostic primitives the emitted front consumes — downstream, not an emitter owner) |
| **Reviewers**     | —                                                                                                                                      |

## Question

The SDK has seeded (reserved) a unified, framework-neutral, **entity-optional** presentation IR — `@View` / `@Region` / `@Block` / `@Bind` authoring → `ViewMetadata` / `RegionMetadata` / `ComponentNodeMetadata` / `BindingMetadata` AST (RFC-2026-06-25 ACCEPTED, SDK PR #70). The RFC names `exeris-tooling` as the owner of two slices: **the processor extraction** (annotations → IR on the metadata-JSON wire) and **the Angular 22 signal-first emitter** (IR → front). **What shape should the tooling presentation emitter take — how is the new IR carried through the pipeline and extracted by the processor, how does the emitter map `BlockType`/`BindSource`/the recursive node tree to Angular 22 standalone components, and how do today's entity-attached `@UI` generators (form/list/detail) converge into the IR — such that it ships without breaking determinism (#3), processor purity (#2), the processor↔generator contract (#4), Java/TS parity (#5), or single-target discipline (#1)?**

## Context

The build-time pipeline turns `@ExerisDomain` user code into kernel-target Java + a parity Angular/TS app. Every generated view is, today, *derived from one entity*: `@UI` / `@UIGroup` / `@Tab` / `@Field.ui` hang presentation off an `@ExerisDomain`, and our `form-gen` / `list-gen` / `detail-gen` / `app-structure-gen` consume that. The Headless-CMS direction needs the opposite: a page composed from *many* content types, an authored-only section, a backend-less front — none of which a single-entity surface can express.

SDK PR #70 answers the **IR-shape** question (the AST records, the binding discriminator, the `@UI`-subsumption story) and explicitly defers the **emitter-shape** question to this repo: *"the Angular 22 signal-first emitter is `exeris-tooling`… it walks `ViewMetadata`→Angular components, mapping bindings to signal inputs and actions to typed clients."* The SDK seed is **reserved** — `ViewMetadata` is a standalone record **not referenced by `DomainMetadata`** (SchemaVersion stays `0.8.0`), no processor/codegen/`-io` consumes it, and `@UI` is **not** deprecated (it stays the functional generated path until our emitter lands).

The cost of leaving this unanswered: the SDK just shipped a whole new artifact *family* whose only build-time consumer is this pipeline; without an agreed emitter shape, the SDK seed is the inert-attribute anti-pattern at family scale (the streaming saga's core lesson — `realTimeApi` sat inert for releases). And the `@UI` convergence — the largest refactor of our emitters since T1 — needs its boundary drawn before either side moves.

The question has a small enumerable answer set because the design splits along near-independent axes — **metadata carriage / extraction**, **emitter target & node mapping**, **binding→data wiring**, **`@UI` convergence**, and **parity/determinism** — each separable, so this RFC recommends per-axis rather than one monolith (the same structure as the SSE RFC-2026-06-22).

## Investigation

### Validated dependency state (2026-06-25)

- **SDK IR** — ✅ seeded, reserved. Records (`exeris-sdk-source-model`): `ViewMetadata(name, kind:ViewKind, route, title, titleKey, layout, regions)`, `RegionMetadata(slot, components)`, `ComponentNodeMetadata(type:BlockType, customType, binding:BindingMetadata, props, children[recursive], field:UIMetadata.UIFieldMetadata)`, `BindingMetadata(source:BindSource, ref, path, expression, language)`. Enums: `ViewKind{PAGE,SECTION,COMPONENT,FRAGMENT}`, `BlockType{HERO,LIST,GRID,RICH_TEXT,NAV,SLOT,CONTAINER,CARD,FORM,IMAGE,CUSTOM}`, `BindSource{ENTITY,PROJECTION,ACTION,STATIC,SLOT,NONE}`. `@JsonInclude(NON_NULL)` (deliberate — boxed-zero `NON_DEFAULT` trap on planned numeric props; do **not** "correct"), blank→null / null-list→empty normalization, `effective*()` defaults (`kind`→PAGE, `type`→CONTAINER, `source`→NONE).
- **SDK annotations** — ✅ `@View` `@Target(TYPE)` (`name`, `kind`, `route`, `title`, `titleKey`, `layout`); `@Region`/`@Block`/`@Bind` `@Target(FIELD, RECORD_COMPONENT)`. The composition tree is **class-structure-derived** (nested records / inner classes) because Java annotations can't recurse.
- **Tooling** — extracts none of it. The `@UI` path (`UIMetadata`, including `UIFieldMetadata`) is live and consumed by `form-gen`/`list-gen`/`detail-gen`.
- **Corpus** — ❌ the Headless-CMS SKU corpus that validates the node/binding field-lists **does not exist yet**. The SDK RFC flags designing the IR before it as the primary risk, and bounds it: the design is provisional, the corpus adjusts record field-lists *before* the build, additive-surface discipline means later patterns extend rather than reshape.

### The contract that constrains the emitter

Three properties drive every option:

1. **Entity-optional.** A `ViewMetadata` may bind to *no* entity (`source = STATIC`/`NONE`). So the IR **cannot** be carried under the entity-keyed `DomainMetadata` — it is its own top-level metadata family. This is why the SDK kept `ViewMetadata` standalone.
2. **TS-only emission.** A View is a *front-end page*, not a kernel handler — there is **no kernel-Java artifact** to emit for a View. The ENTITY/PROJECTION/ACTION bindings reference Java artifacts that **already exist** (CRUD services, projection clients, T1 action handlers); the emitter generates the Angular front that *calls* them. So "Java/TS parity" (#5) is reinterpreted here: the **parity obligation is processor(Java)↔metadata-model(TS)**, not Java-emitter↔TS-emitter. There is no Java emitter counterpart by design — and that is **not** a parity gap to flag, it is the nature of a front artifact.
3. **Recursion.** `ComponentNodeMetadata.children` is a tree; both the extractor (class structure → tree) and the emitter (tree → nested Angular components) are recursive, unlike today's flat field/action walks.

### Axis 1 — Metadata carriage & processor extraction

- **(1a) New standalone `views/<Name>.json` family + a presentation `MetadataLoader`.** Mirrors the SDK's standalone record; honours entity-optional (not under `DomainMetadata`). Processor gains a recursive class-structure walker (`@View` class → nested `@Region`/`@Block`/`@Bind` record components → `ViewMetadata`). Largest new surface, but the only one that models a backend-less View.
- **(1b) Fold under `DomainMetadata.views`.** Cheapest plumbing (reuses the existing loader), but **forces every View to attach to an entity** — contradicts the IR's reason to exist. Rejected by the contract above.
- **(1c) Hybrid** — entity-driven Views under `DomainMetadata`, free Views standalone. Two carriers for one IR → the exact `@UI`-vs-`@View` duplication the SDK RFC rejects. Rejected.

### Axis 2 — Emitter target & node mapping

- **Target: `exeris-codegen-ts` only**, a new `ViewGenerator` family (Angular 22 standalone, signal-first). No `exeris-codegen-java` counterpart (per contract property 2).
- **`BlockType` → Angular component shape.** Closed taxonomy + escape hatch: `CONTAINER`→layout wrapper, `LIST`/`GRID`→collection components (reuse `list-gen` render), `FORM`→reuse `form-gen` render, `CARD`/`HERO`/`IMAGE`/`RICH_TEXT`/`NAV`→ui-kit primitives, `SLOT`→content projection, `CUSTOM`+`customType`→a named hand-written component reference (never forced into the tree — the IR's escape hatch). `props` (opaque JSON) → component `@Input`s.
- **Leaf `field` facet** (`UIFieldMetadata`, the subsumed `@UI` field detail) → reuse the existing form/list field-render logic (DRY — the same code that renders an `@UI` field today).
- **Recursion** → nested standalone components; deterministic depth-first, list-ordered.

### Axis 3 — `BindSource` → data wiring

- `ENTITY(ref, path)` → a signal fed by the generated entity service/store (the CRUD read path).
- `PROJECTION(ref, path)` → a projection client signal (read-model).
- `ACTION(ref)` → the **T1 typed action client** (`{base}/{id}/actions/{kebab}`) — reuse, not reinvent ([[t1-serve-actions]]).
- `STATIC` / `NONE` → authored content, no data wiring (the backend-less front).
- `SLOT` → Angular content projection (`<ng-content>`), the composition seam.

### Axis 4 — `@UI` convergence (the largest emitter refactor)

- **(4a) `@UI` becomes a generated default `ViewMetadata` projection — one path.** Entity-driven `@UI` view-selection (`listView`/`detailView`/`createForm`/`editForm`) → a synthesized `ViewMetadata` of the matching `kind` whose blocks bind `ENTITY`; field-level `@UI` hints → the leaf `field` facet. `form-gen`/`list-gen`/`detail-gen` are re-expressed as the **entity-driven special case** of the View emitter, not a parallel surface. End-state the SDK RFC mandates.
- **(4b) Keep `@UI` generators, add the View emitter alongside.** Two presentation paths coexist — the duplication the SDK RFC explicitly rejects. Rejected as the *end-state*, but is the **transition** state (both live during the ≥1-minor fallback window).
- **Sequencing (honest):** `@UI` stays functional and **undeprecated** until the View emitter can actually replace it. Only then does the SDK fire `@Deprecated(forRemoval)` with `@View` as the replacement, and tooling absorbs it with a ≥1-minor fallback-with-warning (coordinated with `exeris-sdk-ui-kit` ComponentType classes + `MIGRATION.md`). No big-bang supersede of a working surface — the streaming-saga discipline.

### Axis 5 — Parity, determinism, single-target

- **Parity (#5):** TS metadata mirror — `domain-model.ts` gains Zod mirrors of the 4 records + 3 enums; the obligation is processor-emits ⇔ TS-loader-reads, lock-step. No Java emitter (Axis 2).
- **Determinism (#3):** all emission metadata-driven, list-ordered, depth-first; opaque `props`/`expression` pass through verbatim; no wall-clock / UUID / hash-order. The compile gate is the existing `ng build` / `tsc --noEmit` round-trip (T20 gate) + vitest.
- **Single-target (#1):** Angular 22 is the **first** emitter target, **not** a multi-backend abstraction. "Framework-neutral IR" lives in the SDK; additional targets (other frameworks, static export, render-API) are additive *future* tooling work over the same IR — designing for them now would reintroduce the multi-backend abstraction 0.1.0 deleted. We own Angular 22 only.
- **Strict mode (T11):** once the emitter consumes the View metadata it is no longer inert; until then, nothing extracts it, so there is nothing to falsely flag.

## Recommendation

**Lock the design now (this RFC); build only when the gate opens.** Per axis: **1a** (standalone family + recursive extractor), **2** (`exeris-codegen-ts` `ViewGenerator`, TS-only, `BlockType`/leaf-`field` mapping), **3** (binding→wiring reusing CRUD/projection/**T1 action** clients), **4a end-state via 4b transition** (`@UI` folds into the IR, staged deprecation), **5** (TS mirror + determinism + Angular-22-first).

**Build gate (NOT yet met)** — mirrors the SDK RFC's: ship the emitter only when **both** hold:
1. a **Headless-CMS SKU corpus** exists to validate the node/binding field-lists (guards against an early miscut the additive-surface rule can't fully undo), **and**
2. this emitter shape is ratified (the sibling ADR).

**Why design-first, code-later (unlike the SSE Slice 1):** the SSE emitter shipped a slice immediately because a real kernel primitive + a real use (U7 live-view) existed and compiled end-to-end. Here the validating corpus *does not exist yet*, and the SDK RFC names "designing the IR before the corpus" as the primary risk. Extracting `ViewMetadata` into a JSON family nothing consumes would manufacture the inert anti-pattern we just spent the streaming saga avoiding. So the honest first deliverable is the **agreed shape**, not code.

**Slicing when the gate opens** (each a separate PR, gated):
- **Slice A — read-path plumbing.** Processor recursive extractor (`@View`/`@Region`/`@Block`/`@Bind` → `ViewMetadata` JSON) + the TS metadata mirror + a presentation `MetadataLoader`. Lands *with* Slice B (never alone — extraction without an emitter is inert).
- **Slice B — entity-driven View emitter.** The cheapest emission that validates the whole pipeline against the existing CRUD path: a `@View(kind=PAGE/SECTION)` whose blocks bind `ENTITY` → a standalone Angular component reusing form/list/detail render. Proves the tree-walk + binding wiring end-to-end under the FE build gate.
- **Slice C — composition & front-only.** Multi-source pages (`PROJECTION`/`ACTION`/`SLOT`) + backend-less (`STATIC`/`NONE`) Views — the Headless-CMS payoff.
- **Slice D — `@UI` convergence.** Re-express `form/list/detail-gen` as the entity-driven special case (4a); absorb the SDK's `@UI` deprecation with the fallback-with-warning window.

## Open questions for review

1. **Node taxonomy stability.** `BlockType` is provisional pending the SKU corpus. Should Slice B restrict to a *minimal* subset (`CONTAINER`/`LIST`/`FORM`/`CUSTOM`) and let the corpus widen it, to minimize early-miscut blast radius? (Leaning yes — additive widening is cheap, narrowing is breaking.)
2. **Does Slice D supersede or wrap the per-shape generators?** Re-expressing `form/list/detail-gen` as IR special-cases is the clean end-state, but it touches the densest spec clusters and the v22 Signal-Forms reshape ([[angular-v22-migration]] Phase C) lands in the same area. Sequence D *after* Phase C, or fold them? (Open — depends on Phase C timing.)
3. **`props` / `expression` opacity at the TS boundary.** The SDK stores `props` as an opaque JSON string and `expression` with a `language` tag (SpEL default). The emitter must pass both through verbatim (never interpret) to stay framework-neutral-faithful — confirm the TS mirror types them as opaque `string`, not parsed objects.
4. **Studio/LSP co-read.** `exeris-platform` reads the same IR for visual editing. Out of tooling scope, but the metadata-family path (Axis 1a) is a shared contract — coordinate the `views/<Name>.json` location/shape so three consumers (emitter, Studio, LSP) read one artifact.

## Next action

Circulate this DRAFT. On **ACCEPT**: reserve the tooling ADR number in `exeris-docs/adr-index.md` (only once the build gate opens, per the ADR-043/044 cross-repo protocol) and author the presentation-emitter-shape ADR (sibling of ADR-044) fixing Axes 1–5 as the contract and scoping Slices A–D. Until the SKU corpus exists, **emit nothing** — the deliverable of this RFC is the locked shape, not a slice. Tracked against the SDK seed (PR #70) and [[presentation-front-model]].
