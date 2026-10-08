package eu.exeris.tooling.codegen.java.kernel;

import eu.exeris.sdk.sourcemodel.ast.ActionMetadata;
import eu.exeris.sdk.sourcemodel.ast.DataScope;
import eu.exeris.sdk.sourcemodel.ast.DomainEventMetadata;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator.ArtifactType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Per-generator test for {@link KernelSpectateStreamHandlerGenerator} (ADR-044 Amendment 2,
 * decision 7). The compile gate ({@code KernelCodegenCompileTest}) compiles what is asserted here
 * against the kernel SPI; the boot test ({@code SpectateStreamBootE2ETest}) drives it over a socket.
 */
@DisplayName("KernelSpectateStreamHandlerGenerator")
class KernelSpectateStreamHandlerGeneratorTest {

    private final KernelSpectateStreamHandlerGenerator gen = new KernelSpectateStreamHandlerGenerator();

    private static DomainMetadata.Builder order() {
        return DomainMetadata.builder("Order", "com.example.domain").path("/orders");
    }

    /** A GLOBAL live entity with an update event and a create event. */
    private static DomainMetadata liveOrder() {
        return order().realTimeApi(true)
                .events(List.of(
                        DomainEventMetadata.builder("OrderUpdated")
                                .trigger(DomainEventMetadata.Trigger.UPDATE).build(),
                        DomainEventMetadata.builder("OrderCreated")
                                .trigger(DomainEventMetadata.Trigger.CREATE).build()))
                .build();
    }

    @Test
    @DisplayName("supports() follows realTimeApi; generate() returns null without it")
    void supportsRealTimeApiOnly() {
        assertThat(gen.supports(order().build())).isFalse();
        assertThat(gen.supports(liveOrder())).isTrue();
        assertThat(gen.supports(null)).isFalse();
        assertThat(gen.generate(order().build())).isNull();
        assertThat(gen.artifactType()).isEqualTo(ArtifactType.SPECTATE_STREAM_HANDLER);
    }

    @Test
    @DisplayName("emits <Entity>SpectateStreamHandler in the handler package, registered at GET {base}/{id}/stream")
    void emitsOneHandlerPerEntity() {
        GeneratedFile file = gen.generate(liveOrder());

        assertThat(file.className()).isEqualTo("OrderSpectateStreamHandler");
        assertThat(file.packageName()).isEqualTo("com.example.handler");
        assertThat(file.artifactType()).isEqualTo(ArtifactType.SPECTATE_STREAM_HANDLER);
        assertThat(file.content())
                .contains("public class OrderSpectateStreamHandler implements HttpStreamHandler {")
                .contains("registered at {@code GET /orders/{id}/stream}");
    }

    @Test
    @DisplayName("reads the id, then the row, then subscribes to every event filtered on the row id")
    void stepsRunInOrder() {
        String content = gen.generate(liveOrder()).content();

        int id = content.indexOf("id = UUID.fromString(exchange.pathParams().getOrDefault(\"id\", \"\"))");
        int load = content.indexOf("found = service.findById(id)");
        int notFound = content.indexOf("refuse(exchange, 404, \"Not Found\");");
        int subscribe = content.indexOf("tokens.add(bus.subscribe(");
        int spectate = content.indexOf("spectate(exchange, queue);");

        assertThat(id).isGreaterThan(-1);
        assertThat(load).isGreaterThan(id);
        assertThat(notFound).isGreaterThan(load);
        assertThat(subscribe).isGreaterThan(notFound);
        assertThat(spectate).isGreaterThan(subscribe);
        assertThat(content)
                .contains("tokens.add(bus.subscribe(\"OrderUpdatedEvent\", (descriptor, payload) -> {")
                .contains("tokens.add(bus.subscribe(\"OrderCreatedEvent\", (descriptor, payload) -> {")
                .contains("if (descriptor.streamIdHigh() == streamHigh && descriptor.streamIdLow() == streamLow) {")
                .contains("StreamEvent.of(\"OrderUpdated\", data)")
                .contains("bus.unsubscribe(token);");
    }

    @Test
    @DisplayName("open-ended: a keep-alive while the row is quiet, no deadline, a bounded drop-on-full queue")
    void openEndedWithKeepAlive() {
        String content = gen.generate(liveOrder()).content();

        assertThat(content)
                .contains("KEEPALIVE_INTERVAL_MILLIS = 15000L;")
                .contains("STREAM_BUFFER_CAPACITY = 256;")
                .contains("StreamEvent frame = queue.poll(KEEPALIVE_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);")
                .contains("exchange.emit(StreamEvent.of(\"keep-alive\", \"\"));")
                .contains("LOG.log(System.Logger.Level.DEBUG, \"OrderUpdated frame dropped on the Order spectate "
                        + "stream (slow consumer)\");")
                .doesNotContain("DEADLINE")
                .doesNotContain("nanoTime")
                .doesNotContain("currentTimeMillis");
    }

    @Test
    @DisplayName("every refusal is a stream-error frame carrying the by-id GET's status; a disconnect propagates")
    void refusalsAreStreamErrorFrames() {
        String content = gen.generate(liveOrder()).content();

        assertThat(content)
                .contains("private static final String STREAM_ERROR = \"stream-error\";")
                .contains("refuse(exchange, 400, \"Bad Request\");")
                .contains("refuse(exchange, 404, \"Not Found\");")
                .contains("refuse(exchange, 500, \"Internal Server Error\");")
                .contains("{@code status} is the one the respond-once {@code GET} by id answers.")
                .contains("} catch (StreamClosedException closed) {\n            throw closed;")
                .contains("} finally {\n            exchange.close();")
                .doesNotContain("STORAGE_CONTEXT");
    }

    @Test
    @DisplayName("takes the service, and the EventEngine only when there is an event to forward")
    void constructorFollowsTheEvents() {
        assertThat(gen.generate(liveOrder()).content())
                .contains("public OrderSpectateStreamHandler(OrderService service, EventEngine eventEngine) {");
        assertThat(KernelSpectateStreamHandlerGenerator.subscribes(liveOrder())).isTrue();

        DomainMetadata quiet = order().realTimeApi(true).build();
        String content = gen.generate(quiet).content();
        assertThat(KernelSpectateStreamHandlerGenerator.subscribes(quiet)).isFalse();
        assertThat(content)
                .contains("public OrderSpectateStreamHandler(OrderService service) {")
                .contains("found = service.findById(id)")
                .contains("Thread.sleep(KEEPALIVE_INTERVAL_MILLIS);")
                .contains("spectate(exchange);")
                .doesNotContain("EventEngine")
                .doesNotContain("subscribe(")
                .doesNotContain("BlockingQueue");
    }

    @Test
    @DisplayName("a tenant-partitioned entity's handler carries the tenant guard before it reads the id")
    void tenantGuardOnPartitionedMetadata() {
        String content = gen.generate(order().realTimeApi(true).dataScope(DataScope.TENANT).build()).content();

        int guard = content.indexOf("if (!KernelProviders.STORAGE_CONTEXT.isBound())");
        assertThat(guard).isGreaterThan(-1);
        assertThat(content.indexOf("UUID id;")).isGreaterThan(guard);
    }

    @Test
    @DisplayName("a streaming action named spectate would share the handler's name, and is refused")
    void streamingActionNamedSpectateIsRefused() {
        DomainMetadata clash = order().realTimeApi(true)
                .actions(List.of(ActionMetadata.builder("spectate").methodName("spectate").streaming(true).build()))
                .build();
        DomainMetadata respondOnce = order().realTimeApi(true)
                .actions(List.of(ActionMetadata.builder("spectate").methodName("spectate").build()))
                .build();

        assertThatThrownBy(() -> gen.generate(clash))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("both stream handlers would be named OrderSpectateStreamHandler");
        assertThat(gen.generate(respondOnce)).isNotNull();
    }

    @Test
    @DisplayName("stays on the SPI: no SSE wire literal")
    void kernelTargetDiscipline() {
        assertThat(gen.generate(liveOrder()).content())
                .contains("public void handle(HttpStreamExchange exchange)")
                .doesNotContain("text/event-stream");
    }

    @Test
    @DisplayName("same metadata → byte-identical output (determinism, constraint #3)")
    void deterministicOutput() {
        assertThat(gen.generate(liveOrder()).content()).isEqualTo(gen.generate(liveOrder()).content());
    }
}
