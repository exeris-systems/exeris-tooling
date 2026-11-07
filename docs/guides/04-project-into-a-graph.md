---
title: Project into a graph
type: howto
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-11-04
---

# Project into a graph

**Audience:** you declare `@Graph`, `@GraphProperty` and `@GraphEdge` on an entity and want to know what the generated graph sync writes to the kernel graph SPI, and when.

**Goal:** generate the `graph` scenario's `Product`, read its `ProductGraphSync`, and see where the generated application calls it.

> **Verified against** exeris-tooling `main` at commit `dc4564a2` (`0.11.0-SNAPSHOT`) and `exeris-sdk-examples` at tag `v0.13.3`, 2026-11-04. Generated snippets are quoted from the `SdkExamplesE2ETest` workspace, `exeris-e2e-tests/target/sdk-examples/app/`. If a snippet and its source disagree, **the source wins and this guide is the defect**.

## Prerequisites

- [02 Generate an application from an entity](02-generate-an-application.md), for the rest of `Product`'s output: handler, service, repository and migration.
- The `graph` example: [`Product.java`](https://github.com/exeris-systems/exeris-sdk/blob/v0.13.3/exeris-sdk-examples/src/main/java/eu/exeris/sdk/examples/graph/domain/Product.java) and [`Category.java`](https://github.com/exeris-systems/exeris-sdk/blob/v0.13.3/exeris-sdk-examples/src/main/java/eu/exeris/sdk/examples/graph/domain/Category.java). How the annotations are written and read back is the SDK's [graph guide](https://github.com/exeris-systems/exeris-sdk/blob/v0.13.3/docs/guides/04-model-a-graph.md).

## Steps

### 1. Declare edges as keys

An edge field holds the target node's key: a `UUID` for one edge, a `List<UUID>` for many.

Source: [`exeris-sdk-examples/.../graph/domain/Product.java`](https://github.com/exeris-systems/exeris-sdk/blob/v0.13.3/exeris-sdk-examples/src/main/java/eu/exeris/sdk/examples/graph/domain/Product.java), the `categoryId` and `relatedIds` fields (quoted).

```java
@GraphEdge(type = "BELONGS_TO", target = Category.class)
private UUID categoryId;

@GraphEdge(type = "RELATED_TO", target = Product.class, description = "Bought together")
private List<UUID> relatedIds;
```

A `@GraphEdge` field of any other type, a `List<Product>` included, is refused with `EXT-GEN-3003`, because the sync writes one edge per target key and an entity holds none it can write ([diagnostics](../diagnostics.md)).

### 2. Read the descriptors

`@Graph(nodeClass = "CatalogItem")` sets the node label, the `@GraphProperty` fields become the node's properties under their declared names, and each edge field becomes one edge descriptor.

Generated: `src/main/generated/java/eu/exeris/sdk/examples/graph/graph/ProductGraphSync.java`, the descriptor constants (quoted).

```java
public static final GraphNodeDescriptor NODE_DESCRIPTOR = new GraphNodeDescriptor("CatalogItem", "products", "id", List.of("title", "price"), true);

public static final GraphEdgeDescriptor CATEGORY_ID_EDGE = GraphEdgeDescriptor.create("CatalogItem", "BELONGS_TO", "Category");

public static final GraphEdgeDescriptor RELATED_IDS_EDGE = GraphEdgeDescriptor.create("CatalogItem", "RELATED_TO", "Product");
```

`name` is stored as `title`, as its `@GraphProperty(name = "title")` declares, and `note`, which carries no `@GraphProperty`, stays out of the node. `Category` declares no `@Graph`, so it gets no graph sync and appears only as an edge's target label.

### 3. Know what a sync writes and deletes

`syncToGraph(Product)` writes the node with `upsertNode`, then, for each edge field, deletes every stored edge of that type to a target the entity does not hold, and writes an edge with `upsertEdge` to each target it does hold.

Generated: `src/main/generated/java/eu/exeris/sdk/examples/graph/graph/ProductGraphSync.java`, method `syncToGraph` (adapted: the logging and the catch block removed).

```java
try (GraphSession session = graphEngine.openSession()) {
    try (LoanedBuffer properties = encodeNodeProperties(entity)) {
        session.upsertNode(NODE_LABEL, entity.getId(), properties);
    }
    Set<UUID> categoryIdTargets = targets(entity.getCategoryId());
    removeStaleEdges(session, entity.getId(), categoryIdTargets, CATEGORY_ID_EDGE);
    upsertEdges(session, CATEGORY_ID_EDGE, entity.getId(), categoryIdTargets, 1.0);
    Set<UUID> relatedIdsTargets = targets(entity.getRelatedIds());
    removeStaleEdges(session, entity.getId(), relatedIdsTargets, RELATED_IDS_EDGE);
    upsertEdges(session, RELATED_IDS_EDGE, entity.getId(), relatedIdsTargets, 1.0);
}
```

So a sync leaves the graph holding exactly the entity's current edges: setting `categoryId` to another category moves the `BELONGS_TO` edge, and removing a key from `relatedIds` deletes its `RELATED_TO` edge. A `null` key and a repeated key in the list are skipped. The stale targets are found by a one-hop outgoing traversal, the direction every edge is written in.

This example declares no direction, `bidirectional` or weight on its edges, so each descriptor is built with `GraphEdgeDescriptor.create` and each `upsertEdge` call carries weight `1.0`. An edge that declares them carries them in its descriptor and its `upsertEdge` call; those attributes are at the SDK's preview tier ([SDK stability matrix](https://github.com/exeris-systems/exeris-sdk/blob/v0.13.3/docs/stability-matrix.md)).

`deleteFromGraph(UUID)` deletes the node, and the graph SPI's `deleteNode` contract deletes its incident edges with it.

### 4. Know who calls the sync, and when

The generated application wires the graph sync itself ([ADR-070](../adr/ADR-070-generated-composition-root-seam.md), Amendment 4):

- `RuntimeComponents` builds it in `createProductGraphSync()` from the kernel's `GraphEngine` and `MemoryAllocator`, read inside the boot callback where both are bound. The node's properties are encoded as one UTF-8 JSON object in a buffer loaned from that allocator.
- `RuntimeLifecycle.run()` calls `registerDescriptors()` on it before anything else. Registration is what makes the Community SQL graph create `graph_nodes` and one table per edge type, and what lets a node delete cascade through those edge tables.
- `ProductService` takes it as a constructor argument and calls it after each repository write: `syncToGraph` on the entity `save`, `update` or `updateFromRequest` returned, which is the row as stored, and `deleteFromGraph` after `delete`, whether the delete is hard or soft. Every generated write route, each action included, goes through the service, and so does your code when it calls the service.

Generated: `src/main/generated/java/eu/exeris/sdk/examples/graph/service/ProductService.java`, methods `save` and `delete` (quoted).

```java
public Product save(Product entity) {
    Product saved = repository.save(entity);
    graphSync.syncToGraph(saved);
    return saved;
}
```

```java
public void delete(UUID id) {
    repository.deleteById(id);
    graphSync.deleteFromGraph(id);
}
```

The sync runs after the repository returns, so after the row has committed: the graph SPI offers no transaction that spans the relational store. A write the repository refuses, a missing row or a conflict, throws before the graph is touched. A graph failure propagates, so the route answers `500` for a row that is stored. To let the write stand instead, return a graph sync that catches from an override of `createProductGraphSync()`:

```java
@Override
protected ProductGraphSync createProductGraphSync() {
    return new ProductGraphSync(GraphKernelProviders.GRAPH_ENGINE.get(), KernelProviders.MEMORY_ALLOCATOR.get()) {
        @Override
        public void syncToGraph(Product entity) {
            try {
                super.syncToGraph(entity);
            } catch (RuntimeException e) {
                // logged by the graph sync; the node catches up on the entity's next write
            }
        }
    };
}
```

An entity whose `dataScope` is `TENANT` or `UNIVERSE` is not synced by its service. The Community graph engine cannot scope a traversal to the acting tenant on a request thread, where no `PersistenceEngine` is bound, and it keeps every node in one `graph_nodes` table that carries no tenant. `RuntimeComponents` still builds that entity's graph sync, and the service's Javadoc says why it does not call it.

## Verify

`SdkExamplesE2ETest.emittedTreesCompileClean` compiles `ProductGraphSync` and the service that calls it against the kernel graph SPI under `-Xlint:all` with no warning. The emitted `ProductServiceTest` asserts that each write projects the entity the repository returned, that a delete removes the node, and that a refused write projects nothing.

`GraphSyncPostgresBootE2ETest` boots a generated application on a real kernel against PostgreSQL and reads the graph tables back: a create writes the node and its edge, an update that changes `categoryId` deletes the stale edge, and a delete removes the node and its edges. Without the boot registration the same create answers `500` with its row committed. It runs in the `e2e-postgres` profile, because the Community SQL graph writes `jsonb` and `ON CONFLICT`, which H2 does not run.

## Not available today

- **A graph sync for a tenant-partitioned entity.** See step 4.
- **Repair after a crash between the commit and the sync.** The row is stored and the node is not, until the entity's next write syncs it.
- **`@GraphQuery`.** `Product` declares a `neighbours` query, and nothing is generated from it.
- **A wide `List<UUID>` column.** The relational table stores `relatedIds` as JSON text in a `related_ids VARCHAR(255)` column of `products`, so the column's width bounds how many keys one row can hold.

## Where this does not apply

- **Foreign keys.** `categoryId` is a graph edge and not a `@Relationship`, so `products` gets no foreign-key constraint for it. A relational reference is [guide 03](03-reference-other-entities.md).
- **Graph queries at runtime.** Traversals beyond the stale-edge lookup are the kernel graph SPI's, not the generated code's.
- **Trade-off you accept.** Reading the stored edges before writing makes a sync converge on the entity's current state whatever the graph held before, at the cost of a traversal per edge field on every sync.
