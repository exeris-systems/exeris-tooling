# Kernel-free tooling menu — what we can ship without kernel changes (excl. EV1)

Adversarially verified 2026-06-28 (3 parallel verifiers grepping `exeris-kernel-spi`/`-core` + synthesis). Answers: "From Tooling (and in-mandate SDK), what can we do WITHOUT kernel changes, excluding EV1?" Companion to [`generation-expansion-plan.md`](generation-expansion-plan.md) + [`generation-coverage-audit.md`](generation-coverage-audit.md).

**Headline: 9 of 12 candidates are fully kernel-free; 2 (L4, T12) are partial with a precisely-drawn kernel boundary; 0 require kernel changes.**

## Correction (founder, 2026-06-28) — the U-cluster is `@UI`→`@View` absorption, not legacy-`@UI` enrichment
Per RFC-2026-06-25, **`@UI` is *subsumed* by `@View`/`ViewMetadata`, then deprecated/removed** — there is one presentation model, not two. Entity-level `@UI` (list/detail/create/edit selection) → a `@View` of the matching `kind`; **field-level `@UI` render hints → the leaf field facet of the IR (`ComponentNodeMetadata.field : UIFieldMetadata`, reused DRY — not a parallel record).** The **SDK already shipped its half** (the `@View` seed carries `UIFieldMetadata` on leaf nodes + a successor note on `@UI`); the **Tooling-side migration — actually consuming that leaf facet in the emitter + populating it in the processor — was never done, and SDK was waiting on it.** So the U-cluster keystone below is **the `@View` leaf-field-facet migration** (the `@View` first slice deliberately left `ComponentNodeMetadata.field` `null`), NOT enriching the legacy entity-attached `@UI` generators. The end-state: `@View` carries field-level render detail → entity-driven list/form/detail become `@View`-projected → `@UI` runs the deprecation pipeline. U2/U3/U5 below fold into that `@View`-driven path rather than extending the legacy `@UI` one.

## Two "EV1 traps" caught (kernel-gated remainders carved out)
- **L4 projection rebuild loop** — `ProjectionEngine`/`ProjectionHandler`/`Projection` live in `eu.exeris.kernel.**core**.events.projection` (concrete core, **not** SPI). The DTO + aggregate→DTO mapping is kernel-free; the live fold/rebuild is not. → L4 ships the **DTO+mapping slice only**.
- **T12 `KernelWebClient` single-host** — no host/baseUrl param (`KernelWebClient.java:121,187`); peer addressing/discovery is K4 (kernel-core). The peer client + `*View` DTO *generation* is kernel-free; runtime addressing is not. → T12 ships the **client+DTO slice only**.
- (L2 @EventHandler survives clean — binds only the GA `EventBus.subscribe`/`SubscriptionToken` seam, `@since 0.5.0`.)

## The menu (ranked by value-per-effort)

| # | Item | Kernel-free | Tooling-only slice | Obligation (in-mandate) | Effort | Eliminates (dog-food) |
|---|---|---|---|---|---|---|
| 1 | **@View route assembly** | YES | thread `views` into `app-structure-gen` → import+spread per-view routes into `app.routes.ts` | none (1-line parity note) | **S** | hand-written app shell/routes; makes the FE-only CMS front *runnable* |
| 2 | **T9 FK constraints** | YES | Flyway trailing `ALTER … ADD CONSTRAINT FOREIGN KEY` migration (above create-tier) | none (no -io PR; reuses `RelationshipMetadata`) | **S** | — (referential integrity) |
| 3 | **U4 — `@View` leaf-field-facet migration (KEYSTONE; = the `@UI`→`@View` przeniesienie SDK awaited)** | YES | processor populates `ComponentNodeMetadata.field : UIFieldMetadata` from field-level `@UI` render hints during the `@View` walk (the slice left it `null`); `ViewGenerator` emits the field control from the leaf facet; grow TS `ViewMetadata`/leaf schema to carry `UIFieldMetadata`. SDK half already shipped. | **ADR-042 -io mirror** (leaf facet) + a tooling ADR for "the `@View` emitter now consumes the field facet; `@UI` successor path" (no new annotation) | **M** | unblocks U2/U3/U5 *as `@View`-driven*; first step of deprecating `@UI` |
| 4 | **U2 — universal lists** | YES | `list-gen`: enum→badge, bool→icon, FK→link, numeric align, filters (drop `slice(0,2)`), pageSize, row actions | none (consumes U4) | **S–M** | bespoke column formatting across ~15 list components |
| 5 | **U5 — configurable detail** | YES | `detail-gen`: sections/tabs from groups/@Tab; related panels | none (consumes U4) | **S–M** | bespoke detail layouts |
| 6 | **U3 — forms from metadata** | YES | `form-gen`: componentType→control, groups→fieldset, @Tab→tabs, @Relationship→picker | rides U4's ADR | **M** | hand-tuned form controls across ~15 form components |
| 7 | **@View BlockType depth + leaf FORM** | YES | richer per-BlockType markup; FORM block → U3 renderer | none (G1/G2/G3/G6 are a separate SDK RFC, out) | **M** | hand-built CMS block/page markup |
| 8 | **L2 — @EventHandler reactions** | YES | processor extract `@EventHandler` (in @Projection/@Saga) → subscribe+invoke via GA `EventBus.subscribe` | tooling ADR (bundle w/ L4) + ADR-042 -io mirror | **M** | `onBattleEnded` (~15) + reaction glue |
| 9 | **L4 — @Projection DTO+mapping** | **PARTIAL** | processor extract `projections` + `KernelProjectionDtoGenerator` (DTO record + mapping); add `@Projection` to supported annotations | tooling ADR (placement + DTO-not-rebuild boundary) + ADR-042 -io mirror | **M** | read-model records + aggregate→DTO mappers |
| 10 | **L6 — @SagaTransition + StepKind** | YES (net-new = **SDK** annotation, not kernel) | SDK `@SagaTransition`/`@SagaStep.kind`; processor → `SagaMetadata.transitions`; `KernelSagaGenerator` emits outcome-edged bodies on the **GA** flow SPI | **SDK RFC** (1.0 annotation contract) + tooling ADR + -io mirror | **L–XL** | delegating saga bodies (~210) |
| 11 | **T12 — cross-service client+DTO** | **PARTIAL** | contract-registry stage → generalize `KernelClientGenerator` to emit peer client + `*View` DTOs | tooling ADR (registry + K4-out boundary) | **L–XL** | **mesh seam ~1,230 LOC** (biggest single win) |
| 12 | **L5 — @Derived/@Rule** | YES | SpEL→{Java,SQL} transpiler; `@Rule`→handler guard (reuse T10 seam) / Flyway CHECK; `@Derived`→computed accessor | **RFC→ADR** (transpile + safety) + -io mirror | **XL** | roll-ups/formulas/guards (modest) |

## Explicitly out (boundary)
- **EV1 runtime** (kernel event-codec SPI — drafted ADR, founder decision). **L4 rebuild loop** (kernel core). **T12 K4 addressing** (kernel core). **T12 command/event surface** (blocked on T1, separate tooling track). **@View G1/G2/G3/G6 binding depth** (SDK RFC, out of the tooling slice). **L7 @EventSourced** (kernel SPI gap). The dog-food app's `*/engine` cores + genuine orchestration/reaction *bodies* (genuine logic — generators kill the glue, not the rules).

## Recommended sequence
**First move (S, safe-autonomous): `@View route assembly`** — converts the dead-end page generator into a runnable standalone CMS front (the audit's top gap) for the least work; no ADR/SDK/kernel/metadata.

- **Lane A (autonomous, no paperwork):** (1) @View routes (S) · (2) T9 FK (S).
- **Lane B (the `@View` leaf-facet keystone → `@UI` deprecation):** (3) **U4 reframed = the `@View` leaf-field-facet migration** (M, the gating dependency — populate + emit `ComponentNodeMetadata.field`; needs the ADR-042 -io mirror + a tooling ADR *before* coding) → then (4) U2, (5) U5, (6) U3, (7) `@View` depth — emitted *through the `@View` IR* (the field facet + entity-driven views as `@View` compositions), not by extending legacy `@UI`. End-state: `@View` carries field-level render detail → run the `@UI` `@Deprecated(forRemoval)` pipeline (cross-repo: tooling drops the `@UI`/`UIMetadata` read path, `exeris-sdk-ui-kit` absorbs the `ComponentType` classes). U2 first among the emitters.
- **Lane C (one shared tooling ADR):** (8) L2 @EventHandler → (9) L4 DTO slice — bundled (both add `@Projection` to supported annotations + carry a -io mirror).
- **Gate behind governance:** (11) T12 (tooling ADR; biggest LOC win, addressing deferred to K4) · (10) L6 (**SDK RFC first**) · (12) L5 (**RFC→ADR first**; lowest value-per-effort — last).

**Governance:** safe-autonomous = @View routes, T9. Tooling-ADR-first = U4, L2+L4, T12. SDK-RFC-first = L6, L5 (+ @View G-series, excluded). ADR-042 -io mirror = every facet-extracting item (U4, L2, L4, L5, L6); none for T9/T12/U2/U3/U5/@View.

**Spine:** `@View route assembly → U4 keystone → U2` — a working CMS front + the metadata that collapses ~15 hand-written list/form pairs, with only one ADR (U4's) standing between you and the whole emitter cascade.
