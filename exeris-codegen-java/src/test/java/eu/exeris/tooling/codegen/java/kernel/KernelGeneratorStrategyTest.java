package eu.exeris.tooling.codegen.java.kernel;

import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator.ArtifactType;
import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import eu.exeris.sdk.sourcemodel.ast.DomainEventMetadata;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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
                    .contains("private void register(EventTypeSpec spec)");
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
    }

    @Nested
    @DisplayName("Full Generation")
    class FullGenerationTests {

        @Test
        @DisplayName("Should generate the six SPI-aligned artifacts when events are declared (Handler, Service, Repository, Event, Flyway, OpenAPI)")
        void shouldGenerateAllArtifacts() {
            // Given
            DomainMetadata metadata = DomainMetadata.builder("Product", "com.shop.domain")
                    .module("catalog")
                    .path("/products")
                    .events(List.of(DomainEventMetadata.simple("ProductCreated")))
                    .build();

            // When
            List<GeneratedFile> files = strategy.generate(metadata);

            // Then
            assertThat(files).hasSize(6);
            assertThat(files).extracting(GeneratedFile::artifactType)
                    .containsExactlyInAnyOrder(
                            ArtifactType.CONTROLLER,
                            ArtifactType.SERVICE,
                            ArtifactType.REPOSITORY,
                            ArtifactType.EVENT,
                            ArtifactType.CONFIGURATION,
                            ArtifactType.OPENAPI_SPEC
                    );
        }
    }
}

