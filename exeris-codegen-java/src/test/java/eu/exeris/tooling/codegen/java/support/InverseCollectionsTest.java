package eu.exeris.tooling.codegen.java.support;

import eu.exeris.sdk.sourcemodel.ast.BlobMetadata;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.FieldMetadata;
import eu.exeris.sdk.sourcemodel.ast.RelationshipMetadata;
import eu.exeris.sdk.sourcemodel.ast.RelationshipMetadata.CascadeType;
import eu.exeris.sdk.sourcemodel.ast.RelationshipMetadata.RelationType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("InverseCollections: a mappedBy collection is held by the target's rows")
class InverseCollectionsTest {

    private static final String PACKAGE = "com.shop.domain";
    private static final String LINES_TYPE = "java.util.List<com.shop.domain.InvoiceLine>";

    private static FieldMetadata lines() {
        return FieldMetadata.builder("lines", LINES_TYPE).filterable(true).sortable(true).build();
    }

    private static RelationshipMetadata inverse(String mappedBy, CascadeType cascade) {
        return RelationshipMetadata.builder("lines", "InvoiceLine").type(RelationType.ONE_TO_MANY)
                .mappedBy(mappedBy).cascade(cascade).build();
    }

    private static DomainMetadata invoice(RelationshipMetadata... relationships) {
        return DomainMetadata.builder("Invoice", PACKAGE)
                .fields(List.of(FieldMetadata.builder("number", "String").build(), lines(),
                        FieldMetadata.builder("tags", "java.util.List<String>").build()))
                .relationships(List.of(relationships))
                .build();
    }

    private static DomainMetadata invoiceLine(RelationshipMetadata backReference) {
        return DomainMetadata.builder("InvoiceLine", PACKAGE)
                .fields(List.of(FieldMetadata.builder("description", "String").build(),
                        FieldMetadata.builder(backReference.fieldName(), "java.util.UUID").build()))
                .relationships(List.of(backReference))
                .build();
    }

    private static RelationshipMetadata toInvoice(String name) {
        return RelationshipMetadata.builder(name, "Invoice").type(RelationType.MANY_TO_ONE).build();
    }

    private static FieldMetadata field(DomainMetadata metadata, String name) {
        return metadata.fields().stream().filter(f -> f.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("a List field is an inverse only under a ONE_TO_MANY that names its mappedBy")
    void classification() {
        DomainMetadata withInverse = invoice(inverse("invoiceId", CascadeType.NONE));
        assertThat(InverseCollections.isInverse(withInverse, field(withInverse, "lines"))).isTrue();
        assertThat(InverseCollections.isInverse(withInverse, field(withInverse, "tags"))).isFalse();
        assertThat(InverseCollections.of(withInverse)).extracting(FieldMetadata::name).containsExactly("lines");

        DomainMetadata undeclared = invoice();
        DomainMetadata withoutMappedBy = invoice(inverse(" ", CascadeType.NONE));
        DomainMetadata manyToMany = invoice(RelationshipMetadata.builder("lines", "InvoiceLine")
                .type(RelationType.MANY_TO_MANY).mappedBy("invoiceId").build());
        for (DomainMetadata metadata : List.of(undeclared, withoutMappedBy, manyToMany)) {
            assertThat(InverseCollections.isInverse(metadata, field(metadata, "lines"))).isFalse();
            assertThat(InverseCollections.of(metadata)).isEmpty();
        }
    }

    @Test
    @DisplayName("an inverse and a @Blob field are no column; a List of plain values is one")
    void storedFields() {
        DomainMetadata metadata = invoice(inverse("invoiceId", CascadeType.NONE));
        assertThat(StoredFields.isColumn(metadata, field(metadata, "lines"))).isFalse();
        assertThat(StoredFields.isColumn(metadata, field(metadata, "tags"))).isTrue();
        assertThat(StoredFields.isColumn(metadata, field(metadata, "number"))).isTrue();
        FieldMetadata blob = FieldMetadata.builder("scan", "byte[]").blob(new BlobMetadata(null, List.of()))
                .build();
        assertThat(StoredFields.isColumn(metadata, blob)).isFalse();
    }

    @ParameterizedTest(name = "mappedBy \"{2}\" names the back-reference {0} (field {1})")
    @CsvSource({
            "invoiceId, invoiceId,  invoiceId",
            "invoiceId, invoiceId,  invoice",
            "invoice,   invoiceKey, invoiceKey",
            "invoice,   invoiceKey, invoiceId",
            "invoice,   invoiceKey, invoice",
    })
    @DisplayName("backReference resolves mappedBy by name, field name, <base> and <base>Id")
    void backReferenceSpellings(String name, String fieldName, String mappedBy) {
        RelationshipMetadata back = RelationshipMetadata.builder(name, "Invoice").fieldName(fieldName)
                .type(RelationType.MANY_TO_ONE).build();
        DomainMetadata owner = invoice(inverse(mappedBy, CascadeType.NONE));
        List<DomainMetadata> build = List.of(owner, invoiceLine(back));

        assertThat(ForeignKeys.backReference(owner, owner.relationships().getFirst(), build)).contains(back);
    }

    @Test
    @DisplayName("backReference is empty for a mappedBy no MANY_TO_ONE back to the owner answers to")
    void backReferenceUnresolved() {
        DomainMetadata owner = invoice(inverse("order", CascadeType.NONE));
        RelationshipMetadata toOther = RelationshipMetadata.builder("invoiceId", "Customer")
                .type(RelationType.MANY_TO_ONE).build();

        assertThat(ForeignKeys.backReference(owner, owner.relationships().getFirst(),
                List.of(owner, invoiceLine(toInvoice("invoiceId"))))).isEmpty();
        DomainMetadata matchingName = invoice(inverse("invoiceId", CascadeType.NONE));
        assertThat(ForeignKeys.backReference(matchingName, matchingName.relationships().getFirst(),
                List.of(matchingName, invoiceLine(toOther)))).isEmpty();
        assertThat(ForeignKeys.backReference(matchingName, matchingName.relationships().getFirst(),
                List.of(matchingName))).isEmpty();
    }

    @Test
    @DisplayName("a cascadeDelete on the inverse cascades the owning constraint, whatever the build's order")
    void inverseCascadeReachesTheOwningConstraint() {
        DomainMetadata owner = invoice(inverse("invoiceId", CascadeType.REMOVE));
        DomainMetadata child = invoiceLine(toInvoice("invoiceId"));

        assertThat(ForeignKeys.outgoing(child, List.of(owner, child)))
                .singleElement()
                .satisfies(reference -> {
                    assertThat(reference.constraint()).isEqualTo("fk_invoice_lines_invoice_id");
                    assertThat(reference.cascadeDelete()).isTrue();
                });
        assertThat(ForeignKeys.outgoing(child, List.of(child, owner)))
                .isEqualTo(ForeignKeys.outgoing(child, List.of(owner, child)));
    }

    @Test
    @DisplayName("the owning side's cascade still cascades; no cascade, or an unresolved inverse, restricts")
    void otherCascades() {
        DomainMetadata ownerWithout = invoice(inverse("invoiceId", CascadeType.NONE));
        DomainMetadata cascadingChild = invoiceLine(RelationshipMetadata.builder("invoiceId", "Invoice")
                .type(RelationType.MANY_TO_ONE).cascade(CascadeType.ALL).build());
        DomainMetadata child = invoiceLine(toInvoice("invoiceId"));
        DomainMetadata ownerUnresolved = invoice(inverse("order", CascadeType.ALL));

        assertThat(ForeignKeys.outgoing(cascadingChild, List.of(ownerWithout, cascadingChild)))
                .singleElement().extracting(ForeignKeys.Reference::cascadeDelete).isEqualTo(true);
        assertThat(ForeignKeys.outgoing(child, List.of(ownerWithout, child)))
                .singleElement().extracting(ForeignKeys.Reference::cascadeDelete).isEqualTo(false);
        assertThat(ForeignKeys.outgoing(child, List.of(ownerUnresolved, child)))
                .singleElement().extracting(ForeignKeys.Reference::cascadeDelete).isEqualTo(false);
    }

    @Test
    @DisplayName("an inverse is in neither body, reset to null after the @Blob fields, and no filter or sort key")
    void bodiesResetsAndListQuery() {
        FieldMetadata scan = FieldMetadata.builder("scan", "byte[]").blob(new BlobMetadata(null, List.of()))
                .build();
        DomainMetadata metadata = DomainMetadata.builder("Invoice", PACKAGE)
                .fields(List.of(lines(), FieldMetadata.builder("number", "String").filterable(true).build(), scan))
                .relationships(List.of(inverse("invoiceId", CascadeType.NONE)))
                .build();

        assertThat(ServerOwnedFields.notInCreateBody(metadata)).contains("lines", "scan").doesNotContain("number");
        assertThat(ServerOwnedFields.notInUpdateBody(metadata)).contains("lines", "scan").doesNotContain("number");
        assertThat(ServerOwnedFields.resetOnUpdate(metadata)).containsExactly(
                new ServerOwnedFields.Reset("scan", "null"), new ServerOwnedFields.Reset("lines", "null"));
        assertThat(ServerOwnedFields.resetOnCreate(metadata)).endsWith(
                new ServerOwnedFields.Reset("scan", "null"), new ServerOwnedFields.Reset("lines", "null"));
        assertThat(ServerOwnedFields.keptOnUpdate(metadata)).doesNotContain("lines");
        assertThat(ListQuerySupport.filters(metadata)).extracting(ListQuerySupport.Property::name)
                .doesNotContain("lines");
        assertThat(ListQuerySupport.sortable(metadata)).extracting(ListQuerySupport.Property::name)
                .doesNotContain("lines");
    }
}
