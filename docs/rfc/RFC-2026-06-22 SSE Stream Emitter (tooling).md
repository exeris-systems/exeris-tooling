# RFC-2026-06-22: What shape should the tooling SSE stream emitter take, now that kernel 0.10 lands the streaming primitive?

| Field             | Value                                                                                                                                  |
|:------------------|:--------------------------------------------------------------------------------------------------------------------------------------|
| **Status**        | **DRAFT**                                                                                                                              |
| **Author(s)**     | arkstack-dev                                                                                                                           |
| **Date Opened**   | 2026-06-22                                                                                                                             |
| **Date Closed**   | —                                                                                                                                      |
| **Target ADR(s)** | TBD — one tooling ADR fixing the stream-emitter shape (processor↔generator contract + Java/TS parity); reserve in `exeris-docs/adr-index.md` once ACCEPTED. Cross-repo `.link.md` for ADR-043 already landed (`docs/adr/ADR-043.link.md`). |
| **Affected Repos**| `exeris-tooling` (processor + `exeris-codegen-java` + `exeris-codegen-ts`); `exeris-sdk` (per-action driver dependency); `exeris-kernel` (ADR-043, owns the SPI) |
| **Reviewers**     | —                                                                                                                                      |

## Question

Kernel 0.10 (`0.10.0-SNAPSHOT`, ADR-043 ACCEPTED 2026-06-21) introduces the server-push primitive the SDK has long advertised but could not bind to: a sibling `HttpStreamExchange`, an `@FunctionalInterface HttpStreamHandler`, an implementation-blind `StreamEvent` record, and a typed `HttpRouter.Builder.streamRoute(method, path, handler)` registration. **What shape should the tooling stream emitter take — what drives generation (entity-level `@ExerisDomain(realTimeApi)` vs per-action `@Action(streaming)`), where does a generated handler get its `StreamEvent`s from, what route does it register, and what is the parity TS client — such that the first slice ships without breaking determinism (hard-constraint #3), Java/TS parity (strong-default #4), or kernel-target discipline (hard-constraint #1)?**

## Context

The build-time pipeline turns `@ExerisDomain` user code into kernel-target Java (handlers, services, repositories, Application/RuntimeLifecycle wiring) and a parity Angular/TS app. Until now the SDK streaming attributes — `@Action(streaming/streamEventType/realTimeUpdates)`, `@ExerisDomain(realTimeApi)` — were documented *inert* ("server-push is not wired in Open-Core … inert until that affordance lands"). Kernel 0.10 lands the affordance. ADR-043 §Context names this repo by name: tooling "can emit a Java stream handler + a TS `EventSource`/RxJS client (Java/TS parity)," and §5 tracks the SDK/tooling stream emitters as their own follow-ups. ROADMAP **U7 / K2** ("Live-view … blocked on kernel K2") is the backlog handle.

The cost of leaving this unanswered: kernel just spent a sprint building a primitive whose *only* build-time consumer is this pipeline; the SDK surface advertises a feature that 404s end-to-end. The window to keep Java/TS parity cheap is now, before either side emits a one-off shape the other has to chase.

The question has a small enumerable answer set because the design splits along four near-independent axes — **driver granularity**, **event-producer source**, **route shape**, and **TS client shape** — each with 2–3 options and a different blast radius against the pipeline contract. They are separable, so this RFC recommends per-axis rather than forcing one monolithic package.

## Investigation

### Validated dependency state (2026-06-22)

- **Kernel** — ✅ ready. `0.10.0-SNAPSHOT` installed in `~/.m2` (built today); SPI `HttpStreamHandler` / `HttpStreamExchange` / `StreamEvent` / `StreamClosedException`, Core `SseEventEncoder` / `HttpStreamEngine` / `HttpRouter.streamRoute`, and `AbstractHttpStreamExchangeTck` all present (verified `unzip -l`).
- **SDK annotations** — ✅ `@Action(streaming, streamEventType, realTimeUpdates)`, `@ExerisDomain(realTimeApi)` exist.
- **SDK source-model** — ✅ **resolved (2026-06-24, locally).** Originally a gap: `ActionMetadata` carried no streaming fields (`async`/`httpMethod`/`resultType`/… but no `streaming`), so the **per-action** driver could not reach `DomainMetadata`. The SDK has since widened `ActionMetadata` with the streaming fields (present in the locally-installed SDK), so the **per-action** Slice 2 is now **unblocked on the SDK side** — what remains is tooling-side: processor extraction into `DomainMetadata` + the per-action generator branch. The **entity-level** `@ExerisDomain.realTimeApi` was already plumbed (`DomainMetadata.realTimeApi`, `ExerisDomainProcessor.java:606`) and is what Slice 1 consumes.
- **Tooling** — extracts `realTimeApi`; **no generator consumes it** → today it is exactly the kind of inert attribute `-Aexeris.strict` (T11) flags.

### The SPI contract that constrains the emitter

A generated handler implements `void handle(HttpStreamExchange ex)` and runs an imperative emit loop: `ex.emit(StreamEvent.of(type, json))` per update, `ex.close()` at end-of-stream; client disconnect surfaces as an unchecked `StreamClosedException` that the loop simply lets propagate (engine runs teardown). Backpressure parks the VT inside `emit` — the handler must **not** buffer to a heap queue. The handler is a plain Java object (constructor DI, no Spring). Route registration is `routerBuilder.streamRoute(method, path, handler)`, distinct from the respond-once `route(...)`. **Kernel-target discipline:** the handler stays on the SPI — no `text/event-stream` literal, no chunk framing (Core owns those).

### Axis 1 — Driver granularity

- **(1a) Entity-level `@ExerisDomain(realTimeApi)`** — one live-view stream per aggregate (e.g. `GET {base}/stream`). **Unblocked today** (metadata present). Natural fit for U7 live-view.
- **(1b) Per-action `@Action(streaming=true)`** — a streaming endpoint per annotated action, `streamEventType` as the SSE `event:` name. **SDK blocker now cleared** (`ActionMetadata` widened, 2026-06-24); remaining work is tooling-side (processor extraction + per-action generator branch).
- **(1c) Both** — eventually the target; ordering forced by the SDK gap.

### Axis 2 — Event-producer source (the load-bearing open question)

A handler must emit from *something*. Options:
- **(2a) Domain-event bus tap** — subscribe to the entity's `@DomainEvent`s (the kernel transactional-outbox `EventEngine` the `*EventPublisher` already targets, EV1) and project each into a `StreamEvent`. Buildable now as a scaffold; *fully* meaningful once EV1 realizes payloads (today `EventPayload.empty()`).
- **(2b) `@Projection` / `@EventSourced` replay** — ADR-043 names these the "natural sink." Richer, but `@EventSourced` is **EV2-blocked** (no kernel aggregate-event-store SPI).
- **(2c) Scaffold-only** — emit the handler + route + a `// TODO: bind producer` heartbeat/keep-alive, deterministic and compiling, deferring the real source. Honest given EV1/EV2.

### Axis 3 — Route shape

- **(3a) `GET {base}/{id}/stream`** — per-instance live view (mirrors the action route `{base}/{id}/actions/{kebab}`; reuses the `NameCasing` shared by T1).
- **(3b) `GET {base}/stream`** — collection-level stream.
- **(3c) Per-action `POST {base}/{id}/actions/{kebab}` resolving to a stream handler** when `@Action(streaming)` — for 1b; the request opens the stream.

### Axis 4 — TS parity client (strong-default #4)

- **(4a) Native `EventSource`** — smallest, idiomatic SSE; but `EventSource` is GET-only and sends no custom headers (auth via cookie/query) — fine for 3a/3b, awkward for 3c POST-open.
- **(4b) RxJS `Observable<StreamEvent>` over `fetch`+`ReadableStream`** — supports POST-open and headers; matches the v22 emitter's RxJS idiom; more code. ADR-043 says "`EventSource`/RxJS" — both are sanctioned.

### Constraint cross-check

- **Determinism (#3):** all emission is metadata-driven, sorted; no timestamps/UUIDs in output. The keep-alive interval (2c) must be a fixed constant, not wall-clock.
- **Parity (#4):** every Java stream handler needs a TS client for the same route; named in the PR.
- **Kernel-target (#1):** SSE-over-X stays a single Exeris-kernel target; no second backend. No `text/event-stream` literal escapes Core.
- **Strict mode (T11):** once a generator consumes `realTimeApi`, it leaves the inert set automatically (it was never registered there); when per-action lands, ensure `@Action.streaming/streamEventType/realTimeUpdates` are not falsely flagged.

## Recommendation

Ship in **two slices**, recommending per axis:

**Slice 1 (LANDED — entity-level live-view scaffold).** Implemented and behind the gates below (`feat/0.6.0-streaming-slice1-realtimeapi`, off `main`).
- Axis 1 → **1a** (`@ExerisDomain(realTimeApi)`; already in `DomainMetadata`).
- Axis 2 → **2a wired as 2c**: emit a handler that taps the entity's domain-event bus, with a deterministic keep-alive so it compiles and runs end-to-end before EV1 payloads are rich; a clear seam for the real projection.
- Axis 3 → **3b** collection-level `GET {base}/stream` first (simplest; per-instance 3a is an additive follow-up).
- Axis 4 → **4a** native `EventSource` (GET route, no custom headers needed).
- Gate (green): the generated handler compiles against kernel `0.10.0-SNAPSHOT` via `KernelCodegenCompileTest` (tooling kernel pin bumped to 0.10), and the TS client builds under the 0.6.0 `ng build`/`tsc --noEmit` round-trip (T20 gate) + vitest.

**Slice 2 (follow-up — now SDK-unblocked) — per-action streaming.** The SDK `ActionMetadata` widening landed locally (2026-06-24), so the cross-repo blocker (T1↔#58 twin) is cleared; remaining work is tooling-side only: extract the per-action streaming fields in the processor and add the per-action generator branch. Axis 1→1b, Axis 3→3c, Axis 4→4b (RxJS, for POST-open + headers).

**Producer realization** (rich payloads / `@Projection` / `@EventSourced`) tracks EV1/EV2 — Slice 1's seam is built to receive it without reshaping the route or client.

Rationale: 1a+2c+3b+4a is the **largest slice that is unblocked, deterministic, parity-complete, and kernel-target-pure today**, and it is forward-compatible with every Slice-2 / EV choice. It turns the now-inert `realTimeApi` into a working U7 live-view and gives the kernel primitive its first real consumer.

## Open questions for review

Slice 1 settled the determinism-bounded ones with conservative defaults (recorded here so the follow-up ADR can ratify or revise them); the auth question stays genuinely open.

1. **[Slice-1 default]** Keep-alive interval = fixed `15_000L` ms over a finite iteration count (deterministic constant, no wall-clock, no heap queue). Confirm the interval and whether the live producer (EV1) should drop the synthetic keep-alive once real events flow.
2. **[OPEN]** Auth on the held-open stream — ADR-043 obligation 6 (fail-closed on JWT expiry) is kernel-side; does the emitted route need anything beyond the existing `@RowLevelSecurity`/permission wiring? `EventSource` sends no custom headers, so auth rides the cookie/session — confirm this is acceptable for the held-open case.
3. **[Slice-1 default]** Collection-level **3b** (`GET {base}/stream`) shipped; per-instance **3a** is an additive follow-up — confirm we don't want 3a in the first cut.
4. **[Slice-1 default]** Native `EventSource` **4a** shipped for the GET route; RxJS **4b** is reserved for Slice 2's POST-open + headers. Confirm 4a for entity-level live-view despite the v22 emitter's RxJS-heavy idiom.

## Next action

Slice 1 has **landed** behind the two gates above (`feat/0.6.0-streaming-slice1-realtimeapi` → PR against `main`), under this DRAFT per the founder's go-ahead (2026-06-22). On **ACCEPT**: reserve the tooling ADR number in `exeris-docs/adr-index.md` and author the emitter-shape ADR (it fixes the now-shipped Slice-1 shape as the contract and scopes Slice 2). Slice 2 (per-action) is now tooling-only — the SDK `ActionMetadata` blocker cleared (2026-06-24); sequence it after the ADR lands.
