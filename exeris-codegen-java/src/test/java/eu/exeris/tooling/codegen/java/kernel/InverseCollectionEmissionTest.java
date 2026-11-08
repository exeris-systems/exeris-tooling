package eu.exeris.tooling.codegen.java.kernel;

import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.FieldMetadata;
import eu.exeris.sdk.sourcemodel.ast.RelationshipMetadata;
import eu.exeris.sdk.sourcemodel.ast.RelationshipMetadata.CascadeType;
import eu.exeris.sdk.sourcemodel.ast.RelationshipMetadata.RelationType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * An inverse collection through the Java emitters: no column, index, bind or read; reset to
 * {@code null} by the handler; its {@code cascadeDelete} on the owning constraint; and every
 * other {@code List} of an entity refused with {@code EXT-GEN-3003}.
 */
@DisplayName("Inverse collections: held by the target's rows, never by the owner's")
class InverseCollectionEmissionTest {

    private static final String PACKAGE = "com.shop.domain";
    private static final String LINES_TYPE = "java.util.List<com.shop.domain.InvoiceLine>";
    private static final String INVOICE_LINES_FIELD = "com.shop.domain.Invoice.lines : " + LINES_TYPE + " (";

    private static RelationshipMetadata lines(RelationType type, String mappedBy, CascadeType cascade) {
        return RelationshipMetadata.builder("lines", "InvoiceLine").type(type).mappedBy(mappedBy)
                .cascade(cascade).build();
    }

    private static RelationshipMetadata inverse() {
        return lines(RelationType.ONE_TO_MANY, "invoiceId", CascadeType.REMOVE);
    }

    private static DomainMetadata invoice(String linesType, boolean withTags, RelationshipMetadata... relationships) {
        List<FieldMetadata> fields = new ArrayList<>(List.of(
                FieldMetadata.builder("number", "String").filterable(true).build(),
                FieldMetadata.builder("lines", linesType).filterable(true).sortable(true).searchable(true).build()));
        if (withTags) {
            fields.add(FieldMetadata.builder("tags", "java.util.List<String>").build());
        }
        return DomainMetadata.builder("Invoice", PACKAGE).path("/invoices")
                .fields(fields)
                .relationships(List.of(relationships))
                .build();
    }

    private static DomainMetadata invoiceLine() {
        return DomainMetadata.builder("InvoiceLine", PACKAGE).path("/invoice-lines")
                .fields(List.of(FieldMetadata.builder("description", "String").build(),
                        FieldMetadata.builder("invoiceId", "java.util.UUID").build()))
                .relationships(List.of(RelationshipMetadata.builder("invoiceId", "Invoice")
                        .type(RelationType.MANY_TO_ONE).build()))
                .build();
    }

    private static List<DomainMetadata> build(DomainMetadata invoice) {
        return List.of(invoice, invoiceLine());
    }

    @Test
    @DisplayName("the migration has no column and no index for the inverse, and keeps a List of values")
    void migration() {
        String sql = new KernelFlywayGenerator().generate(invoice(LINES_TYPE, true, inverse())).content();

        assertThat(sql).doesNotContain("lines").contains("tags TEXT").contains("number VARCHAR(255)");
    }

    @Test
    @DisplayName("the repository neither binds nor reads the inverse, and needs no JSON for it alone")
    void repository() {
        DomainMetadata withTags = invoice(LINES_TYPE, true, inverse());
        String repository = new KernelRepositoryGenerator().generate(withTags, build(withTags)).content();
        assertThat(repository).doesNotContain("lines").doesNotContain("Lines")
                .contains("entity.setTags(parseList(").contains("tools.jackson");

        DomainMetadata linesOnly = invoice(LINES_TYPE, false, inverse());
        assertThat(KernelRepositoryGenerator.importsJackson(linesOnly)).isFalse();
        assertThat(new KernelRepositoryGenerator().generate(linesOnly, build(linesOnly)).content())
                .doesNotContain("tools.jackson").doesNotContain("parseList");
    }

    @Test
    @DisplayName("create and update reset the inverse to null after decoding")
    void handlerResets() {
        DomainMetadata invoice = invoice(LINES_TYPE, false, inverse());
        String handler = new KernelHandlerGenerator().generate(invoice, build(invoice)).content();

        assertThat(handler.split("entity\\.setLines\\(null\\);", -1)).hasSize(3);
    }

    @Test
    @DisplayName("the inverse's cascadeDelete sets ON DELETE CASCADE on the owning foreign key")
    void cascadeFromTheInverseSide() {
        DomainMetadata cascading = invoice(LINES_TYPE, false, inverse());
        DomainMetadata restricting = invoice(LINES_TYPE, false,
                lines(RelationType.ONE_TO_MANY, "invoiceId", CascadeType.NONE));
        KernelApplicationGenerator generator = new KernelApplicationGenerator();

        assertThat(generator.generateForeignKeys(build(cascading)).content())
                .contains("ALTER TABLE invoice_lines ADD CONSTRAINT fk_invoice_lines_invoice_id FOREIGN KEY"
                        + " (invoice_id) REFERENCES invoices(id) ON DELETE CASCADE;");
        assertThat(generator.generateForeignKeys(List.of(invoiceLine(), cascading)).content())
                .isEqualTo(generator.generateForeignKeys(build(cascading)).content());
        assertThat(generator.generateForeignKeys(build(restricting)).content())
                .contains("REFERENCES invoices(id) ON DELETE RESTRICT;");
    }

    @Test
    @DisplayName("EXT-GEN-3003: a List of an entity that is no inverse collection is refused, with both remedies")
    void entityCollectionRefused() {
        String reason = "a collection of the entity InvoiceLine, which no column stores; read it from"
                + " InvoiceLine's rows with @Relationship(targetEntity = InvoiceLine.class, relationshipType ="
                + " ONE_TO_MANY, mappedBy = \"invoiceId\"), or hold their keys: List<UUID> invoiceLineIds)";
        List<DomainMetadata> shapes = List.of(
                invoice(LINES_TYPE, false),
                invoice(LINES_TYPE, false, lines(RelationType.MANY_TO_MANY, null, CascadeType.NONE)),
                invoice(LINES_TYPE, false, lines(RelationType.ONE_TO_MANY, null, CascadeType.NONE)),
                invoice(LINES_TYPE, false, lines(RelationType.MANY_TO_ONE, null, CascadeType.NONE)));
        for (DomainMetadata invoice : shapes) {
            assertRefused(build(invoice), INVOICE_LINES_FIELD + reason);
        }
    }

    @Test
    @DisplayName("EXT-GEN-3003: an inverse whose mappedBy, target or element does not resolve is refused")
    void unresolvedInverseRefused() {
        assertRefused(build(invoice(LINES_TYPE, false, lines(RelationType.ONE_TO_MANY, "order", CascadeType.NONE))),
                INVOICE_LINES_FIELD + "mappedBy \"order\" names no MANY_TO_ONE of InvoiceLine that references"
                        + " Invoice; declare @Relationship(targetEntity = Invoice.class) UUID invoiceId on InvoiceLine)");
        assertRefused(List.of(invoice(LINES_TYPE, false, inverse())),
                INVOICE_LINES_FIELD + "the target InvoiceLine is not an entity of this build, so no row of it can"
                        + " be read)");
        assertRefused(build(invoice("java.util.List<String>", false, inverse())),
                "com.shop.domain.Invoice.lines : java.util.List<String> (a mappedBy collection is a List of its"
                        + " targetEntity: List<InvoiceLine>)");
    }

    @Test
    @DisplayName("a MANY_TO_MANY held as List<UUID> keys stays a column and is not refused")
    void keyListAccepted() {
        DomainMetadata invoice = invoice("java.util.List<java.util.UUID>", false,
                lines(RelationType.MANY_TO_MANY, null, CascadeType.REMOVE));

        KernelRepositoryGenerator.requirePersistableFields(build(invoice));
        assertThat(new KernelFlywayGenerator().generate(invoice).content()).contains("lines TEXT");
    }

    private static void assertRefused(List<DomainMetadata> build, String expected) {
        assertThatThrownBy(() -> KernelRepositoryGenerator.requirePersistableFields(build))
                .isInstanceOfSatisfying(UnpersistableFieldTypeException.class,
                        e -> assertThat(e.fields()).containsExactly(expected))
                .hasMessageStartingWith("[Exeris] EXT-GEN-3003: ");
    }
}
