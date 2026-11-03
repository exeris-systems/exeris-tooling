---
title: "0.11.0 migration step: graph sync synchronises edges, and a collection edge is a List<UUID>"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-11-03
---

### Graph sync synchronises edges, and a collection edge is a `List<UUID>`

A `@GraphEdge` field holds the key of the node it points at: a `UUID` for one edge, or a
`List<UUID>` for a collection edge. This is the same rule a reference follows elsewhere
(`EXT-GEN-3003`): the field holds the target's `UUID` key, not the target entity.

A regenerated `*GraphSync.syncToGraph(entity)` now synchronises each edge instead of only upserting
it. For each declared edge it:

1. reads the targets stored from the node along the edge's relation type, in one outgoing hop
   (`GraphSession.traverseBreadthFirst`). The hop is outgoing whatever direction the descriptor
   declares, because the sync writes every edge from source to target;
2. calls `deleteEdge` for each stored target the field no longer holds;
3. calls `upsertEdge` for each target it holds. For a `List<UUID>` that is one edge per distinct
   non-`null` key, in list order. Every element gets the edge's single weight: its `weightField`
   value, else its static weight, else `1.0`.

The same method serves a created and an updated entity, since a new node has no stored targets.
Edges of one entity that share a relation type are synchronised together, so a target that one of
them still holds is not deleted for another. `deleteFromGraph` is unchanged: `deleteNode` deletes
the node's edges with it.

**What changes in behaviour.** An update that changes a single edge's target now deletes the edge
to the old target. Before, the old edge stayed beside the new one. A field that is `null` now
deletes the edge it held. Before, the edge was left in place. Each edge sync costs one extra
traversal per relation type.

**What breaks.** Nothing that compiled before. A collection edge on a `List` of entities produced a
`*GraphSync` that `javac` rejected:

```java
@GraphEdge(type = "RELATED_TO", target = Product.class)
private List<Product> related;
```

Code generation now refuses it with `EXT-GEN-3003` before it writes anything, and names the field
to declare instead:

```
com.shop.domain.Product.related : java.util.List<com.shop.domain.Product> (a collection graph
edge to the entity Product, which no edge stores; hold the targets' keys instead: List<UUID>
relatedIds)
```

A `@GraphEdge` on a `List` of anything other than `UUID`, or on a single field that is not a
`UUID`, is refused the same way. To fix, hold the keys:

```java
@GraphEdge(type = "RELATED_TO", target = Product.class)
private List<UUID> relatedIds;
```

The `List<UUID>` is stored as a JSON column, like any other `List` field.

**Where it applies.** Only to entities with a `@GraphEdge`. Every other generated file is
byte-identical to the previous release.
