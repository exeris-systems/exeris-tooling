package eu.exeris.tooling.codegen.java.kernel;

import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator.ArtifactType;
import eu.exeris.sdk.sourcemodel.ast.GraphMetadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link KernelTableNaming#effectiveTable}: the SDK's derived plural by default, the
 * {@code tableName} override when set, and lower-case either way.
 */
@DisplayName("KernelTableNaming")
class KernelTableNamingTest {

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
            "Order, orders",
            "ConstructionOrder, construction_orders",
            "Colony, colonies",
            "Technology, technologies",
            "Box, boxes",
            "Address, addresses",
            "Status, statuses",
            "Branch, branches",
            "Key, keys",
            "Day, days",
    })
    @DisplayName("default table is the snake-cased SDK plural")
    void defaultTableIsTheSdkPlural(String entityName, String expected) {
        DomainMetadata metadata = DomainMetadata.builder(entityName, "eu.exeris.app.domain").build();

        assertThat(KernelTableNaming.effectiveTable(metadata))
                .isEqualTo(expected)
                .isEqualTo(metadata.effectiveTableName());
    }

    @Test
    @DisplayName("an override is honoured, trimmed and lower-cased")
    void overrideIsHonoured() {
        DomainMetadata keepOld = DomainMetadata.builder("Colony", "eu.exeris.app.domain")
                .tableName("colonys").build();
        DomainMetadata mixedCase = DomainMetadata.builder("Order", "eu.exeris.app.domain")
                .tableName("  Legacy_Orders ").build();

        assertThat(KernelTableNaming.effectiveTable(keepOld)).isEqualTo("colonys");
        assertThat(KernelTableNaming.effectiveTable(mixedCase)).isEqualTo("legacy_orders");
    }

    @Test
    @DisplayName("a blank override derives the table")
    void blankOverrideDerives() {
        DomainMetadata metadata = DomainMetadata.builder("Colony", "eu.exeris.app.domain")
                .tableName("   ").build();

        assertThat(KernelTableNaming.effectiveTable(metadata)).isEqualTo("colonies");
    }

    @Test
    @DisplayName("the graph-sync node descriptor names the same table as the repository")
    void graphSyncUsesTheSameTable() {
        DomainMetadata metadata = DomainMetadata.builder("Colony", "eu.exeris.app.domain")
                .graphMetadata(new GraphMetadata("Colony", List.of(), List.of(), List.of()))
                .build();

        String content = new KernelGeneratorStrategy().generate(metadata).stream()
                .filter(f -> f.artifactType() == ArtifactType.GRAPH_SYNC)
                .findFirst()
                .orElseThrow()
                .content();

        assertThat(content).contains("GraphNodeDescriptor.create(\"Colony\", \"colonies\")");
    }
}
