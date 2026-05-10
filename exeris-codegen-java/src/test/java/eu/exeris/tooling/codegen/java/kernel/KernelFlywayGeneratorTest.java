package eu.exeris.tooling.codegen.java.kernel;

import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator.ArtifactType;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.FieldMetadata;
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
        assertThat(file.className()).matches("V\\d+__create_orders");
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

        assertThat(file.className()).matches("V\\d+__create_tags");
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
}
