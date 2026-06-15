package eu.exeris.tooling.codegen.java.kernel;

import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator.ArtifactType;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.FieldMetadata;
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

    /** Extracts the numeric version from a {@code "V<n>__create_x"} filename. */
    private static long versionNumber(String className) {
        return Long.parseLong(className.substring(1, className.indexOf("__")));
    }
}
