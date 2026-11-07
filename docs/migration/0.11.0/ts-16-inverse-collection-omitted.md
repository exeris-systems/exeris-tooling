---
title: "0.11.0 migration step: a mappedBy collection leaves the entity types"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-11-07
---

### `exeris-codegen-ts`: a mappedBy collection leaves the entity types

A `List` field that a `ONE_TO_MANY` relationship with `mappedBy` declares is held by the target's
rows: the generated server answers it as `null` and takes it from neither request body. The
generated TypeScript client therefore leaves it out of the entity interface, `<Entity>Create`,
`<Entity>Update` and the Zod schemas, as it does a `@Blob` field. The type and the schema of the
target are no longer imported for it, and the list and the detail view show no field for it. Peer
DTOs follow the same rule.

The detail view's related-records panel is unchanged: it lists the rows through the target's
service, `findAll({ size: 10 }, { <key>: id })`, filtered by the back-reference that `mappedBy`
names. An entity without such a field generates exactly as before.

**What breaks.** Code that read the collection from an entity, or set it on a create or update
payload, no longer compiles, because the member is gone from those types. The server never
returned or stored a value there. Read the rows from the target's service with the key filter
instead, for example `invoiceLineService.findAll({ size: 20 }, { invoiceId: invoice.id })`.
