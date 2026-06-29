package eu.exeris.tooling.codegen.java.kernel;

import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator.ArtifactType;
import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.FieldMetadata;
import eu.exeris.sdk.sourcemodel.ast.RelationshipMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Per-generator test for {@link KernelServiceGenerator}. The emitted
 * service is a pure CRUD-delegation POJO over {@code *Repository}
 * (no direct Kernel API surface).
 */
@DisplayName("KernelServiceGenerator")
class KernelServiceGeneratorTest {

    private KernelGeneratorStrategy strategy;

    @BeforeEach
    void setup() {
        strategy = new KernelGeneratorStrategy();
    }

    @Test
    @DisplayName("Should generate Service for domain entity")
    void shouldGenerateService() {
        DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                .build();

        List<GeneratedFile> files = strategy.generate(metadata);

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

    @Test
    @DisplayName("T8: emits delegating finders mirroring the repository surface (filterable + MANY_TO_ONE)")
    void shouldEmitDelegatingFinders() {
        DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                .fields(List.of(
                        FieldMetadata.builder("status", "com.example.OrderStatus").filterable(true).build(),
                        FieldMetadata.builder("quantity", "int").filterable(true).build()))
                .relationships(List.of(
                        RelationshipMetadata.builder("customer", "Customer")
                                .type(RelationshipMetadata.RelationType.MANY_TO_ONE).build()))
                .build();

        GeneratedFile service = strategy.generate(metadata).stream()
                .filter(f -> f.artifactType() == ArtifactType.SERVICE)
                .findFirst().orElseThrow();

        String src = service.content();
        assertThat(src)
                // Same signatures as the repository, delegating straight through.
                .contains("public List<Order> findByStatus(OrderStatus status)")
                .contains("return repository.findByStatus(status)")
                .contains("public List<Order> findByQuantity(int quantity)")
                .contains("return repository.findByQuantity(quantity)")
                .contains("public List<Order> findByCustomerId(UUID customerId)")
                .contains("return repository.findByCustomerId(customerId)");
    }
}
