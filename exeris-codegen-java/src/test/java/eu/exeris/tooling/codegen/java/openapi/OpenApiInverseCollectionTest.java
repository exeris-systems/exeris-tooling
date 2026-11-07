package eu.exeris.tooling.codegen.java.openapi;

import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.FieldMetadata;
import eu.exeris.sdk.sourcemodel.ast.RelationshipMetadata;
import eu.exeris.tooling.codegen.java.kernel.KernelOpenApiGenerator;
import io.swagger.v3.core.util.Yaml31;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    @DisplayName("the entity schema marks it readOnly, array or null with no items, and both DTOs leave it out")
    void inverseCollectionSchema() {
        Components components = OpenApiComponentsBuilder.buildComponents(INVOICE, List.of(INVOICE, INVOICE_LINE));

        Schema<?> lines = (Schema<?>) components.getSchemas().get("Invoice").getProperties().get("lines");
        assertThat(lines.getReadOnly()).isTrue();
        assertThat(lines.getTypes()).containsExactly("array", "null");
        assertThat(lines.getItems()).isNull();
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

    @Test
    @DisplayName("every $ref of the entity's own document resolves to a schema that document defines")
    void everyReferenceOfTheEntityDocumentResolves() throws Exception {
        String yaml = new KernelOpenApiGenerator()
                .generateMultiple(INVOICE, List.of(INVOICE, INVOICE_LINE)).getFirst().content();
        Set<String> defined = Yaml31.mapper().readValue(yaml, OpenAPI.class).getComponents().getSchemas().keySet();

        Matcher ref = Pattern.compile("\\$ref: [\"']?#/components/schemas/([A-Za-z0-9_.-]+)").matcher(yaml);
        List<String> referenced = new ArrayList<>();
        while (ref.find()) {
            referenced.add(ref.group(1));
        }
        assertThat(referenced).as("non-vacuous: the document references its own schemas").isNotEmpty();
        assertThat(referenced).allMatch(defined::contains, "a schema the document defines");
    }
}
