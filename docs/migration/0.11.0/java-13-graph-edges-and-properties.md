---
title: "0.11.0 migration step: graph sync projections emit edge directions, weights, and node properties"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-11-01
---

### Graph sync emits edge direction and weight, and node properties

The processor writes the eight `GraphEdgeMetadata` components SDK 0.13 adds, each only when the
source declares it. A field may now carry more than one `@GraphEdge`: each edge is named after the
field and its relation type (`relatedId` + `SIMILAR_TO` → `relatedIdSimilarTo`) and shares the
field. `EXT-PROC-1013`, which refused that declaration, is retired.

A regenerated `*GraphSync`:

- declares a `GraphEdgeDescriptor` with the edge's direction, `bidirectional` flag and static
  weight when any of them is declared, and the `create(...)` form otherwise;
- passes the `weightField` value as the `upsertEdge` weight, else the static weight, else `1.0`;
- on an entity with `@GraphProperty` fields, lists their names in `NODE_DESCRIPTOR` and passes their
  values to `upsertNode` as a UTF-8 JSON object in a buffer from a `MemoryAllocator`. Its
  constructor takes that allocator as a second argument:

```java
new ArticleGraphSync(graphEngine, allocator)
```

An entity without `@GraphProperty` keeps the one-argument constructor and passes `null` properties.
