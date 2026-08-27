package eu.exeris.tooling.codegen.java.kernel;

import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator.ArtifactType;
import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import eu.exeris.sdk.sourcemodel.ast.ActionMetadata;
import eu.exeris.sdk.sourcemodel.ast.ActionParamMetadata;
import eu.exeris.sdk.sourcemodel.ast.DataScope;
import eu.exeris.sdk.sourcemodel.ast.DomainEventMetadata;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.FieldMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Per-generator test for {@link KernelHandlerGenerator}.
 *
 * <p>Goes through {@link KernelGeneratorStrategy} so the test mirrors the
 * way the generator is actually invoked in production (registry-driven,
 * not direct).
 */
@DisplayName("KernelHandlerGenerator")
class KernelHandlerGeneratorTest {

    private KernelGeneratorStrategy strategy;

    @BeforeEach
    void setup() {
        strategy = new KernelGeneratorStrategy();
    }

    /** An entity whose events cover every trigger the handler serves (T48 / ADR-075). */
    private static DomainMetadata orderWithEvents() {
        return DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders")
                .fields(List.of(FieldMetadata.simple("amount", "java.math.BigDecimal")))
                .actions(List.of(ActionMetadata.builder("approve").methodName("approve").build()))
                .events(List.of(
                        DomainEventMetadata.builder("OrderCreated")
                                .trigger(DomainEventMetadata.Trigger.CREATE)
                                .build(),
                        DomainEventMetadata.builder("OrderAmended")
                                .trigger(DomainEventMetadata.Trigger.UPDATE)
                                .payloadFields(List.of("amount"))
                                .build(),
                        DomainEventMetadata.builder("OrderCancelled")
                                .trigger(DomainEventMetadata.Trigger.DELETE)
                                .build(),
                        DomainEventMetadata.builder("OrderApproved")
                                .trigger(DomainEventMetadata.Trigger.ACTION)
                                .actionName("approve")
                                .build()))
                .build();
    }

    /**
     * Strips each line's leading whitespace, so an expected block can be written as a contiguous
     * run of statements without pinning JavaPoet's indentation. What survives is what matters
     * here: which statements sit next to each other, and in what order.
     */
    private static String noIndent(String source) {
        return source.replaceAll("(?m)^[ \\t]+", "");
    }

    private GeneratedFile handlerFor(DomainMetadata metadata) {
        return strategy.generate(metadata).stream()
                .filter(f -> f.artifactType() == ArtifactType.CONTROLLER)
                .findFirst()
                .orElseThrow();
    }

    @Test
    @DisplayName("T48: the publisher is a constructor argument and every trigger gets its call")
    void shouldPublishOnEveryTrigger() {
        GeneratedFile handler = handlerFor(orderWithEvents());

        assertThat(handler.content())
                .contains("import com.example.event.OrderEventPublisher")
                .contains("private final OrderEventPublisher publisher")
                .contains("public OrderHandler(OrderService service, OrderEventPublisher publisher)")
                // Each call lands after its mutation and before the response, which is the
                // ordering the whole design turns on — a publish before the write would
                // announce a row that may not exist.
                .containsSubsequence(
                        "Order saved = service.save(entity)",
                        "publisher.publishOrderCreatedEvent(saved.getId())",
                        "exchange.respond(HttpStatus.CREATED, saved)")
                .containsSubsequence(
                        "Order updated = service.update(id, entity)",
                        "publisher.publishOrderAmendedEvent(id, updated)",
                        "exchange.respond(HttpStatus.OK, updated)")
                .containsSubsequence(
                        "service.delete(id)",
                        "publisher.publishOrderCancelledEvent(id)",
                        "exchange.respond(HttpStatus.NO_CONTENT)")
                // The ACTION trigger is the case a service-held publisher could not reach:
                // the action is invoked on the entity, here.
                .containsSubsequence(
                        "entity.approve()",
                        "Order updated = service.update(id, entity)",
                        "publisher.publishOrderApprovedEvent(id)");
    }

    @Test
    @DisplayName("D7/ADR-076: a write that matched no row answers 404, and the catch that says so "
            + "precedes the one that answers 500")
    void shouldAnswerNotFoundForAnAbsentRow() {
        GeneratedFile handler = handlerFor(DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders")
                .fields(List.of(FieldMetadata.simple("amount", "java.math.BigDecimal")))
                .actions(List.of(ActionMetadata.builder("approve").methodName("approve").build()))
                .build());

        // Contiguous blocks, not containsSubsequence: every CRUD method ends in the same shapes,
        // so a subsequence over the whole file can be satisfied by clauses belonging to a
        // different method. A perturbation run proved it — deleting the delete route's catch
        // still passed a subsequence assertion, which matched the action route's instead.
        assertThat(handler.content())
                .contains("import com.example.repository.OrderNotFoundException");
        assertThat(noIndent(handler.content()))
                // The typed catch must precede catch (RuntimeException); the other order is
                // actually a javac error in the consumer's build ("already caught"), so this
                // pins that the emitter never produces the unbuildable arrangement either.
                .contains("""
                        service.delete(id);
                        exchange.respond(HttpStatus.NO_CONTENT);
                        } catch (OrderNotFoundException e) {
                        exchange.respond(HttpStatus.NOT_FOUND);
                        } catch (RuntimeException e) {""")
                .contains("""
                        Order updated = service.update(id, entity);
                        exchange.respond(HttpStatus.OK, updated);
                        } catch (OrderNotFoundException e) {
                        exchange.respond(HttpStatus.NOT_FOUND);
                        } catch (RuntimeException e) {""")
                // The action route persists through the same service.update, so it inherits it.
                .contains("""
                        entity.approve();
                        Order updated = service.update(id, entity);
                        exchange.respond(HttpStatus.OK, updated);
                        } catch (OrderNotFoundException e) {""")
                // Unversioned: no conflict type exists for this entity, so no clause names one.
                .doesNotContain("OrderVersionConflictException")
                .doesNotContain("HttpStatus.CONFLICT");
    }

    @Test
    @DisplayName("D7/ADR-076: a versioned update answers 409, because it cannot tell a missing "
            + "row from a stale version — but its delete still answers 404")
    void shouldAnswerConflictForAVersionedUpdate() {
        GeneratedFile handler = handlerFor(DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders")
                .versioned(true)
                .fields(List.of(FieldMetadata.simple("amount", "java.math.BigDecimal")))
                .build());

        assertThat(handler.content())
                .contains("import com.example.repository.OrderVersionConflictException");
        assertThat(noIndent(handler.content()))
                .contains("""
                        Order updated = service.update(id, entity);
                        exchange.respond(HttpStatus.OK, updated);
                        } catch (OrderVersionConflictException e) {
                        exchange.respond(HttpStatus.CONFLICT);""")
                // deleteById matches on id alone, so the delete route has no conflict to report
                // even here — the two routes genuinely differ, and this pins that they do.
                .contains("""
                        service.delete(id);
                        exchange.respond(HttpStatus.NO_CONTENT);
                        } catch (OrderNotFoundException e) {
                        exchange.respond(HttpStatus.NOT_FOUND);""");
    }

    @Test
    @DisplayName("T48: an entity with no events keeps its single-argument constructor")
    void shouldNotTakeAPublisherWithoutEvents() {
        GeneratedFile handler = handlerFor(DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders")
                .build());

        assertThat(handler.content())
                .contains("public OrderHandler(OrderService service)")
                .doesNotContain("OrderEventPublisher")
                .doesNotContain("publisher.publish");
    }

    @Test
    @DisplayName("T48: an event with no trigger is published by no handler method")
    void shouldNotPublishAnEventWithoutATrigger() {
        // trigger is nullable by design — null means "this baseline predates EV2
        // extraction", which is a different claim from "fires on CREATE". Guessing CREATE
        // here would publish an event the author never asked for on every create.
        GeneratedFile handler = handlerFor(DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders")
                .events(List.of(DomainEventMetadata.simple("OrderCreated")))
                .build());

        // And it takes no publisher either: a field no emitted line reads is inert wiring.
        assertThat(handler.content())
                .contains("public OrderHandler(OrderService service)")
                .doesNotContain("OrderEventPublisher");
    }

    @Test
    @DisplayName("T48: an event whose trigger no handler method serves brings no publisher")
    void shouldNotTakeAPublisherForAnUnservedTrigger() {
        // MANUAL is published by the consumer's own code. The publisher is still emitted and
        // still joins RuntimeComponents — that is how such code reaches it — but the handler
        // has nothing to do with it.
        GeneratedFile handler = handlerFor(DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders")
                .events(List.of(DomainEventMetadata.builder("OrderNoted")
                        .trigger(DomainEventMetadata.Trigger.MANUAL)
                        .build()))
                .build());

        assertThat(handler.content())
                .contains("public OrderHandler(OrderService service)")
                .doesNotContain("OrderEventPublisher");
    }

    @Test
    @DisplayName("T48: a payload-bearing DELETE event reads the aggregate before deleting it")
    void shouldReadTheAggregateForAPayloadBearingDelete() {
        GeneratedFile handler = handlerFor(DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders")
                .fields(List.of(FieldMetadata.simple("amount", "java.math.BigDecimal")))
                .events(List.of(DomainEventMetadata.builder("OrderCancelled")
                        .trigger(DomainEventMetadata.Trigger.DELETE)
                        .payloadFields(List.of("amount"))
                        .build()))
                .build());

        // Deleting an absent id still answers 204, so the read must not turn a no-op
        // delete into an event.
        assertThat(handler.content())
                .containsSubsequence(
                        "Optional<Order> removed = service.findById(id)",
                        "service.delete(id)",
                        "if (removed.isPresent())",
                        "publisher.publishOrderCancelledEvent(id, removed.get())",
                        "exchange.respond(HttpStatus.NO_CONTENT)");
    }

    @Test
    @DisplayName("T48: a DELETE event with no payload keeps the single-statement delete")
    void shouldNotReadTheAggregateForAPayloadFreeDelete() {
        GeneratedFile handler = handlerFor(orderWithEvents());

        assertThat(handler.content()).doesNotContain("removed = service.findById(id)");
    }

    @Test
    @DisplayName("Should generate Handler emitting against Open-Core SPI HttpExchange")
    void shouldGenerateHandler() {
        DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders")
                .build();

        List<GeneratedFile> files = strategy.generate(metadata);

        GeneratedFile handler = files.stream()
                .filter(f -> f.artifactType() == ArtifactType.CONTROLLER)
                .findFirst()
                .orElseThrow();

        assertThat(handler.className()).isEqualTo("OrderHandler");
        assertThat(handler.packageName()).isEqualTo("com.example.handler");
        assertThat(handler.content())
                .contains("public class OrderHandler")
                .contains("import eu.exeris.kernel.spi.http.HttpExchange")
                .contains("import eu.exeris.kernel.spi.http.HttpStatus")
                .contains("import eu.exeris.kernel.spi.memory.LoanedBuffer")
                .contains("OrderService service")
                .contains("handleGetAll(HttpExchange exchange)")
                .contains("handleGetById(HttpExchange exchange)")
                .contains("handleCreate(HttpExchange exchange)")
                .contains("handleUpdate(HttpExchange exchange)")
                .contains("handleDelete(HttpExchange exchange)")
                // kernel 0.10 boot-path (#224): the {id} path var is read from
                // pathParams(), replacing the raw-path lastIndexOf string surgery
                .contains("exchange.pathParams().getOrDefault(\"id\", \"\")")
                .doesNotContain("lastIndexOf")
                .contains("exchange.respond(HttpStatus.OK")
                .contains("HttpStatus.CREATED")
                .contains("HttpStatus.NO_CONTENT")
                .contains("HttpStatus.BAD_REQUEST")
                .contains("HttpStatus.NOT_FOUND")
                .contains("HttpStatus.INTERNAL_SERVER_ERROR");
    }

    @Test
    @DisplayName("Request body decode resolves the ADR-036 SPI registry, not an inline Jackson MAPPER")
    void shouldDecodeRequestBodyViaRequestBodyDecoderSpi() {
        DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders")
                .build();

        GeneratedFile handler = strategy.generate(metadata).stream()
                .filter(f -> f.artifactType() == ArtifactType.CONTROLLER)
                .findFirst()
                .orElseThrow();

        assertThat(handler.content())
                // ADR-036: request body decode resolves through the SPI registry …
                .contains("import eu.exeris.kernel.spi.http.HttpRequestBodyDecoder")
                .contains("import eu.exeris.kernel.spi.http.HttpRequestBodyDecoderRegistry")
                .contains("import eu.exeris.kernel.spi.http.HttpRequestDecodingContext")
                .contains("import eu.exeris.kernel.spi.http.HttpKernelProviders")
                .contains("import eu.exeris.kernel.spi.context.KernelProviders")
                .contains("httpRequestBodyDecoderRegistry()")
                .contains("registry.resolve(type, contentType)")
                .contains("decoder.decode(body, type, context)")
                .contains("firstHeader(\"content-type\")")
                // … hands the decoder the LoanedBuffer + a fresh decoding context …
                .contains("exchange.request().hasBody()")
                .contains("new HttpRequestDecodingContext(")
                .contains("KernelProviders.MEMORY_ALLOCATOR.get()")
                // … and consumes the LoanedBuffer directly — no byte[]/String round-trip.
                .doesNotContain("new String(")
                .doesNotContain("MemorySegment.copy");
        // The Wall: no concrete Jackson type may be baked into generated application source.
        assertThat(handler.content())
                .doesNotContain("tools.jackson")
                .doesNotContain("ObjectMapper")
                .doesNotContain("MAPPER");
    }

    @Test
    @DisplayName("T43: an unbound MEMORY_ALLOCATOR is a wiring fault (5xx), not a bad request (400)")
    void unboundAllocatorIsRefusedAsAConfigurationFaultNotABadRequest() {
        DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders")
                .build();

        String handler = strategy.generate(metadata).stream()
                .filter(f -> f.artifactType() == ArtifactType.CONTROLLER)
                .findFirst()
                .orElseThrow()
                .content();

        // MEMORY_ALLOCATOR is a ScopedValue, so .get() on an unbound one throws
        // NoSuchElementException — a RuntimeException, which the catch below turned into
        // IllegalArgumentException("Invalid request body") and the call site into 400. The
        // caller was told their body was bad; the body had not been read yet.
        assertThat(handler)
                .contains("if (!KernelProviders.MEMORY_ALLOCATOR.isBound())")
                .contains("No MemoryAllocator is bound")
                // The message has to name the wiring, not the request — same standard the
                // unbound-registry refusal one line up already meets.
                .contains("'memory' subsystem")
                .contains("This is a wiring fault, not a malformed request");

        assertThat(handler)
                // The guard precedes the construction it protects, sits inside the guarded
                // try, and throws the IllegalStateException that the catch re-throws
                // unchanged — so it surfaces as 5xx exactly like the unbound registry, and
                // is never downgraded to 400 (ADR-036 §2).
                .containsSubsequence(
                        "registry.resolve(type, contentType)",
                        "if (!KernelProviders.MEMORY_ALLOCATOR.isBound())",
                        "throw new IllegalStateException(\"No MemoryAllocator is bound",
                        "new HttpRequestDecodingContext(",
                        "catch (IllegalStateException e)",
                        "throw e;",
                        "catch (RuntimeException e)");
    }

    @Test
    @DisplayName("parseBody guards resolve/decode in one try; 5xx (IllegalState) re-thrown, only decode failures map to 400 (ADR-036 §2)")
    void shouldPreserveStatusMappingAcrossResolveAndDecode() {
        DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders")
                .build();

        String handler = strategy.generate(metadata).stream()
                .filter(f -> f.artifactType() == ArtifactType.CONTROLLER)
                .findFirst()
                .orElseThrow()
                .content();

        // Blocker fix: registry.resolve + context construction + decode are all inside
        // the same try, so a resolve-time RuntimeException cannot escape parseBody
        // unmapped. The IllegalStateException catch re-throws unchanged so the
        // intentional 5xx mappings (unbound registry / unregistered decoder) are NOT
        // downgraded to 400; everything else becomes a 400 IllegalArgumentException.
        assertThat(handler)
                .contains("catch (IllegalStateException e)")
                .contains("throw e;")
                .contains("catch (RuntimeException e)")
                .contains("throw new IllegalArgumentException(\"Invalid request body\", e)")
                // resolve sits ABOVE the IllegalState re-throw, i.e. inside the guarded try
                .containsSubsequence(
                        "registry.resolve(type, contentType)",
                        "catch (IllegalStateException e)",
                        "throw e;",
                        "catch (RuntimeException e)");
        // null content-type renders a friendly token in the unresolved-decoder message.
        assertThat(handler).contains("contentType != null ? contentType : \"(absent)\"");
    }

    @Test
    @DisplayName("T1: serves @Action — loads aggregate, invokes the entity method, responds with updated entity")
    void shouldGenerateActionHandlers() {
        DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders")
                .actions(List.of(
                        ActionMetadata.builder("cancel").methodName("cancel").build(),
                        // action identity (name) differs from the JVM method — the
                        // handler must invoke the methodName, not the name.
                        ActionMetadata.builder("markUrgent").methodName("flagUrgent").build()))
                .build();

        String handler = strategy.generate(metadata).stream()
                .filter(f -> f.artifactType() == ArtifactType.CONTROLLER)
                .findFirst().orElseThrow().content();

        assertThat(handler)
                .contains("void handleCancel(HttpExchange exchange)")
                // id via the shared {id} path-template helper (kernel pathParams(), #224)
                .contains("extractPathId(exchange)")
                .contains("service.findById(id)")
                .contains("exchange.respond(HttpStatus.NOT_FOUND)")
                .contains("entity.cancel()")
                .contains("service.update(id, entity)")
                .contains("exchange.respond(HttpStatus.OK, updated)")
                // no raw-path surgery and no separate action-aware extractor any more
                .doesNotContain("extractActionPathId")
                .doesNotContain("\"/actions/\"")
                // name != method: handler name follows the action identity, invocation the method
                .contains("void handleMarkUrgent(HttpExchange exchange)")
                .contains("entity.flagUrgent()");
    }

    @Test
    @DisplayName("T1: @Action with @ActionParams decodes a generated request record and passes the args")
    void shouldGenerateActionHandlerWithParams() {
        DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders")
                .actions(List.of(
                        ActionMetadata.builder("applyDiscount").methodName("applyDiscount")
                                .params(List.of(
                                        ActionParamMetadata.required("percent", "java.math.BigDecimal"),
                                        ActionParamMetadata.required("reason", "java.lang.String")))
                                .build()))
                .build();

        String handler = strategy.generate(metadata).stream()
                .filter(f -> f.artifactType() == ArtifactType.CONTROLLER)
                .findFirst().orElseThrow().content();

        assertThat(handler)
                .contains("record ApplyDiscountRequest(")
                .contains("BigDecimal percent")
                .contains("String reason")
                .contains("parseBody(exchange, ApplyDiscountRequest.class)")
                .contains("entity.applyDiscount(request.percent(), request.reason())");
    }

    @Test
    @DisplayName("ADR-044 Slice 2: a @Action(streaming) action gets NO respond-once handle<Action> "
            + "(served by the per-action stream handler via streamRoute); non-streaming siblings still do")
    void shouldNotEmitRespondOnceHandlerForStreamingAction() {
        DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders")
                .actions(List.of(
                        ActionMetadata.builder("cancel").methodName("cancel").build(),
                        ActionMetadata.builder("trackShipment").methodName("trackShipment")
                                .streaming(true).streamEventType("ShipmentMoved").build()))
                .build();

        String handler = strategy.generate(metadata).stream()
                .filter(f -> f.artifactType() == ArtifactType.CONTROLLER)
                .findFirst().orElseThrow().content();

        assertThat(handler)
                // non-streaming action keeps its respond-once handler + the shared helper
                .contains("void handleCancel(HttpExchange exchange)")
                .contains("extractPathId(exchange)")
                // streaming action is served by the stream handler, not a dead respond-once method
                .doesNotContain("handleTrackShipment");
    }

    @Test
    @DisplayName("ADR-044 Slice 2: an entity whose ONLY action streams emits no respond-once "
            + "action handler (but still carries the shared by-id extractPathId helper)")
    void shouldOmitRespondOnceHandlerWhenAllActionsStream() {
        DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders")
                .actions(List.of(
                        ActionMetadata.builder("trackShipment").methodName("trackShipment")
                                .streaming(true).streamEventType("ShipmentMoved").build()))
                .build();

        String handler = strategy.generate(metadata).stream()
                .filter(f -> f.artifactType() == ArtifactType.CONTROLLER)
                .findFirst().orElseThrow().content();

        assertThat(handler)
                .doesNotContain("handleTrackShipment")
                // the action-aware extractor is gone entirely; the by-id CRUD routes
                // still need the shared {id} helper, so it's always emitted
                .doesNotContain("extractActionPathId")
                .contains("extractPathId(exchange)");
    }

    @Test
    @DisplayName("T10: enforces @Validation server-side in create/update — 400 before persist, parity with the client Zod schema")
    void shouldEnforceValidationServerSide() {
        DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders")
                .fields(List.of(
                        FieldMetadata.builder("orderNumber", "String")
                                .required(true).minLength(3).maxLength(20).pattern("[A-Z0-9-]+").build(),
                        FieldMetadata.builder("amount", "BigDecimal")
                                .required(true).min(0L).max(1000L).build(),
                        FieldMetadata.builder("weight", "Long")
                                .min(1L).build(),
                        FieldMetadata.builder("quantity", "int")
                                .min(1L).build()))
                .build();

        String handler = strategy.generate(metadata).stream()
                .filter(f -> f.artifactType() == ArtifactType.CONTROLLER)
                .findFirst().orElseThrow().content();

        assertThat(handler)
                // value read once into a prefixed local (T22 — collision-proof), then checked
                .contains("var valOrderNumber = entity.getOrderNumber()")
                // required → not-null on a reference type
                .contains("if (valOrderNumber == null)")
                // String length + pattern (null-guarded)
                .contains("valOrderNumber != null && valOrderNumber.length() < 3")
                .contains("valOrderNumber != null && valOrderNumber.length() > 20")
                .contains("!valOrderNumber.matches(\"[A-Z0-9-]+\")")
                // BigDecimal min/max via compareTo
                .contains("valAmount.compareTo(BigDecimal.valueOf(0L)) < 0")
                .contains("valAmount.compareTo(BigDecimal.valueOf(1000L)) > 0")
                // boxed numeric → null-guarded direct comparison
                .contains("valWeight != null && valWeight < 1L")
                // primitive numeric → direct comparison, no null guard
                .contains("valQuantity < 1L")
                .doesNotContain("valQuantity != null")
                // rejects with 400, and the guard precedes BOTH service calls
                // (create AND update each emit it before persisting)
                .contains("exchange.respond(HttpStatus.BAD_REQUEST)")
                .containsSubsequence(
                        "var valOrderNumber = entity.getOrderNumber()",
                        "service.save(entity)",
                        "var valOrderNumber = entity.getOrderNumber()",
                        "service.update(id, entity)");
    }

    @Test
    @DisplayName("T22: a validated field whose name collides with a handler-scope var (id) gets a "
            + "prefixed local — no `var id` clash with handleUpdate's path-id")
    void shouldPrefixValidationLocalToAvoidPathIdCollision() {
        DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders")
                // a validated field literally named `id` — the exact T22 collision
                .fields(List.of(FieldMetadata.builder("id", "java.util.UUID").required(true).build()))
                .build();

        String handler = strategy.generate(metadata).stream()
                .filter(f -> f.artifactType() == ArtifactType.CONTROLLER)
                .findFirst().orElseThrow().content();

        assertThat(handler)
                // the validation local is prefixed; the bare `id` stays the path-id only
                .contains("var valId = entity.getId()")
                .contains("if (valId == null)")
                .doesNotContain("var id = entity.getId()");
    }

    @Test
    @DisplayName("T10: a primitive required field emits no null-check (a primitive can't be null)")
    void shouldNotNullCheckPrimitiveRequired() {
        DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders")
                .fields(List.of(FieldMetadata.builder("quantity", "int").required(true).build()))
                .build();

        String handler = strategy.generate(metadata).stream()
                .filter(f -> f.artifactType() == ArtifactType.CONTROLLER)
                .findFirst().orElseThrow().content();

        // A primitive field with only `required` has no emittable check, so no
        // local read and no null comparison are generated for it at all.
        assertThat(handler)
                .doesNotContain("quantity == null")
                .doesNotContain("var quantity = entity.getQuantity()");
    }
    @Test
    @DisplayName("a tenant-scoped entity refuses when no tenant is bound (T41)")
    void tenantScopedHandlerGuardsAgainstAnUnboundTenant() {
        DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                .dataScope(DataScope.TENANT)
                .build();

        String handler = new KernelHandlerGenerator().generate(metadata).content();

        // Without the guard the request is served: persistence falls back to a system-scope context,
        // the RLS policy matches nothing, and the caller gets 200 [] from a database that has rows.
        assertThat(handler)
                .contains("if (!KernelProviders.STORAGE_CONTEXT.isBound())")
                .contains("respondTenantUnbound(exchange)")
                .contains("no tenant is bound");

        // Every entry point, not just reads — a write with no tenant fails later and worse.
        assertThat(handler.split("respondTenantUnbound\\(exchange\\)", -1).length - 1)
                .as("one guard call site per CRUD handler (the declaration reads (HttpExchange exchange))")
                .isEqualTo(5);
    }

    @Test
    @DisplayName("action handlers are guarded too, and the guard runs before the body is read (T45)")
    void tenantScopedActionHandlersAreGuardedAsWell() {
        // T41 guarded the five CRUD handlers and its own assertion said "at least five", which is
        // exactly why the action handlers could stay unguarded unnoticed. An action loads the
        // aggregate through the same service, so with no tenant bound row-level security hides the
        // row and the caller is told the entity does not exist — a 404 that is a lie.
        DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders")
                .dataScope(DataScope.TENANT)
                .actions(List.of(
                        ActionMetadata.builder("cancel").methodName("cancel").build(),
                        ActionMetadata.builder("applyDiscount").methodName("applyDiscount")
                                .params(List.of(ActionParamMetadata.required("percent", "java.math.BigDecimal")))
                                .build()))
                .build();

        String handler = strategy.generate(metadata).stream()
                .filter(f -> f.artifactType() == ArtifactType.CONTROLLER)
                .findFirst().orElseThrow().content();

        assertThat(handler.split("respondTenantUnbound\\(exchange\\)", -1).length - 1)
                .as("five CRUD handlers plus both actions")
                .isEqualTo(7);

        // Ordering matters as much as presence: the guard has to come before parseBody, or a
        // request with no tenant is answered 400 for its body rather than refused for its wiring.
        int guard = handler.indexOf("respondTenantUnbound(exchange)",
                handler.indexOf("void handleApplyDiscount("));
        int parse = handler.indexOf("parseBody(exchange, ApplyDiscountRequest.class)");
        assertThat(guard)
                .as("the tenant guard precedes the body decode in the action handler")
                .isGreaterThan(0)
                .isLessThan(parse);
    }

    @Test
    @DisplayName("a global entity is not guarded — it needs no tenant to be readable")
    void globalHandlerIsUnguarded() {
        DomainMetadata metadata = DomainMetadata.builder("ShipDesign", "com.example.domain")
                .dataScope(DataScope.GLOBAL)
                .build();

        String handler = new KernelHandlerGenerator().generate(metadata).content();

        assertThat(handler)
                .as("guarding a global entity would refuse perfectly serviceable requests")
                .doesNotContain("STORAGE_CONTEXT")
                .doesNotContain("respondTenantUnbound");
    }
}
