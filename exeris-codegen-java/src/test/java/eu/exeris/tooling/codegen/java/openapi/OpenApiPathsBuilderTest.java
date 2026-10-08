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
    @DisplayName("A by-id GET declares exactly what its handler answers: 200/400/404/500")
    void operationsTagAndStandardErrors() {
        DomainMetadata meta = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders").build();

        Operation get = OpenApiPathsBuilder.buildPaths(meta).get("/orders/{id}").getGet();

        assertThat(get.getTags()).containsExactly("Order");
        // 500 is declared because every emitted handler can reach it — the tenant guard answers
        // it directly, and every service call sits inside a catch (RuntimeException) that does.
        // Until ADR-076 the spec named 404 and omitted 500 while the handler did the opposite.
        // containsOnly, not contains: ADR-079 removed the 401 this set carried, and the point of
        // the fix is the statuses that are absent.
        assertThat(get.getResponses()).containsOnlyKeys("200", "400", "404", "500");
        // id path-param emitted with uuid format.
        assertThat(get.getParameters()).hasSize(1);
        assertThat(get.getParameters().get(0).getName()).isEqualTo("id");
        assertThat(get.getParameters().get(0).getIn()).isEqualTo("path");
        assertThat(get.getParameters().get(0).getRequired()).isTrue();
        assertThat(get.getParameters().get(0).getSchema().getFormat()).isEqualTo("uuid");
    }

    @Test
    @DisplayName("ADR-076: 409 is declared only where a versioned write can report one")
    void conflictOnlyOnVersionedWrites() {
        DomainMetadata unversioned = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders").build();
        DomainMetadata versioned = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders").versioned(true).build();

        Paths plain = OpenApiPathsBuilder.buildPaths(unversioned);
        Paths locked = OpenApiPathsBuilder.buildPaths(versioned);

        assertThat(plain.get("/orders/{id}").getPut().getResponses()).doesNotContainKey("409");
        assertThat(locked.get("/orders/{id}").getPut().getResponses()).containsKey("409");
        // A versioned update raises the conflict INSTEAD of the not-found: one statement matches on
        // id and version together, so the emitted catch is the conflict alone. An unversioned one
        // is the other way round.
        assertThat(locked.get("/orders/{id}").getPut().getResponses()).doesNotContainKey("404");
        assertThat(plain.get("/orders/{id}").getPut().getResponses()).containsKey("404");
        // Not on the routes that cannot raise it: deleteById matches on id alone, and a read
        // has no expected version to be stale against.
        assertThat(locked.get("/orders/{id}").getDelete().getResponses()).doesNotContainKey("409");
        assertThat(locked.get("/orders/{id}").getGet().getResponses()).doesNotContainKey("409");
        assertThat(locked.get("/orders").getPost().getResponses()).doesNotContainKey("409");
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

    @Test
    @DisplayName("ADR-079: no operation declares 401, on any route shape")
    void noOperationDeclaresAnUnreachableUnauthorized() {
        DomainMetadata meta = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders").versioned(true)
                .actions(List.of(ActionMetadata.builder("approve").build()))
                .build();

        Paths paths = OpenApiPathsBuilder.buildPaths(meta);

        // The emitted application binds no HttpRoutePolicy, so the kernel dispatcher resolves every
        // route to permitAll(), never runs the SecurityInterceptor, and cannot reach the 401 it is
        // otherwise able to write. A spec declaring it describes a check that does not run.
        assertThat(paths.values().stream()
                .flatMap(item -> item.readOperations().stream())
                .flatMap(op -> op.getResponses().keySet().stream()))
                .doesNotContain("401");
    }

    @Test
    @DisplayName("ADR-079: each operation declares only the statuses its own handler can answer")
    void responseSetsFollowTheRouteShape() {
        DomainMetadata plain = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders")
                .actions(List.of(ActionMetadata.builder("approve").build()))
                .build();
        DomainMetadata locked = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders").versioned(true)
                .actions(List.of(ActionMetadata.builder("approve").build()))
                .build();

        Paths paths = OpenApiPathsBuilder.buildPaths(plain);

        // The collection GET has no id to miss, so no 404; it parses a query string, and a query the
        // list query refuses is the caller's 400.
        assertThat(paths.get("/orders").getGet().getResponses()).containsOnlyKeys("200", "400", "500");
        // The create POST decodes a body — 400 — but addresses no row, so no 404.
        assertThat(paths.get("/orders").getPost().getResponses()).containsOnlyKeys("201", "400", "500");
        assertThat(paths.get("/orders/{id}").getDelete().getResponses())
                .containsOnlyKeys("204", "400", "404", "500");
        // An action answers 404 twice over — the findById guard, then the write rejection — and on a
        // versioned entity the write half becomes the conflict, so both statuses are declared.
        assertThat(paths.get("/orders/{id}/actions/approve").getPost().getResponses())
                .containsOnlyKeys("200", "400", "404", "500");
        assertThat(OpenApiPathsBuilder.buildPaths(locked)
                .get("/orders/{id}/actions/approve").getPost().getResponses())
                .containsOnlyKeys("200", "400", "404", "409", "500");
    }

    @Test
    @DisplayName("ADR-044 Amendment 2: realTimeApi adds GET {base}/{id}/stream, an event stream answering only 200")
    void spectateRouteIsAnEventStream() {
        DomainMetadata plain = DomainMetadata.builder("Order", "com.example.domain").path("/orders").build();
        DomainMetadata live = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders").realTimeApi(true).build();

        assertThat(OpenApiPathsBuilder.buildPaths(plain)).doesNotContainKey("/orders/{id}/stream");

        PathItem item = OpenApiPathsBuilder.buildPaths(live).get("/orders/{id}/stream");
        assertThat(item.readOperations()).hasSize(1);
        Operation spectate = item.getGet();
        assertThat(spectate.getOperationId()).isEqualTo("spectateOrder");
        assertThat(spectate.getTags()).containsExactly("Order");
        assertThat(spectate.getParameters()).singleElement()
                .satisfies(param -> assertThat(param.getSchema().getFormat()).isEqualTo("uuid"));
        // The engine writes the 200 head before the handler runs; every refusal is a stream-error
        // frame inside it, so 200 is the only status the route can answer.
        assertThat(spectate.getResponses()).containsOnlyKeys("200");
        assertThat(spectate.getResponses().get("200").getContent()).containsOnlyKeys("text/event-stream");
        assertThat(spectate.getDescription()).contains("stream-error").contains("400, 404 or 500");
    }

    @Test
    @DisplayName("the list operation declares page and size always, sort only when something is "
            + "sortable, one typed parameter per filter, and the page envelope")
    void listOperationDeclaresTheQuery() {
        DomainMetadata unsortable = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders")
                .fields(List.of(eu.exeris.sdk.sourcemodel.ast.FieldMetadata.builder("urgent", "boolean")
                        .filterable(true).build()))
                .build();

        Operation list = OpenApiPathsBuilder.buildPaths(unsortable).get("/orders").getGet();

        assertThat(list.getParameters()).extracting(p -> p.getName()).containsExactly("page", "size", "urgent");
        assertThat(list.getParameters().get(2).getSchema().getType()).isEqualTo("boolean");
        assertThat(list.getParameters().get(1).getSchema().getMaximum().intValue()).isEqualTo(100);
        assertThat(list.getResponses().get("200").getContent().get("application/json").getSchema().get$ref())
                .isEqualTo("#/components/schemas/OrderPage");
    }

    @Test
    @DisplayName("the list operation declares no parameter and no sort value for a type nothing "
            + "recognises, and a string parameter for an enum")
    void listOperationSkipsUnrecognisedTypes() {
        DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders")
                .fields(List.of(
                        eu.exeris.sdk.sourcemodel.ast.FieldMetadata.simple("placedAt", "java.time.OffsetDateTime"),
                        eu.exeris.sdk.sourcemodel.ast.FieldMetadata.simple("attributes",
                                "java.util.Map<java.lang.String,java.lang.String>"),
                        eu.exeris.sdk.sourcemodel.ast.FieldMetadata.simple("serial", "java.math.BigInteger"),
                        eu.exeris.sdk.sourcemodel.ast.FieldMetadata.builder("status", "com.example.domain.OrderStatus")
                                .enumType("com.example.domain.OrderStatus").sortable(true).filterable(true).build()))
                .build();

        Operation list = OpenApiPathsBuilder.buildPaths(metadata).get("/orders").getGet();

        assertThat(list.getParameters()).extracting(p -> p.getName())
                .containsExactly("page", "size", "sort", "status");
        // An OffsetDateTime is a sort key (ADR-096 Amendment 1), never a filter parameter.
        assertThat(list.getParameters().get(2).getSchema().getEnum())
                .containsExactly("placedAt,asc", "placedAt,desc", "status,asc", "status,desc");
        assertThat(list.getParameters().get(3).getSchema().getType()).isEqualTo("string");
    }
}
