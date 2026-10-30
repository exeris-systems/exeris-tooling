package eu.exeris.e2e.codegen;

import eu.exeris.e2e.codegen.compile.ProcessorCompiler;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.tooling.codegen.java.CodegenPipeline;
import eu.exeris.tooling.codegen.java.openapi.OpenApiComponentsBuilder;
import io.swagger.v3.oas.models.media.Schema;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An entity whose fields carry {@code inCreate = false} and {@code inUpdate = false}, written by the
 * real processor, held against {@code contract/lifecycle-flags/}: the metadata the processor writes
 * and the property names of the Java create and update bodies. {@code lifecycle-flags.spec.ts} in
 * {@code exeris-codegen-ts} reads the same files and holds the TypeScript {@code Create} and
 * {@code Update} types to the same property names, so the two builds agree on which fields a body
 * offers although they never run together.
 */
@Tag("e2e")
@Tag("codegen")
@DisplayName("Lifecycle flags: the processor's metadata == the contract file, and the Java bodies omit the fields")
class LifecycleFlagsContractE2ETest {

    private static final String RESOURCE = "/contract/lifecycle-flags/";

    @TempDir
    static Path workspace;

    private static JsonMapper json;
    private static JsonNode written;
    private static DomainMetadata ticket;

    @BeforeAll
    static void build() throws IOException {
        json = JsonMapper.builder().build();
        Path classes = workspace.resolve("target/classes");
        ProcessorCompiler.compile(workspace.resolve("src/main/java"), classes, null, Map.of(
                "eu/exeris/e2e/lifecycle/domain/Ticket.java", """
                        package eu.exeris.e2e.lifecycle.domain;

                        import eu.exeris.sdk.annotation.ExerisDomain;
                        import eu.exeris.sdk.annotation.Field;

                        import java.util.UUID;

                        @ExerisDomain(module = "tickets", path = "/tickets")
                        public class Ticket {

                            private UUID id;

                            @Field(label = "Title")
                            private String title;

                            @Field(label = "Code", inUpdate = false)
                            private String code;

                            @Field(label = "Slug", inCreate = false)
                            private String slug;

                            @Field(label = "Fixed", inCreate = false, inUpdate = false)
                            private String fixed;
                        }
                        """));
        Path metadata = classes.resolve("exeris-metadata");
        written = json.readTree(metadata.resolve("Ticket.json").toFile());
        ticket = CodegenPipeline.createDefault().loadMetadata(metadata).getFirst();
    }

    @Test
    @DisplayName("the processor writes the fields of the contract file, both flags written for every field")
    void processorWritesTheContractFields() throws IOException {
        assertThat(written.get("fields")).isEqualTo(resource("exeris-metadata/Ticket.json").get("fields"));
    }

    @Test
    @DisplayName("the create body omits the inCreate = false fields and the update body the inUpdate = false ones")
    void javaBodiesOmitTheFields() throws IOException {
        JsonNode bodies = resource("java-bodies.json").get("Ticket");

        assertThat(properties("TicketCreateDto")).isEqualTo(strings(bodies.get("create")))
                .doesNotContain("slug", "fixed");
        assertThat(properties("TicketUpdateDto")).isEqualTo(strings(bodies.get("update")))
                .doesNotContain("code", "fixed");
    }

    private static TreeSet<String> properties(String schema) {
        Schema<?> body = OpenApiComponentsBuilder.buildComponents(ticket).getSchemas().get(schema);
        return new TreeSet<>(body.getProperties().keySet());
    }

    private static TreeSet<String> strings(JsonNode array) {
        TreeSet<String> names = new TreeSet<>();
        array.forEach(n -> names.add(n.asString()));
        return names;
    }

    private static JsonNode resource(String name) throws IOException {
        try (InputStream in = LifecycleFlagsContractE2ETest.class.getResourceAsStream(RESOURCE + name)) {
            assertThat(in).as("%s%s on the test classpath", RESOURCE, name).isNotNull();
            return json.readTree(in);
        }
    }
}
