---
title: "ADR-044 — Fix the Tooling SSE Stream Emitter Shape"
type: adr
visibility: public
owning-repo: exeris-tooling
status: active
slug: adr/ADR-044
---

# ADR-044: Fix the Tooling SSE Stream Emitter Shape — Two Drivers, Named-Event Client, Domain-Event-Bus Producer Seam

| Attribute       | Value                                                                                                  |
|:----------------|:-------------------------------------------------------------------------------------------------------|
| **Status**      | **ACCEPTED** · amended 2026-10-04 (Amendment 1 — no stream handler for a tenant-partitioned entity) · amended 2026-10-08 (Amendment 2 — the per-action driver runs its action; stream routes isolated by the row they serve) |
| **Deciders**    | Arkadiusz Przychocki                                                                                    |
| **Date**        | 2026-06-24                                                                                              |
| **Scope**       | per-repo (`exeris-tooling`); `tooling/codegen`                                                          |
| **Owning Repo** | `exeris-tooling`                                                                                        |
| **Driven By**   | [RFC-2026-06-22](../rfc/RFC-2026-06-22-sse-stream-emitter-tooling.md) (the four-axis option comparison) — ratifies its recommendation; **consumes** kernel [ADR-043](ADR-043.link.md) |
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

1. **Two drivers, one handler shape, `streamRoute` only.** Entity-level `@ExerisDomain(realTimeApi=true)` emits one `*StreamHandler` per entity, registered collection-level at `GET {base}/stream`. Per-action `@Action(streaming=true)` (Slice 2) emits one stream handler per annotated action at the action route (`{base}/{id}/actions/{kebab}`), the request opening the stream. Both implement `eu.exeris.kernel.spi.http.HttpStreamHandler` and are registered via `routerBuilder.streamRoute(method, path, handler::handle)` — **never** the respond-once `route(...)`. A streaming route never resolves to an `HttpExchange`. *Testable:* the compile gate feeds a `realTimeApi` (and, at Slice 2, an `@Action(streaming)`) entity and `javac`-compiles the emitted handler against the live kernel SPI; substring assertions pin `streamRoute(`.

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
- [RFC-2026-06-22](../rfc/RFC-2026-06-22-sse-stream-emitter-tooling.md) — the four-axis option comparison this ADR ratifies (incl. the resolved review follow-ups).
- `KernelStreamHandlerGenerator.java`, `stream-client-gen.ts` — the Slice-1 implementations of obligations 1–6.

## Engineering Protocol

1. **Compile gate (`KernelCodegenCompileTest`).** Exercises a `realTimeApi=true` entity and `javac`-compiles the emitted handler against the live kernel SPI (no stubs for the streaming types). When Slice 2 lands, extend it with an `@Action(streaming)` entity. This encodes obligations 1 + 4.
2. **TS spec (`stream-client-gen.spec.ts`).** Pins the route parity, the determinism (byte-identical), the named-event honesty note, and — when EV1 lands — an `addEventListener` assertion per declared `@DomainEvent` (obligation 2 + 5).
3. **Determinism + E2E gates** carry obligation 4's byte-identical guarantee.
4. **Migration owner:** `exeris-tooling` (founder). Slice 1 is **compliant and shipped** (PR #104). Tracked, not yet compliant: per-action Slice 2 (driver 1b/route 3c/client 4b), the EV1 named-event listeners (obligation 2's per-name `addEventListener`), and the cross-cutting route-derivation parity test. Each lands under this ADR with no contract change.

## Amendment 1 — No stream handler for a tenant-partitioned entity (2026-10-04)

- **Amends:** obligation 1, for the entity-level driver only. Obligations 2–6 and the Engineering
  Protocol are unchanged.
- **Decided by:** the founder, 2026-10-04. Target exeris-tooling 0.9.0, ROADMAP item T59.

The entity-level producer subscribes to the entity's `@DomainEvent` stream with no filter, and the
kernel's `EventDescriptor` carries no tenant or isolation key, so a handler cannot tell whose event it
is about to send. Driven against the kernel testkit's event engine, one event published under tenant
A of a `TENANT` entity reached a stream opened under tenant B. Obligation 6 places authentication at
the kernel edge; the edge authenticates the caller, but it does not partition the event feed.

1. **`@ExerisDomain(realTimeApi = true)` on a tenant-partitioned entity is a processor error**
   (`EXT-PROC-1014`, ADR-095). Tenant-partitioned means any tier other than `GLOBAL` (ADR-059 link
   stub), including the deprecated `tenantScoped = true`. Obligation 1's entity-level handler is
   emitted for `GLOBAL` entities only.
2. **The per-action driver is not refused.** A `streaming = true` action's handler sends keep-alive
   frames and reads nothing (`EXT-PROC-1107`), so it has nothing to leak. The driver that makes it
   read a row must carry the tenant guard and load the row under row-level security before it opens
   the stream.
3. **Metadata that reaches `exeris:generate` without passing the processor is not checked.** The
   refusal is a processor diagnostic; the generators do not repeat it.

**Reversed by:** the kernel carrying an isolation key on stream events (or a per-subscriber filter),
and the emitted handler filtering on it. That change re-admits tenant-partitioned entities and is
planned with the 0.10.0 amendment that also fixes the per-action driver.

## Amendment 2 — The per-action driver runs its action; stream routes are isolated by the row they serve (2026-10-08)

- **Amends:** obligation 1 (the per-action driver runs the action; a `GET` spectate route joins
  the two drivers), obligation 2 (the frame vocabulary of a per-action stream, and the reserved
  frame names), obligation 4 (the hand-off queue) and Amendment 1 item 2 (the guard and the
  row-level-security load are decided here). Amendment 1 item 1, the `EXT-PROC-1014` refusal, is
  unchanged. Obligations 3, 5 and 6 are unchanged.
- **Decided by:** the founder, 2026-10-08. Target exeris-tooling 0.10.0: ROADMAP items EV1-stream
  and T59, waves J4 and S4 of `docs/0.10.0-release-plan.md`, which lists the eight questions this
  amendment answers.

### Facts the decisions rest on

Read from the sources this amendment changes, and from kernel `main`:

- `KernelHandlerGenerator` serves no `@Action(streaming = true)` action, and
  `KernelActionStreamHandlerGenerator` emits `KernelStreamScaffold.keepAliveScaffold(...)`, so a
  call to a streaming action changes nothing in the domain. The processor says so on every such
  action (`EXT-PROC-1107`).
- The events an action triggers are known at generation time:
  `KernelHandlerGenerator.triggered(metadata, ACTION, actionName)` selects the `@DomainEvent`s whose
  trigger is `ACTION` and whose `actionName` names the action. The completion rule needs no SDK
  widening.
- Every emitted publish call passes the aggregate's id as the event's stream id: `saved.getId()`
  on create, the path `id` on update, delete and action. The generated publisher encodes it into
  `EventDescriptor.streamIdHigh` / `streamIdLow`.
- The kernel's `EventDescriptor` carries the event id, the stream id, the type ordinal, the flags and a
  timestamp. It carries no tenant or isolation key and no correlation id (exeris-kernel#600, open).
- `HttpStreamExchange` documents that the engine has written the SSE response head (`200`) before
  `HttpStreamHandler.handle` runs. A stream handler therefore cannot answer with a status: every
  failure it meets is after the head.
- The Community stream dispatch binds the allocator and the request-body decoder registry for the
  stream's life, and no request-scoped persistence session (ADR-077). The emitted repository runs
  each call through `TransactionalExecutor` (`query` for a read, `executeManaged` for a write), so
  a stream handler's read or write takes a connection for that call and returns it.
- `PRINCIPAL_CONTEXT` and `STORAGE_CONTEXT` are bound by the kernel's `SecurityInterceptor`, which
  the dispatcher runs only for a route whose `HttpRoutePolicy` requirement is not `permitAll()`.
  On a stream route the binding holds for the stream's whole life.

### Decisions

1. **The per-action stream runs its action, then closes when the action's events have arrived.**
   `POST {base}/{id}/actions/{kebab}` (obligation 1's per-action route, unchanged) does, in order:
   the tenant guard (decision 6); the path `id`; the `@ActionParam` body, when the action has one;
   `service.findById(id)` under row-level security; a subscription to each `@DomainEvent` in the
   action's `ACTION`-triggered set, filtered on stream id (decision 6); the action itself, as the
   respond-once action route runs it: invoke the entity method, persist through `service.update`,
   publish after the commit (ADR-075); the result frame (decision 3). The subscription is in place
   before the action runs, so no event the action publishes can precede it. The stream closes once
   every event of the `ACTION`-triggered set has been received for this stream id, or when a
   deadline expires. The deadline is a compile-time constant measured on the monotonic clock; as
   with obligation 4's keep-alive constants, its value is an implementation detail, not a contract
   constant. An action whose set is empty subscribes to nothing and closes after the result frame.
   The per-action stream does not stay open to watch the row: open-ended watching is decision 7's
   route.

2. **A failure after the head is a reserved error frame, then `close()`.** The frame is named
   `stream-error`. Its data is a JSON problem object (RFC 9457 member names) whose `status` is the
   status the respond-once action route answers for the same failure, the statuses ADR-076 fixes
   and ADR-079's emitted OpenAPI declares: `500` for the tenant guard, `400` for a malformed `id`,
   the ADR-036 status for a body that does not decode, `404` for a row that is absent or invisible
   under row-level security, `409` for a version conflict on a versioned entity, `500` for anything
   else. Because the head precedes `handle` (see the facts), this frame carries every refusal of a
   stream route, including the ones a respond-once route answers before it writes anything.
   `stream-error` and `keep-alive` are reserved frame names: the processor refuses a `@DomainEvent`
   whose name is a reserved frame name, with an `EXT-PROC` identifier allocated in the
   implementation pull request (ADR-095).

3. **Event frames carry the `@DomainEvent` name; `streamEventType` names one result frame.** Each
   event the per-action stream forwards is a frame named by its `@DomainEvent` name, the wire name
   the entity-level producer uses (obligation 2). `@Action.streamEventType`, or the action's name
   when it is blank as in the shipped handler, names exactly one frame per stream: the result
   frame, whose data is the action's return value. The contract fixes no order between the result
   frame and the event frames; the end of the stream is the completion signal.

4. **The stream id bounds interleaving to one aggregate, where it is documented.** The stream-id
   filter removes the events of every other aggregate. Two concurrent invocations on the same
   aggregate publish under the same stream id, and `EventDescriptor` carries no correlation id, so
   each stream can receive the other's event frames, and an event of the other invocation can
   satisfy decision 1's completion count. The generated Javadoc and the TS client's documentation
   state this. It holds until the kernel carries a correlation id on the descriptor.

5. **Obligation 4's queue rule is replaced.** "No heap-queue buffering" becomes: a hand-off queue
   between the bus dispatch thread and the stream's thread is permitted if it is bounded, drops on
   full, has a compile-time capacity, and reports each drop. This is the shape the entity-level
   producer ships (`KernelStreamScaffold.STREAM_BUFFER_CAPACITY`, `offer` with a logged drop). The
   intent stands: a slow consumer costs no memory that grows with the backlog, and back pressure on
   `emit` still parks the stream's virtual thread. A dropped frame of the `ACTION`-triggered set
   leaves decision 1's count incomplete, so that stream closes at the deadline.

6. **Tenant isolation is decided per driver.**
   - **Per-action, and decision 7's route: decided, no kernel key needed.** The handler carries the
     T41/T45 tenant guard: on a tenant-partitioned entity, an unbound `STORAGE_CONTEXT` is refused
     (decision 2's frame, `500`). It loads the row with `findById` under row-level security before
     it subscribes or emits a frame; a row the caller cannot read is a `404` frame. It then forwards
     only bus events whose descriptor stream id equals the row's id. The emitted publishers write
     the aggregate id as the stream id (see the facts), so every forwarded event is about a row the
     caller was allowed to read. The per-action stream clients of tenant-partitioned entities can
     then ship (wave S4).
   - **Entity-level: waits for exeris-kernel#600.** A live view of the collection has no row to
     load and no stream id to filter on; it needs an isolation key on the event. `EXT-PROC-1014`
     stays.
   - **Rejected: a row-level-security lookup per event** for the entity-level feed. It costs a
     database read per forwarded event; it cannot verify a `DELETE` event, whose row is gone; and on
     a stream route, which ADR-077 runs with no request session, each lookup takes a pooled
     connection of its own, once per event for as long as the feed runs.

7. **`GET {base}/{id}/stream` is the spectate route.** An open-ended stream of one row's events:
   it subscribes to the entity's `@DomainEvent`s, forwards those whose stream id equals `{id}`, and
   runs until the client disconnects. It is guarded as decision 6 describes (tenant guard,
   `findById` under row-level security, stream-id filter) and refuses through decision 2's frame.
   It is registered with `streamRoute(GET, …)`, described in the emitted OpenAPI, and any file it
   adds carries a row in the generator catalogue (ADR-097). Its TS client's transport follows the
   ADR that T53 produces: native `EventSource` cannot send a bearer header, so the transport
   depends on how a generated client presents its credential.

8. **No default `/api/v1/events/stream`.** The emitted `EventBusService` (`event-gen.ts`) defaults
   to an endpoint that no generated server route serves. The consumer supplies the endpoint, or
   `EventBusService` is not emitted. No server-side fan-in route is generated: it would be a feed
   across every entity and every tenant.

### Left open

Each is decided in, or before, the implementation pull request named beside it:

- The result frame of a `void` action, and how the generators learn the return type: the SDK's
  `ActionMetadata.resultType` carrier exists, but neither the processor nor the SDK's
  `SourceModelReader` fills it, and ADR-042 requires both halves (J4-1).
- Whether a `streamEventType` equal to a reserved frame name, or to the name of an event the
  action triggers, is refused as a colliding `@DomainEvent` name is (J4-1).
- Whether the event types `EventSource` itself dispatches (`message`, `open`, `error`) join the
  reserved set for the clients built on it (J4-2).
- Which declaration emits the spectate route: `realTimeApi = true`, which `EXT-PROC-1014` refuses
  on a tenant-partitioned entity, or a streaming action on the entity (J4-2).

### Consequences

- `EXT-PROC-1107` is retired when J4-1 lands; under ADR-095 a retired identifier is never allocated
  again.
- `STORAGE_CONTEXT` is bound only on a route whose requirement is not `permitAll()`, and the
  emitted application binds no route policy until T53 lands. Until then the tenant guard refuses
  every per-action and spectate stream of a tenant-partitioned entity: the guard is correct before
  T53, and the routes become usable with it.
- Row visibility is checked once, when the stream opens. A row whose visibility changes while a
  spectate stream is open, through a revoked shared scope or a delete, keeps streaming its events
  until the client disconnects.
- Between J4-1 and S4 the Java pipeline emits guarded per-action handlers for tenant-partitioned
  entities while `exeris-codegen-ts` withholds their clients (`hasActionStreamClients`). The gap is
  sequenced, not a parity exception; S4 closes it.
- Amendment 1's "Reversed by" clause named this amendment as the place the reversal would be
  planned. This amendment does not reverse it. If exeris-kernel#600 lands, an Amendment 3 reverses
  Amendment 1 for the entity-level driver: the handler filters on the isolation key and
  `EXT-PROC-1014` is retired.

### Implementation order

1. **J4-1:** the per-action driver (decision 1), the tenant guard, the row-level-security load and
   the stream-id filter (decision 6), the error and result frames (decisions 2 and 3), the
   reserved-name refusal, and the bounded queue (decision 5). Retires `EXT-PROC-1107`.
2. **J4-2:** the `GET {base}/{id}/stream` route (decision 7), its OpenAPI description, its
   generator-catalogue row (ADR-097), and its row in `contract/stream-routes.json`
   (`exeris-e2e-tests`).
3. **S4:** the TS per-action stream clients for tenant-partitioned entities, the spectate client,
   `contract/stream-routes.json` as the shape both builds read, and decision 8.

### Engineering Protocol additions

- The compile gate (`KernelCodegenCompileTest`) compiles a tenant-partitioned entity with a
  streaming action that triggers at least one `@DomainEvent`, and the spectate route.
- A test driven against the kernel testkit's event engine opens streams on two rows of one
  tenant-partitioned entity and asserts that each receives only its own row's events: Amendment 1's
  measurement, inverted.
- `StreamRouteParityE2ETest` and `stream-route-parity.spec.ts` gain the spectate row.
- The determinism check covers the new handler bodies; the deadline and the queue capacity are
  emitted as constants.
