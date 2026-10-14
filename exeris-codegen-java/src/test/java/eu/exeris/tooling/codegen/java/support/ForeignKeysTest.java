package eu.exeris.tooling.codegen.java.support;

import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.RelationshipMetadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ForeignKeys: the constraints a build declares")
class ForeignKeysTest {

    private static final String PACKAGE = "com.example.domain";

    private static final DomainMetadata CUSTOMER = DomainMetadata.builder("Customer", PACKAGE).build();

    private static final DomainMetadata CATEGORY = DomainMetadata.builder("Category", PACKAGE)
            .tableName("Product_Categories")
            .relationships(List.of(relationship("parentId", "Category", RelationshipMetadata.RelationType.MANY_TO_ONE)))
            .build();

    private static final DomainMetadata ORDER = DomainMetadata.builder("Order", PACKAGE)
            .relationships(List.of(
                    relationship("ledger", "Ledger", RelationshipMetadata.RelationType.MANY_TO_ONE),
                    relationship("lines", "OrderLine", RelationshipMetadata.RelationType.ONE_TO_MANY),
                    relationship("customer", "Customer", RelationshipMetadata.RelationType.MANY_TO_ONE)))
            .build();

    private static final DomainMetadata ARCHIVE = DomainMetadata.builder("Archive", PACKAGE)
            .softDelete(true).build();

    private static final DomainMetadata NOTE = DomainMetadata.builder("Note", PACKAGE)
            .relationships(List.of(relationship("archive", "Archive", RelationshipMetadata.RelationType.MANY_TO_ONE)))
            .build();

    private static final List<DomainMetadata> BUILD = List.of(ARCHIVE, CATEGORY, CUSTOMER, NOTE, ORDER);

    private static RelationshipMetadata relationship(String name, String target,
                                                     RelationshipMetadata.RelationType type) {
        return RelationshipMetadata.builder(name, target).type(type).build();
    }

    @Test
    @DisplayName("only MANY_TO_ONE relationships to an entity of the build, named fk_<table>_<column>")
    void outgoing() {
        assertThat(ForeignKeys.outgoing(ORDER, BUILD))
                .singleElement()
                .satisfies(reference -> {
                    assertThat(reference.relation()).isEqualTo("customer");
                    assertThat(reference.targetEntity()).isEqualTo("Customer");
                    assertThat(reference.table()).isEqualTo("orders");
                    assertThat(reference.column()).isEqualTo("customer_id");
                    assertThat(reference.constraint()).isEqualTo("fk_orders_customer_id");
                });
        assertThat(ForeignKeys.outgoing(CATEGORY, BUILD))
                .singleElement()
                .extracting(ForeignKeys.Reference::constraint)
                .isEqualTo("fk_product_categories_parent_id");
    }

    @Test
    @DisplayName("a self reference makes an entity both referencing and referenced")
    void selfReference() {
        assertThat(ForeignKeys.refusesMissingReference(CATEGORY, List.of(CATEGORY))).isTrue();
        assertThat(ForeignKeys.refusesReferencedDelete(CATEGORY, List.of(CATEGORY))).isTrue();
    }

    @Test
    @DisplayName("a delete is refusable only when the row is referenced and the delete removes it")
    void referencedDelete() {
        assertThat(ForeignKeys.refusesReferencedDelete(CUSTOMER, BUILD)).isTrue();
        assertThat(ForeignKeys.referenced(ARCHIVE, BUILD)).isTrue();
        assertThat(ForeignKeys.refusesReferencedDelete(ARCHIVE, BUILD)).isFalse();
        assertThat(ForeignKeys.refusesReferencedDelete(ORDER, BUILD)).isFalse();
        assertThat(ForeignKeys.refusesReferencedDelete(CUSTOMER, List.of(CUSTOMER))).isFalse();
    }

    @Test
    @DisplayName("ColumnNaming.table lower-cases and trims an override, else takes the derived name")
    void table() {
        assertThat(ColumnNaming.table(CATEGORY)).isEqualTo("product_categories");
        assertThat(ColumnNaming.table(CUSTOMER)).isEqualTo("customers");
    }
}
