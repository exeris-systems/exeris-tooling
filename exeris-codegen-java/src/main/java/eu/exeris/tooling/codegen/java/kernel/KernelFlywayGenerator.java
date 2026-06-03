package eu.exeris.tooling.codegen.java.kernel;

import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator;
import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator.ArtifactType;
import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.FieldMetadata;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Generates Flyway SQL migration scripts from {@code @ExerisDomain} metadata.
 *
 * <p>Produces, for each domain entity:
 * <ul>
 *   <li>{@code CREATE TABLE} with id + per-domain columns + system columns
 *       (tenant, audit, soft-delete, version) gated by metadata flags</li>
 *   <li>Indexes for {@code searchable}/{@code unique} fields and the tenant FK</li>
 *   <li>Row-Level-Security policy for tenant isolation (when {@code tenantScoped})</li>
 * </ul>
 *
 * @implNote Emission uses Java text blocks + {@link String#join} over a list
 * of column definitions (ADR-015 — JavaPoet does not apply to non-Java
 * artifacts). The {@code KernelFlywayGeneratorTest} golden snapshots are the
 * regression gate.
 */
public class KernelFlywayGenerator implements KernelArtifactGenerator {

    private static final Set<String> SYSTEM_FIELDS = Set.of(
            "id", "tenantId", "tenant_id",
            "createdAt", "created_at", "createdBy", "created_by",
            "updatedAt", "updated_at", "updatedBy", "updated_by",
            "deleted", "deletedAt", "deleted_at", "deletedBy", "deleted_by",
            "version"
    );

    // Substituted with the table name via String.formatted. Safe because the
    // table name is a snake-cased Java identifier (no `%` possible); never
    // pass arbitrary external input through this template.
    private static final String RLS_TEMPLATE = """
            -- Row Level Security for tenant isolation
            ALTER TABLE %1$s ENABLE ROW LEVEL SECURITY;

            CREATE POLICY %1$s_tenant_policy ON %1$s
                USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
                WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
            """;

    @Override
    public GeneratedFile generate(DomainMetadata metadata) {
        String tableName = toSnakeCase(metadata.entityName()) + "s";
        String version = migrationVersion(metadata) + "__create_" + tableName;

        String header = """
                -- Flyway migration: Create %s table
                -- Generated from @ExerisDomain: %s

                """.formatted(metadata.entityName(), metadata.fullyQualifiedName());

        String createTable = "CREATE TABLE IF NOT EXISTS " + tableName + " (\n"
                + String.join(",\n", buildColumns(metadata))
                + "\n);\n\n";

        String indexes = String.join("", buildIndexes(metadata, tableName)) + "\n";

        String rls = metadata.tenantScoped() ? RLS_TEMPLATE.formatted(tableName) : "";

        String sql = header + createTable + indexes + rls;
        return new GeneratedFile("db/migration", version, sql, ArtifactType.CONFIGURATION, "sql");
    }

    /**
     * Deterministic Flyway version — no wall-clock (hard-constraint #3): same
     * {@code DomainMetadata} → same filename. Tenant-scoped tables (which emit a
     * {@code REFERENCES tenants(id)} FK) tier above unscoped ones so the
     * {@code tenants} table is always created first; within a tier the order is a
     * stable hash of the fully-qualified name. The tenant FK is the only
     * cross-table reference this generator emits, so a two-tier scheme suffices.
     */
    private static String migrationVersion(DomainMetadata metadata) {
        long tier = metadata.tenantScoped() ? 2L : 1L;
        long discriminator = Math.floorMod(metadata.fullyQualifiedName().hashCode(), 1_000_000);
        return "V" + (tier * 1_000_000L + discriminator);
    }

    private List<String> buildColumns(DomainMetadata metadata) {
        List<String> columns = new ArrayList<>();
        columns.add("    id UUID PRIMARY KEY DEFAULT gen_random_uuid()");

        if (metadata.tenantScoped()) {
            columns.add("    tenant_id UUID NOT NULL REFERENCES tenants(id)");
        }

        for (FieldMetadata field : metadata.fields()) {
            if (isSystemField(field.name())) continue;
            columns.add("    " + toSnakeCase(field.name()) + " " + mapJavaTypeToSql(field.type())
                    + (field.required() ? " NOT NULL" : "")
                    + (field.unique() ? " UNIQUE" : ""));
        }

        if (metadata.audited()) {
            columns.add("    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP");
            columns.add("    created_by VARCHAR(255)");
            columns.add("    updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP");
            columns.add("    updated_by VARCHAR(255)");
        }
        if (metadata.softDelete()) {
            columns.add("    deleted BOOLEAN DEFAULT FALSE");
            columns.add("    deleted_at TIMESTAMPTZ");
            columns.add("    deleted_by VARCHAR(255)");
        }
        if (metadata.versioned()) {
            columns.add("    version BIGINT DEFAULT 0");
        }
        return columns;
    }

    private List<String> buildIndexes(DomainMetadata metadata, String tableName) {
        List<String> indexes = new ArrayList<>();
        if (metadata.tenantScoped()) {
            indexes.add("CREATE INDEX IF NOT EXISTS idx_" + tableName + "_tenant ON "
                    + tableName + "(tenant_id);\n");
        }
        for (FieldMetadata field : metadata.fields()) {
            if (isSystemField(field.name())) continue;
            if (field.searchable() || field.unique()) {
                String col = toSnakeCase(field.name());
                indexes.add("CREATE INDEX IF NOT EXISTS idx_" + tableName + "_" + col + " ON "
                        + tableName + "(" + col + ");\n");
            }
        }
        return indexes;
    }

    private boolean isSystemField(String fieldName) {
        return SYSTEM_FIELDS.contains(fieldName) || SYSTEM_FIELDS.contains(toSnakeCase(fieldName));
    }

    private String toSnakeCase(String camelCase) {
        return camelCase.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase();
    }

    private String mapJavaTypeToSql(String javaType) {
        if (javaType == null) return "VARCHAR(255)";
        String simpleType = javaType.contains(".")
                ? javaType.substring(javaType.lastIndexOf('.') + 1)
                : javaType;

        return switch (simpleType) {
            case "String" -> "VARCHAR(255)";
            case "UUID" -> "UUID";
            case "Long", "long" -> "BIGINT";
            case "Integer", "int" -> "INTEGER";
            case "BigDecimal" -> "DECIMAL(19,4)";
            case "Boolean", "boolean" -> "BOOLEAN";
            case "Instant", "LocalDateTime", "OffsetDateTime", "ZonedDateTime" -> "TIMESTAMPTZ";
            case "LocalDate" -> "DATE";
            case "LocalTime" -> "TIME";
            default -> "VARCHAR(255)";
        };
    }

    @Override
    public ArtifactType artifactType() { return ArtifactType.CONFIGURATION; }
}
