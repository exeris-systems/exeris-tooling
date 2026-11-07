---
title: "0.11.0 migration step: the generated application registers and calls the graph sync"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-11-07
---

### The generated application registers and calls the graph sync

A regenerated application wires each `@Graph` entity's `<Entity>GraphSync` itself
([ADR-070](../../adr/ADR-070-generated-composition-root-seam.md), Amendment 4). Before, the class was
generated and nothing built, registered or called it.

- `RuntimeComponents` gains `create<Entity>GraphSync()` and the memoised `<entity>GraphSync()` for
  every `@Graph` entity. The factory reads `GraphKernelProviders.GRAPH_ENGINE`, and
  `KernelProviders.MEMORY_ALLOCATOR` when the entity declares `@GraphProperty`. `GRAPH_ENGINE` is
  listed in `COMPOSITION_SCOPES`.
- `<Entity>GraphSync` gains `registerDescriptors()`, and `RuntimeLifecycle.run()` calls it for
  every graph entity before it builds anything else.
- For an entity whose `dataScope` is neither `TENANT` nor `UNIVERSE`, `<Entity>Service` takes the
  graph sync as a second constructor argument. `save`, `update` and `updateFromRequest` call
  `syncToGraph` on the entity the repository returned, and `delete` calls `deleteFromGraph`, hard
  or soft. Each call runs after the repository returns.

**What breaks.**

- The one-argument constructor `<Entity>Service(<Entity>Repository)` is gone for such an entity. A
  subclass passes the graph sync to `super`, and an override of `create<Entity>Service()` passes the
  accessor:

  ```java
  @Override
  protected ProductService createProductService() {
      return new MyProductService(productRepository(), productGraphSync());
  }
  ```

- A graph failure now fails the write route with `500`, after the row has committed. A client that
  retries a failed create writes a second row. To let the write stand instead, override
  `create<Entity>GraphSync()` and return a graph sync whose `syncToGraph` catches:

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

  Drop the `MemoryAllocator` argument for an entity with no `@GraphProperty`, as its graph sync's
  constructor does.

**What changes in behaviour.**

- On PostgreSQL, the Community graph engine creates `graph_nodes` and one `<edge type>_edges` table
  per edge type at boot (`CREATE TABLE IF NOT EXISTS`), in the schema the application's role writes
  to. That role needs `CREATE` on the schema.
- Every create, update and delete of a synced entity pays the graph's round trips.
- A `syncToGraph` or `deleteFromGraph` call you added by hand, for example in a service subclass or
  after calling the service, now runs twice. The sync is idempotent, so remove the hand-written call.

**Where it applies.** Only to applications with a `@Graph` entity. A tenant-partitioned `@Graph`
entity's service is unchanged and its graph sync is not called, because the Community graph engine
cannot scope a traversal to the acting tenant on a request thread; the service's Javadoc says so.
Every file of an application without a `@Graph` entity is byte-identical to the previous release.
