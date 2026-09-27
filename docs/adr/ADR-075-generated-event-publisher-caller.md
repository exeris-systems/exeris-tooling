---
title: "ADR-075 — The generated event publisher is invoked from the generated handler"
type: adr
visibility: public
owning-repo: exeris-tooling
status: active
slug: adr/ADR-075
---

# ADR-075 — The generated event publisher is invoked from the generated handler

- **Status:** ACCEPTED (2026-08-27) · amended 2026-09-26 (Amendment 1 — the chain stops at the bus; Amendment 2 — subscribers and flows composed, payloads encoded)
- **Repo:** `exeris-tooling`
- **Scope:** tooling / codegen pipeline
- **Visibility:** public
- **Milestone:** 0.8.0 (backlog item **T48**)
- **Supersedes / superseded by:** —

## Context

The pipeline emits a `<Entity>EventPublisher` for every entity that declares a `@DomainEvent`, and
emitted no call to it. `KernelServiceGenerator`'s javadoc said publishing was "intentionally out of
scope" and that the publisher "is wired separately by the application bootstrap" — and no emitter
performed that wiring. The declared chain `@Action` → `@DomainEvent` → saga was generated at every
link except the call, so an action whose real work lives in a saga returned `200` and did nothing.
The emitted code *documented* a step nothing did.

It was also an ADR-070 gap of exactly the shape that ADR exists to close. `RuntimeComponents` carries
a `protected create*` factory for every generated repository, service and handler, and none for the
publisher — so the one component a consumer most needed to reach was the one the seam omitted, and
ADR-070's own rule ("a new emitted component type joins that seam in the same change that introduces
it") had a pre-existing exception nobody had named.

Two measurements decided the shape, and they are why this is not a preference.

**An action never touches the service.** The handler loads the aggregate and invokes
`entity.<method>(...)` directly, then persists through the service. So a publisher held by the
*service* — as a constructor argument, or as a generated decorator installed by the default factory,
the two options `ROADMAP.md` had recorded — is structurally unable to observe the `ACTION` trigger,
which is the case T48 actually names. Either option would have closed the CRUD half of the finding
and left the half that motivated it.

**Until SDK 0.11.0 nothing said *when* an event fires.** The processor read `@DomainEvent.trigger`
only to derive an event-*name* suffix (`CREATE` → `OrderCreatedEvent`), and only when the author
supplied no explicit `name` — so `@DomainEvent(name = "OrderPlaced", trigger = CREATE)` left no trace
of the trigger anywhere, and `action` / `field` were never read at all. SDK 0.11.0 (ADR-072) shipped
`DomainEventMetadata.{trigger, actionName, fieldName}` as the carrier. The `-io` reader took it
first, so the ADR-042 lockstep is satisfied by the processor catching up rather than by a new
cross-repo obligation.

## 🏁 The Decision

**The publisher becomes a component in `RuntimeComponents` and a constructor argument of
`<Entity>Handler`; the processor extracts the trigger triple; each handler method publishes the
events whose trigger it satisfies.**

- `RuntimeComponents` gains `create<Entity>EventPublisher()` — default body
  `new <Entity>EventPublisher(KernelProviders.eventEngine())` — with the same memoising accessor
  every other component has, and `create<Entity>Handler()` passes it. Overriding either is how a
  consumer substitutes a publishing strategy.
- `handleCreate` publishes `CREATE`-triggered events, `handleUpdate` publishes `UPDATE`, `handleDelete`
  publishes `DELETE`, and each action handler publishes the `ACTION`-triggered events whose
  `actionName` matches its own.
- The call lands **after** the mutation and **before** the response. A publish before the write would
  announce a row that may not exist.
- `trigger` stays nullable through the AST. `null` means "this baseline predates EV2 extraction",
  which is a different claim from `CREATE`, and an event with no trigger is published by no handler
  method rather than being defaulted onto create.
- `FIELD_CHANGED`, `STATE_TRANSITION`, `SCHEDULED`, `MANUAL` and `SNAPSHOT` publish nothing. Each
  needs a source of truth the handler does not have — a previous value, a state machine, a scheduler,
  a caller. Emitting a guess for any of them would put a publish call where the author did not ask
  for one.
- `<Entity>EventPublisher` loses `final`, because `create<Entity>EventPublisher()` invites a
  consumer to decorate the default by calling `super` — which a final class forecloses. The emitted
  handler test constructs the real publisher over a new `RecordingEventEngine`: it doubles the
  collaborator, not the publisher, so the test is not the reason for the modifier.

## Consequences

### ✅ Positive Outcomes

- The `@Action` → `@DomainEvent` → saga chain is generated end to end. An action that declares an
  event now produces one. *(Amendment 1, 2026-09-26: emitted end to end, composed only as far as the
  publish — no subscriber or saga is constructed in the running application.)* *(Amendment 2,
  2026-09-26: subscribers and flows are now composed and started; the event→saga edge is still not
  generated.)*
- The publisher is reachable through the composition root, so a consumer can replace it without
  forking generated code — the ADR-070 exception is closed.
- Nothing about the *emitted publisher* changed except its finality: the publish methods, the
  descriptors, the EV1 payload records and the ADR-046 codec resolution are byte-identical.
  *(Amendment 2, 2026-09-26: a payload-bearing publisher now resolves the codec from a registry
  captured at construction — resolved per publish, every payload had shipped empty.)*

### ⚠️ Trade-offs

- **Publishing is coupled to the HTTP transport.** A saga, a scheduled job, or any code calling the
  service directly publishes nothing. This is stated rather than designed around: ADR-070's seam lets
  a consumer install a publishing service of their own, and the alternative — moving action dispatch
  into the service so all four triggers share one domain seam — is a larger change that moves the
  handler/service boundary and is not taken here.
- **The publish runs after the commit, not inside it.** The transaction boundary lives in the
  repository, below the service, so a crash between commit and publish loses the event. The
  descriptors carry `FLAG_PERSISTENT`, which makes *delivery* durable once published — not the
  publish itself. Publishing inside the transaction means publishing below the service, which is the
  seam that cannot see `ACTION`.
- **A payload-bearing `DELETE` event costs one extra read.** The aggregate is gone after
  `service.delete(id)`, so the handler reads it first — emitted only when some `DELETE` event
  actually carries a payload.

  No delete publish needs a "did the row exist" guard, and this was measured rather than assumed:
  the generated `deleteById` throws when `rowsAffected == 0`, and the service delegates straight to
  it, so a `DELETE` on an unknown id — including a retried one, whose second call affects no rows —
  leaves the handler through its 5xx catch before reaching any statement after `service.delete(id)`.
  The `isPresent()` check on the payload path is defensive against a race between the read and the
  delete, not the thing that makes the publish correct.
- **An `ACTION` event whose `actionName` names no declared action is silently unpublished.** Failing
  the build on it would make one typo take down an entity's whole CRUD surface; `-Aexeris.strict` is
  where "you wrote it and it does nothing" belongs (see **D5** / **D6**).

### 📋 What is NOT in scope

- Moving action dispatch from the handler into the service.
- Transport-independent publishing, and publishing inside the transaction.
- The five triggers listed above that publish nothing.
- `@EventSourced` (EV2 proper — the replay SPI is present and unread; see `ROADMAP.md`).

## Cross-references

- **ADR-070** — the composition-root seam this closes an exception in.
- **ADR-072** (`exeris-sdk`) — shipped the trigger triple this consumes.
- **ADR-046** — the EV1 codec resolution inside the publisher, unchanged.
- **ADR-058** — the generated-test emission channel; `RecordingEventEngine` is emitted under it.
- **ADR-042** — the processor/`-io` lockstep; the reader read the triple first.

---

## Amendment 1 — the generated chain stops at the bus (2026-09-26)

**Status:** Accepted *(corrects §Consequences "generated end to end"; the Decision is unchanged)*
**Trigger:** the dog-food's T48 ("every saga is unreachable from the running application"), re-read
against this tree after its 2026-09-25 re-review.

### What

Every link of the `@Action` → `@DomainEvent` → saga chain is *emitted*. The running application
*composes* it only as far as the publish:

| Link | Emitted by | Constructed in the running app | Invoked in the running app |
|---|---|---|---|
| handler action / CRUD method | `KernelHandlerGenerator` | `RuntimeComponents.create<Entity>Handler()` | served by its route |
| `<Entity>EventPublisher` | `KernelEventGenerator` | `RuntimeComponents.create<Entity>EventPublisher()` | by the handler, for `CREATE` / `UPDATE` / `DELETE` / a matching `ACTION` (this ADR) |
| `<Entity>EventSubscriber` | `KernelEventHandlerGenerator` | **never** — `RuntimeComponents` has no factory for it | **never** — no emitted code calls `subscribe()` |
| `<Saga>Flow` | `KernelSagaGenerator` | **never** | **never** — `schedule(FlowContext)` is called only by the emitted `<Saga>FlowTest` |

So a served action publishes, the event reaches the bus, and nothing in the generated application
receives it. Two facts make the gap wider than a missing factory:

1. **The subscriber would not start a saga if it were composed.** Its default handlers log and close
   the payload; the generator removed the old `saga.start(...)` coupling and names saga triggering
   "application-level wiring" (`KernelEventHandlerGenerator` class Javadoc).
2. **Nothing tooling extracts says which event starts which saga.** `SagaMetadata.trigger`
   (`type` / `source` / `topic`) exists in the SDK AST; the processor's `extractSagaMetadata` does
   not read it.

The trade-off stated above — a saga calling the service directly publishes nothing — is the other
direction of the same edge, and is unchanged.

### What this amendment does NOT decide

**Who composes subscribers and sagas.** The options are open, so per the shape gate this is an RFC
rather than an amendment. Candidate owners:

- **the generated `RuntimeComponents`** — a `create*` factory per subscriber and per flow, as
  ADR-070 obligation 1 already requires of a new emitted component type. Settles construction and
  substitution; does not settle the event→saga edge, which has no carrier.
- **the generated `Application` / `RuntimeLifecycle`** — subscribe and initialize at boot,
  unsubscribe on shutdown. Owns the lifecycle as well as the wiring, and needs the same carrier.
- **the application** — today's behaviour, made explicit: the artefacts stay unwired, the emitted
  code says so, and the chain is completed in a `RuntimeComponents` subclass.

Until that is decided, read this ADR's "end to end" as "emitted end to end, composed to the publish".
Tracked as **T48-follow-up** in `ROADMAP.md`.

---

## Amendment 2 — subscribers and saga flows are composed and started; payloads are encoded (2026-09-26)

**Status:** Accepted *(decides Amendment 1's open question "who composes subscribers and sagas" for
construction and lifecycle; the event→saga edge stays open. The Decision's publish site and its
after-commit timing are unchanged.)*
**Trigger:** T48 slice C1, from the architect review of 2026-09-26. The review compared Amendment 1's
three candidate owners against the kernel SPI and closed them to one: the generated
`RuntimeComponents` constructs, and the generated `RuntimeLifecycle` activates. That is the division
ADR-070 already draws, so this is recorded as an amendment rather than an RFC.

### What

Amendment 1's table, as it now stands:

| Link | Emitted by | Constructed in the running app | Started in the running app |
|---|---|---|---|
| handler action / CRUD method | `KernelHandlerGenerator` | `RuntimeComponents.create<Entity>Handler()` | served by its route |
| `<Entity>EventPublisher` | `KernelEventGenerator` | `RuntimeComponents.create<Entity>EventPublisher()` — now built at boot for every entity with events, which registers its event types before anything subscribes | by the handler, for `CREATE` / `UPDATE` / `DELETE` / a matching `ACTION` (this ADR) |
| `<Entity>EventSubscriber` | `KernelEventHandlerGenerator` | **`RuntimeComponents.create<Entity>EventSubscriber()`** | **`subscribe()` at boot, before the app serves; `unsubscribe()` in reverse order after the shutdown latch** |
| `<Saga>Flow` | `KernelSagaGenerator` | **`RuntimeComponents.create<Flow>()`**, where the accessor follows the flow's class name | **`initialize()` at boot**: the plan is compiled and registered before the first request |

The saga plan is registered at boot because that is load-bearing, not an optimisation. The kernel
resumes a parked instance only on a registered plan version (kernel ADR-064). A plan compiled lazily
by the first `schedule()` is never registered for an instance parked across a restart.

**Behaviour is installed by override.** A consumer's
`ConstructionSaga extends ConstructionSagaFlow` is returned from `createConstructionSagaFlow()`, and a
subscriber subclass whose `handle<Event>` methods do real work is returned from
`create<Entity>EventSubscriber()`. Both factories run inside the boot callback, and both engines come
from `KernelProviders` there.

**Payloads are encoded.** A payload-bearing publisher now resolves its `EventPayloadCodec` from a
registry captured at construction. `RuntimeComponents` passes
`KernelProviders.eventPayloadCodecRegistry().orElse(null)`, resolved inside the boot callback. Until
now it read the slot per publish, on the request thread. The kernel binds
`EVENT_PAYLOAD_CODEC_REGISTRY` only in its boot scope, so every payload shipped empty, with a DEBUG
line as the only trace. The one-argument constructor remains. It captures where it is called, so
hand-written callers and the emitted handler test still compile. The defect was inferred in review
and is now measured: on a real boot the frame published from a request thread reads
`data: {"label":"alpha"}`, and restoring per-publish resolution turns it back into `data: ` (empty).

**The publish site and its timing are unchanged.** The handler still publishes after the mutation
commits, with the crash window this ADR's trade-offs state. Publishing inside the transaction still
needs the service or repository seam to see `ACTION`, which the Decision rejected.

### What this amendment does NOT decide

**Which event starts which saga (slice C2).** Nothing generated connects a subscriber to a flow; a
consumer's subscriber subclass does it. The kernel has the mechanism:
`FlowEngine.registerChoreographyMapper` with `ChoreographyDecision.Start`, and Community reports
`choreographySupport`. Emitting it from a *declared* `@Saga(trigger = EVENT)` waits on two things:

- **The SDK trigger enums disagree.** The annotation's `Saga.TriggerType` is
  `{COMMAND, EVENT, SCHEDULE, HTTP, MANUAL}`. The AST's `SagaMetadata.TriggerType` is
  `{EVENT, SCHEDULED, MANUAL, API}`, and `COMMAND` has no AST constant. The processor does not
  extract the trigger at all yet.
- **A duplicate `Start` for the same instance id is unpinned** by the kernel's choreography TCK. A
  generated mapper keyed on the aggregate id needs that answer first.

The first hop of a chain such as Stellar's (`StructureUpgradeQueued` → create a `ConstructionOrder`)
is application logic in either case, and no generator can derive it.

### Consequences

- **[+] The published event reaches something the application composed.** A subscriber is
  subscribed and a saga's plan is registered before the first request. This is asserted on a real
  kernel boot by `GeneratedAppBootE2ETest`.
- **[+] ADR-070's obligation-1 exception is closed for the last two emitted component types.**
- **[-] Every regenerated application builds all its publishers, subscribers and flows at boot.** An
  app that declares `@Saga` without `flow` in `subsystems()` now fails at boot, not at first use. So
  does one that declares events without `events`. A harness composing outside a boot needs
  `EVENT_ENGINE` and `FLOW_ENGINE`, or overrides of those factories. `RuntimeComponents
  .COMPOSITION_SCOPES` lists them (T51).
- **[-] Default subscribers still only log and close the payload.** Starting them delivers nothing
  observable until a consumer overrides them. That is intentional (see the `KernelEventHandlerGenerator`
  class Javadoc), and it is why slice C2 exists.
