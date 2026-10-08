---
title: "0.10.0 migration step: the live view writes keep-alive frames during quiet periods"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-10-08
---

### The live view writes keep-alive frames during quiet periods

`Compatibility impact: additive`, for a client of `GET {base}/stream` on an entity that declares a
`@DomainEvent`.

The generated `<Entity>StreamHandler` of such an entity writes a `keep-alive` frame, with empty
data, after each 15 seconds without an event. A client that disconnects while the entity is quiet
is noticed by the failed write of the next frame, which ends the stream and releases its event-bus
subscriptions and its thread; until now the stream was released only when the next event was
written. The handler declares a `KEEPALIVE_INTERVAL_MILLIS` constant for the interval.

After regenerating:

- `keep-alive` is a reserved frame name ([ADR-044](../../adr/ADR-044-tooling-sse-stream-emitter-shape.md)).
  A native `EventSource` does not deliver a named frame to `onmessage`, and a client that registers
  a listener per event name receives nothing for it;
- a client that reads the stream with a parser that rejects an event it does not know must accept
  `keep-alive`.

**What to do:** nothing, unless a client or a proxy treats every frame as a domain event.
