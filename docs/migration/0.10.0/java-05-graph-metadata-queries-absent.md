---
title: "0.10.0 migration step: `GraphMetadata.queries` is absent from the metadata, as `properties` is"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-10-07
---

### `GraphMetadata.queries` is absent from the metadata, as `properties` is

An `@Graph` entity's metadata JSON carries no `queries` key from 0.10.0; through 0.9.0 it carried
`"queries" : [ ]`. The processor extracts neither `@GraphQuery` nor `@GraphProperty`, and an empty
list claimed the entity declares no queries. Both components are written `null`, and
`@JsonInclude(NON_NULL)` keeps them off the wire: absent means "not carried", `[]` means "carried,
and there are none".

Generated Java and TypeScript are unchanged: no generator reads either component.

**If you read the metadata JSON yourself,** treat an absent `queries` as unknown, not as an empty
list.
