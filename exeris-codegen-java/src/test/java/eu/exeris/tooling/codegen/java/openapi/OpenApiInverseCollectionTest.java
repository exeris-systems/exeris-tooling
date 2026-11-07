package eu.exeris.tooling.codegen.java.openapi;

import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.FieldMetadata;
import eu.exeris.sdk.sourcemodel.ast.RelationshipMetadata;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.media.Schema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("OpenAPI: an inverse collection is read-only, always null, and names the route its rows are on")
class OpenApiInverseCollectionTest {

    private static final DomainMetadata INVOICE = DomainMetadata.builder("Invoice", "com.shop.domain")
            .path("/invoices")
            .fields(List.of(FieldMetadata.builder("number", "String").build(),
                    FieldMetadata.builder("lines", "java.util.List<com.shop.domain.InvoiceLine>").build()))
            .relationships(List.of(RelationshipMetadata.builder("lines", "InvoiceLine")
                    .type(RelationshipMetadata.RelationType.ONE_TO_MANY).mappedBy("invoice").build()))
            .build();

    private static final DomainMetadata INVOICE_LINE = DomainMetadata.builder("InvoiceLine", "com.shop.domain")
            .path("/invoice-lines")
            .fields(List.of(FieldMetadata.builder("invoiceId", "java.util.UUID").build()))
            .relationships(List.of(RelationshipMetadata.builder("invoiceId", "Invoice")
                    .type(RelationshipMetadata.RelationType.MANY_TO_ONE).build()))
            .build();

    @Test
    @DisplayName("the entity schema marks it readOnly, array or null of the target, and both DTOs leave it out")
    void inverseCollectionSchema() {
        Components components = OpenApiComponentsBuilder.buildComponents(INVOICE, List.of(INVOICE, INVOICE_LINE));

        Schema<?> lines = (Schema<?>) components.getSchemas().get("Invoice").getProperties().get("lines");
        assertThat(lines.getReadOnly()).isTrue();
        assertThat(lines.getTypes()).containsExactly("array", "null");
        assertThat(lines.getItems().get$ref()).isEqualTo("#/components/schemas/InvoiceLine");
        assertThat(lines.getDescription())
                .isEqualTo("Always null: the rows are listed on GET /invoice-lines?invoiceId={id}");
        assertThat(components.getSchemas().get("InvoiceCreateDto").getProperties())
                .containsKey("number").doesNotContainKey("lines");
        assertThat(components.getSchemas().get("InvoiceUpdateDto").getProperties())
                .containsKey("number").doesNotContainKey("lines");
    }

    @Test
    @DisplayName("without the target in the build the description names the target's list route")
    void unresolvedTarget() {
        Schema<?> lines = (Schema<?>) OpenApiComponentsBuilder.buildComponents(INVOICE)
                .getSchemas().get("Invoice").getProperties().get("lines");

        assertThat(lines.getDescription()).isEqualTo("Always null: the rows are listed on the InvoiceLine list route");
    }
}
