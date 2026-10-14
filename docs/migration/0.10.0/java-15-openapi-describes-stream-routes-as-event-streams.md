---
title: "0.10.0 migration step: the OpenAPI document describes a streaming action and the live view as event streams"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-10-14
---

### The OpenAPI document describes a streaming action and the live view as event streams

`Compatibility impact: additive`. The document now matches the routes the application already
serves; no route changes.

Regenerating changes `openapi/<entity>-api.yaml` for an entity with an
`@Action(streaming = true)` or with `realTimeApi = true`:

- `POST {base}/{id}/actions/{kebab}` of a streaming action answers only `200` with a
  `text/event-stream` body. It no longer declares the respond-once `200` "Action result" and the
  `400`, `404`, `409` and `500` responses, which the route never sent: the stream handler writes
  the `200` head before it runs, and every refusal is one `stream-error` frame whose data is a
  problem object with the status the respond-once action route answers. The description names the
  frames: one per `@DomainEvent` the action triggers, then the stream closes. The request body is
  the action's request schema, as before.
- `GET {base}/stream`, the live view, is described for the first time: operation `stream<Entity>`,
  `200` `text/event-stream`, one frame per `@DomainEvent` of the entity and a `keep-alive` frame
  while the stream is quiet.
- `GET {base}/{id}/stream`, the spectate route, names the entity's events in its description.

**What to do:** nothing, unless a client was generated from the OpenAPI document. Such a client
called a streaming action as a JSON request and expected a JSON result; regenerate it, and read the
action's response as a server-sent event stream.
