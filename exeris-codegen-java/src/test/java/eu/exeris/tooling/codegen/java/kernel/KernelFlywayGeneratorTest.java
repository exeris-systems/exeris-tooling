package eu.exeris.tooling.codegen.java.kernel;

import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator.ArtifactType;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.FieldMetadata;
import eu.exeris.sdk.sourcemodel.ast.RelationshipMetadata;
import eu.exeris.sdk.sourcemodel.ast.SystemFieldsMetadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Golden-snapshot tests for the SQL emission. Pins byte-equivalent output for
 * the two fixtures used in the Phase 5 (text-block refactor) review:
 * full-feature (tenantScoped + audited + softDelete + versioned + searchable/
 * unique mix) and minimal (no flags, single field). The generator emits a
 * {@code .sql} file, so there is no compile-time signal a future refactor
 * can lean on — the snapshot is the regression gate.
 */
@DisplayName("KernelFlywayGenerator SQL Snapshot")
class KernelFlywayGeneratorTest {

    private final KernelFlywayGenerator generator = new KernelFlywayGenerator();

    @Test
    @DisplayName("Full-feature fixture: tenant FK + audit + soft-delete + version + searchable/unique indexes + RLS")
    void fullFeatureFixture() {
        DomainMetadata metadata = DomainMetadata.builder("Order", "eu.exeris.app.domain")
                .path("/orders")
                .module("sales")
                .description("Order entity")
                .tenantScoped(true)
                .audited(true)
                .softDelete(true)
                .versioned(true)
                .fields(List.of(
                        FieldMetadata.builder("orderNumber", "String").required(true).unique(true).searchable(true).build(),
                        FieldMetadata.builder("customerName", "String").required(true).searchable(true).build(),
                        FieldMetadata.builder("amount", "BigDecimal").required(true).build(),
                        FieldMetadata.builder("placedAt", "Instant").build(),
                        FieldMetadata.builder("active", "Boolean").build(),
                        FieldMetadata.builder("quantity", "int").build()))
                .build();

        GeneratedFile file = generator.generate(metadata);

        assertThat(file.artifactType()).isEqualTo(ArtifactType.CONFIGURATION);
        assertThat(file.extension()).isEqualTo("sql");
        assertThat(file.packageName()).isEqualTo("db/migration");
        // Deterministic version (no wall-clock): tier 2 (tenant-scoped) + stable
        // FQN hash. Exact filename is pinned — a regression on the scheme shows here.
        assertThat(file.className()).isEqualTo("V2483095__create_orders");
        assertThat(file.content()).isEqualTo("""
                -- Flyway migration: Create Order table
                -- Generated from @ExerisDomain: eu.exeris.app.domain.Order

                CREATE TABLE IF NOT EXISTS orders (
                    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                    tenant_id UUID NOT NULL REFERENCES tenants(id),
                    order_number VARCHAR(255) NOT NULL UNIQUE,
                    customer_name VARCHAR(255) NOT NULL,
                    amount DECIMAL(19,4) NOT NULL,
                    placed_at TIMESTAMPTZ,
                    active BOOLEAN,
                    quantity INTEGER,
                    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
                    created_by VARCHAR(255),
                    updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
                    updated_by VARCHAR(255),
                    deleted BOOLEAN DEFAULT FALSE,
                    deleted_at TIMESTAMPTZ,
                    deleted_by VARCHAR(255),
                    version BIGINT DEFAULT 0
                );

                CREATE INDEX IF NOT EXISTS idx_orders_tenant ON orders(tenant_id);
                CREATE INDEX IF NOT EXISTS idx_orders_order_number ON orders(order_number);
                CREATE INDEX IF NOT EXISTS idx_orders_customer_name ON orders(customer_name);

                -- Row Level Security for tenant isolation
                ALTER TABLE orders ENABLE ROW LEVEL SECURITY;

                CREATE POLICY orders_tenant_policy ON orders
                    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
                    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
                """);
    }

    @Test
    @DisplayName("Minimal fixture: no flags, single field, no indexes, no RLS")
    void minimalFixture() {
        DomainMetadata metadata = DomainMetadata.builder("Tag", "eu.exeris.app.domain")
                .description("Tag entity")
                .fields(List.of(
                        FieldMetadata.builder("label", "String").build()))
                .build();

        GeneratedFile file = generator.generate(metadata);

        // Tier 1 (not tenant-scoped) → sorts before any tenant-scoped table.
        assertThat(file.className()).isEqualTo("V1024931__create_tags");
        // Trailing whitespace: the StringBuilder baseline emits `);\n\n` after
        // CREATE TABLE plus a `\n` from the (empty) indexes block — three
        // newlines after the closing `);`. Preserve that.
        assertThat(file.content()).isEqualTo("""
                -- Flyway migration: Create Tag table
                -- Generated from @ExerisDomain: eu.exeris.app.domain.Tag

                CREATE TABLE IF NOT EXISTS tags (
                    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                    label VARCHAR(255)
                );


                """);
    }

    @Test
    @DisplayName("Migration filename is deterministic — repeated generation is identical (no wall-clock)")
    void filenameIsDeterministic() {
        DomainMetadata metadata = DomainMetadata.builder("Order", "eu.exeris.app.domain")
                .tenantScoped(true)
                .fields(List.of(FieldMetadata.builder("orderNumber", "String").build()))
                .build();

        String first = generator.generate(metadata).className();
        String second = generator.generate(metadata).className();

        assertThat(first).isEqualTo(second);
        // No epoch-millis timestamp (13+ digits) — guards against regressing to System.currentTimeMillis().
        assertThat(first).doesNotMatch(".*\\d{13,}.*");
    }

    @Test
    @DisplayName("Tenant-scoped tables version above unscoped ones, so the referenced tenants table is created first")
    void tenantScopedSortsAfterReferencedTable() {
        DomainMetadata scoped = DomainMetadata.builder("Order", "eu.exeris.app.domain")
                .tenantScoped(true)
                .fields(List.of(FieldMetadata.builder("x", "String").build()))
                .build();
        DomainMetadata tenants = DomainMetadata.builder("Tenant", "eu.exeris.app.domain")
                .fields(List.of(FieldMetadata.builder("name", "String").build()))
                .build();

        long scopedVersion = versionNumber(generator.generate(scoped).className());
        long tenantsVersion = versionNumber(generator.generate(tenants).className());

        assertThat(tenantsVersion).isLessThan(scopedVersion);
    }

    @Test
    @DisplayName("The tenants table stays tier 1 even if its entity is (mistakenly) marked tenant-scoped")
    void tenantsTablePinnedToTierOne() {
        DomainMetadata tenants = DomainMetadata.builder("Tenant", "eu.exeris.app.domain")
                .tenantScoped(true) // user error: the FK target must still be created first
                .fields(List.of(FieldMetadata.builder("name", "String").build()))
                .build();

        long version = versionNumber(generator.generate(tenants).className());

        assertThat(version).isLessThan(2_000_000L); // tier 1
    }

    @Test
    @DisplayName("System-field overrides (T5): tenant/audit/soft-delete/version columns + RLS follow overridden names")
    void systemFieldOverridesFixture() {
        SystemFieldsMetadata sf = new SystemFieldsMetadata(
                "id", "createdAt", "createdBy",
                "modifiedAt", "updatedBy", "orgId",
                "rev", "deleted", null, null);

        DomainMetadata metadata = DomainMetadata.builder("Order", "eu.exeris.app.domain")
                .tenantScoped(true).audited(true).softDelete(true).versioned(true)
                .systemFields(sf)
                .fields(List.of(FieldMetadata.builder("orderNumber", "String").build()))
                .build();

        String sql = generator.generate(metadata).content();
        assertThat(sql)
                .contains("    org_id UUID NOT NULL REFERENCES tenants(id)")
                .contains("    modified_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP")
                .contains("    rev BIGINT DEFAULT 0")
                // createdAt / deleted left at defaults.
                .contains("    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP")
                .contains("    deleted BOOLEAN DEFAULT FALSE")
                // tenant index + RLS predicate use the overridden tenant column.
                .contains("CREATE INDEX IF NOT EXISTS idx_orders_tenant ON orders(org_id);")
                .contains("USING (org_id = current_setting('app.tenant_id', true)::uuid)")
                .contains("WITH CHECK (org_id = current_setting('app.tenant_id', true)::uuid)")
                // The default tenant_id *column* must NOT leak (the 'app.tenant_id'
                // GUC key is unrelated and legitimately retains the name).
                .doesNotContain("tenant_id UUID")
                .doesNotContain("(tenant_id ")
                .doesNotContain("(tenant_id)");
    }

    @Test
    @DisplayName("System-field overrides (T5): audit-by / soft-delete-timestamp / soft-deleted-by columns follow overrides")
    void systemFieldOverridesAuxiliaryColumnsFixture() {
        SystemFieldsMetadata sf = new SystemFieldsMetadata(
                "id", "createdAt", "authorId",
                "updatedAt", "editorId", "tenantId",
                "version", "deleted", "removedAt", "removedBy");

        DomainMetadata metadata = DomainMetadata.builder("Order", "eu.exeris.app.domain")
                .audited(true).softDelete(true)
                .systemFields(sf)
                .fields(List.of(FieldMetadata.builder("orderNumber", "String").build()))
                .build();

        String sql = generator.generate(metadata).content();
        assertThat(sql)
                .contains("    author_id VARCHAR(255)")
                .contains("    editor_id VARCHAR(255)")
                .contains("    removed_at TIMESTAMPTZ")
                .contains("    removed_by VARCHAR(255)")
                // the default audit-by / soft-delete-aux column names must not leak
                .doesNotContain("created_by VARCHAR(255)")
                .doesNotContain("updated_by VARCHAR(255)")
                .doesNotContain("deleted_at TIMESTAMPTZ")
                .doesNotContain("deleted_by VARCHAR(255)");
    }

    @Test
    @DisplayName("Table-name override (T6): CREATE TABLE + filename honour the explicit tableName")
    void tableNameOverrideFixture() {
        DomainMetadata metadata = DomainMetadata.builder("Order", "eu.exeris.app.domain")
                .tableName("legacy_orders")
                .fields(List.of(FieldMetadata.builder("orderNumber", "String").build()))
                .build();

        GeneratedFile file = generator.generate(metadata);
        assertThat(file.className()).endsWith("__create_legacy_orders");
        assertThat(file.content())
                .contains("CREATE TABLE IF NOT EXISTS legacy_orders (")
                .doesNotContain("CREATE TABLE IF NOT EXISTS orders (");
    }

    @Test
    @DisplayName("Table-name override (T6): a mixed-case override is lower-cased for cross-engine safety")
    void tableNameOverrideIsLowerCased() {
        DomainMetadata metadata = DomainMetadata.builder("Order", "eu.exeris.app.domain")
                .tableName("MyOrders")
                .fields(List.of(FieldMetadata.builder("orderNumber", "String").build()))
                .build();

        GeneratedFile file = generator.generate(metadata);
        assertThat(file.className()).endsWith("__create_myorders");
        assertThat(file.content())
                .contains("CREATE TABLE IF NOT EXISTS myorders (")
                .doesNotContain("MyOrders");
    }

    @Test
    @DisplayName("T8: filterable fields are indexed (joined with searchable/unique — one index per column)")
    void filterableFieldsAreIndexed() {
        DomainMetadata metadata = DomainMetadata.builder("Order", "eu.exeris.app.domain")
                .fields(List.of(
                        // searchable + filterable → still a single index.
                        FieldMetadata.builder("orderNumber", "String").searchable(true).filterable(true).build(),
                        FieldMetadata.builder("status", "String").filterable(true).build(),
                        // neither searchable/unique nor filterable → no index.
                        FieldMetadata.builder("note", "String").build()))
                .build();

        String sql = generator.generate(metadata).content();
        assertThat(sql)
                .contains("CREATE INDEX IF NOT EXISTS idx_orders_status ON orders(status);")
                // searchable+filterable column appears exactly once.
                .containsOnlyOnce("idx_orders_order_number")
                .doesNotContain("idx_orders_note");
    }

    @Test
    @DisplayName("T8: MANY_TO_ONE relationships get a plain UUID FK column + index (no REFERENCES — that is T9)")
    void manyToOneRelationshipsGetFkColumnAndIndex() {
        DomainMetadata metadata = DomainMetadata.builder("Order", "eu.exeris.app.domain")
                .fields(List.of(FieldMetadata.builder("orderNumber", "String").build()))
                .relationships(List.of(
                        RelationshipMetadata.builder("customer", "Customer")
                                .type(RelationshipMetadata.RelationType.MANY_TO_ONE).build(),
                        RelationshipMetadata.builder("warehouseId", "Warehouse")
                                .type(RelationshipMetadata.RelationType.MANY_TO_ONE).build(),
                        // ONE_TO_MANY → no FK column / index on this side.
                        RelationshipMetadata.builder("items", "OrderItem")
                                .type(RelationshipMetadata.RelationType.ONE_TO_MANY).build()))
                .build();

        String sql = generator.generate(metadata).content();
        assertThat(sql)
                // FK columns are created so the index targets a real column.
                .contains("    customer_id UUID")
                .contains("    warehouse_id UUID")
                // Explicit-UUID-FK name normalisation: warehouse_id, not warehouse_id_id.
                .doesNotContain("warehouse_id_id")
                // FK indexes.
                .contains("CREATE INDEX IF NOT EXISTS idx_orders_customer_id ON orders(customer_id);")
                .contains("CREATE INDEX IF NOT EXISTS idx_orders_warehouse_id ON orders(warehouse_id);")
                // No FK constraint (T9 is out of scope here).
                .doesNotContain("REFERENCES customers")
                .doesNotContain("REFERENCES warehouses")
                // ONE_TO_MANY side emits nothing.
                .doesNotContain("items");
    }

    @Test
    @DisplayName("T8: explicit-UUID-FK relationship does not duplicate the column already declared as a field")
    void explicitUuidFkDoesNotDuplicateFieldColumn() {
        DomainMetadata metadata = DomainMetadata.builder("Order", "eu.exeris.app.domain")
                .fields(List.of(
                        FieldMetadata.builder("orderNumber", "String").build(),
                        // The FK is ALSO a declared field (explicit-UUID-FK style).
                        FieldMetadata.builder("customerId", "UUID").build()))
                .relationships(List.of(
                        RelationshipMetadata.builder("customerId", "Customer")
                                .type(RelationshipMetadata.RelationType.MANY_TO_ONE).build()))
                .build();

        String sql = generator.generate(metadata).content();
        // The field already produced customer_id — the relationship must not add a second column.
        assertThat(sql)
                .containsOnlyOnce("customer_id UUID")
                .containsOnlyOnce("idx_orders_customer_id");
    }

    @Test
    @DisplayName("T8: index emission is deterministic — same metadata yields byte-identical SQL")
    void indexEmissionIsDeterministic() {
        DomainMetadata metadata = DomainMetadata.builder("Order", "eu.exeris.app.domain")
                .tenantScoped(true)
                .fields(List.of(
                        FieldMetadata.builder("status", "String").filterable(true).build(),
                        FieldMetadata.builder("amount", "BigDecimal").filterable(true).build()))
                .relationships(List.of(
                        RelationshipMetadata.builder("warehouseId", "Warehouse")
                                .type(RelationshipMetadata.RelationType.MANY_TO_ONE).build(),
                        RelationshipMetadata.builder("customer", "Customer")
                                .type(RelationshipMetadata.RelationType.MANY_TO_ONE).build()))
                .build();

        String first = generator.generate(metadata).content();
        String second = generator.generate(metadata).content();
        assertThat(second).isEqualTo(first);
        // FK indexes are sorted by relationship name (customer before warehouseId).
        assertThat(first.indexOf("idx_orders_customer_id"))
                .isLessThan(first.indexOf("idx_orders_warehouse_id"));
    }

    /** Extracts the numeric version from a {@code "V<n>__create_x"} filename. */
    private static long versionNumber(String className) {
        return Long.parseLong(className.substring(1, className.indexOf("__")));
    }
}
