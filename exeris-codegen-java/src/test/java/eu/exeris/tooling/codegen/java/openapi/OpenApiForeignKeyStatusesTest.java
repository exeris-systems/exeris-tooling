package eu.exeris.tooling.codegen.java.openapi;

import eu.exeris.sdk.sourcemodel.ast.ActionMetadata;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.RelationshipMetadata;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-076 Amendment 1: the spec declares {@code 422} on the writes of an entity that references an
 * entity of the build, and {@code 409} on the delete of an entity another references, and nowhere
 * else.
 */
@DisplayName("OpenAPI: the statuses a foreign key adds")
class OpenApiForeignKeyStatusesTest {

    private static final String PACKAGE = "com.example.domain";

    private static final DomainMetadata CUSTOMER = DomainMetadata.builder("Customer", PACKAGE)
            .path("/customers").build();

    private static final DomainMetadata ORDER = DomainMetadata.builder("Order", PACKAGE)
            .path("/orders")
            .versioned(true)
            .relationships(List.of(RelationshipMetadata.builder("customer", "Customer")
                    .type(RelationshipMetadata.RelationType.MANY_TO_ONE).build()))
            .actions(List.of(ActionMetadata.builder("approve").methodName("approve").build()))
            .build();

    private static final DomainMetadata ARCHIVE = DomainMetadata.builder("Archive", PACKAGE)
            .path("/archives").softDelete(true).build();

    private static final DomainMetadata NOTE = DomainMetadata.builder("Note", PACKAGE)
            .path("/notes")
            .relationships(List.of(RelationshipMetadata.builder("archive", "Archive")
                    .type(RelationshipMetadata.RelationType.MANY_TO_ONE).build()))
            .build();

    private static final List<DomainMetadata> BUILD = List.of(ARCHIVE, CUSTOMER, NOTE, ORDER);

    @Test
    @DisplayName("a referencing entity's create, update and action declare 422; its delete does not declare 409")
    void referencingEntity() {
        Paths paths = OpenApiPathsBuilder.buildPaths(ORDER, BUILD);
        PathItem item = paths.get("/orders/{id}");

        assertThat(paths.get("/orders").getPost().getResponses()).containsOnlyKeys("201", "400", "422", "500");
        assertThat(item.getPut().getResponses()).containsOnlyKeys("200", "400", "409", "422", "500");
        assertThat(paths.get("/orders/{id}/actions/approve").getPost().getResponses())
                .containsOnlyKeys("200", "400", "404", "409", "422", "500");
        assertThat(item.getDelete().getResponses()).containsOnlyKeys("204", "400", "404", "500");
        assertThat(paths.get("/orders").getPost().getResponses().get("422").getDescription())
                .contains("field member names the relationship");
    }

    @Test
    @DisplayName("a referenced entity's delete declares 409; its writes do not declare 422")
    void referencedEntity() {
        Paths paths = OpenApiPathsBuilder.buildPaths(CUSTOMER, BUILD);

        assertThat(paths.get("/customers/{id}").getDelete().getResponses())
                .containsOnlyKeys("204", "400", "404", "409", "500");
        assertThat(paths.get("/customers/{id}").getDelete().getResponses().get("409").getDescription())
                .startsWith("Still referenced");
        assertThat(paths.get("/customers").getPost().getResponses()).doesNotContainKey("422");
    }

    @Test
    @DisplayName("a soft-deleted target keeps its row, so its delete declares no 409")
    void softDeletedTarget() {
        assertThat(OpenApiPathsBuilder.buildPaths(ARCHIVE, BUILD).get("/archives/{id}").getDelete()
                .getResponses()).doesNotContainKey("409");
    }

    @Test
    @DisplayName("an entity described alone has no foreign key to a target outside it")
    void describedAlone() {
        assertThat(OpenApiPathsBuilder.buildPaths(ORDER).get("/orders").getPost().getResponses())
                .doesNotContainKey("422");
        assertThat(OpenApiPathsBuilder.buildPaths(CUSTOMER).get("/customers/{id}").getDelete()
                .getResponses()).doesNotContainKey("409");
    }

    @Test
    @DisplayName("the aggregated spec reads the build it describes")
    void aggregatedSpec(@TempDir Path dir) throws IOException {
        OpenApiGenerator generator = new OpenApiGenerator();
        generator.setOutputDirectory(dir);

        var spec = generator.generateAggregated(BUILD, "shop");

        assertThat(spec.getPaths().get("/customers/{id}").getDelete().getResponses()).containsKey("409");
        assertThat(spec.getPaths().get("/orders").getPost().getResponses()).containsKey("422");
    }
}
