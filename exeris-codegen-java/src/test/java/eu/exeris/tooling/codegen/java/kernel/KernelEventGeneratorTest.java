package eu.exeris.tooling.codegen.java.kernel;

import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator.ArtifactType;
import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import eu.exeris.sdk.sourcemodel.ast.DomainEventMetadata;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Per-generator test for {@link KernelEventGenerator} (emits the per-entity
 * {@code *EventPublisher} against Open-Core SPI
 * {@code spi.events.{EventEngine, EventDescriptor, EventPayload,
 * EventTypeSpec}}).
 */
@DisplayName("KernelEventGenerator")
class KernelEventGeneratorTest {

    private KernelGeneratorStrategy strategy;

    @BeforeEach
    void setup() {
        strategy = new KernelGeneratorStrategy();
    }

    @Test
    @DisplayName("Should generate EventPublisher emitting against Open-Core SPI EventEngine")
    void shouldGenerateEventPublisher() {
        DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                .events(List.of(
                        DomainEventMetadata.simple("OrderCreated"),
                        DomainEventMetadata.withTopic("OrderShipped", "orders.shipped")))
                .build();

        List<GeneratedFile> files = strategy.generate(metadata);

        GeneratedFile publisher = files.stream()
                .filter(f -> f.artifactType() == ArtifactType.EVENT)
                .findFirst()
                .orElseThrow();

        assertThat(publisher.className()).isEqualTo("OrderEventPublisher");
        assertThat(publisher.packageName()).isEqualTo("com.example.event");
        assertThat(publisher.content())
                .contains("import eu.exeris.kernel.spi.events.EventEngine")
                .contains("import eu.exeris.kernel.spi.events.EventDescriptor")
                .contains("import eu.exeris.kernel.spi.events.EventPayload")
                .contains("import eu.exeris.kernel.spi.events.EventTypeSpec")
                .contains("public final class OrderEventPublisher")
                .contains("EventTypeSpec ORDER_CREATED_EVENT")
                .contains("EventTypeSpec ORDER_SHIPPED_EVENT")
                .contains("EventTypeSpec.ofPersistent(\"OrderCreatedEvent\"")
                .contains("EventTypeSpec.ofPersistent(\"OrderShippedEvent\"")
                .contains("public OrderEventPublisher(EventEngine eventEngine)")
                .contains("publishOrderCreatedEvent(UUID streamId)")
                .contains("publishOrderShippedEvent(UUID streamId)")
                .contains("eventEngine.bus().publish(descriptor, EventPayload.empty())")
                .contains("private void registerEventTypes()")
                .contains("eventEngine.registry().register(ORDER_CREATED_EVENT)")
                .contains("eventEngine.registry().register(ORDER_SHIPPED_EVENT)");
    }

    @Test
    @DisplayName("Should not emit an event publisher for domains without events")
    void shouldSkipEventPublisherWhenNoEvents() {
        DomainMetadata metadata = DomainMetadata.builder("Tenant", "com.example.domain")
                .build();

        List<GeneratedFile> files = strategy.generate(metadata);

        assertThat(files).isNotEmpty()
                .extracting(GeneratedFile::artifactType)
                .doesNotContain(ArtifactType.EVENT);
    }

    @Test
    @DisplayName("Should reject duplicate event names after normalisation")
    void shouldRejectDuplicateNormalisedEventNames() {
        // Both entries normalise to "OrderEvent": a null name on entity Order
        // becomes "<entity>Event", and an explicit "Order" also gets the Event
        // suffix → same constant name + same hash ordinal → duplicate field
        // in the generated class and a guaranteed registry conflict at runtime.
        DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                .events(List.of(
                        DomainEventMetadata.simple(null),
                        DomainEventMetadata.simple("Order")))
                .build();

        assertThatThrownBy(() -> strategy.generate(metadata))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate event names after normalisation");
    }
}
