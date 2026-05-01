package eu.exeris.tooling.codegen.java.kernel;

import eu.exeris.tooling.codegen.core.PluggableBackend;
import eu.exeris.tooling.codegen.core.generator.BackendGenerator;
import eu.exeris.tooling.codegen.core.generator.BackendGenerator.ArtifactType;
import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.FieldMetadata;

import java.util.Set;

/**
 * Generates Flyway SQL migration scripts from @ExerisDomain metadata.
 * Creates:
 * - CREATE TABLE with all fields
 * - RLS policies for tenant isolation (if tenantScoped)
 * - Indexes for searchable/filterable fields
 * - Soft delete, audit, version columns
 */
public class KernelFlywayGenerator implements BackendGenerator {

    // System fields that are handled separately (not from domain fields)
    private static final Set<String> SYSTEM_FIELDS = Set.of(
            "id", "tenantId", "tenant_id",
            "createdAt", "created_at", "createdBy", "created_by",
            "updatedAt", "updated_at", "updatedBy", "updated_by",
            "deleted", "deletedAt", "deleted_at", "deletedBy", "deleted_by",
            "version"
    );

    @Override
    public GeneratedFile generate(DomainMetadata metadata) {
        String tableName = toSnakeCase(metadata.entityName()) + "s";
        String version = "V" + System.currentTimeMillis() + "__create_" + tableName;

        StringBuilder sql = new StringBuilder();
        sql.append("-- Flyway migration: Create ").append(metadata.entityName()).append(" table\n");
        sql.append("-- Generated from @ExerisDomain: ").append(metadata.fullyQualifiedName()).append("\n\n");

        // CREATE TABLE
        sql.append("CREATE TABLE IF NOT EXISTS ").append(tableName).append(" (\n");
        sql.append("    id UUID PRIMARY KEY DEFAULT gen_random_uuid()");

        // Tenant ID (if tenantScoped) - add early for FK constraints
        if (metadata.tenantScoped()) {
            sql.append(",\n    tenant_id UUID NOT NULL REFERENCES tenants(id)");
        }

        // Domain fields from nested FieldMetadata (excluding system fields)
        for (var field : metadata.fields()) {
            String fieldName = field.name();

            // Skip system fields - they are handled separately
            if (isSystemField(fieldName)) {
                continue;
            }

            sql.append(",\n    ").append(toSnakeCase(fieldName)).append(" ");
            sql.append(mapJavaTypeToSql(field.type()));
            if (field.required()) sql.append(" NOT NULL");
            if (field.unique()) sql.append(" UNIQUE");
        }

        // Audit columns (if audited)
        if (metadata.audited()) {
            sql.append(",\n    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP");
            sql.append(",\n    created_by VARCHAR(255)");
            sql.append(",\n    updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP");
            sql.append(",\n    updated_by VARCHAR(255)");
        }

        // Soft delete (if softDelete)
        if (metadata.softDelete()) {
            sql.append(",\n    deleted BOOLEAN DEFAULT FALSE");
            sql.append(",\n    deleted_at TIMESTAMPTZ");
            sql.append(",\n    deleted_by VARCHAR(255)");
        }

        // Version (if versioned)
        if (metadata.versioned()) {
            sql.append(",\n    version BIGINT DEFAULT 0");
        }

        sql.append("\n);\n\n");

        // Indexes
        if (metadata.tenantScoped()) {
            sql.append("CREATE INDEX IF NOT EXISTS idx_").append(tableName).append("_tenant ON ")
               .append(tableName).append("(tenant_id);\n");
        }

        for (var field : metadata.fields()) {
            if (isSystemField(field.name())) continue;

            if (field.searchable() || field.unique()) {
                sql.append("CREATE INDEX IF NOT EXISTS idx_").append(tableName).append("_")
                   .append(toSnakeCase(field.name())).append(" ON ")
                   .append(tableName).append("(").append(toSnakeCase(field.name())).append(");\n");
            }
        }
        sql.append("\n");

        // RLS Policy (if tenantScoped)
        if (metadata.tenantScoped()) {
            sql.append("-- Row Level Security for tenant isolation\n");
            sql.append("ALTER TABLE ").append(tableName).append(" ENABLE ROW LEVEL SECURITY;\n\n");
            sql.append("CREATE POLICY ").append(tableName).append("_tenant_policy ON ").append(tableName).append("\n");
            sql.append("    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)\n");
            sql.append("    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);\n");
        }

        // Generate SQL file in db/migration directory
        String directory = "db/migration";
        return new GeneratedFile(directory, version, sql.toString(), ArtifactType.CONFIGURATION, "sql");
    }

    private boolean isSystemField(String fieldName) {
        return SYSTEM_FIELDS.contains(fieldName) || SYSTEM_FIELDS.contains(toSnakeCase(fieldName));
    }

    private String toSnakeCase(String camelCase) {
        return camelCase.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase();
    }

    private String mapJavaTypeToSql(String javaType) {
        if (javaType == null) return "VARCHAR(255)";

        // Handle fully qualified and simple names
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
    public PluggableBackend backend() { return PluggableBackend.KERNEL; }

    @Override
    public ArtifactType artifactType() { return ArtifactType.CONFIGURATION; }
}
