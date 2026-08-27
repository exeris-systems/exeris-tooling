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

        assertThat(files).hasSize(11);
        assertThat(files).extracting(GeneratedFile::artifactType)
                .containsExactlyInAnyOrder(
                        ArtifactType.CONTROLLER,
                        // ADR-076: one here, not two — Product is unversioned, so it has no
                        // stale-version failure mode and no conflict type is emitted for it.
                        ArtifactType.DOMAIN_ERROR,
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

    @Test
    @DisplayName("no emitted artefact binds a logging facade — generated code logs through System.Logger")
    void bindsNoLoggingFacade() {
        DomainMetadata metadata = DomainMetadata.builder("Product", "com.shop.domain")
                .module("catalog")
                .path("/products")
                .events(List.of(DomainEventMetadata.simple("ProductCreated")))
                .graphMetadata(GraphMetadata.simple("Product"))
                .sagaMetadata(SagaMetadata.simple("ProductSaga"))
                .build();

        // Tooling emits no pom.xml, so anything generated code imports is a hard requirement on
        // the consumer's build. slf4j-api is not a dependency of exeris-kernel-spi or -core — it
        // used to reach an app only through whichever driver tier it happened to pick. The e2e
        // gate enforces this by compiling without slf4j on the classpath at all; this is the fast
        // check that says which artefact regressed.
        for (GeneratedFile file : strategy.generate(metadata)) {
            assertThat(file.content())
                    .as("%s (%s) must not bind a logging facade", file.className(), file.artifactType())
                    .doesNotContain("org.slf4j")
                    .doesNotContain("org.apache.logging")
                    .doesNotContain("java.util.logging");
        }
    }
}
