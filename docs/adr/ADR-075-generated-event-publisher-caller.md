# ADR-075 — The generated event publisher is invoked from the generated handler

- **Status:** ACCEPTED (2026-08-27)
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
  event now produces one.
- The publisher is reachable through the composition root, so a consumer can replace it without
  forking generated code — the ADR-070 exception is closed.
- Nothing about the *emitted publisher* changed except its finality: the publish methods, the
  descriptors, the EV1 payload records and the ADR-046 codec resolution are byte-identical.

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
