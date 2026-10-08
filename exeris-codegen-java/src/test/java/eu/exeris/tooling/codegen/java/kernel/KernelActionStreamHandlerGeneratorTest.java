package eu.exeris.tooling.codegen.java.kernel;

import eu.exeris.sdk.sourcemodel.ast.ActionMetadata;
import eu.exeris.sdk.sourcemodel.ast.ActionParamMetadata;
import eu.exeris.sdk.sourcemodel.ast.DataScope;
import eu.exeris.sdk.sourcemodel.ast.DomainEventMetadata;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.FieldMetadata;
import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator.ArtifactType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Per-generator test for {@link KernelActionStreamHandlerGenerator} (ADR-044 Slice 2, Amendment 2).
 * The compile gate ({@code KernelCodegenCompileTest}) compiles what is asserted here against the
 * kernel SPI; the boot test ({@code ActionStreamBootE2ETest}) drives it over a socket.
 */
@DisplayName("KernelActionStreamHandlerGenerator")
class KernelActionStreamHandlerGeneratorTest {

    private final KernelActionStreamHandlerGenerator gen = new KernelActionStreamHandlerGenerator();

    private static DomainMetadata orderWith(List<ActionMetadata> actions) {
        return DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders")
                .actions(actions)
                .build();
    }

    private static ActionMetadata streaming(String name) {
        return ActionMetadata.builder(name).methodName(name).streaming(true).build();
    }

    private static DomainEventMetadata actionEvent(String name, String action, List<String> payload) {
        return DomainEventMetadata.builder(name)
                .trigger(DomainEventMetadata.Trigger.ACTION)
                .actionName(action)
                .payloadFields(payload)
                .build();
    }

    /** A TENANT, versioned entity whose streaming action decodes a body and triggers two events. */
    private static DomainMetadata trackedOrder() {
        return DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders")
                .dataScope(DataScope.TENANT)
                .versioned(true)
                .fields(List.of(FieldMetadata.builder("label", "String").build()))
                .actions(List.of(ActionMetadata.builder("trackShipment").methodName("track")
                        .streaming(true)
                        .streamEventType("ShipmentMoved")
                        .params(List.of(ActionParamMetadata.required("note", "java.lang.String")))
                        .build()))
                .events(List.of(
                        actionEvent("OrderTracked", "trackShipment", List.of("label")),
                        actionEvent("OrderPinged", "trackShipment", List.of()),
                        actionEvent("OrderCancelled", "cancel", List.of()),
                        DomainEventMetadata.builder("OrderCreated")
                                .trigger(DomainEventMetadata.Trigger.CREATE).build()))
                .build();
    }

    @Test
    @DisplayName("supports() is true only when an action is streaming")
    void supportsOnlyStreamingActions() {
        assertThat(gen.supports(orderWith(List.of(
                ActionMetadata.builder("cancel").methodName("cancel").build())))).isFalse();
        assertThat(gen.supports(orderWith(List.of(streaming("trackShipment"))))).isTrue();
        assertThat(gen.supports(null)).isFalse();
    }

    @Test
    @DisplayName("emits one HttpStreamHandler per streaming action, named <Entity><ActionPascal>StreamHandler")
    void emitsOneFilePerStreamingAction() {
        DomainMetadata order = orderWith(List.of(
                ActionMetadata.builder("cancel").methodName("cancel").build(),
                streaming("trackShipment"),
                streaming("watchPrice")));

        List<GeneratedFile> files = gen.generateMultiple(order);

        assertThat(files).extracting(GeneratedFile::className)
                .containsExactly("OrderTrackShipmentStreamHandler", "OrderWatchPriceStreamHandler");
        assertThat(files).allSatisfy(f -> {
            assertThat(f.artifactType()).isEqualTo(ArtifactType.ACTION_STREAM_HANDLER);
            assertThat(f.packageName()).isEqualTo("com.example.handler");
            assertThat(f.extension()).isEqualTo("java");
        });
    }

    @Test
    @DisplayName("runs in the amended order: guard, id, body, row under RLS, subscribe, action, events")
    void stepsRunInOrder() {
        String content = gen.generate(trackedOrder()).content();

        int guard = content.indexOf("if (!KernelProviders.STORAGE_CONTEXT.isBound())");
        int id = content.indexOf("id = UUID.fromString(exchange.pathParams().getOrDefault(\"id\", \"\"))");
        int body = content.indexOf("request = parseBody(exchange, TrackShipmentRequest.class)");
        int load = content.indexOf("found = service.findById(id)");
        int subscribe = content.indexOf("tokens.add(bus.subscribe(");
        int invoke = content.indexOf("if (invoke(exchange, id, found.get(), request))");
        int await = content.indexOf("awaitEvents(exchange, queue)");

        assertThat(guard).isGreaterThan(-1);
        assertThat(id).isGreaterThan(guard);
        assertThat(body).isGreaterThan(id);
        assertThat(load).isGreaterThan(body);
        assertThat(subscribe).isGreaterThan(load);
        assertThat(invoke).isGreaterThan(subscribe);
        assertThat(await).isGreaterThan(invoke);
    }

    @Test
    @DisplayName("subscribes to the action's ACTION-triggered events only, filtered on the row id")
    void subscribesToTriggeredEventsOnly() {
        String content = gen.generate(trackedOrder()).content();

        assertThat(content)
                .contains("tokens.add(bus.subscribe(\"OrderTrackedEvent\", (descriptor, payload) -> {")
                .contains("tokens.add(bus.subscribe(\"OrderPingedEvent\", (descriptor, payload) -> {")
                .doesNotContain("OrderCancelledEvent")
                .doesNotContain("OrderCreatedEvent")
                .contains("long streamHigh = id.getMostSignificantBits();")
                .contains("if (descriptor.streamIdHigh() == streamHigh && descriptor.streamIdLow() == streamLow) {")
                .contains("StreamEvent.of(\"OrderTracked\", data)")
                .contains("TRIGGERED_EVENTS = List.of(\"OrderTracked\", \"OrderPinged\");")
                // the subscriptions are dropped however serve() unwinds
                .contains("bus.unsubscribe(token);");
    }

    @Test
    @DisplayName("runs the action as the respond-once route does: entity method, update, publish")
    void runsTheAction() {
        String content = gen.generate(trackedOrder()).content();

        assertThat(content)
                .contains("entity.track(request.note());")
                .contains("Order updated = service.update(id, entity);")
                .contains("publisher.publishOrderPingedEvent(id);")
                .contains("publisher.publishOrderTrackedEvent(id, updated);")
                .doesNotContain("publishOrderCancelledEvent")
                .doesNotContain("keep-alive")
                .doesNotContain("KEEPALIVE");
    }

    @Test
    @DisplayName("closes when every triggered event has arrived, or at a constant deadline on the monotonic clock")
    void closesOnCompletionOrDeadline() {
        String content = gen.generate(trackedOrder()).content();

        assertThat(content)
                .contains("STREAM_DEADLINE_MILLIS = 30000L;")
                .contains("STREAM_BUFFER_CAPACITY = 256;")
                .contains("Set<String> pending = new HashSet<>(TRIGGERED_EVENTS);")
                .contains("long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(STREAM_DEADLINE_MILLIS);")
                .contains("while (!pending.isEmpty()) {")
                .contains("pending.remove(frame.event());")
                .contains("LOG.log(System.Logger.Level.WARNING, \"OrderTracked frame dropped on the "
                        + "Order.trackShipment action stream (slow consumer); the stream closes at its deadline\");")
                .doesNotContain("currentTimeMillis");
    }

    @Test
    @DisplayName("every refusal is a stream-error frame carrying the respond-once status")
    void refusalsAreStreamErrorFrames() {
        String content = gen.generate(trackedOrder()).content();

        assertThat(content)
                .contains("private static final String STREAM_ERROR = \"stream-error\";")
                .contains("exchange.emit(StreamEvent.of(STREAM_ERROR, \"{\\\"type\\\":\\\"about:blank\\\","
                        + "\\\"title\\\":\\\"\" + title + \"\\\",\\\"status\\\":\" + status + \"}\"));")
                // tenant guard, malformed id, body, row, version conflict, tenant mismatch, anything else
                .contains("refuse(exchange, 500, \"Internal Server Error\");")
                .contains("refuse(exchange, 400, \"Bad Request\");")
                .contains("refuse(exchange, 404, \"Not Found\");")
                .contains("} catch (OrderVersionConflictException e) {\n            refuse(exchange, 409, \"Conflict\");")
                .contains("} catch (OrderTenantMismatchException e) {\n            refuse(exchange, 400, \"Bad Request\");")
                // a disconnect is not a failure: it propagates, and the stream is closed regardless
                .contains("} catch (StreamClosedException closed) {\n            throw closed;")
                .contains("} finally {\n            exchange.close();");
    }

    @Test
    @DisplayName("an unversioned entity answers a vanished row 404, as its respond-once route does")
    void unversionedEntityAnswersNotFound() {
        String content = gen.generate(orderWith(List.of(streaming("watch")))).content();

        assertThat(content)
                .contains("} catch (OrderNotFoundException e) {\n            refuse(exchange, 404, \"Not Found\");")
                .doesNotContain("VersionConflict")
                .doesNotContain("STORAGE_CONTEXT")
                .doesNotContain("TenantMismatch");
    }

    @Test
    @DisplayName("an action that triggers no event subscribes to nothing and takes only the service")
    void actionWithoutEventsSubscribesToNothing() {
        String content = gen.generate(orderWith(List.of(streaming("watch")))).content();

        assertThat(content)
                .contains("public OrderWatchStreamHandler(OrderService service) {")
                .contains("service.update(id, entity);")
                .contains("invoke(exchange, id, found.get());")
                .doesNotContain("subscribe(")
                .doesNotContain("EventEngine")
                .doesNotContain("BlockingQueue")
                .doesNotContain("parseBody");
    }

    @Test
    @DisplayName("STREAM_EVENT_TYPE names the result frame, which is not emitted; defaults to the action name")
    void resultFrameIsNamedButNotEmitted() {
        assertThat(gen.generate(trackedOrder()).content())
                .contains("STREAM_EVENT_TYPE = \"ShipmentMoved\"")
                .doesNotContain("StreamEvent.of(STREAM_EVENT_TYPE");
        assertThat(gen.generate(orderWith(List.of(streaming("watchPrice")))).content())
                .contains("STREAM_EVENT_TYPE = \"watchPrice\"");
    }

    @Test
    @DisplayName("stays on the SPI: no SSE wire literal, no keep-alive scaffold")
    void kernelTargetDiscipline() {
        assertThat(gen.generate(trackedOrder()).content())
                .contains("implements HttpStreamHandler")
                .contains("public void handle(HttpStreamExchange exchange)")
                .doesNotContain("text/event-stream");
    }

    @Test
    @DisplayName("same metadata → byte-identical output (determinism, constraint #3)")
    void deterministicOutput() {
        assertThat(gen.generate(trackedOrder()).content()).isEqualTo(gen.generate(trackedOrder()).content());
    }
}
