package eu.exeris.tooling.codegen.java.kernel;

import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator.ArtifactType;
import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
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
    @DisplayName("Full Generation")
    class FullGenerationTests {

        @Test
        @DisplayName("Should generate the five SPI-aligned artifacts (Handler, Service, Repository, Flyway, OpenAPI)")
        void shouldGenerateAllArtifacts() {
            // Given
            DomainMetadata metadata = DomainMetadata.builder("Product", "com.shop.domain")
                    .module("catalog")
                    .path("/products")
                    .build();

            // When
            List<GeneratedFile> files = strategy.generate(metadata);

            // Then
            assertThat(files).hasSize(5);
            assertThat(files).extracting(GeneratedFile::artifactType)
                    .containsExactlyInAnyOrder(
                            ArtifactType.CONTROLLER,
                            ArtifactType.SERVICE,
                            ArtifactType.REPOSITORY,
                            ArtifactType.CONFIGURATION,
                            ArtifactType.OPENAPI_SPEC
                    );
        }
    }
}

