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
 * Per-generator test for {@link KernelEventHandlerGenerator} (emits the
 * per-entity {@code *EventSubscriber} against Open-Core SPI
 * {@code spi.events.{EventBus, EventHandler, SubscriptionToken}}).
 */
@DisplayName("KernelEventHandlerGenerator")
class KernelEventHandlerGeneratorTest {

    private KernelGeneratorStrategy strategy;

    @BeforeEach
    void setup() {
        strategy = new KernelGeneratorStrategy();
    }

    @Test
    @DisplayName("Should generate EventSubscriber emitting against Open-Core SPI EventBus + EventHandler")
    void shouldGenerateEventSubscriber() {
        DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                .events(List.of(
                        DomainEventMetadata.simple("OrderCreated"),
                        DomainEventMetadata.withTopic("OrderShipped", "orders.shipped")))
                .build();

        List<GeneratedFile> files = strategy.generate(metadata);

        GeneratedFile subscriber = files.stream()
                .filter(f -> f.artifactType() == ArtifactType.EVENT_HANDLER)
                .findFirst()
                .orElseThrow();

        assertThat(subscriber.className()).isEqualTo("OrderEventSubscriber");
        assertThat(subscriber.packageName()).isEqualTo("com.example.event");
        assertThat(subscriber.content())
                .contains("import eu.exeris.kernel.spi.events.EventEngine")
                .contains("import eu.exeris.kernel.spi.events.EventDescriptor")
                .contains("import eu.exeris.kernel.spi.events.EventPayload")
                .contains("import eu.exeris.kernel.spi.events.SubscriptionToken")
                .contains("public class OrderEventSubscriber")
                .contains("private static final String ORDER_CREATED_EVENT = \"OrderCreatedEvent\"")
                .contains("private static final String ORDER_SHIPPED_EVENT = \"OrderShippedEvent\"")
                .doesNotContain("public static final String ORDER_CREATED_EVENT")
                .contains("public OrderEventSubscriber(EventEngine eventEngine)")
                .contains("public void subscribe()")
                .contains("public void unsubscribe()")
                .contains("if (!subscriptions.isEmpty())")
                .contains("throw new IllegalStateException(\"Already subscribed")
                .contains("subscriptions.add(eventEngine.bus().subscribe(ORDER_CREATED_EVENT, this::handleOrderCreatedEvent))")
                .contains("subscriptions.add(eventEngine.bus().subscribe(ORDER_SHIPPED_EVENT, this::handleOrderShippedEvent))")
                .contains("protected void handleOrderCreatedEvent(EventDescriptor descriptor, EventPayload payload)")
                .contains("protected void handleOrderShippedEvent(EventDescriptor descriptor, EventPayload payload)")
                .contains("try (payload)")
                .contains("eventEngine.bus().unsubscribe(token)");
    }

    @Test
    @DisplayName("Should not emit a subscriber for domains without events")
    void shouldSkipSubscriberWhenNoEvents() {
        DomainMetadata metadata = DomainMetadata.builder("Tenant", "com.example.domain")
                .build();

        List<GeneratedFile> files = strategy.generate(metadata);

        assertThat(files).isNotEmpty()
                .extracting(GeneratedFile::artifactType)
                .doesNotContain(ArtifactType.EVENT_HANDLER);
    }

    @Test
    @DisplayName("Should reject duplicate event names after normalisation")
    void shouldRejectDuplicateNormalisedEventNames() {
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
