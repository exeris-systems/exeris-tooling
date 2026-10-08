---
title: "0.10.0 migration step: A streaming action runs and streams the events it triggers; `EXT-PROC-1107` is retired"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-10-08
---

### A streaming action runs and streams the events it triggers; `EXT-PROC-1107` is retired

Through 0.9.0, `POST {base}/{id}/actions/{kebab}` for an `@Action(streaming = true)` sent four
`keep-alive` frames and closed without calling the entity method, and the processor said so on
every build (`EXT-PROC-1107`). From 0.10.0 the generated `<Entity><Action>StreamHandler` runs the
action (ADR-044 Amendment 2), in this order:

1. on a `TENANT` or `UNIVERSE` entity, the tenant guard: no bound `STORAGE_CONTEXT` is refused;
2. the path `id`, then the `@ActionParam` body when the action declares one;
3. `findById(id)` under row-level security;
4. a subscription to each `@DomainEvent` whose trigger is `ACTION` and whose `action` names this
   action, forwarding only events published under this row's id;
5. the entity method, `service.update`, and the publish calls, as the respond-once action route
   runs them;
6. one frame per forwarded event, named by the `@DomainEvent` name, carrying the encoded payload.

The stream closes once every triggered event has arrived, or 30 seconds after the action ran. An
action that triggers no event closes as soon as it has run. Because the `200` response head is
written before the handler runs, every refusal is one frame named `stream-error` whose data is a
problem object, for example `{"type":"about:blank","title":"Not Found","status":404}`, with the
status the respond-once action route answers: `400` for a malformed id, a body the caller got wrong
or a write naming another tenant, `404` for a row that is absent or not visible, `409` for a version
conflict, `500` for the tenant guard and any other failure. The `EXT-PROC-1107` warning is gone and
listed under *Retired identifiers* in [`diagnostics.md`](../../diagnostics.md).

The handler emits no frame named by `@Action.streamEventType`: that frame carries the action's
return value, and the generators do not know its type yet. Under `-Aexeris.strict` the attribute
is reported as inert (`EXT-PROC-1201`).

Two invocations on the same row at the same time publish under the same stream id, and kernel
events carry no correlation id, so each stream can receive the other's event frames.

**A `TENANT` or `UNIVERSE` entity's streaming action is refused at runtime until the application
binds a route policy.** The kernel binds `STORAGE_CONTEXT` only on a route whose policy is not
`permitAll()`, and the generated application binds none, so the guard sends `stream-error` with
`500` on every call. The generated TypeScript emits no per-action stream client for these entities.

**What to do:**

- An override of `RuntimeComponents.create<Entity><Action>StreamHandler()` constructs the handler
  with the new constructor: the entity's service, then the `MemoryAllocator` when the action has an
  `@ActionParam`, then the entity's publisher and the `EventEngine` when the action triggers an
  event — the arguments the generated factory passes.
- A client of the stream reads event frames by their `@DomainEvent` names, treats `stream-error` as
  the failure, and the end of the stream as completion. The generated TypeScript client delivers
  every named frame already.
- A `@DomainEvent` named `stream-error` or `keep-alive` is now a compile error
  (`EXT-PROC-1016`): rename the event. A streaming action whose `streamEventType` — or, when that is
  blank, whose action name — is `stream-error`, `keep-alive`, or the name of an event the action
  triggers is a compile error too (`EXT-PROC-1017`): set `streamEventType` to another name.

The checks run in the annotation processor. Metadata JSON that reaches `exeris:generate` without
passing through the processor is not checked.
