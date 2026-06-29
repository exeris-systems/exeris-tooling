# Generation-coverage audit — BE/FE/E2E separation, FE-only/CMS lane, and the hand-written → generation roadmap

Audit date: 2026-06-28. Method: 4 parallel readers across `exeris-tooling` / `exeris-sdk` / `Stellar-Tactics` / `exeris-ai-bridge`, then an adversarial verifier that **re-ran the load-bearing claims** (FE-only CLI run, grep-confirmed unconsumed records). Evidence is file:line-anchored in the workflow transcript; this doc is the synthesis + the prioritized roadmap.

---

## 1. BE / FE / E2E separation — **YES, cleanly separated** (one hinge)

The pipeline is split along a single hinge: **one processor → `exeris-metadata/*.json` (the sole contract) → two fully independent toolchains.**

```
@ExerisDomain/@Action/@CapabilityModule/@View  (Java sources)
        │  exeris-processor (in javac) — the ONLY author of metadata JSON
        ▼
   target/classes/exeris-metadata/*.json     ← <entity>.json · enum_* · capability_* · view_*
        ├───────────────────────────────┬──────────────────────────────
        ▼ BE                             ▼ FE
   mvn exeris:generate                 exeris-gen generate --input <dir>
   (CodegenPipeline, Java)             (codegen-ts CLI, npm — NOT in the Maven reactor)
   → src/main/generated/java/          → src/app/{types,schemas,services,components,pages}
```

| Mode | How | Status |
|---|---|---|
| **BE-only** | `mvn exeris:generate` → `CodegenPipeline.run(metadataDir, out, basePkg)` — no FE reference anywhere in the Java pipeline | ✅ works standalone |
| **FE-only** | `exeris-gen generate --input <metadata-dir>` — `findMetadataFiles` needs **only `*.json` in a dir**; never touches Java/Maven/kernel | ✅ works standalone (verified) |
| **E2E (BE+FE)** | No single cross-toolchain orchestrator in `exeris-tooling`; CI fans out: `mvn verify` ‖ `vitest + verify:generated` ‖ `ng build` of a `gen-sample-app`. *(Stellar's `build.sh` is a combined reactor+test driver for the **showcase**, not the tooling repo.)* | ✅ per-side; not one harness |

**The one coupling:** metadata JSON is authored *only* by the Java processor (runs inside `javac`). So a *cold* FE run presupposes a prior `mvn compile` — **unless the JSON is hand-/Studio-authored** (the front-only lane, §2). The two toolchains otherwise share nothing but the JSON.

### The RFC four-quadrant model → what produces each today
(SDK [RFC-2026-06-25](https://github.com/exeris-systems/exeris-sdk/blob/main/docs/rfc/RFC-2026-06-25-presentation-front-model.md): backend facet × front facet over one JSON.)

| | **front** | **no front** |
|---|---|---|
| **backend** | entity-driven view (`@UI` cheap path / `@View`) — *partial (@View slice)* · multi-source composition — *gated* | **API-only** (no `@View`) — ✅ **shipping today** |
| **no backend** | **front-only** (authored `view_*.json`, Studio/IR) — ✅ **page-emission works today** | — degenerate |

---

## 2. FE-only for CMS / Studio — **the page lane works today; not yet a turn-key standalone front**

**Works now (verified end-to-end by re-running the CLI):** a hand-authored `view_*.json` with `STATIC`/`NONE` bindings (no domain JSON, no backend) → `exeris-gen generate` → a **backend-free, standalone, signal-first Angular page component** (`pages/<kebab>.component.ts`) + a lazy route. The verifier confirmed the emitted page imports only `@angular/core` + `@angular/common` — no `inject`, no `*Service`, no HTTP. This is the RFC's **front-only quadrant, realized** (first slice, RFC-2026-06-28).

- **Lane A (backend-anchored):** `@View` on a plain Java class → processor `view_*.json`. Entity-driven / multi-source.
- **Lane B (front-only, no backend):** hand-/Studio-authored `view_*.json` → codegen-ts. **This is the entire FE-only CMS story** — you never touch an `@ExerisDomain`.

**Gaps to a *runnable* standalone CMS front (owner-tagged):**

| Gap | Owner | Note |
|---|---|---|
| **App-shell route assembly** | tooling (codegen-ts) | The per-view `.route.ts` is emitted but **not wired into `app.routes.ts`** (degenerate empty redirect today). *The most immediate gap* — distinct from binding depth. |
| **G1 parameterised/relational binding** ("X of the current Y") | SDK (shape) + tooling | The corpus's defining trait; today only an opaque-`expression` TODO. **Highest-leverage fork.** |
| **G2 STREAM/live binding** | SDK (`BindSource`) + tooling | Cheap; SSE runtime already landed (ADR-043/044). |
| **G3 mesh binding** | SDK + tooling | T12; central to multi-service, minor for a single CMS front. |
| **G6 token/theme binding** | tooling + `exeris-sdk-ui-kit` | The view can't yet *name* its design system / theme variant (the Stellar T25/faction-overlay axis). |
| **Studio visual authoring UI** | `exeris-platform` — **ABSENT locally** | Only its `lsp:*` *read* surface is described (via `exeris-ai-bridge`), and that exposes domains/actions only — **no presentation/view/Studio surface**. The authoring UI is a separate, not-present repo. |
| **Headless CMS SKU corpus** | SDK/SKU roadmap — **ABSENT** (H1-2028) | Gates the *full* emitter (RFC condition 2). Stellar's `view-ir-corpus.md` is the early validating stand-in. |

**Bottom line:** FE-only *page generation* is live; a turn-key standalone front needs app-shell assembly (small, tooling) + the binding depth (G1 keystone) + eventually the Studio UI (absent platform repo).

---

## 3. Minimize mechanical work — hand-written inventory → generation levers

### 3a. The cross-cutting root cause (highest-leverage single fix)
Five of the eight "move-to-generation" levers share **one root**: the processor's entity-build pass extracts only `fields/actions/events/relationships/uiMetadata/graph/eventSourced/saga/internalApi` — **never** `projections`, `eventHandlers`, `rules`, or `FieldMetadata.derived`/`dataType`. The SDK has *already grown the AST* to carry behaviour/choreography/read-models (records shipped reserved), but the single extraction pass + the emitters were never taught to read them. So `DomainMetadata.projections/.eventHandlers/.rules` always serialize empty.

> **One processor-extraction expansion + matching emitters unlocks most of the levers below.** This is the keystone investment.

### 3b. SDK records shipped-but-unconsumed (the half-built levers — SDK done, tooling pending)

| Lever | SDK record | Processor extracts? | Codegen emits? | Eliminates | Effort |
|---|---|:--:|:--:|---|:--:|
| `@Field.dataType` (B5) | ✅ | ❌ (inert) | ❌ | currency/percent/url formatting in lists/forms; unblocks U2/U3 | **S** |
| `@EventHandler` | ✅ | ❌ | ❌ (TS "EventHandlerGenerator" is a misnomer — reads `@DomainEvent` emit side) | hand-written reaction/choreography bodies | **M** |
| `@DomainEvent` payload (EV1) | ⚠️ record needs small grow | ❌ (drops fields) | ❌ (`EventPayload.empty()`) | hand-written event-payload projection | **M** |
| grown `@Projection` | ✅ | ❌ | ❌ (TS schema still pre-growth) | read-model DTO + mapping (public-projection split) | **M–L** |
| `@Derived` / `@Rule` | ✅ | ❌ | ❌ | roll-ups / formulas / threshold guards | **L–XL** |
| `@View` (G1–G6) | ✅ | ✅ (slice) | ⚠️ partial (STATIC/NONE/ENTITY/ACTION; G1/G2/G3/G6 TODO) | multi-content-type / parameterised / streamed / themed pages | **L** (corpus-gated) |
| `@SagaTransition` + `StepKind` | ⚠️ AST yes, **annotation missing** | ❌ | ❌ | await/dispatch/compensate state-machine bodies | **L–XL** |
| `@EventSourced` (EV2) | ✅ | ✅ | ❌ (kernel-blocked: no event-store SPI) | event-sourced aggregate (append/load/snapshot) | **XL, blocked** |

### 3c. Stellar hand-written inventory (what to keep vs move)

**Keep hand-written — genuine domain logic (~7,500 LOC):** the `*/engine` cores (~1,820 — combat/economy/research/galaxy/faction/commander; pure deterministic rules), `domain/` annotated source (3,050 — the source of truth that *drives* generation), most `app/*` service composition (~2,500), `sim/` harnesses (595), the web `ui/` design system (320 — hand-authored by design, T25/G6).

**Move to generation — mechanical mass (~3,300 LOC, mapped to levers):**

| Mechanical area | ~LOC | Lever |
|---|--:|---|
| **Mesh seam** — `UniverseClient` + `InProcess`/`Http` adapters + 15 `*View` records | **~1,230** | **T12** (cross-app contract registry → generated remote dispatch + DTOs + K4 addressing) — *biggest single win* |
| **app port/adapter/memory triad** | **~1,084** | **T8** finders (done — kills `findAll().filter()`) + generated repository-fake codegen (test fixtures) |
| **web mocks + string-union enums** | **~750** | **T20** (real TS enums + `*DisplayNames` — already fixed in tooling; Stellar just needs a **frontend regen**) + generated fixtures |
| **delegating saga bodies** (`ShipBuildSaga`/`OutpostBuildSaga`/`BattleResolutionSaga`) | **~210** | `@SagaStep(service,command)` dispatch generation (needs `@SagaTransition`/`StepKind`) |
| `CommanderApplicationService.onBattleEnded` (XP accrual) | ~15 | **`@EventHandler`** |
| **web screens** (9 components + shell) | ~2,500 | **`@View`** compositions (first slice landed; G1–G6 gate the rest) |

*(The orchestrating sagas — `ResearchSaga`/`FleetMovementSaga`/`ConstructionSaga`/… ~665 LOC — are **genuine** orchestration; only their `FlowContext`→UUID boilerplate helper is generatable.)*

---

## 4. Recommended sequence (leverage × effort)

1. **Keystone (do first):** expand the processor entity-build pass to extract the already-shipped AST (`projections`, `eventHandlers`, `rules`, `FieldMetadata.derived`/`dataType`) — stops them serializing empty; unblocks 5 levers' emitters. Pairs with **B5/`@Field.dataType`** (S) and the **U4** UI-fidelity slice.
2. **Quick wins (S):** `@Field.dataType` formatting (unblocks **U2** universal lists / **U3** forms); **regenerate Stellar's frontend** to pick up the T20 real enums (kills the string-union drift that forced the hand-written `*.model.ts` vocabulary).
3. **Medium (M):** `@EventHandler` emitter (kills hand-written reactions) · **EV1** `@DomainEvent` payload projection (empty events → field projection) · grown `@Projection` DTO emitter.
4. **Large (L):** `@View` full emitter — **G1 parameterised binding is the keystone** (unblocks the corpus's defining trait) + app-shell route assembly (turns FE-only into a runnable front) · the U2/U3/U4 FE-fidelity cluster · `@Derived`/`@Rule`.
5. **XL / cross-repo:** **T12 mesh** (the single biggest Stellar hand-code mass, ~1,230 LOC) · `@SagaTransition` state-machine bodies (needs the net-new annotation) · **EV2** event-sourcing (kernel-blocked — needs the aggregate-event-store SPI first).

**Net:** the platform is already a clean BE/FE-separable, contract-driven pipeline; FE-only/CMS page generation works today. The largest mechanical reductions are **T12 (mesh)**, the **port/adapter triad (T8 + fakes)**, and the **web vocabulary (T20 regen)** — and most of the behaviour levers are *already half-built in the SDK*, waiting on one processor-extraction expansion + their emitters.
