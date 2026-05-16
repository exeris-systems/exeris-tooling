package eu.exeris.tooling.codegen.java.openapi;

import eu.exeris.sdk.sourcemodel.ast.ActionMetadata;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.RequestBody;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RequestBodyFactory")
class RequestBodyFactoryTest {

    @Test
    @DisplayName("buildCreateRequestBody: required JSON content referencing <Entity>CreateDto schema")
    void createRequestBody() {
        RequestBody body = RequestBodyFactory.buildCreateRequestBody("Order");

        assertThat(body.getRequired()).isTrue();
        assertThat(body.getDescription()).isEqualTo("Create Order request");
        Schema<?> schema = body.getContent().get("application/json").getSchema();
        assertThat(schema.get$ref()).isEqualTo("#/components/schemas/OrderCreateDto");
    }

    @Test
    @DisplayName("buildUpdateRequestBody: required JSON content referencing <Entity>UpdateDto schema")
    void updateRequestBody() {
        RequestBody body = RequestBodyFactory.buildUpdateRequestBody("Order");

        assertThat(body.getRequired()).isTrue();
        assertThat(body.getDescription()).isEqualTo("Update Order request");
        Schema<?> schema = body.getContent().get("application/json").getSchema();
        assertThat(schema.get$ref()).isEqualTo("#/components/schemas/OrderUpdateDto");
    }

    @Test
    @DisplayName("buildActionRequestBody: schema name is <Entity><Capitalised-Action>Request")
    void actionRequestBodyCapitalises() {
        ActionMetadata action = ActionMetadata.builder("approve").build();

        RequestBody body = RequestBodyFactory.buildActionRequestBody("Order", action);

        assertThat(body.getRequired()).isTrue();
        assertThat(body.getDescription()).isEqualTo("Request for approve action");
        Schema<?> schema = body.getContent().get("application/json").getSchema();
        assertThat(schema.get$ref()).isEqualTo("#/components/schemas/OrderApproveRequest");
    }

    @Test
    @DisplayName("buildActionRequestBody capitalises camelCase action names")
    void actionRequestBodyCapitalisesCamelCase() {
        ActionMetadata action = ActionMetadata.builder("requestRefund").build();

        Schema<?> schema = RequestBodyFactory.buildActionRequestBody("Order", action)
                .getContent().get("application/json").getSchema();

        // capitalize() only uppercases the first character — internal
        // camelCase is preserved verbatim.
        assertThat(schema.get$ref()).isEqualTo("#/components/schemas/OrderRequestRefundRequest");
    }
}
