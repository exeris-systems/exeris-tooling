package eu.exeris.tooling.codegen.java.openapi;

import eu.exeris.sdk.sourcemodel.ast.ActionMetadata;
import eu.exeris.sdk.sourcemodel.ast.ActionParamMetadata;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("OpenApiPathsBuilder")
class OpenApiPathsBuilderTest {

    @Test
    @DisplayName("Collection + item paths emitted with the canonical CRUD operations")
    void crudPaths() {
        DomainMetadata meta = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders").build();

        Paths paths = OpenApiPathsBuilder.buildPaths(meta);

        PathItem collection = paths.get("/orders");
        assertThat(collection).isNotNull();
        assertThat(collection.getGet()).isNotNull();
        assertThat(collection.getGet().getOperationId()).isEqualTo("listOrder");
        assertThat(collection.getPost()).isNotNull();
        assertThat(collection.getPost().getOperationId()).isEqualTo("createOrder");

        PathItem item = paths.get("/orders/{id}");
        assertThat(item).isNotNull();
        assertThat(item.getGet().getOperationId()).isEqualTo("getOrderById");
        assertThat(item.getPut().getOperationId()).isEqualTo("updateOrder");
        assertThat(item.getDelete().getOperationId()).isEqualTo("deleteOrder");
    }

    @Test
    @DisplayName("Every CRUD operation tags the entity and lists the standard 400/401/404 responses")
    void operationsTagAndStandardErrors() {
        DomainMetadata meta = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders").build();

        Operation get = OpenApiPathsBuilder.buildPaths(meta).get("/orders/{id}").getGet();

        assertThat(get.getTags()).containsExactly("Order");
        assertThat(get.getResponses()).containsKeys("200", "400", "401", "404");
        // id path-param emitted with uuid format.
        assertThat(get.getParameters()).hasSize(1);
        assertThat(get.getParameters().get(0).getName()).isEqualTo("id");
        assertThat(get.getParameters().get(0).getIn()).isEqualTo("path");
        assertThat(get.getParameters().get(0).getRequired()).isTrue();
        assertThat(get.getParameters().get(0).getSchema().getFormat()).isEqualTo("uuid");
    }

    @Test
    @DisplayName("DELETE operation responds with 204 (rather than 200)")
    void deleteUses204() {
        DomainMetadata meta = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders").build();

        Operation delete = OpenApiPathsBuilder.buildPaths(meta).get("/orders/{id}").getDelete();

        assertThat(delete.getResponses()).containsKey("204");
        assertThat(delete.getResponses()).doesNotContainKey("200");
    }

    @Test
    @DisplayName("POST collection uses 201 + a CreateDto request body reference")
    void createReturnsCreated() {
        DomainMetadata meta = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders").build();

        Operation post = OpenApiPathsBuilder.buildPaths(meta).get("/orders").getPost();

        assertThat(post.getResponses()).containsKey("201");
        assertThat(post.getRequestBody()).isNotNull();
        assertThat(post.getRequestBody().getContent().get("application/json").getSchema().get$ref())
                .isEqualTo("#/components/schemas/OrderCreateDto");
    }

    @Test
    @DisplayName("Action paths: kebab-cased URLs, tagged \"<Entity> Actions\", description fallback when unset")
    void actionPathsWithoutParams() {
        DomainMetadata meta = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders")
                .actions(List.of(
                        ActionMetadata.builder("approveOrder")
                                .description("Approve an order").build()))
                .build();

        Paths paths = OpenApiPathsBuilder.buildPaths(meta);
        PathItem actionPath = paths.get("/orders/{id}/actions/approve-order");

        assertThat(actionPath).isNotNull();
        Operation post = actionPath.getPost();
        assertThat(post.getOperationId()).isEqualTo("approveOrderOrder");
        assertThat(post.getTags()).containsExactly("Order Actions");
        assertThat(post.getSummary()).isEqualTo("Approve an order");
        // No params on the action → no request body emitted.
        assertThat(post.getRequestBody()).isNull();
    }

    @Test
    @DisplayName("Action without description falls back to \"Execute <name>\" summary")
    void actionSummaryFallback() {
        DomainMetadata meta = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders")
                .actions(List.of(ActionMetadata.builder("ship").build()))
                .build();

        Operation post = OpenApiPathsBuilder.buildPaths(meta)
                .get("/orders/{id}/actions/ship")
                .getPost();

        assertThat(post.getSummary()).isEqualTo("Execute ship");
    }

    @Test
    @DisplayName("Actions with declared params emit a request body referencing the action schema")
    void actionsWithParamsEmitRequestBody() {
        ActionParamMetadata orderId = ActionParamMetadata.builder("note", "String").build();
        DomainMetadata meta = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders")
                .actions(List.of(
                        ActionMetadata.builder("approve").addParam(orderId).build()))
                .build();

        Operation post = OpenApiPathsBuilder.buildPaths(meta)
                .get("/orders/{id}/actions/approve")
                .getPost();

        assertThat(post.getRequestBody()).isNotNull();
        assertThat(post.getRequestBody().getContent().get("application/json").getSchema().get$ref())
                .isEqualTo("#/components/schemas/OrderApproveRequest");
    }
}
