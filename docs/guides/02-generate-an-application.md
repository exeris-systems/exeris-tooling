---
title: Generate an application from an entity
type: howto
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-11-04
---

# Generate an application from an entity

**Audience:** your build runs the tooling, as in [guide 01](01-add-the-tooling-to-a-build.md), and you want to know what one entity turns into and which parts of it you still write.

**Goal:** generate the application of the `ordering` scenario's `Order`, read its routes, its persistence and its events, run its emitted tests, and decide whether to keep regenerating or detach.

> **Verified against** exeris-tooling `main` at commit `dc4564a2` (`0.11.0-SNAPSHOT`) and `exeris-sdk-examples` at tag `v0.13.3`, 2026-11-04. Generated snippets are quoted from the `SdkExamplesE2ETest` workspace, `exeris-e2e-tests/target/sdk-examples/app/`, with base package `eu.exeris.sdk.examples.app`. If a snippet and its source disagree, **the source wins and this guide is the defect**.

## Prerequisites

- [01 Add the tooling to a build](01-add-the-tooling-to-a-build.md) done.
- The `ordering` example: [`Order.java`](https://github.com/exeris-systems/exeris-sdk/blob/v0.13.3/exeris-sdk-examples/src/main/java/eu/exeris/sdk/examples/ordering/domain/Order.java). How its annotations are written is the SDK's [behavior guide](https://github.com/exeris-systems/exeris-sdk/blob/v0.13.3/docs/guides/03-declare-behavior.md).

## Steps

### 1. Read the entity the way the generators do

Source: [`exeris-sdk-examples/.../ordering/domain/Order.java`](https://github.com/exeris-systems/exeris-sdk/blob/v0.13.3/exeris-sdk-examples/src/main/java/eu/exeris/sdk/examples/ordering/domain/Order.java), the class annotations (adapted: `@SagaTransition` and `@Rule` removed, `@Saga` shortened to its name and trigger).

```java
@ExerisDomain(module = "ordering", path = "/orders", description = "A customer purchase")
@DomainEvent(name = "OrderPlaced", trigger = DomainEvent.Trigger.CREATE,
        topic = "ordering.order.placed",
        includeFields = {"number", "status", "totalCents"})
@DomainEvent(name = "OrderCancelled", trigger = DomainEvent.Trigger.ACTION, action = "cancel",
        topic = "ordering.order.cancelled")
@DomainEvent(name = "OrderStatusChanged", trigger = DomainEvent.Trigger.FIELD_CHANGED, field = "status",
        topic = "ordering.order.status-changed",
        excludeFields = {"subtotalCents", "discountCents"})
@Saga(name = "OrderFulfilment",
        trigger = @Saga.SagaTrigger(type = Saga.SagaTrigger.TriggerType.EVENT, topic = "ordering.order.placed"))
public class Order {
```

`path` becomes the route prefix. Each `@DomainEvent` becomes a publish method, each `@Action` a route, the `@Saga` a flow class, and each `@Field` a column.

### 2. Find what was written

The run writes these files for `Order`, under `src/main/generated/java`:

Generated: `src/main/generated/java/.exeris-codegen-manifest`, the lines for `Order` (quoted).

```text
db/migration/V1423891__create_orders.sql
eu/exeris/sdk/examples/ordering/client/OrderClient.java
eu/exeris/sdk/examples/ordering/event/OrderEventPublisher.java
eu/exeris/sdk/examples/ordering/event/OrderEventSubscriber.java
eu/exeris/sdk/examples/ordering/handler/OrderHandler.java
eu/exeris/sdk/examples/ordering/repository/OrderListQuery.java
eu/exeris/sdk/examples/ordering/repository/OrderNotFoundException.java
eu/exeris/sdk/examples/ordering/repository/OrderPage.java
eu/exeris/sdk/examples/ordering/repository/OrderRepository.java
eu/exeris/sdk/examples/ordering/saga/OrderFulfilmentFlow.java
eu/exeris/sdk/examples/ordering/service/OrderService.java
openapi/order-api.yaml
```

The manifest lists every path the run owns; a tool that explains a generated tree reads it and nothing else. Which generator wrote each path, and under which condition, is the [generator catalogue](../generators.md#main-root-entity-scope). The project-scope files, `Application`, `RuntimeComponents`, `RuntimeLifecycle` and `GeneratedRoutePolicy`, are in the base package and serve every entity of the build.

The base package is `exeris.basePackage`. Left unset over entities in several packages, as the corpus's are, the pipeline picks one and warns with `EXT-GEN-3104` ([diagnostics](../diagnostics.md)).

### 3. Read the routes

`RuntimeLifecycle` registers each handler method on the kernel router.

Generated: `src/main/generated/java/eu/exeris/sdk/examples/app/RuntimeLifecycle.java`, the `/orders` routes (quoted).

```java
routerBuilder.route(HttpMethod.GET, "/orders", orderHandler::handleGetAll);
routerBuilder.route(HttpMethod.GET, "/orders/{id}", orderHandler::handleGetById);
routerBuilder.route(HttpMethod.POST, "/orders", orderHandler::handleCreate);
routerBuilder.route(HttpMethod.PUT, "/orders/{id}", orderHandler::handleUpdate);
routerBuilder.route(HttpMethod.DELETE, "/orders/{id}", orderHandler::handleDelete);
routerBuilder.route(HttpMethod.POST, "/orders/{id}/actions/cancel", orderHandler::handleCancel);
```

The action route is `POST {path}/{id}/actions/{kebab-case action name}` for every action. `openapi/order-api.yaml` documents the same routes as OpenAPI 3.1.

An action route loads the row, calls the entity's method, stores the row and publishes the action's event.

Generated: `src/main/generated/java/eu/exeris/sdk/examples/ordering/handler/OrderHandler.java`, method `handleCancel` (adapted: the id parsing and the catch blocks removed).

```java
Optional<Order> found = service.findById(id);
if (found.isEmpty()) {
    exchange.respond(HttpStatus.NOT_FOUND);
    return;
}
Order entity = found.get();
entity.cancel();
Order updated = service.update(id, entity);
publisher.publishOrderCancelledEvent(id, updated);
exchange.respond(HttpStatus.OK, updated);
```

So the body of `Order.cancel()` in your entity is the business logic of that route. An exception it throws is answered 500, as the method's generated Javadoc notes.

### 4. Read the table

Generated: `src/main/generated/java/db/migration/V1423891__create_orders.sql` (adapted: the two comment lines removed).

```sql
CREATE TABLE IF NOT EXISTS orders (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    number VARCHAR(255) NOT NULL UNIQUE,
    status VARCHAR(255),
    subtotal_cents BIGINT NOT NULL,
    discount_cents BIGINT,
    total_cents BIGINT
);

CREATE INDEX IF NOT EXISTS idx_orders_number ON orders(number);
```

`required = true` gives `NOT NULL`, `unique = true` gives `UNIQUE` and an index. The version prefix is derived from the entity's qualified name, never from the clock, so regenerating writes the same file name; the formula is the catalogue's `{nnnnnn}` [placeholder](../generators.md#placeholders).

### 5. Supply the values the server owns

`number` is `required`, `readOnly` and `inCreate = false`: the client may not send it, and the row may not be stored without it. The generated repository refuses the insert rather than invent a value.

Generated: `src/main/generated/java/eu/exeris/sdk/examples/ordering/repository/OrderRepository.java`, method `save` (quoted, one statement).

```java
throw new IllegalStateException("Cannot create Order: field 'number' is required and read-only, so the server must set it before the row is written");
```

Until you set it, `POST /orders` answers 500. The seam is `RuntimeComponents`: override `createOrderService()` to return a subclass of `OrderService` whose `save` assigns the number before calling `super.save`, and install your `RuntimeComponents` by overriding `Application.components(TransactionalExecutor)`. The hooks are listed on the [codegen-java page](../modules/04-codegen-java.md#the-generated-application) and decided in [ADR-070](../adr/ADR-070-generated-composition-root-seam.md).

### 6. Implement the saga steps

`OrderFulfilmentFlow` compiles the three `@SagaStep` methods and their compensations into a flow plan. Each step's default body logs and continues.

Generated: `src/main/generated/java/eu/exeris/sdk/examples/ordering/saga/OrderFulfilmentFlow.java`, method `initialize` (adapted: the cache check removed).

```java
FlowDefinitionBuilder builder = flowEngine.plans().newDefinition(DEFINITION_NAME);
builder.step("capturePayment", this::capturePayment, this::refundPayment);
builder.step("reserveStock", this::reserveStock, this::releaseStock);
builder.step("scheduleShipment", this::scheduleShipment, null);
builder.transition(0, 1);
builder.transition(1, 2);
builder.timeoutDuration(TIMEOUT_NANOS).maxRetries(MAX_RETRIES);
this.plan = flowEngine.plans().compile(builder.build());
```

The step methods are `protected` on the flow class and do not call the entity's methods of the same name. Subclass the flow, override the steps, and return the subclass from `RuntimeComponents.createOrderFulfilmentFlow()`. `RuntimeLifecycle` calls `initialize()` at boot.

### 7. Run the emitted tests

With `exeris.tests=true`, `runTests` writes tests for the handler, service and repository of each entity and for each saga flow, into `src/test/generated/java`, with recording doubles of the kernel SPI interfaces in the base package's `testsupport` package ([ADR-058](../adr/ADR-058-generated-test-emission-channel.md)). For `Order` the run writes `OrderHandlerTest`, `OrderServiceTest`, `OrderRepositoryTest` and `OrderFulfilmentFlowTest`. They need JUnit 5 and AssertJ in your test scope and nothing else.

### 8. Commit the tree, or detach it

The generated tree is committed (L1). Regenerating rewrites the files the manifest lists and deletes each path the previous manifest listed and the run did not write, so an edit inside `src/main/generated/java` is lost on the next build. Customize through the `protected` hooks above, from your own sources.

When you would rather own the code, `mvn exeris:detach` moves the tree into `src/main/java` (L2) and you remove the `exeris:generate` execution. It moves the main tree only, never overwrites a file of yours, and has no way back. The goal's contract is on the [Maven plugin page](../modules/06-codegen-maven-plugin.md#detach); the terms are in the [glossary](../glossary.md) and the policy in [`committed-generated-code.md`](../../.agents/policies/committed-generated-code.md).

## Verify

`SdkExamplesE2ETest` checks this scenario with the others: `everyEntityIsServed` asserts the handler, service and repository of `Order`, `emittedTreesCompileClean` compiles both generated trees under `-Xlint:all` with no warning, `generatedTestsPass` runs the emitted tests, and `generationIsDeterministic` regenerates into a second workspace and compares every file. In your own build, `mvn verify` with `exeris.tests=true` runs the emitted tests with your other tests.

## Not available today

- **`FIELD_CHANGED` events.** `OrderEventPublisher.publishOrderStatusChangedEvent` is generated and nothing calls it. Only `CREATE`, `UPDATE`, `DELETE` and `ACTION` triggers publish from a generated handler ([ADR-075](../adr/ADR-075-generated-event-publisher-caller.md)).
- **Starting the saga from its trigger.** `@Saga.SagaTrigger(type = EVENT, topic = ...)` reaches the metadata, and no generated subscriber schedules the flow when `ordering.order.placed` is published. Your code calls `OrderFulfilmentFlow.schedule(FlowContext)`.
- **`@Rule`, `@Derived` and `@SagaTransition`.** They compile and generate nothing, and under `-Aexeris.strict=true` the processor reports each as `EXT-PROC-1203`. `totalCents` is an ordinary column that nothing computes, and the flow's transitions come from the step order.

## Where this does not apply

- **Several entities that reference each other.** Foreign keys, reference refusals and tenant isolation are [guide 03](03-reference-other-entities.md).
- **Running the application.** Booting `Application` on a kernel driver is the kernel's [guide](https://github.com/exeris-systems/exeris-kernel/blob/main/docs/guides/02-build-an-application.md).
- **Trade-off you accept.** Regenerating keeps the code in step with the entity and forbids editing it in place, so every change goes through an override. Detaching allows any edit and stops every later fix in the generators from reaching you.
