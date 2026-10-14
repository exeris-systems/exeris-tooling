package eu.exeris.e2e.codegen;

import eu.exeris.e2e.codegen.compile.ProcessorCompiler;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.tooling.codegen.core.MetadataLoader;
import eu.exeris.tooling.codegen.java.CodegenPipeline;
import eu.exeris.tooling.codegen.java.kernel.KernelOpenApiGenerator;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The OpenAPI document of an entity whose metadata the real processor wrote: javac with
 * {@code ExerisDomainProcessor} compiles an annotated fixture, the pipeline loads the
 * {@code exeris-metadata/} JSON, and {@code KernelOpenApiGenerator} writes the document, which is
 * then read back as YAML.
 *
 * <p>The fixture has a streaming action that triggers an event, a streaming action with a request
 * body and no event, a respond-once action, and {@code realTimeApi}. Every stream route is served
 * through {@code streamRoute(...)}, whose engine writes the {@code 200} head before the handler
 * runs, so each one answers only {@code 200 text/event-stream} and describes its frames (ADR-044
 * Amendment 2, ADR-079). The respond-once action keeps its respond-once statuses.
 */
@Tag("e2e")
@Tag("codegen")
@DisplayName("OpenAPI from processor metadata: stream routes are event streams, every schema typed and resolved")
class OpenApiFromProcessorE2ETest {

    private static final String EVENT_STREAM = "text/event-stream";
    private static final String OK = "200";
    private static final String COMPONENT_PREFIX = "#/components/schemas/";

    @TempDir
    static Path workspace;

    private static String yaml;
    private static OpenAPI document;

    @BeforeAll
    static void compileAndGenerate() throws IOException {
        Path classes = workspace.resolve("target/classes");
        ProcessorCompiler.compile(workspace.resolve("src/main/java"), classes, null, domainSources());
        DomainMetadata parcel = CodegenPipeline.createDefault()
                .loadMetadata(classes.resolve(MetadataLoader.METADATA_DIR)).stream()
                .filter(m -> m.entityName().equals("Parcel"))
                .findFirst().orElseThrow();

        yaml = new KernelOpenApiGenerator().generate(parcel).content();
        SwaggerParseResult parsed = new OpenAPIV3Parser().readContents(yaml);
        assertThat(parsed.getMessages()).as("parser messages").isEmpty();
        document = parsed.getOpenAPI();

        assertThat(new KernelOpenApiGenerator().generate(parcel).content())
                .as("a second emission from the same metadata").isEqualTo(yaml);
    }

    @Test
    @DisplayName("a streaming action answers only 200 text/event-stream, names its event and keeps its request body")
    void streamingActionIsAnEventStream() {
        Operation track = document.getPaths().get("/parcels/{id}/actions/track").getPost();
        assertEventStreamOnly(track);
        assertThat(track.getOperationId()).isEqualTo("trackParcel");
        assertThat(track.getRequestBody()).isNull();
        assertThat(track.getDescription())
                .contains("(ParcelTracked)")
                .doesNotContain("ParcelCreated")
                .contains("stream-error")
                .contains("400, 404 or 500");

        Operation relabel = document.getPaths().get("/parcels/{id}/actions/relabel").getPost();
        assertEventStreamOnly(relabel);
        assertThat(relabel.getRequestBody().getContent().get("application/json").getSchema().get$ref())
                .isEqualTo(COMPONENT_PREFIX + "ParcelRelabelRequest");
        assertThat(relabel.getDescription()).contains("triggers no @DomainEvent");
    }

    @Test
    @DisplayName("a respond-once action on the same entity keeps its JSON statuses")
    void respondOnceActionIsUnchanged() {
        Operation approve = document.getPaths().get("/parcels/{id}/actions/approve").getPost();
        assertThat(approve.getResponses()).containsOnlyKeys(OK, "400", "404", "500");
        assertThat(approve.getResponses().get(OK).getContent()).isNull();
    }

    @Test
    @DisplayName("realTimeApi documents the live view and the spectate route as event streams")
    void liveViewAndSpectateAreEventStreams() {
        Operation live = document.getPaths().get("/parcels/stream").getGet();
        assertEventStreamOnly(live);
        assertThat(live.getOperationId()).isEqualTo("streamParcel");
        assertThat(live.getDescription()).contains("ParcelTracked, ParcelCreated").contains("keep-alive");

        Operation spectate = document.getPaths().get("/parcels/{id}/stream").getGet();
        assertEventStreamOnly(spectate);
        assertThat(spectate.getOperationId()).isEqualTo("spectateParcel");
        assertThat(spectate.getDescription()).contains("ParcelTracked, ParcelCreated").contains("stream-error");
    }

    @Test
    @DisplayName("every schema in the document has a type, a $ref or a composition")
    void everySchemaIsTyped() {
        List<String> untyped = new ArrayList<>();
        allSchemas().forEach((at, schema) -> {
            boolean typed = schema != null && (schema.get$ref() != null
                    || (schema.getTypes() != null && !schema.getTypes().isEmpty())
                    || schema.getAllOf() != null || schema.getAnyOf() != null || schema.getOneOf() != null);
            if (!typed) {
                untyped.add(at);
            }
        });
        assertThat(untyped).isEmpty();
    }

    @Test
    @DisplayName("every $ref in the document resolves to a schema it defines")
    void everyReferenceResolves() {
        Map<String, Schema> defined = document.getComponents().getSchemas();
        List<String> dangling = new ArrayList<>();
        allSchemas().forEach((at, schema) -> {
            String ref = schema == null ? null : schema.get$ref();
            if (ref != null && !(ref.startsWith(COMPONENT_PREFIX)
                    && defined.containsKey(ref.substring(COMPONENT_PREFIX.length())))) {
                dangling.add(at + " -> " + ref);
            }
        });
        assertThat(dangling).isEmpty();
    }

    private static void assertEventStreamOnly(Operation operation) {
        assertThat(operation.getResponses()).containsOnlyKeys(OK);
        assertThat(operation.getResponses().get(OK).getContent()).containsOnlyKeys(EVENT_STREAM);
    }

    /** Each schema of the document with where it sits, nested schemas included. */
    private static Map<String, Schema<?>> allSchemas() {
        Map<String, Schema<?>> found = new LinkedHashMap<>();
        document.getComponents().getSchemas().forEach((name, schema) ->
                collect(COMPONENT_PREFIX + name, schema, found));
        document.getPaths().forEach((path, item) -> item.readOperationsMap().forEach((method, op) -> {
            String at = method + " " + path;
            if (op.getParameters() != null) {
                for (Parameter parameter : op.getParameters()) {
                    collect(at + " parameter " + parameter.getName(), parameter.getSchema(), found);
                }
            }
            if (op.getRequestBody() != null && op.getRequestBody().getContent() != null) {
                op.getRequestBody().getContent().forEach((media, type) ->
                        collect(at + " requestBody " + media, type.getSchema(), found));
            }
            op.getResponses().forEach((status, response) -> {
                if (response.getContent() != null) {
                    response.getContent().forEach((media, type) ->
                            collect(at + " " + status + " " + media, type.getSchema(), found));
                }
            });
        }));
        return found;
    }

    private static void collect(String at, Schema<?> schema, Map<String, Schema<?>> found) {
        found.put(at, schema);
        if (schema == null) {
            return;
        }
        if (schema.getProperties() != null) {
            schema.getProperties().forEach((name, property) -> collect(at + "/properties/" + name, property, found));
        }
        if (schema.getItems() != null) {
            collect(at + "/items", schema.getItems(), found);
        }
        if (schema.getAdditionalProperties() instanceof Schema<?> additional) {
            collect(at + "/additionalProperties", additional, found);
        }
        for (List<Schema> composed : Arrays.asList(schema.getAllOf(), schema.getAnyOf(), schema.getOneOf())) {
            if (composed != null) {
                for (int i = 0; i < composed.size(); i++) {
                    collect(at + "/composition[" + i + "]", composed.get(i), found);
                }
            }
        }
    }

    private static Map<String, String> domainSources() {
        return Map.of("eu/exeris/e2e/parcels/domain/Parcel.java", """
                package eu.exeris.e2e.parcels.domain;

                import eu.exeris.sdk.annotation.Action;
                import eu.exeris.sdk.annotation.ActionParam;
                import eu.exeris.sdk.annotation.DomainEvent;
                import eu.exeris.sdk.annotation.DomainEvent.Trigger;
                import eu.exeris.sdk.annotation.ExerisDomain;
                import eu.exeris.sdk.annotation.Field;

                import java.util.UUID;

                @ExerisDomain(module = "parcels", path = "/parcels", realTimeApi = true,
                              dataScope = ExerisDomain.DataScope.GLOBAL)
                @DomainEvent(name = "ParcelTracked", trigger = Trigger.ACTION, action = "track",
                        topic = "parcels.tracked", includeFields = {"label"})
                @DomainEvent(name = "ParcelCreated", trigger = Trigger.CREATE, topic = "parcels.created")
                public class Parcel {

                    private UUID id;

                    @Field(label = "Label", required = true)
                    private String label;

                    public UUID getId() { return id; }
                    public void setId(UUID id) { this.id = id; }
                    public String getLabel() { return label; }
                    public void setLabel(String label) { this.label = label; }

                    @Action(name = "track", label = "Track", streaming = true)
                    public void track() { this.label = this.label + " tracked"; }

                    @Action(name = "relabel", label = "Relabel", streaming = true)
                    public void relabel(@ActionParam(label = "Label") String label) {
                        this.label = label;
                    }

                    @Action(name = "approve", label = "Approve")
                    public void approve() { }
                }
                """);
    }
}
