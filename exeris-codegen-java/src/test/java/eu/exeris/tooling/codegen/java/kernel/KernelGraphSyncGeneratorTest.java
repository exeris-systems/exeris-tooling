package eu.exeris.tooling.codegen.java.kernel;

import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator.ArtifactType;
import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.GraphEdgeMetadata;
import eu.exeris.sdk.sourcemodel.ast.GraphMetadata;
import eu.exeris.sdk.sourcemodel.ast.GraphPropertyMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Per-generator test for {@link KernelGraphSyncGenerator} (emits the
 * per-entity {@code *GraphSync} projection against Open-Core SPI
 * {@code spi.graph.{GraphEngine, GraphSession}} + {@code spi.graph.model.*}).
 */
@DisplayName("KernelGraphSyncGenerator")
class KernelGraphSyncGeneratorTest {

    private KernelGeneratorStrategy strategy;

    @BeforeEach
    void setup() {
        strategy = new KernelGeneratorStrategy();
    }

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

        assertThat(files).isNotEmpty()
                .extracting(GeneratedFile::artifactType)
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
                .hasMessageContaining("Duplicate edge names")
                .hasMessageContaining("ownerId")
                // @GraphEdge has no name attribute; the remedy is one edge per field.
                .hasMessageContaining("each field may carry at most one @GraphEdge")
                .hasMessageNotContaining("declare a unique name");
    }

    @Test
    @DisplayName("Should generate edge descriptors and upserts with direction, weight, and repeated edges")
    void shouldGenerateCustomEdgesAndWeightField() {
        DomainMetadata metadata = DomainMetadata.builder("Article", "com.example.domain")
                .graphMetadata(new GraphMetadata("Article",
                        List.of(),
                        List.of(
                                new GraphEdgeMetadata("authorId", "Author", "WRITTEN_BY", null,
                                        GraphEdgeMetadata.Direction.INCOMING, null, null, null, null, null, null),
                                new GraphEdgeMetadata("topicId", "Topic", "TAGGED_WITH", null,
                                        null, null, null, null, true, "score", null),
                                new GraphEdgeMetadata("relatedReferences", "Article", "REFERENCES", "relatedId",
                                        null, null, null, null, null, null, 3.5),
                                new GraphEdgeMetadata("relatedSimilarTo", "Article", "SIMILAR_TO", "relatedId",
                                        null, true, null, null, null, null, null)),
                        List.of()))
                .build();

        List<GeneratedFile> files = strategy.generate(metadata);
        GeneratedFile graphSync = files.stream()
                .filter(f -> f.artifactType() == ArtifactType.GRAPH_SYNC)
                .findFirst()
                .orElseThrow();

        assertThat(graphSync.content())
                .contains("new GraphEdgeDescriptor(\"Article\", \"WRITTEN_BY\", \"Author\", "
                        + "1.0, false, GraphEdgeDescriptor.Direction.INCOMING, null)")
                .contains("new GraphEdgeDescriptor(\"Article\", \"REFERENCES\", \"Article\", "
                        + "3.5, false, GraphEdgeDescriptor.Direction.OUTGOING, null)")
                .contains("new GraphEdgeDescriptor(\"Article\", \"SIMILAR_TO\", \"Article\", "
                        + "1.0, true, GraphEdgeDescriptor.Direction.BOTH, null)")
                .contains("session.upsertEdge(TOPIC_ID_EDGE, entity.getId(), entity.getTopicId(), "
                        + "(entity.getScore() != null ? entity.getScore().doubleValue() : 1.0), null)")
                .contains("session.upsertEdge(RELATED_REFERENCES_EDGE, entity.getId(), "
                        + "entity.getRelatedId(), 3.5, null)")
                .contains("session.upsertEdge(RELATED_SIMILAR_TO_EDGE, entity.getId(), "
                        + "entity.getRelatedId(), 1.0, null)");
    }

    @Test
    @DisplayName("Should generate node descriptor with property list and property buffer encoding")
    void shouldGenerateNodeProperties() {
        DomainMetadata metadata = DomainMetadata.builder("Article", "com.example.domain")
                .graphMetadata(new GraphMetadata("Article",
                        List.of(
                                new GraphPropertyMetadata("title", "STRING", true),
                                new GraphPropertyMetadata("article_slug", "STRING", false, "slug")),
                        List.of(),
                        List.of()))
                .build();

        List<GeneratedFile> files = strategy.generate(metadata);
        GeneratedFile graphSync = files.stream()
                .filter(f -> f.artifactType() == ArtifactType.GRAPH_SYNC)
                .findFirst()
                .orElseThrow();

        assertThat(graphSync.content())
                .contains("import eu.exeris.kernel.spi.memory.LoanedBuffer")
                .contains("public static final GraphNodeDescriptor NODE_DESCRIPTOR = "
                        + "new GraphNodeDescriptor(\"Article\", \"articles\", \"id\", "
                        + "List.of(\"title\", \"article_slug\"), true)")
                .contains("try (LoanedBuffer properties = encodeNodeProperties(entity))")
                .contains("session.upsertNode(NODE_LABEL, entity.getId(), properties)")
                .contains("public ArticleGraphSync(GraphEngine graphEngine, MemoryAllocator allocator)")
                .contains("private LoanedBuffer encodeNodeProperties(Article entity)")
                .contains("appendProperty(json, \"\\\"title\\\":\", entity.getTitle())")
                .contains("appendProperty(json, \"\\\"article_slug\\\":\", entity.getSlug())")
                .contains("LoanedBuffer buffer = allocator.allocateNetwork(bytes.length)")
                .contains("buffer.setSize(bytes.length)")
                .doesNotContain("implements LoanedBuffer");
    }
}
