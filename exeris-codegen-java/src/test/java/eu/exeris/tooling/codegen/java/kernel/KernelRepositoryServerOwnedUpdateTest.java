package eu.exeris.tooling.codegen.java.kernel;

import eu.exeris.sdk.sourcemodel.ast.DataScope;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.FieldMetadata;
import eu.exeris.sdk.sourcemodel.ast.SystemFieldsMetadata;
import eu.exeris.tooling.codegen.java.kernel.KernelRepositoryGenerator.Column;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The update keeps every server-owned column: its SET list carries the domain columns and the two
 * the server sets (the audit update stamp and the version), and the columns it keeps are read back
 * onto the returned entity in the same transaction.
 */
@DisplayName("KernelRepositoryGenerator — update keeps server-owned columns")
class KernelRepositoryServerOwnedUpdateTest {

    private static final FieldMetadata ORDER_NUMBER = FieldMetadata.builder("orderNumber", "String").build();

    private static String repository(DomainMetadata metadata) {
        return new KernelRepositoryGenerator().generate(metadata).content();
    }

    private static DomainMetadata.Builder order() {
        return DomainMetadata.builder("Order", "com.example.domain").path("/orders");
    }

    private static List<String> columns(List<Column> columns) {
        return columns.stream().map(Column::sqlName).toList();
    }

    @Test
    @DisplayName("audited: created_at leaves the SET list and is read back; updated_at stays server-set")
    void auditedKeepsCreatedAt() {
        DomainMetadata metadata = order().audited(true).fields(List.of(ORDER_NUMBER)).build();

        assertThat(repository(metadata))
                .contains("UPDATE orders SET order_number = ?, updated_at = ? WHERE id = ?")
                .contains("entity.setUpdatedAt(Instant.now());")
                .contains("SELECT created_at FROM orders WHERE id = ?")
                .contains("entity.setCreatedAt(row.getInstant(0));")
                .contains("readStoredColumns(conn, id, entity);");
        assertThat(columns(KernelRepositoryGenerator.updateColumns(metadata)))
                .containsExactly("order_number", "updated_at");
        assertThat(columns(KernelRepositoryGenerator.storedColumns(metadata))).containsExactly("created_at");
    }

    @Test
    @DisplayName("versioned: the version stays in the SET list and the WHERE clause; nothing is read back")
    void versionedWritesTheVersion() {
        DomainMetadata metadata = order().versioned(true).fields(List.of(ORDER_NUMBER)).build();

        assertThat(repository(metadata))
                .contains("UPDATE orders SET order_number = ?, version = ? WHERE id = ? AND version = ?")
                .doesNotContain("readStoredColumns");
        assertThat(KernelRepositoryGenerator.storedColumns(metadata)).isEmpty();
    }

    @Test
    @DisplayName("soft-delete: the marker leaves the SET list, and a tombstoned row is not updated")
    void softDeleteKeepsTheMarker() {
        DomainMetadata metadata = order().softDelete(true).fields(List.of(ORDER_NUMBER)).build();

        assertThat(repository(metadata))
                .contains("UPDATE orders SET order_number = ? WHERE id = ? AND deleted = false")
                .contains("SELECT deleted FROM orders WHERE id = ?")
                .contains("entity.setDeleted(row.getBoolean(0));")
                .doesNotContain("deleted = ?");
    }

    @Test
    @DisplayName("tenant: the owner is read back, so the returned entity names the stored owner")
    void tenantReadsBackTheOwner() {
        DomainMetadata metadata = order().tenantScoped(true).fields(List.of(ORDER_NUMBER)).build();

        assertThat(repository(metadata))
                .contains("UPDATE orders SET order_number = ? WHERE id = ?")
                .contains("SELECT tenant_id FROM orders WHERE id = ?")
                .contains("entity.setTenantId(row.getUuid(0));");
    }

    @Test
    @DisplayName("every flag at once: one SET list, one guard per flag, one read-back in layout order")
    void fullMatrix() {
        DomainMetadata metadata = order().tenantScoped(true).audited(true).softDelete(true).versioned(true)
                .fields(List.of(ORDER_NUMBER)).build();

        String repo = repository(metadata);
        assertThat(repo)
                .contains("UPDATE orders SET order_number = ?, updated_at = ?, version = ? "
                        + "WHERE id = ? AND version = ? AND deleted = false")
                .contains("SELECT tenant_id, created_at, deleted FROM orders WHERE id = ?")
                .containsSubsequence("stmt.bindUuid(3, id);", "stmt.bindLong(4, expectedVersion);")
                // A versioned update reports a vanished row the way its zero-row branch does.
                .containsSubsequence("private void readStoredColumns(PersistenceConnection conn, UUID id, "
                        + "Order entity)", "if (!qr.next())", "throw new OrderVersionConflictException(id);");
    }

    @Test
    @DisplayName("the read-back runs inside the update's transaction, only after a row matched")
    void readBackIsInsideTheTransaction() {
        String repo = repository(order().audited(true).fields(List.of(ORDER_NUMBER)).build());

        assertThat(repo.replaceAll("\\s+", " "))
                .contains("rowsAffected[0] = stmt.executeUpdate(); } "
                        + "if (rowsAffected[0] != 0L) { readStoredColumns(conn, id, entity); } } );")
                .contains("throw new OrderNotFoundException(id);");
    }

    @Test
    @DisplayName("domain fields in an audit or soft-delete role are kept, not written from the body")
    void roleFieldsAreKept() {
        DomainMetadata metadata = order().audited(true).softDelete(true)
                .fields(List.of(ORDER_NUMBER,
                        FieldMetadata.builder("createdBy", "String").build(),
                        FieldMetadata.builder("updatedBy", "String").build(),
                        FieldMetadata.builder("deletedAt", "java.time.Instant").build(),
                        FieldMetadata.builder("deletedBy", "String").build()))
                .build();

        assertThat(columns(KernelRepositoryGenerator.updateColumns(metadata)))
                .containsExactly("order_number", "updated_at");
        assertThat(columns(KernelRepositoryGenerator.storedColumns(metadata)))
                .containsExactly("created_by", "updated_by", "deleted_at", "deleted_by", "created_at", "deleted");
        assertThat(repository(metadata))
                .contains("UPDATE orders SET order_number = ?, updated_at = ? WHERE id = ? AND deleted = false")
                .contains("entity.setCreatedBy(row.getString(0));")
                .contains("entity.setDeletedAt(row.getInstant(2));");
    }

    @Test
    @DisplayName("declared role names are honoured: a renamed audit field is kept by its declared name")
    void declaredRoleNames() {
        DomainMetadata metadata = order().audited(true)
                .systemFields(SystemFieldsMetadata.builder()
                        .createdAtField("openedAt").updatedAtField("touchedAt").createdByField("author")
                        .build())
                .fields(List.of(ORDER_NUMBER, FieldMetadata.builder("author", "String").build()))
                .build();

        assertThat(repository(metadata))
                .contains("UPDATE orders SET order_number = ?, touched_at = ? WHERE id = ?")
                .contains("SELECT author, opened_at FROM orders WHERE id = ?");
    }

    @Test
    @DisplayName("a renamed key closes both statements, and is not read back")
    void renamedKey() {
        DomainMetadata metadata = DomainMetadata.builder("Invoice", "com.example.domain").path("/invoices")
                .audited(true)
                .systemFields(SystemFieldsMetadata.builder().primaryKeyField("invoiceNo").build())
                .fields(List.of(FieldMetadata.builder("invoiceNo", "java.util.UUID").build(),
                        FieldMetadata.builder("total", "int").build()))
                .build();

        assertThat(repository(metadata))
                .contains("UPDATE invoices SET total = ?, updated_at = ? WHERE invoice_no = ?")
                .contains("SELECT created_at FROM invoices WHERE invoice_no = ?")
                .contains("entity.setInvoiceNo(id);");
        assertThat(columns(KernelRepositoryGenerator.storedColumns(metadata))).containsExactly("created_at");
    }

    @Test
    @DisplayName("a UNIVERSE shared scope stays in the SET list (ADR-090 §3); the owner is read back")
    void sharedScopeStaysWritable() {
        DomainMetadata metadata = DomainMetadata.builder("Species", "com.example.domain").path("/species")
                .dataScope(DataScope.UNIVERSE)
                .systemFields(SystemFieldsMetadata.builder().sharedScopeField("worldId").build())
                .fields(List.of(FieldMetadata.builder("name", "String").build(),
                        FieldMetadata.builder("worldId", "java.util.UUID").build()))
                .build();

        assertThat(columns(KernelRepositoryGenerator.updateColumns(metadata))).containsExactly("name", "world_id");
        assertThat(columns(KernelRepositoryGenerator.storedColumns(metadata))).containsExactly("tenant_id");
    }

    @Test
    @DisplayName("an entity with no server-owned column but the key emits no read-back")
    void plainEntityReadsNothingBack() {
        String repo = repository(order().fields(List.of(ORDER_NUMBER)).build());

        assertThat(repo)
                .contains("UPDATE orders SET order_number = ? WHERE id = ?")
                .doesNotContain("readStoredColumns")
                .doesNotContain("PersistenceConnection");
    }
}
