---
title: "0.10.0 migration step: A `realTimeApi` entity serves `GET {base}/{id}/stream`; `message`, `open` and `error` are reserved frame names"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-10-08
---

### A `realTimeApi` entity serves `GET {base}/{id}/stream`; `message`, `open` and `error` are reserved frame names

From 0.10.0 an `@ExerisDomain(realTimeApi = true)` entity gets a second stream route beside the
live view `GET {base}/stream`: `GET {base}/{id}/stream` streams the events of one row (ADR-044
Amendment 2). The generated `<Entity>SpectateStreamHandler`:

1. reads the path `id`;
2. loads the row with `findById(id)`;
3. subscribes to each of the entity's `@DomainEvent`s, forwarding only the events published under
   this row's id — the id the generated create, update, delete and action routes publish under;
4. sends each forwarded event as a frame named by its `@DomainEvent` name, and a `keep-alive` frame
   after 15 seconds without one, until the client disconnects. There is no deadline.

An entity with no `@DomainEvent` gets the route too: it checks the row and sends `keep-alive`
frames. Because the `200` response head is written before the handler runs, a refusal is one frame
named `stream-error` whose data is a problem object with the status the by-id `GET` answers: `400`
for a malformed id, `404` for a row that is absent or not visible, `500` for any other failure. The
stream then closes. Row visibility is checked once, when the stream opens. The emitted OpenAPI
document describes the route as a `text/event-stream` response. The live view and the spectate route
do not shadow each other: a path placeholder matches exactly one segment.

The generated TypeScript emits no client for the spectate route yet; it is served, and listed under
`withoutClient` in the stream route contract.

**What to do:**

- A `RuntimeComponents` subclass gains `create<Entity>SpectateStreamHandler()`; its constructor takes
  the entity's service, then the `EventEngine` when the entity declares an event. Nothing changes for
  code that does not override it.
- A hand-written `GET` stream route of the shape `{base}/{x}/stream`, registered in
  `configureRoutes`, no longer serves: spelled `{base}/{id}/stream` it fails the boot, because the
  router admits one stream route per method and path; under another placeholder name it is never
  reached, because the generated template is registered first. Remove it, or move it to another path.
- A `@DomainEvent` named `message`, `open` or `error` is now a compile error (`EXT-PROC-1016`): a
  browser `EventSource` dispatches these event types itself, so a client built on it could not tell
  the event from the connection's own. Rename the event. A streaming action whose `streamEventType`,
  or, when that is blank, whose action name, is one of them is a compile error too
  (`EXT-PROC-1017`): set `streamEventType` to another name. The names match case-sensitively, so
  `Error` or `OrderOpened` are not affected.
- A `realTimeApi` entity that also declares a streaming action named `spectate` fails generation:
  both stream handlers would be named `<Entity>SpectateStreamHandler`. Rename the action.

The name checks run in the annotation processor. Metadata JSON that reaches `exeris:generate`
without passing through the processor is not checked for them.
