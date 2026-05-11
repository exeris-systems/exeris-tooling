package eu.exeris.tooling.codegen.java.kernel;

import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator.ArtifactType;
import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import eu.exeris.sdk.sourcemodel.ast.DomainEventMetadata;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.GraphEdgeMetadata;
import eu.exeris.sdk.sourcemodel.ast.GraphMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for Kernel Generator Strategy - Exeris Kernel (Pure Java) backend.
 */
@DisplayName("KernelGeneratorStrategy Tests")
class KernelGeneratorStrategyTest {

    private KernelGeneratorStrategy strategy;

    @BeforeEach
    void setup() {
        strategy = new KernelGeneratorStrategy();
    }

    @Nested
    @DisplayName("Handler Generation")
    class HandlerGenerationTests {

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
                    .contains("import tools.jackson.databind.ObjectMapper")
                    .contains("OrderService service")
                    .contains("handleGetAll(HttpExchange exchange)")
                    .contains("handleGetById(HttpExchange exchange)")
                    .contains("handleCreate(HttpExchange exchange)")
                    .contains("handleUpdate(HttpExchange exchange)")
                    .contains("handleDelete(HttpExchange exchange)")
                    .contains("exchange.respond(HttpStatus.OK")
                    .contains("HttpStatus.CREATED")
                    .contains("HttpStatus.NO_CONTENT")
                    .contains("HttpStatus.BAD_REQUEST")
                    .contains("HttpStatus.NOT_FOUND")
                    .contains("HttpStatus.INTERNAL_SERVER_ERROR");
        }
    }

    @Nested
    @DisplayName("Service Generation")
    class ServiceGenerationTests {

        @Test
        @DisplayName("Should generate Service for domain entity")
        void shouldGenerateService() {
            // Given
            DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                    .build();

            // When
            List<GeneratedFile> files = strategy.generate(metadata);

            // Then
            GeneratedFile service = files.stream()
                    .filter(f -> f.artifactType() == ArtifactType.SERVICE)
                    .findFirst()
                    .orElseThrow();

            assertThat(service.className()).isEqualTo("OrderService");
            assertThat(service.packageName()).isEqualTo("com.example.service");
            assertThat(service.content())
                    .contains("public class OrderService")
                    .contains("OrderRepository repository")
                    .contains("findById")
                    .contains("save")
                    .contains("delete");
        }
    }

    @Nested
    @DisplayName("Repository Generation")
    class RepositoryGenerationTests {

        @Test
        @DisplayName("Should generate Repository class with JDBC")
        void shouldGenerateRepository() {
            // Given
            DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                    .build();

            // When
            List<GeneratedFile> files = strategy.generate(metadata);

            // Then
            GeneratedFile repo = files.stream()
                    .filter(f -> f.artifactType() == ArtifactType.REPOSITORY)
                    .findFirst()
                    .orElseThrow();

            assertThat(repo.className()).isEqualTo("OrderRepository");
            assertThat(repo.packageName()).isEqualTo("com.example.repository");
            assertThat(repo.content())
                    .contains("public class OrderRepository")
                    .contains("DataSource")
                    .contains("findById")
                    .contains("findAll")
                    .contains("save")
                    .contains("deleteById")
                    .contains("count");
        }
    }

    @Nested
    @DisplayName("Event Generation")
    class EventGenerationTests {

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

            assertThat(files).extracting(GeneratedFile::artifactType)
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

    @Nested
    @DisplayName("Event Handler Generation")
    class EventHandlerGenerationTests {

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

            assertThat(files).extracting(GeneratedFile::artifactType)
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

    @Nested
    @DisplayName("Graph Sync Generation")
    class GraphSyncGenerationTests {

        @Test
        @DisplayName("Should generate GraphSync emitting against Open-Core SPI GraphEngine + GraphSession")
        void shouldGenerateGraphSync() {
            DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                    .graphMetadata(new GraphMetadata("Order",
                            List.of(),
                            List.of(
                                    new GraphEdgeMetadata("ownerId", "User", "OWNED_BY"),
                                    new GraphEdgeMetadata("parentOrderId", "Order", "FOLLOWS")),
                            List.of()))
                    .build();

            List<GeneratedFile> files = strategy.generate(metadata);

            GeneratedFile graphSync = files.stream()
                    .filter(f -> f.artifactType() == ArtifactType.GRAPH_SYNC)
                    .findFirst()
                    .orElseThrow();

            assertThat(graphSync.className()).isEqualTo("OrderGraphSync");
            assertThat(graphSync.packageName()).isEqualTo("com.example.graph");
            assertThat(graphSync.content())
                    .contains("import eu.exeris.kernel.spi.graph.GraphEngine")
                    .contains("import eu.exeris.kernel.spi.graph.GraphSession")
                    .contains("import eu.exeris.kernel.spi.graph.model.GraphNodeDescriptor")
                    .contains("import eu.exeris.kernel.spi.graph.model.GraphEdgeDescriptor")
                    .contains("public class OrderGraphSync")
                    .contains("private static final String NODE_LABEL = \"Order\"")
                    .contains("public static final GraphNodeDescriptor NODE_DESCRIPTOR =")
                    .contains("GraphNodeDescriptor.create(\"Order\", \"orders\")")
                    .contains("public static final GraphEdgeDescriptor OWNER_ID_EDGE =")
                    .contains("GraphEdgeDescriptor.create(\"Order\", \"OWNED_BY\", \"User\")")
                    .contains("public static final GraphEdgeDescriptor PARENT_ORDER_ID_EDGE =")
                    .contains("GraphEdgeDescriptor.create(\"Order\", \"FOLLOWS\", \"Order\")")
                    .contains("public OrderGraphSync(GraphEngine graphEngine)")
                    .contains("public void syncToGraph(Order entity)")
                    .contains("try (GraphSession session = graphEngine.openSession())")
                    .contains("session.upsertNode(NODE_LABEL, entity.getId(), null)")
                    .contains("if (entity.getOwnerId() != null)")
                    .contains("session.upsertEdge(OWNER_ID_EDGE, entity.getId(), entity.getOwnerId(), 1.0, null)")
                    .contains("public void deleteFromGraph(UUID entityId)")
                    .contains("session.deleteNode(NODE_LABEL, entityId)");
        }

        @Test
        @DisplayName("Should not emit GraphSync for domains without graph metadata")
        void shouldSkipGraphSyncWhenNoGraphMetadata() {
            DomainMetadata metadata = DomainMetadata.builder("Tenant", "com.example.domain")
                    .build();

            List<GeneratedFile> files = strategy.generate(metadata);

            assertThat(files).extracting(GeneratedFile::artifactType)
                    .doesNotContain(ArtifactType.GRAPH_SYNC);
        }

        @Test
        @DisplayName("Should reject duplicate edge names")
        void shouldRejectDuplicateEdgeNames() {
            DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                    .graphMetadata(new GraphMetadata("Order", List.of(),
                            List.of(
                                    new GraphEdgeMetadata("ownerId", "User", "OWNED_BY"),
                                    new GraphEdgeMetadata("ownerId", "User", "CREATED_BY")),
                            List.of()))
                    .build();

            assertThatThrownBy(() -> strategy.generate(metadata))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Duplicate edge names");
        }
    }

    @Nested
    @DisplayName("Full Generation")
    class FullGenerationTests {

        @Test
        @DisplayName("Should generate the eight SPI-aligned artifacts when events + graph are declared (Controller, Service, Repository, Event, EventHandler, GraphSync, Flyway, OpenAPI)")
        void shouldGenerateAllArtifacts() {
            // Given
            DomainMetadata metadata = DomainMetadata.builder("Product", "com.shop.domain")
                    .module("catalog")
                    .path("/products")
                    .events(List.of(DomainEventMetadata.simple("ProductCreated")))
                    .graphMetadata(GraphMetadata.simple("Product"))
                    .build();

            // When
            List<GeneratedFile> files = strategy.generate(metadata);

            // Then
            assertThat(files).hasSize(8);
            assertThat(files).extracting(GeneratedFile::artifactType)
                    .containsExactlyInAnyOrder(
                            ArtifactType.CONTROLLER,
                            ArtifactType.SERVICE,
                            ArtifactType.REPOSITORY,
                            ArtifactType.EVENT,
                            ArtifactType.EVENT_HANDLER,
                            ArtifactType.GRAPH_SYNC,
                            ArtifactType.CONFIGURATION,
                            ArtifactType.OPENAPI_SPEC
                    );
        }
    }
}

