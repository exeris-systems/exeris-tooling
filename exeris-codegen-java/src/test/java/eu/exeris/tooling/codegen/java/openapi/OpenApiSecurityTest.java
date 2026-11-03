package eu.exeris.tooling.codegen.java.openapi;

import eu.exeris.sdk.sourcemodel.ast.ActionMetadata;
import eu.exeris.sdk.sourcemodel.ast.BlobMetadata;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.FieldMetadata;
import eu.exeris.sdk.sourcemodel.ast.RouteAccess;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The security each operation declares, held against the requirement the generated route policy
 * binds for its route (ADR-079 Amendment 2).
 */
@DisplayName("OpenAPI security: per operation, from the route requirement")
class OpenApiSecurityTest {

    private static final String PACKAGE = "com.example.domain";
    private static final String BLOB_REF = "eu.exeris.kernel.spi.storage.blob.BlobRef";
    private static final String BASE = "/orders";
    private static final String ITEM = "/orders/{id}";
    private static final String APPROVE = "/orders/{id}/actions/approve";
    private static final SecurityRequirement BEARER = new SecurityRequirement().addList("bearerAuth");

    private static DomainMetadata.Builder order() {
        return DomainMetadata.builder("Order", PACKAGE).path(BASE)
                .actions(List.of(ActionMetadata.builder("approve").methodName("approve").build()));
    }

    private static List<Operation> operations(Paths paths) {
        return paths.values().stream().flatMap(item -> item.readOperations().stream()).toList();
    }

    @Test
    @DisplayName("authenticated: every operation requires the bearer scheme with no scopes, and gains 401 alone")
    void authenticatedRequiresTheBearerScheme() {
        DomainMetadata meta = order().routeAccess(RouteAccess.AUTHENTICATED).build();

        Paths paths = OpenApiPathsBuilder.buildPaths(meta);

        assertThat(operations(paths)).isNotEmpty().allSatisfy(op -> {
            assertThat(op.getSecurity()).containsExactly(BEARER);
            assertThat(op.getResponses()).containsKey("401").doesNotContainKey("403");
            assertThat(op.getResponses().get("401").getContent()).isNull();
        });
        assertThat(paths.get(ITEM).getGet().getResponses().keySet())
                .containsExactly("200", "400", "401", "404", "500");
        assertThat(paths.get(BASE).getPost().getResponses().keySet())
                .containsExactly("201", "400", "401", "500");
    }

    @Test
    @DisplayName("anyScope over two scopes: two requirement objects in declaration order, and 401 and 403")
    void anyScopeIsOneRequirementObjectPerScope() {
        DomainMetadata meta = order().permissions(List.of("order:write", "order:read")).build();

        Paths paths = OpenApiPathsBuilder.buildPaths(meta);

        assertThat(operations(paths)).isNotEmpty().allSatisfy(op -> {
            assertThat(op.getSecurity()).containsExactly(
                    new SecurityRequirement().addList("bearerAuth", "order:write"),
                    new SecurityRequirement().addList("bearerAuth", "order:read"));
            assertThat(op.getResponses()).containsKeys("401", "403");
            assertThat(op.getResponses().get("403").getContent()).isNull();
        });
        assertThat(paths.get(BASE).getGet().getResponses().keySet())
                .containsExactly("200", "400", "401", "403", "500");
        assertThat(paths.get(APPROVE).getPost().getResponses().keySet())
                .containsExactly("200", "400", "401", "403", "404", "500");
    }

    @Test
    @DisplayName("permitAll: no operation declares security, 401 or 403, and the components carry no scheme")
    void permitAllAddsNothing() {
        DomainMetadata meta = order().routeAccess(RouteAccess.PUBLIC).build();

        assertThat(operations(OpenApiPathsBuilder.buildPaths(meta))).isNotEmpty().allSatisfy(op -> {
            assertThat(op.getSecurity()).isNull();
            assertThat(op.getResponses()).doesNotContainKeys("401", "403");
        });
        assertThat(OpenApiComponentsBuilder.buildComponents(meta).getSecuritySchemes()).isNull();
    }

    @Test
    @DisplayName("undeclared: no operation declares security, 401 or 403, and the components carry no scheme")
    void undeclaredAddsNothing() {
        DomainMetadata meta = order().realTimeApi(true).build();

        assertThat(operations(OpenApiPathsBuilder.buildPaths(meta))).isNotEmpty().allSatisfy(op -> {
            assertThat(op.getSecurity()).isNull();
            assertThat(op.getResponses()).doesNotContainKeys("401", "403");
        });
        assertThat(OpenApiComponentsBuilder.buildComponents(meta).getSecuritySchemes()).isNull();
    }

    @Test
    @DisplayName("an action declaring PUBLIC on an authenticated entity carries no requirement on its route")
    void publicActionOverridesAnAuthenticatedEntity() {
        DomainMetadata meta = order().routeAccess(RouteAccess.AUTHENTICATED)
                .actions(List.of(ActionMetadata.builder("approve").methodName("approve")
                        .routeAccess(RouteAccess.PUBLIC).build()))
                .build();

        Paths paths = OpenApiPathsBuilder.buildPaths(meta);

        Operation approve = paths.get(APPROVE).getPost();
        assertThat(approve.getSecurity()).isNull();
        assertThat(approve.getResponses()).doesNotContainKeys("401", "403");
        assertThat(paths.get(ITEM).getGet().getSecurity()).containsExactly(BEARER);
    }

    @Test
    @DisplayName("an action declaring permissions on an undeclared entity requires them on its route alone")
    void scopedActionOverridesAnUndeclaredEntity() {
        DomainMetadata meta = order()
                .actions(List.of(ActionMetadata.builder("approve").methodName("approve")
                        .permissions(List.of("order:approve")).build()))
                .build();

        Paths paths = OpenApiPathsBuilder.buildPaths(meta);

        assertThat(paths.get(APPROVE).getPost().getSecurity())
                .containsExactly(new SecurityRequirement().addList("bearerAuth", "order:approve"));
        assertThat(operations(paths)).filteredOn(op -> op != paths.get(APPROVE).getPost())
                .isNotEmpty().allSatisfy(op -> assertThat(op.getSecurity()).isNull());
        // The action's route demands an identity, so the entity's components name the scheme.
        assertThat(OpenApiComponentsBuilder.buildComponents(meta).getSecuritySchemes())
                .containsOnlyKeys("bearerAuth");
    }

    @Test
    @DisplayName("the four routes of a @Blob field carry the entity's requirement")
    void blobRoutesCarryTheEntityRequirement() {
        DomainMetadata meta = order().permissions(List.of("order:read"))
                .fields(List.of(FieldMetadata.builder("receipt", BLOB_REF)
                        .blob(BlobMetadata.ofContentTypes(List.of("application/pdf"))).build()))
                .build();

        Paths paths = OpenApiPathsBuilder.buildPaths(meta);

        List<Operation> blob = paths.get("/orders/{id}/blobs/receipt").readOperations();
        assertThat(blob).hasSize(4).allSatisfy(op -> assertThat(op.getSecurity())
                .containsExactly(new SecurityRequirement().addList("bearerAuth", "order:read")));
        assertThat(paths.get("/orders/{id}/blobs/receipt").getPut().getResponses().keySet())
                .containsExactly("204", "400", "401", "403", "404", "415", "500");
    }

    @Test
    @DisplayName("stream routes carry their route's requirement and gain the edge's statuses beside 200")
    void streamRoutesCarryTheirRequirement() {
        DomainMetadata meta = order().realTimeApi(true).routeAccess(RouteAccess.AUTHENTICATED)
                .actions(List.of(ActionMetadata.builder("track").methodName("track").streaming(true)
                        .permissions(List.of("order:track")).build()))
                .build();

        Paths paths = OpenApiPathsBuilder.buildPaths(meta);

        assertThat(paths.get("/orders/stream").getGet().getSecurity()).containsExactly(BEARER);
        assertThat(paths.get("/orders/{id}/stream").getGet().getResponses().keySet())
                .containsExactly("200", "401");
        Operation track = paths.get("/orders/{id}/actions/track").getPost();
        assertThat(track.getSecurity())
                .containsExactly(new SecurityRequirement().addList("bearerAuth", "order:track"));
        assertThat(track.getResponses().keySet()).containsExactly("200", "401", "403");
    }

    @Test
    @DisplayName("the scheme is HTTP bearer with no bearer format, on an entity that demands identity")
    void schemeIsHttpBearer() {
        Components components = OpenApiComponentsBuilder.buildComponents(
                order().routeAccess(RouteAccess.AUTHENTICATED).build());

        assertThat(components.getSecuritySchemes()).containsOnlyKeys("bearerAuth");
        SecurityScheme scheme = components.getSecuritySchemes().get("bearerAuth");
        assertThat(scheme.getType()).isEqualTo(SecurityScheme.Type.HTTP);
        assertThat(scheme.getScheme()).isEqualTo("bearer");
        assertThat(scheme.getBearerFormat()).isNull();
    }

    @Test
    @DisplayName("both documents: the scheme where a route demands identity, never a root-level requirement")
    void documentsCarryTheSchemeAndNoRootRequirement(@TempDir Path dir) throws IOException {
        DomainMetadata secured = order().routeAccess(RouteAccess.AUTHENTICATED).build();
        DomainMetadata open = DomainMetadata.builder("Tag", PACKAGE).path("/tags").build();
        OpenApiGenerator generator = new OpenApiGenerator();
        generator.setOutputDirectory(dir);

        OpenAPI aggregate = generator.generateAggregated(List.of(open, secured), "shop");
        OpenAPI single = generator.generate(secured);

        assertThat(aggregate.getSecurity()).isNull();
        assertThat(aggregate.getComponents().getSecuritySchemes()).containsOnlyKeys("bearerAuth");
        assertThat(single.getSecurity()).isNull();
        assertThat(single.getComponents().getSecuritySchemes()).containsOnlyKeys("bearerAuth");
        assertThat(aggregate.getPaths().get("/tags").getGet().getSecurity()).isNull();
        // The undeclared entity's own document is the one ADR-079 describes: no scheme.
        assertThat(generator.generate(open).getComponents().getSecuritySchemes()).isNull();

        SwaggerParseResult parsed = new OpenAPIV3Parser().readContents(generator.generateYaml(secured));
        assertThat(parsed.getMessages()).isEmpty();
        assertThat(parsed.getOpenAPI().getPaths().get(ITEM).getGet().getSecurity()).containsExactly(BEARER);
    }
}
