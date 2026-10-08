---
title: "0.10.0 migration step: -Aexeris.strict reports the @DomainEvent attributes no generator consumes"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-10-08
---

### `-Aexeris.strict` reports the `@DomainEvent` attributes no generator consumes

Through 0.9.0 the strict audit skipped `@DomainEvent`: an attribute set on it was never reported,
whichever annotation form carried it. A build with `-Aexeris.strict` now warns, with diagnostic
`EXT-PROC-1201`, on each `@DomainEvent` attribute that no generator reads, on a class-level event
and on an event declared as a nested class.

The attributes generators read, and which stay silent, are `name`, `trigger`, `topic`, `action`,
`includeFields`, `excludeFields` and `sensitiveFields`. Every other attribute is reported, among
them `description` and `field`, which reach the metadata JSON but no emitter, and the messaging,
schema, retention, outbox and monitoring attributes, which the processor does not read.

Generated Java and TypeScript, the metadata JSON and the default build are unchanged. No public
Java API changes.

**If your build treats warnings as errors under `-Aexeris.strict`,** remove the reported attribute
from the event, or leave strict mode off for that build. Nothing else changes.
