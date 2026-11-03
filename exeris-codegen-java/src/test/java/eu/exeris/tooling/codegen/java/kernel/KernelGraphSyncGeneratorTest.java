package eu.exeris.tooling.codegen.java.kernel;

import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator.ArtifactType;
import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.FieldMetadata;
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
                .contains("Set<UUID> ownerIdTargets = targets(entity.getOwnerId());")
                .contains("removeStaleEdges(session, entity.getId(), ownerIdTargets, OWNER_ID_EDGE);")
                .contains("upsertEdges(session, OWNER_ID_EDGE, entity.getId(), ownerIdTargets, 1.0);")
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
                .contains("upsertEdges(session, TOPIC_ID_EDGE, entity.getId(), topicIdTargets, "
                        + "(entity.getScore() != null ? entity.getScore().doubleValue() : 1.0));")
                .contains("Set<UUID> relatedReferencesTargets = targets(entity.getRelatedId());")
                .contains("upsertEdges(session, RELATED_REFERENCES_EDGE, entity.getId(), "
                        + "relatedReferencesTargets, 3.5);")
                .contains("Set<UUID> relatedSimilarToTargets = targets(entity.getRelatedId());")
                .contains("upsertEdges(session, RELATED_SIMILAR_TO_EDGE, entity.getId(), "
                        + "relatedSimilarToTargets, 1.0);");
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

    @Test
    @DisplayName("a single edge is synchronised: stored targets read in one outgoing hop, stale ones deleted, "
            + "the held one upserted")
    void scalarEdgeIsSynchronised() {
        String content = graphSync(entityWithEdges(
                List.of(field("ownerId", "java.util.UUID")),
                new GraphEdgeMetadata("ownerId", "User", "OWNED_BY", null,
                        GraphEdgeMetadata.Direction.INCOMING, true, null, null, null, null, null)));

        assertThat(content)
                .contains("Set<UUID> ownerIdTargets = targets(entity.getOwnerId());")
                .contains("removeStaleEdges(session, entity.getId(), ownerIdTargets, OWNER_ID_EDGE);")
                .contains("upsertEdges(session, OWNER_ID_EDGE, entity.getId(), ownerIdTargets, 1.0);")
                .contains("private static Set<UUID> targets(UUID target) {")
                .contains("GraphEdgeDescriptor outgoing = new GraphEdgeDescriptor(edge.sourceNode(), "
                        + "edge.edgeType(), edge.targetNode(),\n"
                        + "                edge.weight(), false, GraphEdgeDescriptor.Direction.OUTGOING, "
                        + "edge.tableName());")
                .contains("session.traverseBreadthFirst(GraphTraversal.create(sourceId, outgoing, 1))")
                .contains("if (!kept.contains(stored)) {")
                .contains("session.deleteEdge(descriptor, sourceId, stored);")
                .contains("session.upsertEdge(edge, sourceId, target, weight, null);")
                .doesNotContain("targets(List<UUID> keys)")
                .doesNotContain("union(")
                .doesNotContain("if (entity.getOwnerId() != null)");
        assertThat(content.indexOf("removeStaleEdges(session, entity.getId()"))
                .as("stale edges are deleted before the held ones are upserted")
                .isLessThan(content.indexOf("upsertEdges(session, OWNER_ID_EDGE"));
    }

    @Test
    @DisplayName("a List<UUID> edge upserts one edge per distinct non-null key, all with the edge's one weight")
    void collectionEdgeIsSynchronised() {
        String content = graphSync(entityWithEdges(
                List.of(field("relatedIds", "java.util.List<java.util.UUID>"), field("score", "double")),
                new GraphEdgeMetadata("relatedIds", "Product", "RELATED_TO", null,
                        null, null, null, null, true, "score", null)));

        assertThat(content)
                .contains("Set<UUID> relatedIdsTargets = targets(entity.getRelatedIds());")
                .contains("removeStaleEdges(session, entity.getId(), relatedIdsTargets, RELATED_IDS_EDGE);")
                .contains("upsertEdges(session, RELATED_IDS_EDGE, entity.getId(), relatedIdsTargets, "
                        + "entity.getScore());")
                .contains("private static Set<UUID> targets(List<UUID> keys) {")
                .contains("Set<UUID> targets = new LinkedHashSet<>();")
                .contains("if (key != null) {")
                .doesNotContain("targets(UUID target)")
                .doesNotContain("session.upsertEdge(RELATED_IDS_EDGE");
    }

    @Test
    @DisplayName("edges sharing a relation type are synchronised together, so one's target is not stale for another")
    void edgesSharingARelationTypeAreSynchronisedTogether() {
        String content = graphSync(entityWithEdges(
                List.of(field("primaryTagId", "UUID"), field("tagIds", "List<UUID>"), field("ownerId", "UUID")),
                new GraphEdgeMetadata("primaryTagId", "Tag", "TAGGED"),
                new GraphEdgeMetadata("ownerId", "User", "OWNED_BY"),
                new GraphEdgeMetadata("tagIds", "Tag", "TAGGED")));

        assertThat(content)
                .contains("Set<UUID> primaryTagIdTargets = targets(entity.getPrimaryTagId());\n"
                        + "            Set<UUID> tagIdsTargets = targets(entity.getTagIds());\n"
                        + "            removeStaleEdges(session, entity.getId(), union(primaryTagIdTargets, "
                        + "tagIdsTargets), PRIMARY_TAG_ID_EDGE, TAG_IDS_EDGE);\n"
                        + "            upsertEdges(session, PRIMARY_TAG_ID_EDGE, entity.getId(), "
                        + "primaryTagIdTargets, 1.0);\n"
                        + "            upsertEdges(session, TAG_IDS_EDGE, entity.getId(), tagIdsTargets, 1.0);\n"
                        + "            Set<UUID> ownerIdTargets = targets(entity.getOwnerId());")
                .contains("@SafeVarargs")
                .contains("private static Set<UUID> union(Set<UUID>... targetSets) {")
                .contains("private static Set<UUID> targets(UUID target) {")
                .contains("private static Set<UUID> targets(List<UUID> keys) {");
    }

    @Test
    @DisplayName("deleteFromGraph deletes the node, whose edges the SPI deletes with it")
    void deleteRemovesTheNodeOnly() {
        String content = graphSync(entityWithEdges(
                List.of(field("relatedIds", "List<UUID>")),
                new GraphEdgeMetadata("relatedIds", "Product", "RELATED_TO")));

        String delete = content.substring(content.indexOf("public void deleteFromGraph(UUID entityId)"));
        delete = delete.substring(0, delete.indexOf("\n    }\n"));
        assertThat(delete)
                .contains("session.deleteNode(NODE_LABEL, entityId);")
                .doesNotContain("deleteEdge")
                .doesNotContain("traverseBreadthFirst");
    }

    @Test
    @DisplayName("EXT-GEN-3003: a List of entities on a @GraphEdge is refused, naming List<UUID> <field>Ids")
    void collectionOfEntitiesIsRefused() {
        DomainMetadata product = entityWithEdges(
                List.of(field("related", "java.util.List<com.example.domain.Product>")),
                new GraphEdgeMetadata("related", "Product", "RELATED_TO"));

        assertThatThrownBy(() -> strategy.generate(product))
                .isInstanceOfSatisfying(UnpersistableFieldTypeException.class, e -> assertThat(e.fields())
                        .containsExactly("com.example.domain.Product.related : java.util.List<com.example.domain"
                                + ".Product> (a collection graph edge to the entity Product, which no edge stores;"
                                + " hold the targets' keys instead: List<UUID> relatedIds)"))
                .hasMessageStartingWith("[Exeris] EXT-GEN-3003: ")
                .hasMessageContaining("A @GraphEdge field holds its target's UUID key, or a List<UUID> of keys");
    }

    @Test
    @DisplayName("EXT-GEN-3003: a @GraphEdge on a List of non-keys or on a non-UUID scalar is refused")
    void nonKeyEdgeFieldsAreRefused() {
        DomainMetadata product = entityWithEdges(
                List.of(field("labels", "List<String>"), field("code", "String")),
                new GraphEdgeMetadata("labels", "Label", "LABELLED"),
                new GraphEdgeMetadata("codeLinked", "Code", "LINKED", "code",
                        null, null, null, null, null, null, null),
                new GraphEdgeMetadata("codeMirrored", "Code", "MIRRORED", "code",
                        null, null, null, null, null, null, null));

        assertThatThrownBy(() -> strategy.generate(product))
                .isInstanceOfSatisfying(UnpersistableFieldTypeException.class, e -> assertThat(e.fields())
                        .containsExactlyInAnyOrder(
                                "com.example.domain.Product.labels : List<String> (a collection graph edge holds"
                                        + " its targets' keys: List<UUID> labels)",
                                "com.example.domain.Product.code : String (a graph edge holds its target's key:"
                                        + " UUID code)"));
    }

    @Test
    @DisplayName("an entity without edges emits no edge-sync helper")
    void noEdgesNoHelpers() {
        String content = graphSync(entityWithEdges(List.of(field("title", "String"))));

        assertThat(content)
                .doesNotContain("removeStaleEdges")
                .doesNotContain("upsertEdges")
                .doesNotContain("targets(")
                .doesNotContain("GraphTraversal");
    }

    private String graphSync(DomainMetadata metadata) {
        return strategy.generate(metadata).stream()
                .filter(f -> f.artifactType() == ArtifactType.GRAPH_SYNC)
                .findFirst()
                .orElseThrow()
                .content();
    }

    private static DomainMetadata entityWithEdges(List<FieldMetadata> fields, GraphEdgeMetadata... edges) {
        return DomainMetadata.builder("Product", "com.example.domain")
                .fields(fields)
                .graphMetadata(new GraphMetadata("Product", List.of(), List.of(edges), List.of()))
                .build();
    }

    private static FieldMetadata field(String name, String type) {
        return FieldMetadata.builder(name, type).build();
    }
}
