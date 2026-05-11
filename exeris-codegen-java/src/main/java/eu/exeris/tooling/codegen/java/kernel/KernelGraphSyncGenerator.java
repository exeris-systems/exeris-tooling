package eu.exeris.tooling.codegen.java.kernel;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator;
import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator.ArtifactType;
import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import eu.exeris.tooling.codegen.java.support.KernelScaffold;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.GraphEdgeMetadata;
import eu.exeris.sdk.sourcemodel.ast.GraphMetadata;

import javax.lang.model.element.Modifier;

/**
 * Kernel Graph Sync Generator.
 * <p>
 * Emits a per-entity {@code *GraphSync} class that projects the relational
 * aggregate into the Open-Core graph subsystem through
 * {@link eu.exeris.kernel.spi.graph.GraphEngine}. Canonical wiring shape
 * matches the working community benchmark app's {@code GraphShopAdapter}
 * (under {@code exeris-benchmarks/targets/exeris-community-app}).
 * <p>
 * The emitted class exposes:
 * <ul>
 *   <li>A {@code public static final}
 *       {@link eu.exeris.kernel.spi.graph.model.GraphNodeDescriptor}
 *       {@code NODE_DESCRIPTOR} constant constructed via
 *       {@code GraphNodeDescriptor.create(label, sourceTable)} — pass it to
 *       {@code GraphEngine.registerNodes(...)} at bootstrap.</li>
 *   <li>One {@code public static final}
 *       {@link eu.exeris.kernel.spi.graph.model.GraphEdgeDescriptor}
 *       constant per declared {@link GraphEdgeMetadata}, built via
 *       {@code GraphEdgeDescriptor.create(sourceLabel, edgeType, targetLabel)}.
 *       Pass them to {@code GraphEngine.registerEdges(...)} at bootstrap.</li>
 *   <li>{@code syncToGraph(entity)} — opens a {@code GraphSession} via
 *       try-with-resources, calls {@code session.upsertNode(NODE_LABEL,
 *       entity.getId(), null)}, then per declared edge calls
 *       {@code session.upsertEdge(EDGE, sourceId, targetId, 1.0, null)}.</li>
 *   <li>{@code deleteFromGraph(entityId)} — opens a session and calls
 *       {@code session.deleteNode(NODE_LABEL, entityId)}.</li>
 * </ul>
 * <p>
 * Node properties default to {@code null} per the SPI contract
 * ("Pass {@code null} if there are no properties to set"). Downstream
 * consumers that need to ship property bytes override {@code syncToGraph}
 * and pass a {@link eu.exeris.kernel.spi.memory.LoanedBuffer} they have
 * allocated (the session reads but does not own the buffer's lifecycle).
 * Same for edge weights/properties: defaults to {@code 1.0} weight and
 * {@code null} properties; override to set them.
 * <p>
 * The legacy generator's {@code buildNodeProperties(entity) → Map<String,
 * Object>} pathway is dropped — the SPI's {@code upsertNode} signature
 * takes a {@link eu.exeris.kernel.spi.memory.LoanedBuffer}, not a heap
 * {@code Map}.
 *
 * @implNote Emission is JavaPoet-based (ADR-015).
 *
 * @author Exeris Team
 * @since 0.1.0
 */
public class KernelGraphSyncGenerator implements KernelArtifactGenerator {

    private static final ClassName UUID = ClassName.get("java.util", "UUID");
    private static final ClassName SLF4J_LOGGER = ClassName.get("org.slf4j", "Logger");
    private static final ClassName SLF4J_LOGGER_FACTORY = ClassName.get("org.slf4j", "LoggerFactory");
    private static final ClassName RUNTIME_EXCEPTION = ClassName.get("java.lang", "RuntimeException");

    private static final ClassName GRAPH_ENGINE =
            ClassName.get("eu.exeris.kernel.spi.graph", "GraphEngine");
    private static final ClassName GRAPH_SESSION =
            ClassName.get("eu.exeris.kernel.spi.graph", "GraphSession");
    private static final ClassName GRAPH_NODE_DESCRIPTOR =
            ClassName.get("eu.exeris.kernel.spi.graph.model", "GraphNodeDescriptor");
    private static final ClassName GRAPH_EDGE_DESCRIPTOR =
            ClassName.get("eu.exeris.kernel.spi.graph.model", "GraphEdgeDescriptor");

    @Override
    public GeneratedFile generate(DomainMetadata metadata) {
        if (!metadata.hasGraphMetadata()) {
            return null;
        }

        GraphMetadata graph = metadata.graphMetadata();
        String entity = metadata.entityName();
        String nodeLabel = graph.label() != null ? graph.label() : entity;
        String sourceTable = toSnakeCase(entity) + "s";

        if (graph.edges() != null && !graph.edges().isEmpty()) {
            assertDistinctEdgeNames(entity, graph);
        }

        String packageName = metadata.packageName().replace(".domain", ".graph");
        String className = entity + "GraphSync";
        ClassName entityType = ClassName.get(metadata.packageName(), entity);
        ClassName selfType = ClassName.get(packageName, className);

        TypeSpec.Builder builder = KernelScaffold.publicClass(className)
                .addJavadoc("Generated graph-sync projection for $L.\n", entity)
                .addJavadoc("<p>Projects the relational aggregate into the Open-Core SPI\n")
                .addJavadoc("graph subsystem via {@link $T}. Node label: {@code $L}.\n",
                        GRAPH_ENGINE, nodeLabel)
                .addJavadoc("<p><b>DO NOT EDIT</b> - Regenerate from domain model.\n")
                .addField(FieldSpec.builder(SLF4J_LOGGER, "LOG",
                                Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                        .initializer("$T.getLogger($T.class)", SLF4J_LOGGER_FACTORY, selfType)
                        .build())
                .addField(FieldSpec.builder(String.class, "NODE_LABEL",
                                Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                        .initializer("$S", nodeLabel)
                        .build())
                .addField(FieldSpec.builder(GRAPH_NODE_DESCRIPTOR, "NODE_DESCRIPTOR",
                                Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                        .initializer("$T.create($S, $S)", GRAPH_NODE_DESCRIPTOR, nodeLabel, sourceTable)
                        .build());

        boolean hasEdges = graph.edges() != null && !graph.edges().isEmpty();
        if (hasEdges) {
            for (GraphEdgeMetadata edge : graph.edges()) {
                builder.addField(buildEdgeDescriptor(nodeLabel, edge));
            }
        }

        builder.addField(FieldSpec.builder(GRAPH_ENGINE, "graphEngine",
                        Modifier.PRIVATE, Modifier.FINAL).build());

        builder.addMethod(MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(GRAPH_ENGINE, "graphEngine")
                .addStatement("this.graphEngine = graphEngine")
                .build());

        builder.addMethod(buildSyncToGraph(entityType, graph, hasEdges));
        builder.addMethod(buildDeleteFromGraph());

        return new GeneratedFile(packageName, className,
                KernelScaffold.render(packageName, builder.build()), ArtifactType.GRAPH_SYNC);
    }

    private FieldSpec buildEdgeDescriptor(String sourceLabel, GraphEdgeMetadata edge) {
        String edgeType = edge.relationType() != null ? edge.relationType() : "RELATES_TO";
        String targetLabel = edge.targetLabel() != null ? edge.targetLabel() : "Node";
        String constantName = toConstantCase(edge.name()) + "_EDGE";
        return FieldSpec.builder(GRAPH_EDGE_DESCRIPTOR, constantName,
                        Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .initializer("$T.create($S, $S, $S)",
                        GRAPH_EDGE_DESCRIPTOR, sourceLabel, edgeType, targetLabel)
                .build();
    }

    private MethodSpec buildSyncToGraph(ClassName entityType, GraphMetadata graph, boolean hasEdges) {
        MethodSpec.Builder method = MethodSpec.methodBuilder("syncToGraph")
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeName.VOID)
                .addParameter(entityType, "entity")
                .addJavadoc("Projects {@code entity} into the graph: upserts the node with no\n")
                .addJavadoc("properties (pass {@code null} per the SPI contract), then upserts\n")
                .addJavadoc("each declared edge with default {@code weight=1.0} and {@code null}\n")
                .addJavadoc("properties. Subclasses override this method to ship payload bytes\n")
                .addJavadoc("via an allocator-owned {@link eu.exeris.kernel.spi.memory.LoanedBuffer}.\n")
                .beginControlFlow("try ($T session = graphEngine.openSession())", GRAPH_SESSION)
                .addStatement("session.upsertNode(NODE_LABEL, entity.getId(), null)");

        if (hasEdges) {
            for (GraphEdgeMetadata edge : graph.edges()) {
                String edgeName = edge.name();
                String getter = "get" + capitalize(edgeName);
                String constantName = toConstantCase(edgeName) + "_EDGE";
                method.beginControlFlow("if (entity.$L() != null)", getter)
                        .addStatement("session.upsertEdge($L, entity.getId(), entity.$L(), 1.0, null)",
                                constantName, getter)
                        .endControlFlow();
            }
        }

        return method
                .addStatement("LOG.debug($S, entity.getId())",
                        "Synced " + entityType.simpleName() + " to graph: id={}")
                .nextControlFlow("catch ($T e)", RUNTIME_EXCEPTION)
                .addStatement("LOG.error($S, entity.getId(), e)",
                        "Failed to sync " + entityType.simpleName() + " to graph: id={}")
                .addStatement("throw e")
                .endControlFlow()
                .build();
    }

    private MethodSpec buildDeleteFromGraph() {
        return MethodSpec.methodBuilder("deleteFromGraph")
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeName.VOID)
                .addParameter(UUID, "entityId")
                .addJavadoc("Removes the entity's node (and all edges incident to it) from the graph.\n")
                .beginControlFlow("try ($T session = graphEngine.openSession())", GRAPH_SESSION)
                .addStatement("session.deleteNode(NODE_LABEL, entityId)")
                .addStatement("LOG.debug($S, entityId)", "Deleted node from graph: id={}")
                .nextControlFlow("catch ($T e)", RUNTIME_EXCEPTION)
                .addStatement("LOG.error($S, entityId, e)", "Failed to delete node from graph: id={}")
                .addStatement("throw e")
                .endControlFlow()
                .build();
    }

    private void assertDistinctEdgeNames(String entity, GraphMetadata graph) {
        long distinct = graph.edges().stream()
                .map(GraphEdgeMetadata::name)
                .distinct()
                .count();
        if (distinct != graph.edges().size()) {
            throw new IllegalArgumentException(
                    "Duplicate edge names on entity '" + entity
                            + "'. Each @GraphEdge must declare a unique name.");
        }
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private String toConstantCase(String camelCase) {
        StringBuilder sb = new StringBuilder(camelCase.length() + 4);
        for (int i = 0; i < camelCase.length(); i++) {
            char c = camelCase.charAt(i);
            if (Character.isUpperCase(c) && i > 0) {
                sb.append('_');
            }
            sb.append(Character.toUpperCase(c));
        }
        return sb.toString();
    }

    private String toSnakeCase(String s) {
        if (s == null || s.isBlank()) return "";
        return s.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase();
    }

    @Override
    public ArtifactType artifactType() {
        return ArtifactType.GRAPH_SYNC;
    }
}
