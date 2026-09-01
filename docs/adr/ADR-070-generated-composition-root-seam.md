# ADR-070 — Open the generated composition root: `RuntimeComponents`

- **Status:** ACCEPTED (2026-08-18)
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
   added to this seam in the same change that introduces it.
2. **Defaults resolve dependencies through the accessor, never a field or a local.**
   `createOrderService()` emits `new OrderService(orderRepository())`. This is what makes a single
   override propagate: replace the repository and the untouched service factory picks it up.
3. **`RuntimeComponents` is not `final`, `create*` is `protected`, accessors are `public`.**
   `RuntimeLifecycle` stays `final` — it is a driver, not an extension point.
4. **`Application#components(TransactionalExecutor)` is the installation point**, and the boot
   callback threads it: `new RuntimeLifecycle(handlerSlot, components(transactionalExecutor())).run()`.
   Being inside `KernelBootstrap.boot(...)` is load-bearing — it is what lets a factory body call
   `KernelProviders.flowEngine()` / `eventEngine()`.
5. **`configureRoutes(HttpRouter.Builder)` runs after every generated route and before `build()`.**
   A hand-written route can add to the table; it can never silently displace a generated one.
   Enforced by an ordering assertion, not by convention.
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
   the trade-off, and that is the standing upstream ask.
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
- ADR-024 / G2 boot-conductor call site — the composed variant threads the seam identically;
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
