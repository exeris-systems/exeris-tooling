package eu.exeris.tooling.codegen.java.kernel;

import eu.exeris.tooling.codegen.core.generator.BackendGenerator.ArtifactType;
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
        @DisplayName("Should generate Handler for domain entity")
        void shouldGenerateHandler() {
            // Given
            DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                    .path("/orders")
                    .build();

            // When
            List<GeneratedFile> files = strategy.generate(metadata);

            // Then
            GeneratedFile handler = files.stream()
                    .filter(f -> f.artifactType() == ArtifactType.CONTROLLER)
                    .findFirst()
                    .orElseThrow();

            assertThat(handler.className()).isEqualTo("OrderHandler");
            assertThat(handler.packageName()).isEqualTo("com.example.handler");
            assertThat(handler.content())
                    .contains("public class OrderHandler")
                    .contains("OrderService service")
                    .contains("handleGetAll")
                    .contains("handleGetById")
                    .contains("handleCreate")
                    .contains("handleUpdate")
                    .contains("handleDelete");
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
        @DisplayName("Should generate all six artifacts (Handler, Service, Repository, Flyway, Client, OpenAPI)")
        void shouldGenerateAllArtifacts() {
            // Given
            DomainMetadata metadata = DomainMetadata.builder("Product", "com.shop.domain")
                    .module("catalog")
                    .path("/products")
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
                            ArtifactType.CONFIGURATION,
                            ArtifactType.CLIENT,
                            ArtifactType.OPENAPI_SPEC
                    );
        }
    }
}

