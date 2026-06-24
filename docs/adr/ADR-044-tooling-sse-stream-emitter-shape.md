# ADR-044: Fix the Tooling SSE Stream Emitter Shape — Two Drivers, Named-Event Client, Domain-Event-Bus Producer Seam

| Attribute       | Value                                                                                                  |
|:----------------|:-------------------------------------------------------------------------------------------------------|
| **Status**      | **ACCEPTED**                                                                                            |
| **Deciders**    | Arkadiusz Przychocki                                                                                    |
| **Date**        | 2026-06-24                                                                                              |
| **Scope**       | per-repo (`exeris-tooling`); `tooling/codegen`                                                          |
| **Owning Repo** | `exeris-tooling`                                                                                        |
| **Driven By**   | [RFC-2026-06-22](../rfc/RFC-2026-06-22%20SSE%20Stream%20Emitter%20%28tooling%29.md) (the four-axis option comparison) — ratifies its recommendation; **consumes** kernel [ADR-043](ADR-043.link.md) |
| **Compliance**  | [ADR-015](ADR-015-codegen-emission-strategy.md) (emission strategy: JavaPoet for Java, text for text, shared scaffold); hard-constraint #1 (single Exeris-kernel target), #3 (deterministic codegen), strong-default #4 (Java/TS emitter parity) |

## Context and Problem Statement

Kernel 0.10 (ADR-043, ACCEPTED 2026-06-21) landed the server-push HTTP-streaming SPI — `HttpStreamExchange` / `@FunctionalInterface HttpStreamHandler` / the implementation-blind `StreamEvent`, registered via `HttpRouter.Builder.streamRoute(method, path, handler)`. Before it, the SDK's streaming surface (`@ExerisDomain(realTimeApi)`, `@Action(streaming, streamEventType, realTimeUpdates)`) was parsed into metadata but **inert** — no kernel primitive to bind to, exactly what `-Aexeris.strict` (T11) flags. ADR-043 §5 names this repo as the build-time consumer that turns the primitive into emitted code.

RFC-2026-06-22 decomposed the emitter design into four near-independent axes — **driver granularity**, **event-producer source**, **route shape**, and **TS client shape** — and recommended shipping in two slices. Slice 1 (entity-level live-view: `@ExerisDomain(realTimeApi)` → collection `GET {base}/stream` + native `EventSource`) shipped in PR #104. During that review two things became load-bearing decisions rather than implementation details: (a) native `EventSource.onmessage` receives **only unnamed** SSE frames, so any **named** server event — the scaffold's `keep-alive`, and every future domain event — is silently dropped unless the client registers a per-name `addEventListener`; and (b) a functional handler must emit from a real producer, and ADR-043 names `@DomainEvent` / `@Projection` / `@EventSourced` as the sinks. Leaving either undecided lets the Java and TS emitters drift into one-off shapes the other must chase, and risks a parity claim that is hollow the moment EV1 emits real events.

The cost of not fixing the shape now: the kernel spent a sprint on a primitive whose only build-time consumer is this pipeline; the SDK advertises a feature that 404s or silently no-ops end-to-end; and the processor↔generator contract for "events over a stream" stays unwritten exactly as external `@DomainEvent` payloads (EV1) and per-action streaming (Slice 2, now SDK-unblocked) come into view.

**This ADR answers: what is the stable, enforceable shape of the tooling SSE stream emitter — which annotations drive it, what route each registers, how named events reach the typed TS client, and where a generated handler gets its `StreamEvent`s from — across both slices?**

## 🏁 The Decision

**Fix the emitter as: two drivers onto one handler shape (entity-level `@ExerisDomain(realTimeApi)` → collection live-view; per-action `@Action(streaming)` → action-scoped stream), both bound exclusively via `streamRoute(...)`; a NAMED-event SSE wire contract whose TS client registers one `addEventListener` per declared `@DomainEvent` (names from `DomainEventMetadata`); and a single canonical producer seam — the entity's `@DomainEvent` bus (EV1) — with a deterministic keep-alive scaffold standing in until EV1 payloads are rich.**

This ratifies RFC-2026-06-22's recommendation across all four axes and resolves the two review-surfaced forks: **named events + `addEventListener`** (not a bare `onmessage`) and **domain-event-bus tap** (not producer-agnostic). The decision governs both the shipped Slice 1 and the not-yet-built Slice 2; implementation status is orthogonal to the contract.

**Concrete obligations:**

1. **Two drivers, one handler shape, `streamRoute` only.** Entity-level `@ExerisDomain(realTimeApi=true)` emits one `*StreamHandler` per entity, registered collection-level at `GET {base}/stream`. Per-action `@Action(streaming=true)` (Slice 2) emits one stream handler per annotated action at the action route (`{base}/{id}/actions/{kebab}`), the request opening the stream. Both implement `eu.exeris.kernel.spi.http.HttpStreamHandler` and are registered via `routerBuilder.streamRoute(method, path, handler::handle)` — **never** the respond-once `route(...)`. A streaming route never resolves to an `HttpExchange`. *Testable:* the compile gate feeds a `realTimeApi` (and, at Slice 2, an `@Action(streaming)`) entity and `javac`-compiles the emitted handler against the live kernel 0.10 SPI; substring assertions pin `streamRoute(`.

2. **Named-event SSE wire contract.** A generated handler emits `StreamEvent.of(eventType, json)` where `eventType` is the `@DomainEvent` name (entity-level) or the `@Action.streamEventType` (per-action); the keep-alive uses the reserved name `keep-alive` with empty data. The TS client registers **one `addEventListener(<eventName>, …)` per declared `@DomainEvent`** (enumerated from `DomainEventMetadata`), not a bare `onmessage` for domain events; `onmessage` MAY remain only for genuinely unnamed frames. Generated JSDoc/Javadoc must state the named-vs-unnamed semantics truthfully. *Testable:* the TS spec asserts an `addEventListener` per event name; the emitted code carries the named-event note (already pinned for Slice 1).

3. **Single producer seam: the `@DomainEvent` bus (EV1).** The handler's producer is a subscription to the entity's `@DomainEvent` stream (the kernel transactional-outbox `EventEngine` the generated `*EventPublisher` already targets), projecting each event into a `StreamEvent`. Until EV1 realizes rich payloads, a **deterministic, finite keep-alive scaffold** stands in behind a clearly-marked seam (`// TODO: bind domain-event bus producer (EV1)`). `@Projection` / `@EventSourced` (EV2) are **additive future sources under this same seam**, not alternative emitter shapes. *Testable:* the handler wires the domain-event seam and no second producer path; the scaffold loop is replaced, not reshaped, when EV1 lands.

4. **Kernel-target discipline & determinism (restated, inherited).** The handler stays on the SPI: no `text/event-stream` literal, no chunk framing (Core's `SseEventEncoder` / `HttpStreamEngine` own the wire); client disconnect surfaces as an unchecked `StreamClosedException` from `emit(...)` and is **let to propagate** (never caught-and-swallowed); no heap-queue buffering (back-pressure parks the virtual thread). Output is byte-identical for identical `DomainMetadata`; the keep-alive interval is a compile-time constant, never a wall-clock read. The shipped Slice-1 default is `KEEPALIVE_INTERVAL_MILLIS = 15_000L` over 4 iterations, but the *values* are an implementation detail, not a contract constant — the EV1 seam (obligation 3) replaces the loop entirely, so a future producer is free to drop the synthetic keep-alive. *Testable:* existing determinism + compile + E2E gates.

5. **Java/TS parity is route- and event-exact.** Every Java stream handler has a route-identical TS client; every named server event has a matching client `addEventListener`. Adding a server event name without the matching client listener (or vice versa) is a contract bug. *Testable:* parity named explicitly in each PR; route derivation cross-checked (the tracked TS `streamUrl` ↔ Java `effectivePath` parity test).

6. **Auth is kernel-edge — the emitter generates none.** The stream path inherits authentication/authorization from the same kernel edge as respond-once routes (ADR-040: credential→`PrincipalContext` dispatch runs before the handler). The emitter adds **no** auth code beyond `streamRoute` registration; the TS client's `withCredentials: true` carries the cookie credential to that edge. Mid-stream JWT expiry (ADR-043 obligation 6, fail-closed) is **kernel-side**, not emitted. *Testable:* no tokens/headers/auth branching in the emitted stream handler or client beyond `withCredentials`.

## Consequences

### ✅ Positive Outcomes

- **[+] The kernel primitive gets its first real, contract-bound consumer.** `realTimeApi` stops being an inert attribute (T11) and becomes a working U7 live-view, with a shape the per-action slice extends rather than replaces.
- **[+] Named-event parity is honest and typed.** Domain events reach the client as native, typed SSE channels (`addEventListener('OrderCreated', …)`) — no silent drops, no app-side type demux — and event names are deterministic because they come from `DomainEventMetadata`.
- **[+] One producer story.** Fixing the `@DomainEvent` bus as the canonical seam means EV1 (rich payloads) and EV2 (`@EventSourced`) land **additively** under one seam; no reshaping of route or client when the producer matures.
- **[+] Both slices share one reviewable shape.** Driver granularity is the only axis that differs between Slice 1 and Slice 2; route, producer, determinism, and parity rules are identical, keeping reviewer ramp-up low.
- **[+] Auth concern is closed at the contract level.** The emitter never reasons about identity; ADR-040's kernel-edge dispatch owns it, so the stream path can't drift into app-level security logic.

### ⚠️ Trade-offs

- **[-] The named-event client couples to EV1 `DomainEventMetadata`.** Per-name `addEventListener` requires the declared event-name list at codegen. Until EV1, the entity-level client surfaces only the `keep-alive` heartbeat it ignores — the per-name listeners arrive **with** EV1. This is honest scaffolding, but it means the Slice-1 client is not yet functionally useful, only shape-complete.
- **[-] Slice 2 introduces a second client shape.** Per-action streaming opens over POST with headers, which native `EventSource` (GET-only, no custom headers) can't do; the per-action client uses RxJS over `fetch` + `ReadableStream` (RFC Axis 4b). Two client idioms (EventSource for entity-level, RxJS for per-action) coexist — justified by the transport limits, bounded by the shared route/producer rules.
- **[-] Scaffold reconnect churn.** Until EV1 replaces the loop, the entity-level handler closes after a finite keep-alive (~60s with current constants), so a browser `EventSource` auto-reconnects on that cadence. Documented in generated Javadoc; removed when the EV1 subscription lands.

### 📋 What is NOT in scope

- **WebSocket / bidirectional transport** — kernel deferred it (ADR-043); SSE-first stands.
- **Kernel-side mid-stream expiry behaviour** — the ADR-043 × ADR-040 intersection (does `HttpStreamEngine` re-validate past `expiresAt()`?) is a kernel decision, not a tooling-emitter one.
- **EV2 `@EventSourced` replay source** — additive under obligation 3's seam; not designed here.
- **The published-BOM SNAPSHOT release gate** — resolving kernel `0.10.0-SNAPSHOT` → `0.10.0` before tooling `0.6.0` ships is a release-ordering concern, not an emitter-shape decision.
- **SDK `ActionMetadata` widening** — the per-action driver's cross-repo prerequisite; landed locally (2026-06-24), tracked on the SDK side.

## Cross-references

- [ADR-043](ADR-043.link.md) (Kernel HTTP Streaming SPI) — the primitive this emitter binds to; owns the wire format and the fail-closed mid-stream-expiry obligation.
- [ADR-040](https://github.com/exeris-systems/exeris-kernel/blob/main/docs/adr/ADR-040-identity-provider-spi.md) (Identity Provider SPI) — makes stream auth a kernel-edge concern (obligation 6); **ACCEPTED** (kernel v0.10 cycle); the authoritative copy reaches `main` with the kernel 0.10 release, like ADR-043's content.
- [ADR-015](ADR-015-codegen-emission-strategy.md) (Codegen Emission Strategy) — JavaPoet/text-block/shared-scaffold rules the stream generators follow.
- [RFC-2026-06-22](../rfc/RFC-2026-06-22%20SSE%20Stream%20Emitter%20%28tooling%29.md) — the four-axis option comparison this ADR ratifies (incl. the resolved review follow-ups).
- `KernelStreamHandlerGenerator.java`, `stream-client-gen.ts` — the Slice-1 implementations of obligations 1–6.

## Engineering Protocol

1. **Compile gate (`KernelCodegenCompileTest`).** Exercises a `realTimeApi=true` entity and `javac`-compiles the emitted handler against the live kernel 0.10 SPI (no stubs for the streaming types). When Slice 2 lands, extend it with an `@Action(streaming)` entity. This encodes obligations 1 + 4.
2. **TS spec (`stream-client-gen.spec.ts`).** Pins the route parity, the determinism (byte-identical), the named-event honesty note, and — when EV1 lands — an `addEventListener` assertion per declared `@DomainEvent` (obligation 2 + 5).
3. **Determinism + E2E gates** carry obligation 4's byte-identical guarantee.
4. **Migration owner:** `exeris-tooling` (founder). Slice 1 is **compliant and shipped** (PR #104). Tracked, not yet compliant: per-action Slice 2 (driver 1b/route 3c/client 4b), the EV1 named-event listeners (obligation 2's per-name `addEventListener`), and the cross-cutting route-derivation parity test. Each lands under this ADR with no contract change.
