package eu.exeris.tooling.codegen.java.kernel;

import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator.ArtifactType;
import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import eu.exeris.sdk.sourcemodel.ast.DomainEventMetadata;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.GraphMetadata;
import eu.exeris.sdk.sourcemodel.ast.SagaMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Registration / full-pipeline smoke tests for
 * {@link KernelGeneratorStrategy} itself.
 *
 * <p>Per-generator emission shape is covered by the dedicated
 * {@code Kernel*GeneratorTest} classes in this package — this file
 * verifies only that the strategy's registered set produces the right
 * <i>artifact type set</i> for a feature-complete domain. If a generator
 * is added/removed from the registry, this test changes; per-emission
 * details should stay in the per-generator tests.
 */
@DisplayName("KernelGeneratorStrategy — registration smoke")
class KernelGeneratorStrategyTest {

    private KernelGeneratorStrategy strategy;

    @BeforeEach
    void setup() {
        strategy = new KernelGeneratorStrategy();
    }

    @Test
    @DisplayName("Should generate the ten SPI-aligned artifacts when events + graph + saga are declared (Controller, Service, Repository, Event, EventHandler, GraphSync, Saga, Flyway, OpenAPI, Client)")
    void shouldGenerateAllArtifacts() {
        DomainMetadata metadata = DomainMetadata.builder("Product", "com.shop.domain")
                .module("catalog")
                .path("/products")
                .events(List.of(DomainEventMetadata.simple("ProductCreated")))
                .graphMetadata(GraphMetadata.simple("Product"))
                .sagaMetadata(SagaMetadata.simple("ProductSaga"))
                .build();

        List<GeneratedFile> files = strategy.generate(metadata);

        // CLIENT artifact added by KernelClientGenerator unparking
        // (this PR). Strategy now emits 10 artifacts per feature-
        // complete domain instead of the previous 9.
        assertThat(files).hasSize(10);
        assertThat(files).extracting(GeneratedFile::artifactType)
                .containsExactlyInAnyOrder(
                        ArtifactType.CONTROLLER,
                        ArtifactType.SERVICE,
                        ArtifactType.REPOSITORY,
                        ArtifactType.EVENT,
                        ArtifactType.EVENT_HANDLER,
                        ArtifactType.GRAPH_SYNC,
                        ArtifactType.SAGA,
                        ArtifactType.CONFIGURATION,
                        ArtifactType.OPENAPI_SPEC,
                        ArtifactType.CLIENT
                );
    }
}
