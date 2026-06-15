package eu.exeris.tooling.codegen.java.kernel;

import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator;
import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator.ArtifactType;
import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.FieldMetadata;
import eu.exeris.sdk.sourcemodel.ast.SystemFieldsMetadata;

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

    // Substituted via String.formatted: %1$s = table name, %2$s = tenant
    // column. Safe because both are snake-cased Java identifiers (no `%`
    // possible); never pass arbitrary external input through this template.
    // The tenant column is parameterised so a tenantIdField override (T5) keeps
    // the RLS predicate aligned with the actual column.
    private static final String RLS_TEMPLATE = """
            -- Row Level Security for tenant isolation
            ALTER TABLE %1$s ENABLE ROW LEVEL SECURITY;

            CREATE POLICY %1$s_tenant_policy ON %1$s
                USING (%2$s = current_setting('app.tenant_id', true)::uuid)
                WITH CHECK (%2$s = current_setting('app.tenant_id', true)::uuid);
            """;

    @Override
    public GeneratedFile generate(DomainMetadata metadata) {
        String tableName = KernelTableNaming.effectiveTable(metadata);
        String version = migrationVersion(metadata, tableName) + "__create_" + tableName;

        String header = """
                -- Flyway migration: Create %s table
                -- Generated from @ExerisDomain: %s

                """.formatted(metadata.entityName(), metadata.fullyQualifiedName());

        String createTable = "CREATE TABLE IF NOT EXISTS " + tableName + " (\n"
                + String.join(",\n", buildColumns(metadata))
                + "\n);\n\n";

        String indexes = String.join("", buildIndexes(metadata, tableName)) + "\n";

        String rls = metadata.tenantScoped()
                ? RLS_TEMPLATE.formatted(tableName, tenantColumn(metadata)) : "";

        String sql = header + createTable + indexes + rls;
        return new GeneratedFile("db/migration", version, sql, ArtifactType.CONFIGURATION, "sql");
    }

    /**
     * Deterministic Flyway version — no wall-clock (hard-constraint #3): same
     * {@code DomainMetadata} → same filename. Tenant-scoped tables (which emit a
     * {@code REFERENCES tenants(id)} FK) tier above unscoped ones so the referenced
     * table is created first; within a tier the order is a stable FQN hash. The
     * tenant FK is the only cross-table reference this generator emits, so a
     * two-tier scheme suffices. The {@code tenants} table itself is pinned to tier 1
     * regardless of its flags, so the guarantee holds even if a {@code Tenant}
     * entity is (mistakenly) marked tenant-scoped.
     *
     * <p><b>Collision:</b> the discriminator space is 1,000,000 per tier. For
     * realistic models (far fewer than ~1,000 entities per tier) collisions are
     * negligible; if two FQNs ever collide, Flyway fails loudly with "Found more
     * than one migration with version N" — rename one of the colliding Java classes.
     */
    private static String migrationVersion(DomainMetadata metadata, String tableName) {
        boolean scoped = metadata.tenantScoped() && !"tenants".equals(tableName);
        long tier = scoped ? 2L : 1L;
        long discriminator = Math.floorMod(metadata.fullyQualifiedName().hashCode(), 1_000_000);
        return "V" + (tier * 1_000_000L + discriminator);
    }

    private List<String> buildColumns(DomainMetadata metadata) {
        List<String> columns = new ArrayList<>();
        columns.add("    id UUID PRIMARY KEY DEFAULT gen_random_uuid()");

        if (metadata.tenantScoped()) {
            columns.add("    " + tenantColumn(metadata) + " UUID NOT NULL REFERENCES tenants(id)");
        }

        for (FieldMetadata field : metadata.fields()) {
            if (isSystemField(field.name())) continue;
            columns.add("    " + toSnakeCase(field.name()) + " " + mapJavaTypeToSql(field.type())
                    + (field.required() ? " NOT NULL" : "")
                    + (field.unique() ? " UNIQUE" : ""));
        }

        if (metadata.audited()) {
            columns.add("    " + sysCol(metadata, "createdAt") + " TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP");
            columns.add("    created_by VARCHAR(255)");
            columns.add("    " + sysCol(metadata, "updatedAt") + " TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP");
            columns.add("    updated_by VARCHAR(255)");
        }
        if (metadata.softDelete()) {
            columns.add("    " + sysCol(metadata, "deleted") + " BOOLEAN DEFAULT FALSE");
            columns.add("    deleted_at TIMESTAMPTZ");
            columns.add("    deleted_by VARCHAR(255)");
        }
        if (metadata.versioned()) {
            columns.add("    " + sysCol(metadata, "version") + " BIGINT DEFAULT 0");
        }
        return columns;
    }

    /** Snake-cased SQL name of the tenant column (honours tenantIdField, T5). */
    private String tenantColumn(DomainMetadata metadata) {
        return sysCol(metadata, "tenantId");
    }

    /**
     * Resolves the SQL column name for a system field. {@code logical} is the
     * default java name (tenantId / createdAt / updatedAt / deleted / version);
     * any {@code @ExerisDomain} override (T5) is applied before snake-casing.
     * Default case is byte-identical to the previous hardcoded SQL names.
     */
    private String sysCol(DomainMetadata metadata, String logical) {
        SystemFieldsMetadata sf = metadata.systemFields();
        String javaName = switch (logical) {
            case "tenantId" -> resolve(sf == null ? null : sf.tenantIdField(), "tenantId");
            case "createdAt" -> resolve(sf == null ? null : sf.createdAtField(), "createdAt");
            case "updatedAt" -> resolve(sf == null ? null : sf.updatedAtField(), "updatedAt");
            case "deleted" -> resolve(sf == null ? null : sf.softDeleteField(), "deleted");
            case "version" -> resolve(sf == null ? null : sf.versionField(), "version");
            default -> logical;
        };
        return toSnakeCase(javaName);
    }

    private static String resolve(String override, String fallback) {
        return (override != null && !override.isBlank()) ? override : fallback;
    }

    private List<String> buildIndexes(DomainMetadata metadata, String tableName) {
        List<String> indexes = new ArrayList<>();
        if (metadata.tenantScoped()) {
            indexes.add("CREATE INDEX IF NOT EXISTS idx_" + tableName + "_tenant ON "
                    + tableName + "(" + tenantColumn(metadata) + ");\n");
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
