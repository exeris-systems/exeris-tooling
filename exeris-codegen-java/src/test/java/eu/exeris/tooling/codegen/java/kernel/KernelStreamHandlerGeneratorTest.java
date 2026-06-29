package eu.exeris.tooling.codegen.java.kernel;

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
 * Per-generator test for {@link KernelStreamHandlerGenerator} (ADR-043 Slice 1).
 *
 * <p>The handler has two shapes, switched on whether the entity declares a
 * {@code @DomainEvent}: the EV1 producer (subscribe → project → emit) and the
 * keep-alive fallback. The end-to-end javac evidence that the producer compiles
 * against the live kernel 0.10 SPI lives in {@code KernelCodegenCompileTest}
 * (e2e module); this test pins the emitted shape.
 */
@DisplayName("KernelStreamHandlerGenerator")
class KernelStreamHandlerGeneratorTest {

    private final KernelStreamHandlerGenerator gen = new KernelStreamHandlerGenerator();

    private static DomainMetadata.Builder order() {
        return DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders")
                .realTimeApi(true);
    }

    @Test
    @DisplayName("supports() tracks the realTimeApi flag")
    void supportsTracksRealTimeApi() {
        assertThat(gen.supports(order().build())).isTrue();
        assertThat(gen.supports(DomainMetadata.builder("Order", "com.example.domain")
                .realTimeApi(false).build())).isFalse();
    }

    @Test
    @DisplayName("file identity: <Entity>StreamHandler in the .handler package, STREAM_HANDLER type")
    void fileIdentity() {
        GeneratedFile file = gen.generate(order()
                .events(List.of(DomainEventMetadata.simple("OrderCreated"))).build());

        assertThat(file.className()).isEqualTo("OrderStreamHandler");
        assertThat(file.packageName()).isEqualTo("com.example.handler");
        assertThat(file.artifactType()).isEqualTo(ArtifactType.STREAM_HANDLER);
        assertThat(file.extension()).isEqualTo("java");
    }

    @Test
    @DisplayName("EV1 producer: subscribes per @DomainEvent and projects each into a named StreamEvent")
    void producerSubscribesAndProjects() {
        DomainMetadata meta = order().events(List.of(
                DomainEventMetadata.simple("OrderCreated"),
                DomainEventMetadata.withTopic("OrderShipped", "orders.shipped"))).build();

        String content = gen.generate(meta).content();

        assertThat(content)
                .contains("implements HttpStreamHandler")
                .contains("public void handle(HttpStreamExchange exchange)")
                // bus reached via the ScopedValue accessor, not a constructed engine;
                // acquired inside try so a failed acquisition still runs close()
                .contains("EventBus bus = null")
                .contains("bus = KernelProviders.eventEngine().bus()")
                .contains("if (bus != null)")
                // bounded hand-off (No-Waste-Compute, ADR-043 obligation 4)
                .contains("STREAM_BUFFER_CAPACITY = 256")
                .contains("BlockingQueue<StreamEvent> queue = new ArrayBlockingQueue<>(STREAM_BUFFER_CAPACITY)")
                // subscription key is the normalised <Name>Event the publisher registered
                .contains("bus.subscribe(\"OrderCreatedEvent\", (descriptor, payload) ->")
                .contains("bus.subscribe(\"OrderShippedEvent\", (descriptor, payload) ->")
                // RAII close on the dispatch VT; bytes copied before close
                .contains("try (payload)")
                .contains("payload.segment().toArray(ValueLayout.JAVA_BYTE)")
                // SSE event: name is the RAW event name (the TS discriminator vocabulary)
                .contains("StreamEvent.of(\"OrderCreated\", data)")
                .contains("StreamEvent.of(\"OrderShipped\", data)")
                // drain on the stream VT; disconnect propagates; subscriptions dropped
                .contains("exchange.emit(queue.take())")
                .contains("catch (StreamClosedException closed)")
                .contains("bus.unsubscribe(token)")
                .contains("exchange.close()")
                // it's the producer, not the keep-alive fallback
                .doesNotContain("KEEPALIVE")
                .doesNotContain("Thread.sleep")
                // kernel-target discipline: Core owns the wire
                .doesNotContain("text/event-stream");
    }

    @Test
    @DisplayName("keep-alive fallback: no @DomainEvent → deterministic keep-alive loop, no bus subscription")
    void keepAliveFallbackWhenNoEvents() {
        String content = gen.generate(order().build()).content();

        assertThat(content)
                .contains("public void handle(HttpStreamExchange exchange)")
                // deterministic keep-alive scaffold (constant, no wall-clock)
                .contains("KEEPALIVE_INTERVAL_MILLIS = 15000L")
                .contains("StreamEvent.of(\"keep-alive\", \"\")")
                .contains("exchange.close()")
                // no producer machinery
                .doesNotContain("bus.subscribe")
                .doesNotContain("KernelProviders.eventEngine()")
                .doesNotContain("text/event-stream");
    }

    @Test
    @DisplayName("blank @DomainEvent name → SSE event: falls back to the normalised <Entity>Event wire-name")
    void blankNameFallsBackToNormalisedWireName() {
        String content = gen.generate(order()
                .events(List.of(DomainEventMetadata.simple(""))).build()).content();

        // subscription key and wire-name both collapse to <Entity>Event so the
        // frame is never an unnamed (empty event:) one.
        assertThat(content)
                .contains("bus.subscribe(\"OrderEvent\", (descriptor, payload) ->")
                .contains("StreamEvent.of(\"OrderEvent\", data)");
    }

    @Test
    @DisplayName("two @DomainEvents normalising to the same <Name>Event key fail at generation")
    void throwsOnDuplicateNormalisedKeys() {
        // both normalise to "OrderEvent": "" → <Entity>Event, "Order" → "Order" + "Event".
        // Without the assertDistinctEventNames guard the producer would emit two
        // bus.subscribe("OrderEvent", ...) calls and double-deliver every event.
        DomainMetadata meta = order().events(List.of(
                DomainEventMetadata.simple(""),
                DomainEventMetadata.simple("Order"))).build();

        assertThatThrownBy(() -> gen.generate(meta))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("same metadata → byte-identical output (determinism, constraint #3)")
    void deterministicOutput() {
        DomainMetadata meta = order().events(List.of(
                DomainEventMetadata.simple("OrderCreated"),
                DomainEventMetadata.withTopic("OrderShipped", "orders.shipped"))).build();

        assertThat(gen.generate(meta).content()).isEqualTo(gen.generate(meta).content());
    }
}
