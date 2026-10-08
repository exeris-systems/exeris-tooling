---
title: "0.10.0 migration step: `exeris-codegen-ts`: a streaming action on a tenant-partitioned entity gets its stream client"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-10-08
---

### `exeris-codegen-ts`: a streaming action on a tenant-partitioned entity gets its stream client

`Compatibility impact: additive`.

A streaming action (`@Action(streaming = true)`) on a tenant-partitioned entity (`dataScope`
`TENANT` or `UNIVERSE`, or the deprecated `tenantScoped: true`) had no front-end entry point: no
stream client and no service method. The per-action stream route now runs a tenant guard, reads the
row through row-level security before the stream opens and filters the events by stream id
(ADR-044 Amendment 2), so the regeneration emits `src/app/services/<entity>.action-streams.ts` and
its export in `services/action-streams.index.ts` for such an entity, in the shape a `GLOBAL`
entity's client has. The streaming action still has no service method.

Until a route policy binds a requirement that is not permit-all, the server binds no storage
context for the stream, and the tenant guard answers the stream with a `stream-error` frame (status
500). The client is correct; the stream opens usefully once route authorization binds that context.

The live view (`<entity>.stream.ts`) and the spectate stream stay `GLOBAL`-only: the events they
forward carry no isolation key.

**What to do:** nothing. An app that wrote its own client for such an action can switch to the
generated one.
