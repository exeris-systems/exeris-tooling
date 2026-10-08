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
                // T43-follow-up put MemoryAllocator between the two. Asserted piecewise because
                // JavaPoet wraps a signature this long and the wrap point is not the contract.
                .contains("public OrderHandler(OrderService service, MemoryAllocator allocator")
                .contains("OrderEventPublisher publisher)")
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
    @DisplayName("a foreign tenant answers 400 on create, update and action — never on delete")
    void shouldAnswerBadRequestForAForeignTenant() {
        GeneratedFile handler = handlerFor(DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders")
                .tenantScoped(true)
                .fields(List.of(FieldMetadata.simple("amount", "java.math.BigDecimal")))
                .actions(List.of(eu.exeris.sdk.sourcemodel.ast.ActionMetadata.builder("approve")
                        .methodName("approve").build()))
                .build());

        assertThat(handler.content())
                .contains("import com.example.repository.OrderTenantMismatchException");
        // Contiguous blocks, for the reason shouldAnswerNotFoundForAnAbsentRow gives: each typed
        // catch sits ahead of the RuntimeException tail of its own method.
        assertThat(noIndent(handler.content()))
                .contains("""
                        exchange.respond(HttpStatus.CREATED, saved);
                        } catch (OrderTenantMismatchException e) {
                        exchange.respond(HttpStatus.BAD_REQUEST);
                        } catch (RuntimeException e) {""")
                .contains("""
                        exchange.respond(HttpStatus.OK, updated);
                        } catch (OrderNotFoundException e) {
                        exchange.respond(HttpStatus.NOT_FOUND);
                        } catch (OrderTenantMismatchException e) {
                        exchange.respond(HttpStatus.BAD_REQUEST);
                        } catch (RuntimeException e) {""")
                .contains("""
                        service.delete(id);
                        exchange.respond(HttpStatus.NO_CONTENT);
                        } catch (OrderNotFoundException e) {
                        exchange.respond(HttpStatus.NOT_FOUND);
                        } catch (RuntimeException e) {""");
        // Create, update and the one action: three catches, none on delete.
        assertThat(handler.content().split("catch \\(OrderTenantMismatchException e\\)", -1)).hasSize(4);
    }

    @Test
    @DisplayName("a UNIVERSE entity catches both caller-fault types in one clause")
    void shouldAnswerBadRequestForAForeignSharedScope() {
        GeneratedFile handler = handlerFor(DomainMetadata.builder("Species", "com.example.domain")
                .path("/species")
                .dataScope(eu.exeris.sdk.sourcemodel.ast.DataScope.UNIVERSE)
                .systemFields(new eu.exeris.sdk.sourcemodel.ast.SystemFieldsMetadata("id", "createdAt",
                        "createdBy", "updatedAt", "updatedBy", "tenantId", "version", null, null, null,
                        "worldId"))
                .fields(List.of(FieldMetadata.simple("tenantId", "java.util.UUID"),
                        FieldMetadata.simple("worldId", "java.util.UUID")))
                .build());

        assertThat(noIndent(handler.content())).contains("""
                exchange.respond(HttpStatus.CREATED, saved);
                } catch (SpeciesTenantMismatchException | SpeciesSharedScopeMismatchException e) {
                exchange.respond(HttpStatus.BAD_REQUEST);""");
    }

    @Test
    @DisplayName("a global entity's handler catches no caller-fault type")
    void globalHandlerCatchesNoMismatch() {
        GeneratedFile handler = handlerFor(DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders")
                .fields(List.of(FieldMetadata.simple("amount", "java.math.BigDecimal")))
                .build());

        assertThat(handler.content()).doesNotContain("MismatchException");
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
                .contains("public OrderHandler(OrderService service, MemoryAllocator allocator)")
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
                .contains("public OrderHandler(OrderService service, MemoryAllocator allocator)")
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
                .contains("public OrderHandler(OrderService service, MemoryAllocator allocator)")
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
                // The {id} path var is read from pathParams(), not raw-path string surgery
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
                // No KernelProviders import on this path: RuntimeComponents reads
                // MEMORY_ALLOCATOR and passes the allocator to the handler's constructor, and
                // the plain CRUD handler has no other use for KernelProviders.
                .contains("import eu.exeris.kernel.spi.memory.MemoryAllocator")
                .contains("httpRequestBodyDecoderRegistry()")
                .contains("registry.resolve(type, contentType)")
                .contains("decoder.decode(body, type, context)")
                .contains("firstHeader(\"content-type\")")
                // … hands the decoder the LoanedBuffer + a fresh decoding context …
                .contains("exchange.request().hasBody()")
                .contains("new HttpRequestDecodingContext(")
                .contains("this.allocator)")
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
    @DisplayName("T43-follow-up: the allocator is captured at construction, so a request can never find it unbound")
    void allocatorIsCapturedAtConstructionRatherThanResolvedPerRequest() {
        DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders")
                .build();

        String handler = strategy.generate(metadata).stream()
                .filter(f -> f.artifactType() == ArtifactType.CONTROLLER)
                .findFirst()
                .orElseThrow()
                .content();

        // KernelProviders.MEMORY_ALLOCATOR is a ScopedValue bound around the bootstrap callback.
        // The handler takes the allocator as a constructor argument resolved there, and reads no
        // binding per request.
        assertThat(handler)
                .contains("private final MemoryAllocator allocator;")
                .contains("public OrderHandler(OrderService service, MemoryAllocator allocator)")
                .contains("this.allocator = Objects.requireNonNull(allocator")
                .contains("this.allocator)");

        // The per-request lookup and its guard are gone, together: there is nothing left to
        // guard once the field cannot be unbound. Both halves are asserted, because leaving the
        // guard behind would be dead code that reads as a live protection.
        assertThat(handler)
                .doesNotContain("MEMORY_ALLOCATOR.isBound()")
                .doesNotContain("MEMORY_ALLOCATOR.get()")
                .doesNotContain("No MemoryAllocator is bound");

        // The decoding context is still built inside the guarded try, and the two failure modes
        // that ARE request-time — unbound registry, unregistered decoder — keep their 5xx
        // mapping (ADR-036 §2). Only the allocator left that list.
        assertThat(handler)
                .containsSubsequence(
                        "registry.resolve(type, contentType)",
                        "new HttpRequestDecodingContext(",
                        "catch (IllegalStateException e)",
                        "throw e;",
                        "catch (RuntimeException e)");
    }

    @Test
    @DisplayName("parseBody guards resolve/decode in one try; 5xx (IllegalState) re-thrown, only caller-classified decode failures map to 400 (ADR-036 §2)")
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
        // downgraded to 400; a CALLER-classified failure becomes a 400 IllegalArgumentException.
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
    @DisplayName("parseBody answers 400 only for a CALLER-classified decode failure; any other is wrapped for a 500 (ADR-036 §2, kernel ADR-083)")
    void onlyACallerClassifiedDecodeFailureIsTheCallersFault() {
        DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders")
                .build();

        String handler = new KernelHandlerGenerator().generate(metadata).content();

        // The kernel classifies the failure, not its JDK type: a SYSTEM kernel exception, a
        // driver exception or a JDK exception must not reach the call site's 400 catch.
        assertThat(handler)
                .contains("import eu.exeris.kernel.spi.exceptions.FaultOrigin;")
                .containsSubsequence(
                        "catch (IllegalStateException e)",
                        "throw e;",
                        "catch (RuntimeException e)",
                        "if (FaultOrigin.classify(e) == FaultOrigin.CALLER)",
                        "throw new IllegalArgumentException(\"Invalid request body\", e)",
                        "throw new RuntimeException(\"Request body decoding failed with a server-side fault\", e)");
        // An equality against CALLER, never a switch: FaultOrigin may gain constants.
        assertThat(handler).doesNotContain("switch (FaultOrigin");
        // classify, not instanceof — a kernel exception classified CALLER later is picked up.
        assertThat(handler).doesNotContain("instanceof RequestBodyDecodeException");

        // The wrapped failure is answered and logged as the deployment's, with no request data.
        assertThat(handler)
                .contains("private void respondDecodeFailed(HttpExchange exchange, RuntimeException cause)")
                .contains("the request body decoder failed with a fault the kernel does not classify "
                        + "as the caller's");
    }

    @Test
    @DisplayName("every parseBody call site answers a server-side decode failure 500, after the 400 and decoder-unavailable catches")
    void everyBodyCallSiteAnswersAServerSideDecodeFailure() {
        DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders")
                .actions(List.of(
                        ActionMetadata.builder("cancel").methodName("cancel").build(),
                        ActionMetadata.builder("applyDiscount").methodName("applyDiscount")
                                .params(List.of(ActionParamMetadata.required("percent", "java.math.BigDecimal")))
                                .build()))
                .build();

        String handler = new KernelHandlerGenerator().generate(metadata).content();

        assertThat(handler.split("respondDecodeFailed\\(exchange, e\\)", -1).length - 1)
                .as("handleCreate, handleUpdate and the one action that carries a body")
                .isEqualTo(3);
        // The RuntimeException catch must close each try last: ahead of either sibling it would
        // swallow the 400 and the decoder-unavailable answers.
        for (String parse : List.of("entity = parseBody(exchange, Order.class)",
                "request = parseBody(exchange, ApplyDiscountRequest.class)")) {
            int from = handler.indexOf(parse);
            assertThat(from).as(parse).isNotNegative();
            assertThat(handler.substring(from))
                    .as(parse)
                    .containsSubsequence(
                            "catch (IllegalArgumentException e)",
                            "exchange.respond(HttpStatus.BAD_REQUEST)",
                            "catch (IllegalStateException e)",
                            "respondDecoderUnavailable(exchange, e)",
                            "catch (RuntimeException e)",
                            "respondDecodeFailed(exchange, e)");
        }
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
    @DisplayName("a required read-only field is checked on neither route: the create schema and the update "
            + "body leave it out, and updateFromRequest keeps its stored value")
    void readOnlyFieldIsCheckedOnNeitherRoute() {
        DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders")
                .fields(List.of(
                        FieldMetadata.builder("orderNumber", "String").required(true).build(),
                        FieldMetadata.builder("status", "String").required(true).readOnly(true).build()))
                .build();

        String handler = strategy.generate(metadata).stream()
                .filter(f -> f.artifactType() == ArtifactType.CONTROLLER)
                .findFirst().orElseThrow().content();
        String create = handler.substring(handler.indexOf("void handleCreate("), handler.indexOf("service.save(entity)"));
        String update = handler.substring(handler.indexOf("void handleUpdate("),
                handler.indexOf("service.updateFromRequest(id, entity)"));

        assertThat(create).contains("var valOrderNumber = entity.getOrderNumber()")
                .doesNotContain("valStatus");
        assertThat(update).contains("var valOrderNumber = entity.getOrderNumber()")
                .doesNotContain("valStatus");
    }

    @Test
    @DisplayName("handleCreate drops the audit, version and soft-delete values the body carried, before the "
            + "validation and the service; an entity with none of them resets nothing")
    void createResetsTheServerOwnedFields() {
        DomainMetadata owned = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders").audited(true).versioned(true).softDelete(true)
                .fields(List.of(FieldMetadata.builder("title", "String").required(true).build(),
                        FieldMetadata.builder("createdBy", "String").build()))
                .build();
        DomainMetadata plain = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders")
                .fields(List.of(FieldMetadata.builder("title", "String").build()))
                .build();

        String create = createOf(owned);

        assertThat(create).contains("entity.setCreatedAt(null)", "entity.setUpdatedAt(null)",
                "entity.setCreatedBy(null)", "entity.setDeleted(false)", "entity.setVersion(0L)")
                .doesNotContain("setDeletedAt", "setDeletedBy", "setUpdatedBy");
        assertThat(create.indexOf("entity.setVersion(0L)")).isLessThan(create.indexOf("var valTitle"));
        assertThat(createOf(plain)).doesNotContain("entity.set");
    }

    private String createOf(DomainMetadata metadata) {
        String handler = strategy.generate(metadata).stream()
                .filter(f -> f.artifactType() == ArtifactType.CONTROLLER)
                .findFirst().orElseThrow().content();
        return handler.substring(handler.indexOf("void handleCreate("), handler.indexOf("service.save(entity)"));
    }

    @Test
    @DisplayName("a required inCreate = false field is checked on update only, a required inUpdate = false "
            + "field on create only")
    void lifecycleFlagsSelectTheCheckedRoute() {
        DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders")
                .fields(List.of(
                        FieldMetadata.builder("slug", "String").required(true).inCreate(false).build(),
                        FieldMetadata.builder("code", "String").required(true).inUpdate(false).build()))
                .build();

        String handler = strategy.generate(metadata).stream()
                .filter(f -> f.artifactType() == ArtifactType.CONTROLLER)
                .findFirst().orElseThrow().content();
        String create = handler.substring(handler.indexOf("void handleCreate("), handler.indexOf("service.save(entity)"));
        String update = handler.substring(handler.indexOf("void handleUpdate("),
                handler.indexOf("service.updateFromRequest(id, entity)"));

        assertThat(create).contains("var valCode = entity.getCode()").doesNotContain("valSlug");
        assertThat(update).contains("var valSlug = entity.getSlug()").doesNotContain("valCode");
    }

    @Test
    @DisplayName("with a read-only field, PUT updates from the request and an action updates with the "
            + "fields its entity method changed")
    void putAndActionTakeDifferentUpdates() {
        DomainMetadata withReadOnly = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders")
                .fields(List.of(FieldMetadata.builder("orderNumber", "String").build(),
                        FieldMetadata.builder("status", "String").readOnly(true).build()))
                .actions(List.of(ActionMetadata.builder("approve").methodName("approve").build()))
                .build();
        DomainMetadata withoutReadOnly = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders")
                .fields(List.of(FieldMetadata.builder("orderNumber", "String").build()))
                .actions(List.of(ActionMetadata.builder("approve").methodName("approve").build()))
                .build();

        String handler = controller(withReadOnly);
        String update = handler.substring(handler.indexOf("void handleUpdate("), handler.indexOf("void handleApprove("));
        String action = handler.substring(handler.indexOf("void handleApprove("));
        assertThat(update).contains("service.updateFromRequest(id, entity)").doesNotContain("service.update(id");
        assertThat(action).contains("entity.approve();", "service.update(id, entity)")
                .doesNotContain("updateFromRequest");
        assertThat(controller(withoutReadOnly)).doesNotContain("updateFromRequest")
                .contains("service.update(id, entity)");
    }

    private String controller(DomainMetadata metadata) {
        return strategy.generate(metadata).stream()
                .filter(f -> f.artifactType() == ArtifactType.CONTROLLER)
                .findFirst().orElseThrow().content();
    }

    @Test
    @DisplayName("T22: a validated field whose name collides with a handler-scope var (id) gets a "
            + "prefixed local — no `var id` clash with handleUpdate's path-id")
    void shouldPrefixValidationLocalToAvoidPathIdCollision() {
        DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders")
                // a validated field literally named `id`, the key being another field — the exact
                // T22 collision
                .systemFields(eu.exeris.sdk.sourcemodel.ast.SystemFieldsMetadata.builder()
                        .primaryKeyField("orderNo").build())
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

    @Test
    @DisplayName("a decoder fault is answered and logged instead of escaping the handler (T52)")
    void decoderFaultIsAnsweredRatherThanEscaping() {
        DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders")
                .build();

        String handler = new KernelHandlerGenerator().generate(metadata).content();

        // parseBody re-throws IllegalStateException unchanged so ADR-036 is honoured and an
        // absent decoder is never downgraded to 400. Nothing caught it either, so it escaped
        // the handler: the dispatcher answered a bare 500, empty body, nothing logged.
        // ...and it stays 5xx, blaming the deployment rather than the caller.
        assertThat(handler)
                .as("a missing decoder is a deployment fault, so it is answered, logged, and never a 400")
                .contains("catch (IllegalStateException e)")
                .contains("respondDecoderUnavailable(exchange, e)")
                .contains("no request body decoder was available")
                .contains("private void respondDecoderUnavailable(HttpExchange exchange, "
                        + "IllegalStateException cause)")
                .contains("exchange.respond(HttpStatus.INTERNAL_SERVER_ERROR)");

        assertThat(handler.split("respondDecoderUnavailable\\(exchange, e\\)", -1).length - 1)
                .as("one per parseBody call site: handleCreate and handleUpdate")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("every parseBody call site guards the decoder fault, actions included (T52)")
    void everyBodyCallSiteGuardsTheDecoderFault() {
        // The action handler decodes through its own inline try rather than appendBodyParseGuard,
        // so it is a second call site that a fix applied to one place would miss — the same way
        // T45's action handlers were missed by the T41 guard.
        DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders")
                .actions(List.of(
                        ActionMetadata.builder("cancel").methodName("cancel").build(),
                        ActionMetadata.builder("applyDiscount").methodName("applyDiscount")
                                .params(List.of(ActionParamMetadata.required("percent", "java.math.BigDecimal")))
                                .build()))
                .build();

        String handler = strategy.generate(metadata).stream()
                .filter(f -> f.artifactType() == ArtifactType.CONTROLLER)
                .findFirst().orElseThrow().content();

        assertThat(handler.split("respondDecoderUnavailable\\(exchange, e\\)", -1).length - 1)
                .as("handleCreate, handleUpdate, and the one action that carries a body "
                        + "(cancel takes no params, so it never decodes)")
                .isEqualTo(3);

        // The caller-fault branch is untouched: a body the decoder rejected is still the
        // caller's 400. Both catches must sit on the same try, or one of the two answers is lost.
        assertThat(handler.split("exchange\\.respond\\(HttpStatus\\.BAD_REQUEST\\)", -1).length - 1)
                .as("three body decodes, the three path-id guards on getById/update/delete, "
                        + "one per action - appendPathIdGuard runs for both, not only the "
                        + "one that carries a body - and the list query's refusal")
                .isEqualTo(9);

        int parse = handler.indexOf("parseBody(exchange, ApplyDiscountRequest.class)");
        int badRequest = handler.indexOf("exchange.respond(HttpStatus.BAD_REQUEST)", parse);
        int decoderFault = handler.indexOf("respondDecoderUnavailable(exchange, e)", parse);
        assertThat(decoderFault)
                .as("the decoder-fault catch closes the same try that the 400 catch opens")
                .isGreaterThan(badRequest);
    }

    @Test
    @DisplayName("handleGetAll parses the query string into the list query, answers 400 for what it "
            + "refuses before the service is reached, and responds with the page")
    void handleGetAllServesOnePage() {
        String handler = new KernelHandlerGenerator().generate(KernelListQueryGeneratorTest.order())
                .content().replaceAll("\\s+", " ");

        assertThat(handler)
                .contains("public void handleGetAll(HttpExchange exchange) { OrderListQuery query; try { "
                        + "query = OrderListQuery.parse(rawQuery(exchange)); } catch (IllegalArgumentException e) { "
                        + "exchange.respond(HttpStatus.BAD_REQUEST); return; } try { "
                        + "OrderPage page = service.findPage(query); exchange.respond(HttpStatus.OK, page);")
                .contains("private static String rawQuery(HttpExchange exchange) { "
                        + "String target = exchange.request().path(); int start = target.indexOf('?'); "
                        + "return start < 0 ? \"\" : target.substring(start + 1); }")
                .doesNotContain("service.findAll()");
    }

    @Test
    @DisplayName("a tenant-scoped list refuses an unbound tenant before it parses the query")
    void tenantGuardPrecedesTheListQuery() {
        DomainMetadata tenantScoped = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders").tenantScoped(true).build();
        String handler = new KernelHandlerGenerator().generate(tenantScoped).content();
        String getAll = handler.substring(handler.indexOf("public void handleGetAll"),
                handler.indexOf("public void handleGetById"));

        assertThat(getAll.indexOf("respondTenantUnbound(exchange)"))
                .isNotNegative()
                .isLessThan(getAll.indexOf("OrderListQuery.parse"));
    }
}
