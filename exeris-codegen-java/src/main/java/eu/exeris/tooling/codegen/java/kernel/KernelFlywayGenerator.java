package eu.exeris.tooling.codegen.java.kernel;

import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator;
import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator.ArtifactType;
import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.FieldMetadata;
import eu.exeris.sdk.sourcemodel.ast.RelationshipMetadata;
import eu.exeris.sdk.sourcemodel.ast.SystemFieldsMetadata;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
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
 *   <li>{@code CHECK} constraints mirroring the field-validation the handler
 *       already enforces at request time (T10, defense in depth)</li>
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

        // T10: CHECK constraints ride inside the CREATE TABLE parens, after the
        // column list, so the whole table shape lands in one statement (no
        // trailing ALTER, no create-order hazard).
        List<String> tableBody = new ArrayList<>(buildColumns(metadata));
        tableBody.addAll(buildCheckConstraints(metadata, tableName));

        String createTable = "CREATE TABLE IF NOT EXISTS " + tableName + " (\n"
                + String.join(",\n", tableBody)
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

        Set<String> emittedColumns = new HashSet<>();
        emittedColumns.add("id");

        if (metadata.tenantScoped()) {
            columns.add("    " + tenantColumn(metadata) + " UUID NOT NULL REFERENCES tenants(id)");
            emittedColumns.add(tenantColumn(metadata));
        }

        for (FieldMetadata field : metadata.fields()) {
            if (isSystemField(metadata, field.name())) continue;
            columns.add("    " + toSnakeCase(field.name()) + " " + mapJavaTypeToSql(field.type())
                    + (field.required() ? " NOT NULL" : "")
                    + (field.unique() ? " UNIQUE" : ""));
            emittedColumns.add(toSnakeCase(field.name()));
        }

        // T8: a plain UUID column for each MANY_TO_ONE FK that is not already a
        // declared field column (the entity-typed `@Relationship Customer customer`
        // style has no FieldMetadata, so its `customer_id` column would otherwise
        // never be created — and the T8 FK index would then target a non-existent
        // column). No REFERENCES / FK constraint is emitted here: that is T9, held
        // back as a trailing ALTER migration to avoid the create-order hazard.
        for (String fkCol : foreignKeyColumns(metadata)) {
            if (emittedColumns.add(fkCol)) {
                columns.add("    " + fkCol + " UUID");
            }
        }

        if (metadata.audited()) {
            columns.add("    " + sysCol(metadata, "createdAt") + " TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP");
            columns.add("    " + sysCol(metadata, "createdBy") + " VARCHAR(255)");
            columns.add("    " + sysCol(metadata, "updatedAt") + " TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP");
            columns.add("    " + sysCol(metadata, "updatedBy") + " VARCHAR(255)");
        }
        if (metadata.softDelete()) {
            columns.add("    " + sysCol(metadata, "deleted") + " BOOLEAN DEFAULT FALSE");
            columns.add("    " + sysCol(metadata, "deletedAt") + " TIMESTAMPTZ");
            columns.add("    " + sysCol(metadata, "deletedBy") + " VARCHAR(255)");
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
     * T8: the FK columns for every MANY_TO_ONE relationship, sorted by relationship
     * name for byte-deterministic emission. Each name is normalised so both
     * relationship styles map to the same column the repository finder filters on:
     * the entity-typed {@code @Relationship Customer customer} → {@code customer_id}
     * and the explicit-UUID-FK {@code @Relationship UUID customerId} →
     * {@code customer_id} (not {@code customer_id_id}).
     */
    private List<String> foreignKeyColumns(DomainMetadata metadata) {
        if (!metadata.hasRelationships()) {
            return List.of();
        }
        return metadata.relationships().stream()
                .filter(r -> r.type() == RelationshipMetadata.RelationType.MANY_TO_ONE)
                .sorted(Comparator.comparing(RelationshipMetadata::name))
                .map(r -> KernelTableNaming.foreignKeyColumn(r.name()))
                .toList();
    }

    /**
     * Resolves the SQL column name for a system field. {@code logical} is the
     * default java name (tenantId / createdAt / createdBy / updatedAt /
     * updatedBy / deleted / deletedAt / deletedBy / version); any
     * {@code @ExerisDomain} override (T5) is applied before snake-casing.
     * Default case is byte-identical to the previous hardcoded SQL names
     * (the audit-by, soft-delete-timestamp and soft-deleted-by columns default
     * to {@code created_by}/{@code updated_by}/{@code deleted_at}/{@code deleted_by}).
     */
    private String sysCol(DomainMetadata metadata, String logical) {
        SystemFieldsMetadata sf = metadata.systemFields();
        String javaName = switch (logical) {
            case "tenantId" -> resolve(sf == null ? null : sf.tenantIdField(), "tenantId");
            case "createdAt" -> resolve(sf == null ? null : sf.createdAtField(), "createdAt");
            case "createdBy" -> resolve(sf == null ? null : sf.createdByField(), "createdBy");
            case "updatedAt" -> resolve(sf == null ? null : sf.updatedAtField(), "updatedAt");
            case "updatedBy" -> resolve(sf == null ? null : sf.updatedByField(), "updatedBy");
            case "deleted" -> resolve(sf == null ? null : sf.softDeleteField(), "deleted");
            case "deletedAt" -> resolve(sf == null ? null : sf.softDeleteTimestampField(), "deletedAt");
            case "deletedBy" -> resolve(sf == null ? null : sf.softDeletedByField(), "deletedBy");
            case "version" -> resolve(sf == null ? null : sf.versionField(), "version");
            default -> throw new AssertionError("unknown system-field logical name: " + logical);
        };
        return toSnakeCase(javaName);
    }

    private static String resolve(String override, String fallback) {
        return (override != null && !override.isBlank()) ? override : fallback;
    }

    private List<String> buildIndexes(DomainMetadata metadata, String tableName) {
        List<String> indexes = new ArrayList<>();
        Set<String> indexedColumns = new HashSet<>();
        if (metadata.tenantScoped()) {
            indexes.add("CREATE INDEX IF NOT EXISTS idx_" + tableName + "_tenant ON "
                    + tableName + "(" + tenantColumn(metadata) + ");\n");
            indexedColumns.add(tenantColumn(metadata));
        }
        // T8: also index filterable fields (joined with the existing searchable /
        // unique condition so a column that is e.g. both searchable and filterable
        // still yields a single index). Source order preserved, matching the prior
        // behaviour for searchable/unique fields.
        for (FieldMetadata field : metadata.fields()) {
            if (isSystemField(metadata, field.name())) continue;
            if (field.searchable() || field.unique() || field.filterable()) {
                String col = toSnakeCase(field.name());
                if (indexedColumns.add(col)) {
                    indexes.add("CREATE INDEX IF NOT EXISTS idx_" + tableName + "_" + col + " ON "
                            + tableName + "(" + col + ");\n");
                }
            }
        }
        // T8: index each MANY_TO_ONE FK column (sorted by relationship name for
        // deterministic output). The FK index makes cross-aggregate finder lookups
        // (findBy<Rel>Id) non-O(n). Index only — the REFERENCES constraint is T9.
        for (String fkCol : foreignKeyColumns(metadata)) {
            if (indexedColumns.add(fkCol)) {
                indexes.add("CREATE INDEX IF NOT EXISTS idx_" + tableName + "_" + fkCol + " ON "
                        + tableName + "(" + fkCol + ");\n");
            }
        }
        return indexes;
    }

    /**
     * T10: table-level {@code CHECK} constraints mirroring the request-time
     * validation the handler already enforces (#103) — defense in depth at the DB
     * tier, so a row inserted around the handler (bulk load, another service, a
     * manual {@code psql}) still cannot violate the declared bounds. Emitted in
     * source-field order for byte-deterministic output (hard-constraint #3), each
     * with a stable, droppable name ({@code chk_<table>_<col>_<rule>}).
     *
     * <p>Only <b>dialect-safe</b> bounds are emitted: numeric {@code min}/{@code max}
     * and string length {@code minLength}/{@code maxLength} (via {@code char_length}).
     * {@code @Field(pattern=...)} is deliberately <b>not</b> emitted as a CHECK: the
     * handler validates it with Java {@link String#matches} (full-match, Java regex
     * dialect), whereas Postgres {@code ~} is a POSIX <em>partial</em> match — the
     * two diverge, so a DB CHECK would risk both false rejects (valid rows blocked)
     * and false accepts. Pattern stays enforced at the handler + client (Zod) edges.
     */
    private List<String> buildCheckConstraints(DomainMetadata metadata, String tableName) {
        List<String> checks = new ArrayList<>();
        for (FieldMetadata field : metadata.fields()) {
            if (isSystemField(metadata, field.name())) continue;
            String col = toSnakeCase(field.name());
            if (isNumericType(field.type())) {
                if (field.min() != null) {
                    checks.add(checkClause(tableName, col, "min", col + " >= " + field.min()));
                }
                if (field.max() != null) {
                    checks.add(checkClause(tableName, col, "max", col + " <= " + field.max()));
                }
            }
            if (isStringType(field.type())) {
                if (field.minLength() != null) {
                    checks.add(checkClause(tableName, col, "min_length",
                            "char_length(" + col + ") >= " + field.minLength()));
                }
                if (field.maxLength() != null) {
                    checks.add(checkClause(tableName, col, "max_length",
                            "char_length(" + col + ") <= " + field.maxLength()));
                }
            }
        }
        return checks;
    }

    private static String checkClause(String tableName, String col, String rule, String predicate) {
        return "    CONSTRAINT chk_" + tableName + "_" + col + "_" + rule + " CHECK (" + predicate + ")";
    }

    private static String simpleTypeName(String javaType) {
        if (javaType == null) return "";
        return javaType.contains(".") ? javaType.substring(javaType.lastIndexOf('.') + 1) : javaType;
    }

    private static boolean isStringType(String javaType) {
        return "String".equals(simpleTypeName(javaType));
    }

    /** Numeric types a min/max {@code CHECK} is safe to emit for. Must stay in
     *  lock-step with the numeric cases of {@link #mapJavaTypeToSql}: a type
     *  accepted here but mapped to a non-numeric SQL column (e.g. {@code VARCHAR})
     *  would emit {@code CHECK (col >= n)} against text — a migration that fails
     *  to apply. Mirrors the handler generator's numeric-bound surface. */
    private static boolean isNumericType(String javaType) {
        return switch (simpleTypeName(javaType)) {
            case "BigDecimal", "Long", "long", "Integer", "int",
                 "Short", "short", "Byte", "byte", "Float", "float", "Double", "double" -> true;
            default -> false;
        };
    }

    /** Logical names of every system column {@link #sysCol} can resolve (T5). */
    private static final List<String> SYSTEM_LOGICAL_NAMES = List.of(
            "tenantId", "createdAt", "createdBy", "updatedAt", "updatedBy",
            "deleted", "deletedAt", "deletedBy", "version");

    private boolean isSystemField(DomainMetadata metadata, String fieldName) {
        if (SYSTEM_FIELDS.contains(fieldName) || SYSTEM_FIELDS.contains(toSnakeCase(fieldName))) {
            return true;
        }
        // Override-aware (T5): a renamed system field (e.g. tenantIdField="orgId")
        // is not in the static SYSTEM_FIELDS set, but must still be treated as a
        // system column so it is not re-emitted as a domain column / index.
        String snake = toSnakeCase(fieldName);
        for (String logical : SYSTEM_LOGICAL_NAMES) {
            if (sysCol(metadata, logical).equals(snake)) {
                return true;
            }
        }
        return false;
    }

    private String toSnakeCase(String camelCase) {
        return camelCase.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase();
    }

    private String mapJavaTypeToSql(String javaType) {
        return switch (simpleTypeName(javaType)) {
            case "String" -> "VARCHAR(255)";
            case "UUID" -> "UUID";
            case "Long", "long" -> "BIGINT";
            case "Integer", "int" -> "INTEGER";
            case "Short", "short", "Byte", "byte" -> "SMALLINT";
            case "BigDecimal" -> "DECIMAL(19,4)";
            case "Double", "double" -> "DOUBLE PRECISION";
            case "Float", "float" -> "REAL";
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
