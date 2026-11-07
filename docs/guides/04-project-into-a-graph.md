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

**Goal:** generate the `graph` scenario's `Product`, read its `ProductGraphSync`, and call it from your own code.

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

### 4. Call the sync from your code

The run generates `ProductGraphSync` and wires it nowhere: no handler, service or `RuntimeComponents` factory constructs or calls it. `Application.subsystems()` lists `graph`, so the kernel boots a graph engine, and your code projects a row by calling `syncToGraph` after it is stored and `deleteFromGraph` after it is deleted. One place to do that is an override of `RuntimeComponents.createProductService()` returning a service subclass ([guide 02](02-generate-an-application.md), step 5).

The constructor takes the kernel's `GraphEngine` and a `MemoryAllocator`; the node's properties are encoded as one UTF-8 JSON object in a buffer loaned from that allocator.

## Verify

`SdkExamplesE2ETest.emittedTreesCompileClean` compiles `ProductGraphSync` against the kernel graph SPI under `-Xlint:all` with no warning. No emitted test exercises the graph sync, so its behavior against a graph store is checked only by a test you write against a graph driver.

## Not available today

- **A graph sync the application calls for you.** See step 4.
- **`@GraphQuery`.** `Product` declares a `neighbours` query, and nothing is generated from it.
- **A long indexed `List<UUID>` column.** The relational table stores `relatedIds` as JSON text in a `related_ids TEXT` column of `products`. A field without `@Field` is `searchable` and `filterable`, so the column is also indexed, and PostgreSQL refuses an index entry larger than 2,704 bytes: one row holds at most 69 keys. Annotating the field `@Field` drops the index and the bound, since the repository never queries the column.

## Where this does not apply

- **Foreign keys.** `categoryId` is a graph edge and not a `@Relationship`, so `products` gets no foreign-key constraint for it. A relational reference is [guide 03](03-reference-other-entities.md).
- **Graph queries at runtime.** Traversals beyond the stale-edge lookup are the kernel graph SPI's, not the generated code's.
- **Trade-off you accept.** Reading the stored edges before writing makes a sync converge on the entity's current state whatever the graph held before, at the cost of a traversal per edge field on every sync.
