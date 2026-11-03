package eu.exeris.e2e.codegen;

import eu.exeris.e2e.codegen.compile.EmittedJavac;
import eu.exeris.e2e.codegen.compile.ProcessorCompiler;
import eu.exeris.kernel.spi.graph.GraphEngine;
import eu.exeris.kernel.spi.graph.GraphSession;
import eu.exeris.kernel.spi.graph.model.GraphEdgeDescriptor;
import eu.exeris.kernel.spi.graph.model.GraphTraversal;
import eu.exeris.tooling.codegen.java.CodegenPipeline;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The generated {@code *GraphSync} of an entity with graph edges, compiled and driven: each edge is
 * synchronised against the targets the graph stores from the node, so an update deletes the edges
 * to targets the entity no longer holds. A {@code List<UUID>} edge writes one edge per distinct
 * key, and edges sharing a relation type are synchronised together.
 *
 * <p>The graph is {@link InMemoryGraph}, a {@link GraphSession} double that stores edges as the
 * Community SQL binding does, one row per edge table, source and target, and answers only the
 * one-hop outgoing traversal the sync reads with.
 */
@Tag("e2e")
@Tag("codegen")
@DisplayName("graph sync: List<UUID> and single edges are synchronised against the stored graph")
class GraphEdgeSyncE2ETest {

    /**
     * A {@code List<UUID>} edge, single edges of each direction, and a relation type a single and a
     * collection edge share, through the processor and the pipeline: the main and test trees
     * compile, and the emitted graph sync, run against an in-memory {@link GraphSession} double
     * that stores edges and answers traversals as the Community binding does, keeps exactly the
     * targets the entity holds after a create and after each update.
     */
    @Test
    @DisplayName("graph sync of List<UUID> and single edges compiles and synchronises stored edges on update")
    void graphSyncCompilesAndSynchronises(@TempDir Path workspace) throws Exception {
        Path entityClasses = workspace.resolve("target/classes");
        Path generatedMain = workspace.resolve("src/main/generated/java");
        Path generatedTests = workspace.resolve("src/test/generated/java");
        ProcessorCompiler.compile(workspace.resolve("src/main/java"), entityClasses, null, collectionEdgeCorpus());

        CodegenPipeline pipeline = CodegenPipeline.createDefault();
        Path metadataDir = entityClasses.resolve("exeris-metadata");
        pipeline.run(metadataDir, generatedMain, "com.catalog");
        pipeline.runTests(metadataDir, generatedTests, "com.catalog");
        assertThat(Files.readString(generatedMain.resolve("com/catalog/graph/ProductGraphSync.java")))
                .contains("Set<UUID> relatedIdsTargets = targets(entity.getRelatedIds());")
                .contains("union(primaryTagIdTargets, tagIdsTargets)");

        List<String> files = new ArrayList<>();
        for (Path root : List.of(generatedMain, generatedTests)) {
            try (Stream<Path> tree = Files.walk(root)) {
                tree.filter(p -> p.toString().endsWith(".java")).map(Path::toString).sorted().forEach(files::add);
            }
        }
        Path appClasses = workspace.resolve("target/app-classes");
        EmittedJavac.Result result = EmittedJavac.compile(files, appClasses,
                System.getProperty("java.class.path") + File.pathSeparator + entityClasses);
        assertThat(result.clean())
                .as("javac output:%n%s", result.render())
                .isTrue();

        try (URLClassLoader loader = new URLClassLoader(new URL[] {
                appClasses.toUri().toURL(), entityClasses.toUri().toURL()}, getClass().getClassLoader())) {
            assertCollectionEdgeSync(loader);
        }
    }

    private static void assertCollectionEdgeSync(ClassLoader loader) throws Exception {
        UUID p = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID a = UUID.fromString("00000000-0000-0000-0000-00000000000a");
        UUID b = UUID.fromString("00000000-0000-0000-0000-00000000000b");
        UUID c = UUID.fromString("00000000-0000-0000-0000-00000000000c");
        UUID q = UUID.fromString("00000000-0000-0000-0000-0000000000ff");
        InMemoryGraph graph = new InMemoryGraph();
        // Edges another node's sync wrote, one of them into p: never this node's to delete.
        graph.edges.put("related_to_edges|" + a + "|" + p, 1.0);
        graph.edges.put("related_to_edges|" + q + "|" + a, 1.0);

        Class<?> productType = loader.loadClass("com.catalog.domain.Product");
        Object sync = loader.loadClass("com.catalog.graph.ProductGraphSync")
                .getConstructor(GraphEngine.class).newInstance(graph.engine());
        Method syncToGraph = sync.getClass().getMethod("syncToGraph", productType);
        Object product = productType.getConstructor().newInstance();
        set(product, "setId", UUID.class, p);
        set(product, "setScore", double.class, 0.5);

        set(product, "setRelatedIds", List.class, Arrays.asList(a, b, a, null));
        set(product, "setCategoryId", UUID.class, c);
        set(product, "setPrimaryTagId", UUID.class, a);
        set(product, "setTagIds", List.class, List.of(b));
        syncToGraph.invoke(sync, product);
        assertThat(graph.targets("related_to_edges", p)).containsExactly(a, b);
        assertThat(graph.targets("belongs_to_edges", p)).containsExactly(c);
        assertThat(graph.targets("tagged_edges", p)).containsExactly(a, b);
        assertThat(graph.edges).containsEntry("tagged_edges|" + p + "|" + a, 1.0)
                .containsEntry("tagged_edges|" + p + "|" + b, 0.5);

        set(product, "setRelatedIds", List.class, List.of(b, c));
        set(product, "setCategoryId", UUID.class, a);
        set(product, "setPrimaryTagId", UUID.class, c);
        syncToGraph.invoke(sync, product);
        assertThat(graph.targets("related_to_edges", p)).containsExactly(b, c);
        assertThat(graph.targets("belongs_to_edges", p)).containsExactly(a);
        assertThat(graph.targets("tagged_edges", p)).containsExactly(b, c);

        set(product, "setRelatedIds", List.class, null);
        set(product, "setCategoryId", UUID.class, null);
        syncToGraph.invoke(sync, product);
        assertThat(graph.targets("related_to_edges", p)).isEmpty();
        assertThat(graph.targets("belongs_to_edges", p)).isEmpty();
        assertThat(graph.edges).containsKeys("related_to_edges|" + a + "|" + p, "related_to_edges|" + q + "|" + a);

        sync.getClass().getMethod("deleteFromGraph", UUID.class).invoke(sync, p);
        assertThat(graph.deletedNodes).containsExactly(p);
    }

    private static void set(Object target, String setter, Class<?> type, Object value) throws Exception {
        target.getClass().getMethod(setter, type).invoke(target, value);
    }

    /**
     * Edges as the Community SQL binding stores them, one row per {@code (table, source, target)},
     * and a one-hop traversal answered for the outgoing direction only: the sync reads the targets
     * it wrote, so any other direction or depth is a defect this double fails on.
     */
    private static final class InMemoryGraph {
        final Map<String, Double> edges = new LinkedHashMap<>();
        final List<UUID> deletedNodes = new ArrayList<>();

        List<UUID> targets(String table, UUID source) {
            String prefix = table + "|" + source + "|";
            return edges.keySet().stream()
                    .filter(key -> key.startsWith(prefix))
                    .map(key -> UUID.fromString(key.substring(prefix.length())))
                    .sorted()
                    .toList();
        }

        GraphEngine engine() {
            GraphSession session = (GraphSession) Proxy.newProxyInstance(GraphSession.class.getClassLoader(),
                    new Class<?>[] {GraphSession.class}, (proxy, method, args) -> session(method.getName(), args));
            return (GraphEngine) Proxy.newProxyInstance(GraphEngine.class.getClassLoader(),
                    new Class<?>[] {GraphEngine.class}, (proxy, method, args) -> {
                        if (method.getName().equals("openSession")) {
                            return session;
                        }
                        throw new UnsupportedOperationException(method.getName());
                    });
        }

        private Object session(String method, Object[] args) {
            switch (method) {
                case "upsertNode", "close" -> {
                    return null;
                }
                case "upsertEdge" -> {
                    edges.put(key(args), (Double) args[3]);
                    return null;
                }
                case "deleteEdge" -> {
                    edges.remove(key(args));
                    return null;
                }
                case "deleteNode" -> {
                    deletedNodes.add((UUID) args[1]);
                    return null;
                }
                case "traverseBreadthFirst" -> {
                    GraphTraversal traversal = (GraphTraversal) args[0];
                    GraphEdgeDescriptor edge = traversal.edgeDescriptor();
                    assertThat(edge.direction()).isEqualTo(GraphEdgeDescriptor.Direction.OUTGOING);
                    assertThat(edge.bidirectional()).isFalse();
                    assertThat(traversal.maxDepth()).isEqualTo(1);
                    return targets(edge.tableName(), traversal.startNodeId());
                }
                default -> throw new UnsupportedOperationException(method);
            }
        }

        private static String key(Object[] args) {
            return ((GraphEdgeDescriptor) args[0]).tableName() + "|" + args[1] + "|" + args[2];
        }
    }

    private static Map<String, String> collectionEdgeCorpus() {
        return Map.of("com/catalog/domain/Product.java", """
                package com.catalog.domain;

                import eu.exeris.sdk.annotation.ExerisDomain;
                import eu.exeris.sdk.annotation.Field;
                import eu.exeris.sdk.annotation.Graph;
                import eu.exeris.sdk.annotation.GraphEdge;

                import java.util.List;
                import java.util.UUID;

                @ExerisDomain(module = "catalog", path = "/products")
                @Graph(nodeClass = "Product")
                public class Product {

                    private UUID id;

                    @Field(label = "Title")
                    private String title;

                    @GraphEdge(type = "RELATED_TO", targetLabel = "Product", bidirectional = true)
                    private List<UUID> relatedIds;

                    @GraphEdge(type = "BELONGS_TO", targetLabel = "Category", direction = GraphEdge.Direction.INCOMING)
                    private UUID categoryId;

                    @GraphEdge(type = "TAGGED", targetLabel = "Tag")
                    private UUID primaryTagId;

                    @GraphEdge(type = "TAGGED", targetLabel = "Tag", weighted = true, weightField = "score")
                    private List<UUID> tagIds;

                    @Field(label = "Score")
                    private double score;

                    public UUID getId() { return id; }
                    public void setId(UUID id) { this.id = id; }
                    public String getTitle() { return title; }
                    public void setTitle(String title) { this.title = title; }
                    public List<UUID> getRelatedIds() { return relatedIds; }
                    public void setRelatedIds(List<UUID> relatedIds) { this.relatedIds = relatedIds; }
                    public UUID getCategoryId() { return categoryId; }
                    public void setCategoryId(UUID categoryId) { this.categoryId = categoryId; }
                    public UUID getPrimaryTagId() { return primaryTagId; }
                    public void setPrimaryTagId(UUID primaryTagId) { this.primaryTagId = primaryTagId; }
                    public List<UUID> getTagIds() { return tagIds; }
                    public void setTagIds(List<UUID> tagIds) { this.tagIds = tagIds; }
                    public double getScore() { return score; }
                    public void setScore(double score) { this.score = score; }
                }
                """);
    }
}
