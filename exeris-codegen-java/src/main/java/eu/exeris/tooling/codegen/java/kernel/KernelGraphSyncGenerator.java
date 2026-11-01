package eu.exeris.tooling.codegen.java.kernel;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator;
import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator.ArtifactType;
import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import eu.exeris.tooling.codegen.java.support.KernelScaffold;
import eu.exeris.tooling.codegen.java.support.PrimaryKeys;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.GraphEdgeMetadata;
import eu.exeris.sdk.sourcemodel.ast.GraphMetadata;
import eu.exeris.sdk.sourcemodel.ast.GraphPropertyMetadata;

import javax.lang.model.element.Modifier;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Kernel Graph Sync Generator.
 * <p>
 * Emits a per-entity {@code *GraphSync} class that projects the relational
 * aggregate into the Open-Core graph subsystem through
 * {@code eu.exeris.kernel.spi.graph.GraphEngine}. Canonical wiring shape
 * matches the working community benchmark app's {@code GraphShopAdapter}
 * (under {@code exeris-benchmarks/targets/exeris-community-app}).
 * <p>
 * The emitted class exposes:
 * <ul>
 *   <li>A {@code public static final}
 *       {@code eu.exeris.kernel.spi.graph.model.GraphNodeDescriptor}
 *       {@code NODE_DESCRIPTOR} constant constructed via
 *       {@code GraphNodeDescriptor.create(label, sourceTable)} — pass it to
 *       {@code GraphEngine.registerNodes(...)} at bootstrap.</li>
 *   <li>One {@code public static final}
 *       {@code eu.exeris.kernel.spi.graph.model.GraphEdgeDescriptor}
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
 * The {@code @GraphProperty} values travel to {@code upsertNode} as one UTF-8 JSON object of
 * name to value, in a buffer loaned from the {@code MemoryAllocator} the graph sync is constructed
 * with. The SPI leaves the encoding to the driver; the Community graph driver reads the buffer as
 * that JSON object. An entity with no {@code @GraphProperty} passes {@code null} and takes no
 * allocator. An edge carries its declared direction and static weight in its descriptor, and its
 * {@code weightField} value, else its static weight, else {@code 1.0}, as the upsert weight.
 *
 * @implNote Emission is JavaPoet-based (ADR-015).
 *
 * @since 0.1
 */
public class KernelGraphSyncGenerator implements KernelArtifactGenerator {

    private static final ClassName UUID = ClassName.get("java.util", "UUID");
    private static final ClassName RUNTIME_EXCEPTION = ClassName.get("java.lang", "RuntimeException");

    private static final ClassName GRAPH_ENGINE =
            ClassName.get("eu.exeris.kernel.spi.graph", "GraphEngine");
    private static final ClassName GRAPH_SESSION =
            ClassName.get("eu.exeris.kernel.spi.graph", "GraphSession");
    private static final ClassName GRAPH_NODE_DESCRIPTOR =
            ClassName.get("eu.exeris.kernel.spi.graph.model", "GraphNodeDescriptor");
    private static final ClassName GRAPH_EDGE_DESCRIPTOR =
            ClassName.get("eu.exeris.kernel.spi.graph.model", "GraphEdgeDescriptor");
    private static final ClassName LOANED_BUFFER =
            ClassName.get("eu.exeris.kernel.spi.memory", "LoanedBuffer");
    private static final ClassName MEMORY_SEGMENT =
            ClassName.get("java.lang.foreign", "MemorySegment");
    private static final ClassName VALUE_LAYOUT =
            ClassName.get("java.lang.foreign", "ValueLayout");
    private static final ClassName MEMORY_ALLOCATOR =
            ClassName.get("eu.exeris.kernel.spi.memory", "MemoryAllocator");
    private static final ClassName STANDARD_CHARSETS =
            ClassName.get("java.nio.charset", "StandardCharsets");
    private static final ClassName LIST =
            ClassName.get("java.util", "List");

    /**
     * Creates the generator. It keeps no per-domain state, so one instance serves every domain
     * in a build.
     */
    public KernelGraphSyncGenerator() {
        // no state to initialise
    }

    @Override
    public GeneratedFile generate(DomainMetadata metadata) {
        if (!metadata.hasGraphMetadata()) {
            return null;
        }

        GraphMetadata graph = metadata.graphMetadata();
        String entity = metadata.entityName();
        String nodeLabel = graph.label() != null ? graph.label() : entity;
        // The table the repository and the migration name, so the node descriptor points at it.
        String sourceTable = KernelTableNaming.effectiveTable(metadata);

        boolean hasEdges = graph.edges() != null && !graph.edges().isEmpty();
        if (hasEdges) {
            assertDistinctEdgeNames(entity, graph);
        }
        boolean hasProperties = graph.properties() != null && !graph.properties().isEmpty();

        String packageName = metadata.packageName().replace(".domain", ".graph");
        String className = entity + "GraphSync";
        ClassName entityType = ClassName.get(metadata.packageName(), entity);
        ClassName selfType = ClassName.get(packageName, className);

        FieldSpec.Builder nodeDescriptorBuilder = FieldSpec.builder(GRAPH_NODE_DESCRIPTOR, "NODE_DESCRIPTOR",
                        Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL);
        if (hasProperties) {
            CodeBlock propList = graph.properties().stream()
                    .map(p -> CodeBlock.of("$S", p.name()))
                    .collect(CodeBlock.joining(", "));
            nodeDescriptorBuilder.initializer("new $T($S, $S, $S, $T.of($L), true)",
                    GRAPH_NODE_DESCRIPTOR, nodeLabel, sourceTable,
                    PrimaryKeys.field(metadata), LIST, propList);
        } else {
            nodeDescriptorBuilder.initializer("$T.create($S, $S)",
                    GRAPH_NODE_DESCRIPTOR, nodeLabel, sourceTable);
        }

        TypeSpec.Builder builder = KernelScaffold.publicClass(className)
                .addJavadoc("Generated graph-sync projection for $L.\n", entity)
                .addJavadoc("<p>Projects the relational aggregate into the Open-Core SPI\n")
                .addJavadoc("graph subsystem via {@link $T}. Node label: {@code $L}.\n",
                        GRAPH_ENGINE, nodeLabel)
                .addJavadoc("<p><b>DO NOT EDIT</b> - Regenerate from domain model.\n")
                .addField(KernelScaffold.loggerField(selfType))
                .addField(FieldSpec.builder(String.class, "NODE_LABEL",
                                Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                        .initializer("$S", nodeLabel)
                        .build())
                .addField(nodeDescriptorBuilder.build());

        if (hasEdges) {
            for (GraphEdgeMetadata edge : graph.edges()) {
                builder.addField(buildEdgeDescriptor(nodeLabel, edge));
            }
        }

        builder.addField(FieldSpec.builder(GRAPH_ENGINE, "graphEngine",
                        Modifier.PRIVATE, Modifier.FINAL).build());

        MethodSpec.Builder constructor = MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(GRAPH_ENGINE, "graphEngine")
                .addStatement("this.graphEngine = graphEngine");
        if (hasProperties) {
            builder.addField(FieldSpec.builder(MEMORY_ALLOCATOR, "allocator",
                    Modifier.PRIVATE, Modifier.FINAL).build());
            constructor.addParameter(MEMORY_ALLOCATOR, "allocator")
                    .addStatement("this.allocator = allocator");
        }
        builder.addMethod(constructor.build());

        builder.addMethod(buildSyncToGraph(entityType, metadata, graph, hasEdges, hasProperties));
        builder.addMethod(buildDeleteFromGraph());

        if (hasProperties) {
            builder.addMethod(buildEncodeNodeProperties(entityType, metadata, graph.properties()));
            builder.addMethod(buildAppendProperty());
            builder.addMethod(buildEscapeJson());
        }

        return new GeneratedFile(packageName, className,
                KernelScaffold.render(packageName, builder.build()), ArtifactType.GRAPH_SYNC);
    }

    private FieldSpec buildEdgeDescriptor(String sourceLabel, GraphEdgeMetadata edge) {
        String edgeType = edge.relationType() != null ? edge.relationType() : "RELATES_TO";
        String targetLabel = edge.targetLabel() != null ? edge.targetLabel() : "Node";
        String constantName = toConstantCase(edge.name()) + "_EDGE";
        boolean hasCustomAttributes = edge.direction() != null
                || Boolean.TRUE.equals(edge.bidirectional())
                || edge.staticWeight() != null;
        FieldSpec.Builder field = FieldSpec.builder(GRAPH_EDGE_DESCRIPTOR, constantName,
                        Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL);
        if (hasCustomAttributes) {
            double weight = edge.staticWeight() != null ? edge.staticWeight() : 1.0;
            boolean bidirectional = Boolean.TRUE.equals(edge.bidirectional());
            String directionName = edge.effectiveDirection().name();
            field.initializer("new $T($S, $S, $S, $L, $L, $T.$L, null)",
                    GRAPH_EDGE_DESCRIPTOR, sourceLabel, edgeType, targetLabel,
                    weight, bidirectional,
                    GRAPH_EDGE_DESCRIPTOR.nestedClass("Direction"), directionName);
        } else {
            field.initializer("$T.create($S, $S, $S)",
                    GRAPH_EDGE_DESCRIPTOR, sourceLabel, edgeType, targetLabel);
        }
        return field.build();
    }

    private MethodSpec buildSyncToGraph(ClassName entityType, DomainMetadata metadata, GraphMetadata graph,
                                        boolean hasEdges, boolean hasProperties) {
        String keyGetter = PrimaryKeys.getter(metadata);
        MethodSpec.Builder method = MethodSpec.methodBuilder("syncToGraph")
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeName.VOID)
                .addParameter(entityType, "entity")
                .addJavadoc("Projects {@code entity} into the graph: upserts the node with\n")
                .addJavadoc(hasProperties
                        ? "encoded properties, then upserts\n"
                        : "no properties (pass {@code null} per the SPI contract), then upserts\n")
                .addJavadoc("each declared edge with its configured direction, weight, and {@code null}\n")
                .addJavadoc("properties. Subclasses override this method to customize payload bytes\n")
                .addJavadoc("via an allocator-owned {@link eu.exeris.kernel.spi.memory.LoanedBuffer}.\n")
                .beginControlFlow("try ($T session = graphEngine.openSession())", GRAPH_SESSION);

        if (hasProperties) {
            method.beginControlFlow("try ($T properties = encodeNodeProperties(entity))", LOANED_BUFFER)
                    .addStatement("session.upsertNode(NODE_LABEL, entity.$L(), properties)", keyGetter)
                    .endControlFlow();
        } else {
            method.addStatement("session.upsertNode(NODE_LABEL, entity.$L(), null)", keyGetter);
        }

        if (hasEdges) {
            for (GraphEdgeMetadata edge : graph.edges()) {
                String getter = "get" + capitalize(edge.effectiveField());
                String constantName = toConstantCase(edge.name()) + "_EDGE";
                String weightExpr = weightExpression(metadata, edge);
                method.beginControlFlow("if (entity.$L() != null)", getter)
                        .addStatement("session.upsertEdge($L, entity.$L(), entity.$L(), $L, null)",
                                constantName, keyGetter, getter, weightExpr)
                        .endControlFlow();
            }
        }

        return method
                .addStatement("LOG.log($T.DEBUG, $S, entity.$L())", KernelScaffold.LOGGER_LEVEL,
                        "Synced " + entityType.simpleName() + " to graph: id={0}", keyGetter)
                .nextControlFlow("catch ($T e)", RUNTIME_EXCEPTION)
                // System.Logger has no "trailing Throwable" convention: log(Level, String, Object...)
                // would format the exception as a parameter and drop the stack trace. Concatenating
                // the id and using log(Level, String, Throwable) keeps both.
                .addStatement("LOG.log($T.ERROR, $S + entity.$L(), e)", KernelScaffold.LOGGER_LEVEL,
                        "Failed to sync " + entityType.simpleName() + " to graph: id=", keyGetter)
                .addStatement("throw e")
                .endControlFlow()
                .build();
    }

    private MethodSpec buildDeleteFromGraph() {
        return MethodSpec.methodBuilder("deleteFromGraph")
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeName.VOID)
                .addParameter(UUID, "entityId")
                .addJavadoc("Removes the entity's node from the graph.\n")
                .addJavadoc("<p>The SPI's {@link $T#deleteNode(String, java.util.UUID)} contract\n",
                        GRAPH_SESSION)
                .addJavadoc("guarantees that all edges incident to the node are deleted together;\n")
                .addJavadoc("no explicit edge teardown is needed here.\n")
                .beginControlFlow("try ($T session = graphEngine.openSession())", GRAPH_SESSION)
                .addStatement("session.deleteNode(NODE_LABEL, entityId)")
                .addStatement("LOG.log($T.DEBUG, $S, entityId)", KernelScaffold.LOGGER_LEVEL,
                        "Deleted node from graph: id={0}")
                .nextControlFlow("catch ($T e)", RUNTIME_EXCEPTION)
                .addStatement("LOG.log($T.ERROR, $S + entityId, e)", KernelScaffold.LOGGER_LEVEL,
                        "Failed to delete node from graph: id=")
                .addStatement("throw e")
                .endControlFlow()
                .build();
    }

    /**
     * The upsert weight of one edge: its {@code weightField} value, else its static weight, else
     * {@code 1.0}. A boxed weight field that is {@code null} falls back the same way.
     */
    private static String weightExpression(DomainMetadata metadata, GraphEdgeMetadata edge) {
        String fallback = edge.staticWeight() != null ? String.valueOf(edge.staticWeight()) : "1.0";
        if (edge.weightField() == null) {
            return fallback;
        }
        String read = "entity." + getterName(metadata, edge.weightField()) + "()";
        return isPrimitive(metadata, edge.weightField())
                ? read
                : "(" + read + " != null ? " + read + ".doubleValue() : " + fallback + ")";
    }

    private MethodSpec buildEncodeNodeProperties(ClassName entityType, DomainMetadata metadata,
                                                 List<GraphPropertyMetadata> properties) {
        MethodSpec.Builder method = MethodSpec.methodBuilder("encodeNodeProperties")
                .addModifiers(Modifier.PRIVATE)
                .returns(LOANED_BUFFER)
                .addParameter(entityType, "entity")
                .addJavadoc("The entity's graph properties as one UTF-8 JSON object, in a buffer the\n")
                .addJavadoc("caller closes. A {@code null} value is left out.\n")
                .addStatement("$T json = new $T($S)", StringBuilder.class, StringBuilder.class, "{");
        for (GraphPropertyMetadata prop : properties) {
            method.addStatement("appendProperty(json, $S, entity.$L())",
                    "\"" + escapeJson(prop.name()) + "\":", getterName(metadata, prop.effectiveField()));
        }
        return method.addStatement("json.append('}')")
                .addStatement("byte[] bytes = json.toString().getBytes($T.UTF_8)", STANDARD_CHARSETS)
                .addStatement("$T buffer = allocator.allocateNetwork(bytes.length)", LOANED_BUFFER)
                .beginControlFlow("try")
                .beginControlFlow("if (buffer.capacity() < bytes.length)")
                .addStatement("throw new $T($S + bytes.length + $S + buffer.capacity())",
                        IllegalStateException.class, "graph properties need ", " bytes, the allocator loaned ")
                .endControlFlow()
                .addStatement("$T.copy(bytes, 0, buffer.segment(), $T.JAVA_BYTE, 0, bytes.length)",
                        MEMORY_SEGMENT, VALUE_LAYOUT)
                .addStatement("buffer.setSize(bytes.length)")
                .addStatement("return buffer")
                .nextControlFlow("catch ($T e)", RuntimeException.class)
                .addStatement("buffer.close()")
                .addStatement("throw e")
                .endControlFlow()
                .build();
    }

    private MethodSpec buildAppendProperty() {
        return MethodSpec.methodBuilder("appendProperty")
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .addJavadoc("Appends {@code key} and {@code value} to the JSON object, unless the value\n")
                .addJavadoc("is {@code null}. A finite number or a boolean is written bare; anything else\n")
                .addJavadoc("as its string form, quoted.\n")
                .addParameter(StringBuilder.class, "json")
                .addParameter(String.class, "key")
                .addParameter(Object.class, "value")
                .beginControlFlow("if (value == null)")
                .addStatement("return")
                .endControlFlow()
                .beginControlFlow("if (json.length() > 1)")
                .addStatement("json.append(',')")
                .endControlFlow()
                .addStatement("json.append(key)")
                .beginControlFlow("if (value instanceof Boolean || value instanceof Number number\n"
                        + "&& !(number instanceof Double d && !Double.isFinite(d))\n"
                        + "&& !(number instanceof Float f && !Float.isFinite(f)))")
                .addStatement("json.append(value)")
                .nextControlFlow("else")
                .addStatement("json.append('\"').append(escapeJson(value.toString())).append('\"')")
                .endControlFlow()
                .build();
    }

    private MethodSpec buildEscapeJson() {
        return MethodSpec.methodBuilder("escapeJson")
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .returns(String.class)
                .addParameter(String.class, "s")
                .addStatement("$T out = new $T(s.length() + 8)", StringBuilder.class, StringBuilder.class)
                .beginControlFlow("for (int i = 0; i < s.length(); i++)")
                .addStatement("char c = s.charAt(i)")
                .beginControlFlow("switch (c)")
                .addStatement("case '\"' -> out.append($S)", "\\\"")
                .addStatement("case '\\\\' -> out.append($S)", "\\\\")
                .addStatement("case '\\n' -> out.append($S)", "\\n")
                .addStatement("case '\\r' -> out.append($S)", "\\r")
                .addStatement("case '\\t' -> out.append($S)", "\\t")
                .beginControlFlow("default ->")
                .beginControlFlow("if (c < ' ')")
                .addStatement("out.append($T.format($T.ROOT, $S, (int) c))",
                        String.class, java.util.Locale.class, "\\u%04x")
                .nextControlFlow("else")
                .addStatement("out.append(c)")
                .endControlFlow()
                .endControlFlow()
                .endControlFlow()
                .endControlFlow()
                .addStatement("return out.toString()")
                .build();
    }

    /** The JSON string escape the emitted {@code escapeJson} applies, for a key fixed at generation. */
    private static String escapeJson(String s) {
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < ' ') {
                        out.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    private static String getterName(DomainMetadata metadata, String fieldName) {
        String prefix = "boolean".equals(fieldType(metadata, fieldName)) ? "is" : "get";
        return prefix + capitalize(fieldName);
    }

    private static boolean isPrimitive(DomainMetadata metadata, String fieldName) {
        String type = fieldType(metadata, fieldName);
        return type != null && switch (type) {
            case "boolean", "byte", "short", "int", "long", "float", "double", "char" -> true;
            default -> false;
        };
    }

    private static String fieldType(DomainMetadata metadata, String fieldName) {
        if (metadata.fields() == null) {
            return null;
        }
        return metadata.fields().stream()
                .filter(f -> fieldName.equals(f.name()))
                .map(f -> f.type())
                .findFirst()
                .orElse(null);
    }

    /**
     * Each edge on an entity must have a distinct name so the emitted descriptor constants
     * do not collide.
     */
    private void assertDistinctEdgeNames(String entity, GraphMetadata graph) {
        List<String> duplicates = graph.edges().stream()
                .map(GraphEdgeMetadata::name)
                .collect(Collectors.groupingBy(n -> n, Collectors.counting()))
                .entrySet().stream()
                .filter(e -> e.getValue() > 1)
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
        if (!duplicates.isEmpty()) {
            throw new IllegalArgumentException(
                    "Duplicate edge names on entity '" + entity + "': " + duplicates
                            + ". An edge is named by the field that declares it, so each field may "
                            + "carry at most one @GraphEdge.");
        }
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
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

    @Override
    public ArtifactType artifactType() {
        return ArtifactType.GRAPH_SYNC;
    }
}
