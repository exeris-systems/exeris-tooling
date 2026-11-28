---
title: "0.12.0 migration step: `exeris-codegen-ts`: an event interface declares the fields the publisher sends and no other"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-11-28
---

### `exeris-codegen-ts`: an event interface declares the fields the publisher sends and no other

The generated Java publisher sends the payload record of a `@DomainEvent`: its `payloadFields`, without its `sensitiveFields`, and without a name the entity declares no field for. The TypeScript interfaces now declare the same set.

- `<Entity><Event>Event` in `events/<entity>.events.ts` and `DomainEvent` in `events/event-bus.service.ts` no longer declare `occurredAt`, `userId` and `correlationId`. No frame carries them, so a read of one gave `undefined`. `id`, `aggregateId`, `aggregateType` and `type` are unchanged, and `parseEvent` no longer builds the three.
- `<Entity><Event>Payload` and the spectate payload `<Entity><Event>SpectatePayload` no longer declare a sensitive field, and no longer declare `unknown` for a name no entity field carries.

For an `Order` event listing `payloadFields = {"orderId", "customerEmail", "total"}` with `sensitiveFields = {"customerEmail"}`, the payload interface was:

```ts
export interface OrderPlacedPayload {
    orderId: string;
    customerEmail: string; // sensitive: redacted before publish
    total: string;
}
```

and is now:

```ts
export interface OrderPlacedPayload {
    orderId: string;
    total: string;
}
```

An event whose payload resolves to no field is emitted as before, with the `// No additional payload fields` comment. Code that reads `occurredAt`, `userId`, `correlationId` or a sensitive member of an event no longer compiles; remove the read. The frame carries no envelope field, so none replaces them here.
