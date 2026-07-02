package eu.exeris.tooling.codegen.java.kernel;

import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator.ArtifactType;
import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import eu.exeris.sdk.sourcemodel.ast.DomainEventMetadata;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.FieldMetadata;
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
                // ADR-050: a declared @DomainEvent.topic lands on the per-type spec
                // via the three-arg factory; an event without a topic keeps the
                // two-arg form (topic = null → "no override").
                .contains("EventTypeSpec.ofPersistent(\"OrderShippedEvent\", "
                        + "1393336469, \"orders.shipped\")")
                .contains("EventTypeSpec.ofPersistent(\"OrderCreatedEvent\", 1195226144)")
                .contains("public OrderEventPublisher(EventEngine eventEngine)")
                .contains("publishOrderCreatedEvent(UUID streamId)")
                .contains("publishOrderShippedEvent(UUID streamId)")
                .contains("eventEngine.bus().publish(descriptor, EventPayload.empty())")
                .contains("private void registerEventTypes()")
                .contains("eventEngine.registry().register(ORDER_CREATED_EVENT)")
                .contains("eventEngine.registry().register(ORDER_SHIPPED_EVENT)");
    }

    @Test
    @DisplayName("Event with payloadFields emits a redacted record + ADR-046 codec-resolved publish (EV1)")
    void shouldEmitPayloadRecordAndCodecResolution() {
        DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                .fields(List.of(
                        FieldMetadata.builder("total", "BigDecimal").build(),
                        FieldMetadata.builder("active", "boolean").build(),
                        FieldMetadata.builder("secret", "String").build()))
                .events(List.of(
                        DomainEventMetadata.builder("OrderCreated")
                                .payloadFields(List.of("total", "active", "secret"))
                                .sensitiveFields(List.of("secret"))
                                .build()))
                .build();

        GeneratedFile publisher = strategy.generate(metadata).stream()
                .filter(f -> f.artifactType() == ArtifactType.EVENT)
                .findFirst()
                .orElseThrow();

        assertThat(publisher.content())
                // Redacted payload record — `total` + `active` kept, `secret`
                // (sensitive) dropped: neither its component nor its getter is emitted.
                .contains("record OrderCreatedEventPayload(BigDecimal total, boolean active)")
                .doesNotContain("String secret")
                .doesNotContain("entity.getSecret()")
                // publish takes the entity and builds the record from its getters;
                // the boolean field uses the `isX()` accessor (B3), not `getX()`.
                .contains("publishOrderCreatedEvent(UUID streamId, Order entity)")
                .contains("new OrderCreatedEventPayload(entity.getTotal(), entity.isActive())")
                // ADR-046 "site B" — resolve the codec via the provider slot, encode.
                .contains("KernelProviders.eventPayloadCodecRegistry()")
                .contains("resolve(payloadType, EventCodecContext.JSON)")
                .contains("codec.encode(payload, EventCodecContext.json(")
                // Fallback to empty payload + producer-side codec-resolution JFR.
                .contains("return EventPayload.empty()")
                .contains("class CodecUnresolvedEvent extends Event")
                // The Wall: no driver / Jackson symbol leaks into the generated publisher.
                .doesNotContain("tools.jackson")
                .doesNotContain("CommunityJsonEventPayloadCodec");
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
