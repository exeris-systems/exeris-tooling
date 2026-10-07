---
title: "0.10.0 migration step: `exeris-codegen-ts`: a tenant-partitioned entity's DTOs omit `tenantId` (T36)"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-10-07
---

### `exeris-codegen-ts`: a tenant-partitioned entity's DTOs omit `tenantId` (T36)

A tenant-partitioned (TENANT or UNIVERSE) entity with no `systemFields` block no longer has
`tenantId` in its `<Entity>Create` and `<Entity>Update` types or in its `<Entity>CreateSchema`; the
`<Entity>` type and `<Entity>Schema` keep it. 0.9.0 kept it there, marked `@deprecated`. The server
owns it: the generated repository stamps the bound tenant, answers 400 to another one and never
updates it, and the emitted OpenAPI marks it `readOnly` and leaves it out of `…CreateDto` and
`…UpdateDto` (ADR-090). The TypeScript DTOs now say the same. Peer DTOs (`peers/<peer>/…`) follow the
same rule.

The DTOs omit exactly the field the OpenAPI marks `readOnly`: on a TENANT or UNIVERSE entity, the
`systemFields.tenantIdField` it names, else `tenantId`, with or without a block. A GLOBAL entity has
no owner, so a field it declares as `tenantId` is an ordinary field and stays in its DTOs. That
includes a GLOBAL entity with a `systemFields` block, which the processor writes for any system-field
override and always with `tenantIdField = "tenantId"`: 0.9.0 omitted such an entity's `tenantId` from
the `…Create` and `…Update` types and the create schema while its OpenAPI accepted it, and 0.10.0
puts it back.

**What to do:** on a tenant-partitioned entity, stop setting `tenantId` on the objects you pass to
`create` and `update`. A literal that still sets it fails to compile (an excess property), and
`CreateSchema.parse` strips it. On a GLOBAL entity with a block and a declared `tenantId` field, the
field is back in `…Create`; if it is required, a create call that does not set it fails to compile.
