# Generation-expansion plan — wiring the shipped-but-inert SDK records into the pipeline

Settled 2026-06-28 by a design panel (3 lensed proposals — pipeline-architect / contract-steward / delivery-pm — → adversarial synthesis), all facts re-verified against the repos. Companion to [`generation-coverage-audit.md`](generation-coverage-audit.md). This is the coordination spine; waves marked **founder review** are not auto-executed.

## Approach decision — VERTICAL slices on a shared extraction seam

Not horizontal ("extract all five record families in one pass"). The processor extraction is cheap (~4 builder lines per family in `buildFullDomainMetadata`), **but the real cost is downstream and independent per lever**: the SDK `-io` `SourceModelReader` is a hand-written JavaParser field-mirror (not generic Jackson) whose guard `UNMODELED_FACET_ANNOTATIONS = Set.of()` **re-arms the instant the processor extracts a facet** — so every extraction carries a same-train, attribute-complete reader PR (ADR-042) or `applyMutation` mis-attributes the facet as user drift. A horizontal PR would detonate five reader obligations against one `unmodeledFacets` baseline; one wrong mirror breaks conflict detection for *all* entities. Vertical isolates each lever's blast radius and gate attribution. Concession: author the extract-and-set helper shape **once**, then turn it on per-lever.

## Per-lever disposition (facts re-verified)

| Lever | Disposition | Key verified fact | -io parity | Inert removal | Kernel SPI |
|---|---|---|---|---|---|
| **L1 `@Field.dataType`** | **GO-NOW** (no ADR) | reader pre-armed by name (`SourceModelReader:880-885`); presentation-only | yes (same train) | `INERT_ATTRIBUTES ("Field","dataType")` | n/a |
| **L2 `@EventHandler`** | **RECLASSIFIED → DEFER (bundle with L4)** | **Wave-1B finding (2026-06-28):** `EventBus.subscribe` IS GA (not blocked), BUT the `@EventHandler` annotation Javadoc documents its homes as **"in projections or sagas"** (every example is a method inside a `@Projection` or `@Saga` class), and the existing `KernelEventHandlerGenerator` already emits an emit-side `*EventSubscriber` (subclass-and-override). So consuming the `@EventHandler` *annotation* is a **`@Projection`-reaction / `@Saga`-trigger** concern, not a free-standing generator. A standalone reaction family would overlap L4 (projection read-model updates) or the saga path + L6. → **bundle with L4**, do not ship standalone. | yes (re-arm) | none today | yes |
| **L3 EV1 `@DomainEvent` payload** | **RFC-FIRST** (Wave 2) | **corrected:** `DomainEventMetadata` is the bare 4-tuple `(name,topic,description,aggregateType)` — payload fields NOT on the AST → real additive grow; `KernelEventGenerator` hardcodes `EventPayload.empty()` | yes (after grow) | none | yes |
| **L4 grown `@Projection`** | **DEFER** (placement ADR) | `@Target(TYPE)` separate classes → entity-list-vs-own-family unsettled; consumes event payloads → behind EV1 | yes | none | partial (verify `ProjectionEngine` SPI-vs-core) |
| **L5 `@Derived`/`@Rule`** | **DEFER** (RFC→ADR) | needs a SpEL→Java/SQL expression strategy — a new correctness-critical subsystem, XL | yes (if extracted) | none | partial (overlaps T10 CHECK) |
| **L6 `@SagaTransition`+`StepKind`** | **BLOCKED → SDK-RFC** | **verified:** `@SagaTransition` annotation does NOT exist; `@SagaStep` has no `kind()`. Net-new public surface on a 1.0-bound API | yes (after SDK) | none | flow SPI exists |
| **L7 `@EventSourced` (EV2)** | **BLOCKED (out)** | **verified:** `EventStore` is a transactional outbox (`append`/`pollPending`/`markPublished`), NOT an aggregate store | no | keep entry | **no — SPI gap** |
| **L8 `@View` G1** | **DEFER** (own track) | corpus-gated (RFC-2026-06-28); front-only facet to `view_*.json`, not a `DomainMetadata` list | no (own path) | n/a | n/a |

## Wave plan

- **Wave 1A — L1 `@Field.dataType` (the reference loop).** Processor extract + INERT removal → SDK `-io` reader mirror → Java∪TS emitter (TS `currency`/`percent`/`url` formatter; additive `domain-model.ts` `dataType`) + reader↔processor parity test. Repos: `exeris-tooling/{exeris-processor,exeris-codegen-ts,exeris-codegen-java}` + **`exeris-sdk/exeris-sdk-source-model-io`** (same train). **Safe-autonomous** (no ADR). *(In progress.)*
- **Wave 1B — L2 `@EventHandler` — HALTED before implementation (2026-06-28), reclassified.** Grounding it in the real annotation showed `@EventHandler`'s homes are `@Projection`/`@Saga` (see the disposition note + risk 3), so it is **not** a clean standalone generator — folded into **L4** (projection) and the saga path. No code landed; the finding is recorded for founder review. *(This is the vertical-slice discipline working as intended: each lever is grounded before it is built.)*
- **Wave 2 — L3 EV1 payload.** RFC (payload serialization + `sensitiveFields` redaction) → SDK `DomainEventMetadata` grow (`AstJsonRoundTripTest`) → processor extract → reader catch-up → `KernelEventGenerator` payload-projecting publish → reconcile the **live parity bug** at `event-gen.ts:264` (consumes `event.fields` Java never emits). **Founder review.**
- **Wave 3+ — Deferred.** L4 (after EV1 + placement ADR + engine SPI check), L6 (after SDK `@SagaTransition` RFC), L5 (after expression-eval RFC→ADR), L8 (own corpus-gated track). **Founder review.**

## ADR/RFC obligations (per tooling/SDK CLAUDE.md triggers)
- **ADR — L2** new Java-only `*Reactions` generator family + new live metadata family + Java∪TS parity statement.
- **RFC(-light) — L3 EV1** AST grow governed by the SDK Jackson wire-format contract review; settles payload format + `sensitiveFields` redaction + publish-overload shape.
- **ADR — L4** projection placement (entity-list vs `projection_*.json`) + `ProjectionEngine` SPI-vs-core.
- **SDK RFC — L6** net-new `@SagaTransition` + additive `@SagaStep.kind()`; resolve overlap with `effectiveKind()` (covers INVOKE/COMPENSATE, not AWAIT_*).
- **ADR — L5** SpEL→Java/SQL transpilation target split + determinism/safety contract.

## Risks / open questions for founder review
1. **ADR-042 reader obligation is a hard per-lever second-repo cost** — every lever = {processor extract + INERT removal + `exeris-sdk` reader mirror + parity test + Java∪TS emitter}. No "processor-only" slice exists.
2. **`schemaVersion` re-baseline (ADR-042 obl. 5)** — adding facets to the baseline JSON ⇒ `NO_BASELINE` for downstream `applyMutation` until re-baselined in lock-step. Who owns the trigger; is it CI-gated?
3. **L2/L4 bundling — REVISED after the Wave-1B finding.** The panel leaned "ship L2 alone." Grounding L2 in the actual `@EventHandler` annotation overturned that: its documented homes are `@Projection` and `@Saga`, so the `@EventHandler`-annotation consumer **is** the projection-reaction / saga-trigger generator. It should be **bundled with L4** (and coordinated with the saga path), not shipped standalone — otherwise it invents a usage the SDK Javadoc doesn't sanction and overlaps deferred work. Net: L2 follows L4; neither is a clean independent GO-NOW. (This is why Wave 1B was halted before implementation and surfaced for review.)
4. **L4 engine binding** — whether `ProjectionEngine`/`ProjectionHandler` is an SPI seam or core-internal is unverified; if core-internal the emitter can ship the DTO but not the rebuild loop.
5. **L6 `kind` vs `effectiveKind()`** — without `kind`, AWAIT_* steps are structurally invisible; the SDK RFC must decide required-vs-inference-with-override.
