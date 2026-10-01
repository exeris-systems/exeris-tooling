---
title: "ADR-070 — Open the generated composition root: `RuntimeComponents`"
type: adr
visibility: public
owning-repo: exeris-tooling
status: active
slug: adr/ADR-070
---

# ADR-070 — Open the generated composition root: `RuntimeComponents`

- **Status:** ACCEPTED (2026-08-18) · amended 2026-09-26 (Amendment 1 — obligation 6; Amendment 2 — edge router, subscribers and flows, scope lists) · amended 2026-10-01 (Amendment 3 — StreamRouteResolver, obligations 4–6)
- **Repo:** `exeris-tooling`
- **Scope:** tooling / codegen pipeline
- **Visibility:** public
- **Milestone:** 0.8.0 (backlog item **T49**)
- **Supersedes / superseded by:** —

## Context

The pipeline emitted two bootstrap files. `RuntimeLifecycle` constructed every component with an
inline `new`:

```java
OrderRepository orderRepository = new OrderRepository(transactionalExecutor);
OrderService orderService = new OrderService(orderRepository);
OrderHandler orderHandler = new OrderHandler(orderService);
```

and `Application` exposed three `protected` hooks — `subsystems()`, `transactionalExecutor()`,
`capManifest()` — all of which configure *infrastructure*. Nothing in either file admitted
application logic.

The consequence is sharper than "no extension point", because the rest of the pipeline is already
built for extension and this was the one missing link. ADR-058 fixed the emitted service shape as
`public`, non-final, with assignment-only constructors, precisely so a consumer can write
`MyOrderService extends OrderService` — and then there was nowhere to install it. The emitted class
was extensible by contract and unreachable in practice.

The second half is construction rather than substitution. A real application does not only replace
generated objects; it builds its own out of them. The reference is the community benchmark app this
generator was modelled on, which hand-writes exactly that inside the boot callback
(`exeris-benchmarks/targets/exeris-community-app/…/CommunityBenchmarkRuntimeLifecycle.java:74-78`):

```java
FlowEngine flowEngine = KernelProviders.flowEngine();
EventEngine eventEngine = KernelProviders.eventEngine();
DomainEventPublisher domainEventPublisher = new DomainEventPublisher(eventEngine);
OrderSagaOrchestrator orchestrator =
        new OrderSagaOrchestrator(flowEngine, orderRepository, domainEventPublisher, transactionalExecutor);
```

Note what that needs: a kernel engine resolved from a bound `ScopedValue`, **and** a generated
repository. A seam that only lets you swap implementations cannot express it; a seam outside the
boot callback cannot resolve the engines. And the collaborator built there is useless without a
route to reach it, so route registration is part of the same problem, not a separate one.

This is why T48 (emitted `*EventPublisher` classes that nothing ever invokes) and T50 (no declared
runtime driver) sit downstream: both are wiring defects, and there was no place to put wiring.

The question this ADR answers: **where does application-authored construction enter a generated
application, given that generated files are regenerated and must not be edited?**

## 🏁 The Decision

**Emit a third bootstrap file, `RuntimeComponents`, that owns the construction of every generated
component behind an overridable factory per component, and have `Application` expose it as the
installation point.**

`RuntimeLifecycle` stops calling `new` on generated types entirely. It asks `RuntimeComponents` for
the handlers it routes to, and offers the same `HttpRouter.Builder` to a `configureRoutes` hook
after every generated route is registered.

**Concrete obligations:**

1. **Three members per component.** Each generated `*Repository`, `*Service`, `*Handler` and SSE
   `*StreamHandler` gets a `private` field, a `public` memoising accessor, and a
   `protected create*()` factory holding the default construction. A new emitted component type is
   added to this seam in the same change that introduces it. *(Amendment 2, 2026-09-26: subscribers
   and saga flows join the seam, and a value a component needs later is captured here, never read on
   a request or stream thread.)*
2. **Defaults resolve dependencies through the accessor, never a field or a local.**
   `createOrderService()` emits `new OrderService(orderRepository())`. This is what makes a single
   override propagate: replace the repository and the untouched service factory picks it up.
3. **`RuntimeComponents` is not `final`, `create*` is `protected`, accessors are `public`.**
   `RuntimeLifecycle` stays `final` — it is a driver, not an extension point.
4. **`Application#components(TransactionalExecutor)` is the installation point**, and the boot
   callback threads it: `new RuntimeLifecycle(handlerSlot, components(transactionalExecutor())).run()`.
   Being inside `KernelBootstrap.boot(...)` is load-bearing — it is what lets a factory body call
   `KernelProviders.flowEngine()` / `eventEngine()`. *(Amendment 2, 2026-09-26: the callback now
   threads a second slot, and the kernel holds a router built before boot — see Amendment 2.)*
5. **`configureRoutes(HttpRouter.Builder)` runs after every generated route and before `build()`.**
   A hand-written route can add to the table; it can never silently displace a generated one.
   Enforced by an ordering assertion, not by convention. *(Amendment 2, 2026-09-26: respond-once
   routes only — a `streamRoute` registered here does not resolve.)*
6. **`decorate(HttpRouter)` runs between `build()` and the handler slot.** *(Added 0.9.0 — the
   T49 residual.)* Whatever it returns is what the kernel serves; the default returns the router
   unchanged. It is the sibling of obligation 5 — same object, one line later — and exists because
   a per-request concern the generated code does not own (a tenant binding, a decoder registry, an
   allocator) otherwise forces a consumer to reimplement `Application#run()`, once per application.

   **A wrapper and a stream route are mutually exclusive, and the emitted app enforces it.** The
   kernel resolves a streaming route only when the bound handler *is* an `HttpRouter`
   (`handler instanceof HttpRouter`). Any wrapper erases that type, so every `streamRoute` would
   register and then never match — silently, since registration succeeds either way, which is why
   this bug class needed a real boot to find the first time. An application that emits a stream
   route therefore **refuses to boot** when `decorate` returns a non-`HttpRouter`, naming both
   halves; an application that emits none carries no guard. This is a kernel constraint rather than
   a tooling choice: a stream resolved through an interface a decorator could delegate would remove
   the trade-off, and that is the standing upstream ask. *(Amendment 1, 2026-09-26: the converse
   does not hold — an undecorated generated app serves no stream route either; T23.)* *(Amendment 2,
   2026-09-26: the refusal is removed. Streams resolve on the pre-boot edge router, outside the
   wrapper, so a wrapper and a stream route no longer exclude each other.)*
7. **The emitted `main()` says that it is not polymorphic.** `main` does `new Application().run()`,
   so a subclass overriding `components(...)` is *not* reached through it. The emitted javadoc states
   this and shows the subclass's own `main`. An extension hook whose obvious entry point silently
   ignores it is the failure class this repo keeps paying for; it gets named in the output.

## Consequences

### ✅ Positive Outcomes

- **[+] The extensible service shape becomes usable.** ADR-058's `public` non-final service with an
  assignment-only constructor now has a documented way in.
- **[+] Construction, not just configuration.** A consumer builds a saga orchestrator or an event
  publisher from generated parts plus bound kernel engines, and routes to it — inside generated
  code's own lifecycle, without editing a generated file.
- **[+] T48 becomes addressable by a consumer today.** Overriding `createOrderService()` to return a
  publishing subclass built with `new OrderEventPublisher(KernelProviders.eventEngine())` closes the
  `@Action` → `@DomainEvent` → saga chain in user code, ahead of the pipeline doing it.
- **[+] One place to look.** Every `new` on a generated type now lives in one file, which is also
  where the `.domain`-package derivation is validated.

### ⚠️ Trade-offs

- **[-] A third file, and it is the largest of the three.** Three members per component times four
  component kinds per entity. It is generated, so the cost is diff size and review noise on a
  committed L1 tree, not maintenance — but a 23-aggregate domain gets a long file.
- **[-] `RuntimeLifecycle`'s constructor signature changed.** Anything hand-written that constructed
  it directly must pass a `RuntimeComponents`. Regenerated code needs no action; a hand-rolled
  launcher does. Recorded in `MIGRATION-0.x-to-1.0.md`.
- **[-] Lazy construction where it used to be eager.** Accessors memoise without synchronisation.
  This is safe because composition runs single-threaded on the boot thread before the handler slot
  is set — no request can observe a half-built graph — but it is an invariant the emitted javadoc
  has to carry rather than a property the code enforces.
- **[-] The seam is virtual-dispatch, not a container.** No scanning, no annotations, no reflection.
  A consumer wanting constructor injection by type will find this deliberately plain.

### 📋 What is NOT in scope

- **Actually wiring the publishers (T48).** This ADR builds the place; it does not fill it.
- **Declaring the runtime driver (T50).** Still a consumer-build requirement with no emitted
  declaration.
- **A TS counterpart.** Emitter parity (hard constraint 5) governs *metadata visibility*, and this
  change consumes no new metadata — it is an emission-shape change on the Java side. The Angular app
  already has a composition root with override semantics (Angular DI providers), so a parallel
  `RuntimeComponents` there would duplicate the framework.
- **Making `main()` polymorphic.** Resolving an application class by system property or service
  loader was rejected: it trades a compile-time error for a runtime one. The subclass writes four
  lines of `main`.

## Cross-references

- ADR-015 (Codegen emission strategy) — JavaPoet for Java emission; this generator stays compliant.
- ADR-058 (Generated-test emission channel) — fixes the `public`/non-final/assignment-only shape of
  emitted services that this ADR makes installable.
- ADR-024 / GC2 boot-conductor call site — the composed variant threads the seam identically;
  `RuntimeComponents` is byte-identical with and without a composition.
- `ROADMAP.md` — T49, and T48 / T50 downstream of it.
- `exeris-benchmarks/targets/exeris-community-app/…/CommunityBenchmarkRuntimeLifecycle.java` — the
  hand-written composition root this seam is shaped to admit.

## Engineering Protocol

1. `KernelApplicationGeneratorTest` asserts the three-member shape, accessor-routed defaults, the
   `configureRoutes` ordering (hook after the last generated route, before `build()`), that the
   lifecycle constructs no generated type, and that composition leaves `RuntimeComponents`
   byte-identical.
2. `KernelCodegenCompileTest` compiles the emitted `RuntimeComponents` against the real
   `exeris-kernel-spi` / `-core` artifacts. Verified non-vacuous: emitting a wrong-arity constructor
   fails the gate at `RuntimeComponents.java`.
3. Migration note lands in `docs/MIGRATION-0.x-to-1.0.md` under the 0.8.0 train.

---

## Amendment 1 — obligation 6's stream guard protects nothing on the generated boot (2026-09-26)

**Status:** Accepted *(corrects the premise of obligation 6; the hook, its default and the guard are
unchanged by this amendment)*
**Trigger:** T23 reopened in `ROADMAP.md` — the dog-food's K9, widened on 2026-09-26.

### What

Obligation 6 says a wrapper and a stream route are mutually exclusive and that the emitted app
enforces it. The exclusion is real. Its implied converse — that an app which leaves `decorate` alone
serves its streams — is not. The kernel never sees what `decorate` returns: `Application.run()` binds
its own `forwardingHandler` lambda as `HTTP_SERVER_HANDLER`, the http subsystem reads that binding
once when it starts, and the dispatcher resolves a stream only for a handler that is an `HttpRouter`.
On a real boot of the generated application no stream route resolves, decorated or not, so the
boot-time refusal buys nothing. It would bite only in a launcher that composes through
`RuntimeLifecycle` and binds the slot's content as the server handler itself.

### Consequences recorded, not decided here

- The guard's message and the `decorate` Javadoc attribute to a wrapper a failure that happens
  without one. Correcting them changes emitted output and takes a MIGRATION note.
- The standing upstream ask now fixes both halves: resolution through an interface a forwarder or a
  wrapper can delegate, with the resolved stream handler run inside the wrapper's scope (K9). When it
  exists, the guard becomes a pass-through.
- Tooling cannot bind the composed router where the kernel reads it: the router is built inside the
  boot callback after the subsystem has read its handler (obligation 4 is why it must be), a running
  engine refuses a new handler by contract, and `HttpRouter` is `final`. A route table built *before*
  boot with late-bound targets is possible in principle; it would change obligations 5 and 6, and it
  is an open design question rather than part of this amendment.
- Whether the refusal stays until K9 lands is an open call — see T49 and T23 in `ROADMAP.md`.

---

## Amendment 2 — the kernel holds a router built before boot; subscribers, flows and the scope lists join the seam (2026-09-26)

**Status:** Accepted *(changes obligations 1, 4, 5 and 6; decides the open question Amendment 1
recorded. The seam's shape — `RuntimeComponents` constructs, `RuntimeLifecycle` drives — is
unchanged.)*
**Trigger:** T23 slice B1, T48 slice C1 and T51, from the architect review of 2026-09-26 (after B0
pinned kernel 0.12.0).

### What

**Obligation 4 — two slots, and the kernel is handed an `HttpRouter`.** `Application.run()` no
longer binds a forwarding lambda. It builds `RuntimeLifecycle.edgeRouter(handlerSlot,
componentsSlot)` before boot and binds that as `HTTP_SERVER_HANDLER`. The edge router carries every
generated stream route: `GET <base>/stream` for `realTimeApi`, and `POST <base>/{id}/actions/<kebab>`
for `@Action(streaming)`. Each target resolves its handler from `componentsSlot` when a stream
opens, and closes a stream opened before the components exist. Its `notFound` forwards every other
request to `handlerSlot`, and answers `503` while that slot is empty. The boot callback threads
`new RuntimeLifecycle(handlerSlot, componentsSlot, components(transactionalExecutor())).run()`.
**Composition does not move.** It stays inside the callback, because that is the only place the
scopes it reads are bound. The 0.8.0 two-argument constructor remains, delegating with a
`componentsSlot` nothing reads.

It works because the stream half of the route table is known at generation time, and building an
`HttpRouter` reads no `ScopedValue`. The http subsystem therefore gets the one handler type the stream
dispatcher resolves (`handler instanceof HttpRouter`), at the one moment it reads its handler
(`start()`, before the boot callback). With no stream routes the edge router is exactly the lambda it
replaced. It is emitted for every application, one shape for all.

**The order inside `run()`** is fixed, and each step exists for a measured reason:

1. **Every publisher is built.** A publisher registers its event types when it is constructed, and
   the kernel bus rejects a subscription to an unregistered type. An entity whose events no handler
   publishes (`MANUAL`, `STATE_TRANSITION`, …) previously had no publisher built at all, so its live
   view was rejected on every open.
2. **Every saga's `initialize()`, then every subscriber's `subscribe()`** (T48 slice C1; ADR-075
   Amendment 2).
3. **Every stream-route target accessor is forced** on the boot thread, so no factory ever runs on a
   stream thread. The unsynchronised memo field is written once, before it is published.
4. The respond-once router is built, `configureRoutes` runs, `decorate` wraps it.
5. **`componentsSlot` is set, then `handlerSlot`.** Once the handler slot is set the app is serving,
   and its streams must already resolve.
6. The shutdown latch releases, and the subscribers are unsubscribed in reverse order.

**Obligation 5 — `configureRoutes` is respond-once only.** The kernel never asks the handler-slot
router to resolve a stream, so a `streamRoute` registered there does not match. That was already true
on the generated boot (Amendment 1); the emitted Javadoc now says so.

**Obligation 6 — the guard is removed.** `decorate` applies to respond-once routes. Stream routes
resolve on the edge router and run outside the wrapper, and the kernel never sees what `decorate`
returns, so returning any wrapper is safe. That replaces "a wrapper and a stream route are mutually
exclusive, and the emitted app enforces it". The refusal protected nothing in any reachable state:
before this amendment the kernel held a lambda, and now it holds the edge router.

**Obligation 1 — two more component kinds, and one capture rule.** `<Entity>EventSubscriber` and
`<Saga>Flow` get the three members (T48 slice C1). The flow's accessor follows its class name, so a
subclass such as `ConstructionSaga extends ConstructionSagaFlow` installs by overriding
`createConstructionSagaFlow()`. Two existing factories now capture what their component used to read
later:

- the EV1 stream handler takes its `EventEngine`, because the stream thread binds only the
  allocator and the decoder registry;
- a payload-bearing publisher takes the `EventPayloadCodecRegistry`, because the request thread
  binds none.

The rule, stated once: **a value a component needs later is captured at composition.** It is never
read on a request or stream thread unless the kernel binds it there.

**The seam publishes what it reads (T51).** `RuntimeComponents.COMPOSITION_SCOPES` and
`REQUEST_SCOPES` are `List<ScopedValue<?>>`, derived from the branches that emit each read, with
Javadoc naming every reader:

- composition: `MEMORY_ALLOCATOR`, `EVENT_ENGINE`, `FLOW_ENGINE`;
- request: `HTTP_REQUEST_BODY_DECODER_REGISTRY`, and `STORAGE_CONTEXT` for tenant-partitioned
  entities.

`EVENT_PAYLOAD_CODEC_REGISTRY` is optional, so the Javadoc names it without listing it.
`PERSISTENCE_ENGINE` is read only by `Application.transactionalExecutor()`'s default. A harness that
composes outside a boot binds the first list, or overrides the factories that read it, and supplies
the second per request.

### Why this shape

Amendment 1 named it: "a route table built *before* boot with late-bound targets is possible in
principle; it would change obligations 5 and 6." Two alternatives were rejected:

- **Binding the composed router earlier.** Composition would have to leave the boot callback, and it
  reads scopes that are bound only there.
- **Waiting for the kernel (K9).** Correct, but blocked on another repository, while the generated
  boot served no stream at all.

### What remains for the kernel (K9, narrowed)

1. **A `streamRoute` a consumer registers in `configureRoutes`.** It needs stream resolution through
   an SPI interface a forwarding handler can delegate. With one, the edge router's miss path would ask
   the handler slot, and hand-registered streams would resolve too. The SPI needs its own
   return type: `HttpRouter.StreamMatch` is in core, and the SPI cannot name it.
2. **Scope around streams.** A tenant a `decorate` wrapper binds for a request is not bound for a
   stream. The kernel ask is to run the resolved stream handler inside the wrapper's scope. A tooling
   stop-gap is possible without it: a `decorateStream(HttpStreamHandler)` hook, since
   `HttpStreamExchange.request()` exposes the headers.
3. **Not a kernel ask, recorded for completeness.** A stream opened while the app is composing gets
   `200` and an immediate close, not `503`, because the stream engine writes the response head
   before any handler runs.

### Consequences

- **[+] Stream routes resolve on a real boot of the generated `Application`.**
  `GeneratedAppBootE2ETest` boots it on kernel 0.12.0 (http + events + flow, H2), opens
  `/beacons/stream`, and reads a published frame:
  `event: BeaconPinged` / `data: {"label":"alpha"}`. The test was checked for vacuity. Restoring the
  forwarding lambda brings back the measured `400`. Reading the engine on the stream thread brings
  back `NoSuchElementException: ScopedValue not bound`.
- **[+] A streaming application can use `decorate`**, which is what the dog-food's
  `DevTenantBinding` needed. Its tenant does not yet reach stream handlers (K9 item 2).
- **[+] Subscribers and saga flows are constructed, substitutable and started** by the generated
  application.
- **[-] Every consumer's three bootstrap files regenerate** (0.x allows it). `RuntimeLifecycle` gains
  a constructor and a static method. `run()` no longer registers stream routes.
- **[-] Launchers that bound the handler-slot router directly lose generated streams.** This is the
  one launcher shape that had them, such as the dog-food's `GeneratedAppHttpBootTest`, and it
  switches to `edgeRouter(...)` with the three-argument constructor.
- **[-] Composing outside a boot needs more scopes.** It now needs `EVENT_ENGINE` for an EV1 stream
  handler and for every subscriber, and `FLOW_ENGINE` for every saga, or overrides of those
  factories. `COMPOSITION_SCOPES` lists them.
- **[-] Missing subsystems now fail at boot.** A regenerated app that declares `@Saga` without
  `flow` in `subsystems()`, or events without `events`, fails there instead of never running them.

### Engineering Protocol (additions)

1. `KernelApplicationGeneratorTest`: the edge router's shape and fallthrough. No guard. Stream routes
   are on `edgeRouter` and not in `run()`. Publishers, sagas and subscribers start in order, before
   `componentsSlot`, which is set before `handlerSlot`. Unsubscribe runs in reverse. The scope lists
   and the readers they name.
2. `GeneratedAppBootE2ETest`, two tests. The emitted `Application.run()` on a real kernel (the frame
   above, activation order, subscriber delivery, unsubscribe on stop). `edgeRouter(...)` on
   `KernelBootstrapHttpEngineFixture` (`503` and close-on-open while composing, then forwarding).
3. `ScopeLedgerE2ETest`: every `(Http)?KernelProviders` read in five generated corpora is listed or
   allowlisted, and every listed scope is read in its phase. It fails in both directions when the
   derivation drifts.
4. `KernelCodegenCompileTest` compiles all of it against `exeris-kernel-spi` / `-core` 0.12.0.

---

## Amendment 3 — StreamRouteResolver (K9) (2026-10-01)

**Status:** Accepted *(replaces obligations 4, 5 and 6 as stated in the Decision and as changed by
Amendments 1 and 2, including Amendment 2's edge router, `componentsSlot` and the stream routes it
carries; adds one Engineering Protocol item. Obligations 1–3 and 7 and the scope lists are
unchanged.)*
**Trigger:** kernel 0.12 ships `StreamRouteResolver` and `StreamMatch` in the SPI
(`eu.exeris.kernel.spi.http`): a driver resolves a stream through the bound handler's
`StreamRouteResolver`, and a handler that wraps a router implements it by delegating.

### Obligation 4, as it now reads

**`Application#components(TransactionalExecutor)` is the installation point**, and the boot
callback threads it: `new RuntimeLifecycle(handlerSlot, components(transactionalExecutor())).run()`.
Being inside `KernelBootstrap.boot(...)` is load-bearing — it is what lets a factory body call
`KernelProviders.flowEngine()` / `eventEngine()`. The kernel holds the handler
`RuntimeLifecycle.edgeHandler(handlerSlot)` builds before boot, because the http subsystem reads its
server handler once, when it starts, ahead of the callback. It forwards every request, and every
stream resolution, to whatever the slot holds. While the slot is empty it resolves no stream and
answers every request, a stream open included, `503`. `RuntimeLifecycle.run()` registers every
generated stream route on the router it composes, beside the respond-once routes.

### Obligation 5, as it now reads

**`configureRoutes(HttpRouter.Builder)` runs after every generated route and before `build()`.**
A hand-written route can add to the table; it can never silently displace a generated one. That
covers stream routes: a `streamRoute` registered here resolves exactly like a generated one. A
respond-once route cannot displace a generated one, because the first registration that matches
wins, and the unit tests assert the order. A stream route at an exact path can, because the kernel's
stream table keeps the last registration, so the emitted `run()` checks every generated stream route
after `build()` and refuses to boot, naming the method and path, when one was replaced. A stream at
a concrete exact path under a generated template (for example `POST /orders/42/actions/x`) takes
precedence for that one path, because the kernel resolves an exact path before a template; this
per-path override is permitted and is not refused.

### Obligation 6, as it now reads

**`decorate(HttpRouter)` runs between `build()` and the handler slot.** Whatever it returns is what
the kernel serves; the default returns the router unchanged. It is the sibling of obligation 5 —
same object, one line later — and exists because a per-request concern the generated code does not
own (a tenant binding, a decoder registry, an allocator) otherwise forces a consumer to reimplement
`Application#run()`, once per application.

**A wrapper either resolves the router's streams or is refused.** The edge handler asks the slot,
and `run()` publishes exactly what `decorate` returned:

- **The router itself** (the default) resolves every stream route, generated and hand-registered.
- **A wrapper that implements `StreamRouteResolver`** by delegating to the router resolves them too.
  What it binds around the `HttpStreamHandler` it returns is bound for that stream, inside the
  kernel's own bindings, for the stream's whole life. Its `resolveStream` runs before route
  authorization and outside every binding, so it decides from method and path alone. The emitted
  `decorate` Javadoc shows the shape. `run()` probes every generated stream route through it after
  the type check; a `null` answer for any of them fails the boot, naming the wrapper's class and
  the route. Any non-null match passes, since a wrapper may return a match of its own.
- **Any other wrapper**, in an application with generated stream routes, fails the boot: `run()`
  throws `IllegalStateException` naming the wrapper's class and telling the author to implement
  `StreamRouteResolver` and delegate to the router. Behind it no stream route would resolve.
  Nothing is served outside a wrapper.
- In an application **without generated stream routes** any wrapper is accepted. A stream
  registered in `configureRoutes` behind a wrapper that is not a resolver does not resolve (kernel
  0.12 exposes no way to ask a built router whether it has stream routes, so this case is not
  refused); the request falls through to the wrapper's respond-once dispatch.

### Engineering Protocol (addition)

1. `GeneratedAppBootE2ETest` boots the emitted `Application.run()` on kernel 0.12.0 and holds
   obligations 4–6 on the wire: a generated stream and a `configureRoutes` stream behind a
   delegating `decorate` wrapper, whose binding reaches the stream; a wrapper that is not a
   resolver, and a resolver that answers `null` for a generated route, each refused at boot with
   its class named; a `configureRoutes` stream at a generated path, refused at boot; and
   `edgeHandler(...)` answering `503` to a stream open while the slot is empty. The `decorate`,
   edge and displacement cases fail when the emitted mechanism each covers is removed.
