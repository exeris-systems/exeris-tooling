---
title: "0.10.0 migration step: `exeris-codegen-ts`: the event bus takes its endpoint from the consumer"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-10-08
---

### `exeris-codegen-ts`: the event bus takes its endpoint from the consumer

`Compatibility impact: breaking (ADR-044)`, for code that imports `EventBusService`, `DomainEvent` or
an `<Entity>EventHandler` from the generated tree.

`EventBusService` used to open an `EventSource` on `/api/v1/events/stream`, a path no generated
server route serves: a feed across every entity and every tenant is not generated (ADR-044
Amendment 2). The service now connects to the URL the new `eventBusEndpoint` configuration key names,
and without the key the event surface is not emitted: no `events/event-bus.service.ts`, no
`events/<entity>.events.ts` (each handler injects the bus), and none of their exports in the app
barrel. No emitted file contains the old path.

**What to do:** where the app uses the bus or an event handler, set `eventBusEndpoint` in
`exeris-codegen.json` to the URL of the feed your server runs, then regenerate:

```json
{ "eventBusEndpoint": "/orders/events" }
```

The key is file-only; it has no CLI flag. An app that never imported the bus needs no change: its
regeneration removes the files it never used. A
single entity's live view is the generated `<entity>.stream.ts` client.
