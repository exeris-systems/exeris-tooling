package eu.exeris.tooling.codegen.java.kernel;

import eu.exeris.sdk.sourcemodel.ast.DomainEventMetadata;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.FieldMetadata;
import eu.exeris.sdk.sourcemodel.ast.GraphMetadata;
import eu.exeris.sdk.sourcemodel.ast.RelationshipMetadata;
import eu.exeris.sdk.sourcemodel.ast.SystemFieldsMetadata;
import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator.ArtifactType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An entity whose key {@code primaryKeyField} renames: every generator identifies the row by
 * that field, and the route variable and the {@code findById} / {@code UUID id} names stay (ADR-104).
 */
@DisplayName("ADR-104: a renamed primary key reaches every generated artefact")
class RenamedPrimaryKeyTest {

    private static final String PACKAGE = "com.example.domain";
    private static final String DEFAULT_GETTER = "getId()";

    private static final DomainMetadata INVOICE = DomainMetadata.builder("Invoice", PACKAGE)
            .path("/invoices")
            .module("billing")
            .systemFields(SystemFieldsMetadata.builder().primaryKeyField("invoiceNo").build())
            .fields(List.of(
                    FieldMetadata.simple("invoiceNo", "java.util.UUID"),
                    FieldMetadata.builder("customer", "String").required(true).sortable(true).filterable(true)
                            .build()))
            .events(List.of(DomainEventMetadata.builder("InvoiceIssued")
                    .trigger(DomainEventMetadata.Trigger.CREATE)
                    .build()))
            .graphMetadata(GraphMetadata.simple("Invoice"))
            .build();

    private static final DomainMetadata PAYMENT = DomainMetadata.builder("Payment", PACKAGE)
            .path("/payments")
            .module("billing")
            .fields(List.of(FieldMetadata.builder("amount", "BigDecimal").build()))
            .relationships(List.of(RelationshipMetadata.builder("invoice", "Invoice")
                    .type(RelationshipMetadata.RelationType.MANY_TO_ONE)
                    .build()))
            .build();

    private final KernelGeneratorStrategy strategy = new KernelGeneratorStrategy();

    private String artifact(ArtifactType type) {
        return strategy.generate(INVOICE).stream()
                .filter(f -> f.artifactType() == type)
                .findFirst().orElseThrow().content();
    }

    @Test
    @DisplayName("the migration's key column is invoice_no, and no id column is emitted")
    void migrationKeysByTheRenamedColumn() {
        String migration = artifact(ArtifactType.CONFIGURATION);

        assertThat(migration)
                .contains("    invoice_no UUID PRIMARY KEY DEFAULT gen_random_uuid()")
                .doesNotContain(" id UUID")
                .containsOnlyOnce("invoice_no");
    }

    @Test
    @DisplayName("the repository selects, updates, deletes and orders by invoice_no, and saves through the key's accessors")
    void repositoryIdentifiesTheRowByTheRenamedKey() {
        String repository = artifact(ArtifactType.REPOSITORY);

        assertThat(repository)
                .contains("WHERE invoice_no = ?")
                .doesNotContain("WHERE id = ?")
                .contains("\" ORDER BY invoice_no\"")
                .contains("\" DESC, invoice_no\"")
                .contains("\" ASC, invoice_no\"")
                .contains("entity.getInvoiceNo()")
                .contains("entity.setInvoiceNo(")
                .doesNotContain(DEFAULT_GETTER)
                .doesNotContain("setId(")
                // The role names stay: they name the operation, not the field.
                .contains("findById(UUID id)")
                .contains("deleteById(UUID id)");
    }

    @Test
    @DisplayName("the handler and the graph sync read the key through getInvoiceNo; the route variable stays {id}")
    void handlerAndGraphSyncReadTheRenamedKey() {
        assertThat(artifact(ArtifactType.CONTROLLER))
                .contains("saved.getInvoiceNo()")
                .doesNotContain(DEFAULT_GETTER);
        assertThat(artifact(ArtifactType.GRAPH_SYNC))
                .contains("entity.getInvoiceNo()")
                .doesNotContain(DEFAULT_GETTER);
    }

    @Test
    @DisplayName("a MANY_TO_ONE into the entity references its key column; the referencing column keeps its name")
    void foreignKeyReferencesTheRenamedColumn() {
        GeneratedFile foreignKeys = new KernelApplicationGenerator().generateForeignKeys(List.of(INVOICE, PAYMENT));

        assertThat(foreignKeys).isNotNull();
        assertThat(foreignKeys.content())
                .contains("FOREIGN KEY (invoice_id) REFERENCES invoices(invoice_no)");
    }

    @Test
    @DisplayName("a MANY_TO_ONE into an entity that renames nothing references (id)")
    void foreignKeyIntoDefaultKeyStaysId() {
        DomainMetadata customer = DomainMetadata.builder("Customer", PACKAGE).path("/customers").build();
        DomainMetadata order = DomainMetadata.builder("Order", PACKAGE)
                .path("/orders")
                .relationships(List.of(RelationshipMetadata.builder("customer", "Customer")
                        .type(RelationshipMetadata.RelationType.MANY_TO_ONE)
                        .build()))
                .build();

        assertThat(new KernelApplicationGenerator().generateForeignKeys(List.of(customer, order)).content())
                .contains("REFERENCES customers(id)");
    }
}
