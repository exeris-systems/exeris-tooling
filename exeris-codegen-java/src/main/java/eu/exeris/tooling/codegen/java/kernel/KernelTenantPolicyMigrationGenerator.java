package eu.exeris.tooling.codegen.java.kernel;

import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.FieldMetadata;
import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator;
import eu.exeris.tooling.codegen.java.support.DataScopeSupport;

import java.util.Optional;

/**
 * Emits the tenant policy update of a tenant-partitioned entity as its own Flyway migration
 * (kernel ADR-012 §4c.2, §4c.3).
 *
 * <p>For every tenant-partitioned entity ({@code TENANT} and {@code UNIVERSE},
 * {@link DataScopeSupport#isTenantPartitioned}), this migration replaces the initial
 * {@code <table>_tenant_policy} with an unguarded tenant cast in {@code USING} and
 * {@code WITH CHECK}, and for a {@code UNIVERSE} table carrying a shared-scope column,
 * pins the shared-scope tag on write.
 *
 * <h2>Why a separate file</h2>
 * <ul>
 *   <li><b>Committed generated code and Flyway checksums:</b> An existing database that has
 *       already applied the {@code CREATE TABLE} migration ({@code V1...} or {@code V2...}) must
 *       not have that migration edited in place. Modifying an applied migration causes Flyway
 *       checksum validation failures on application startup. Emitting the updated policy in tier 5
 *       ({@code V5<nnnnnn>__tenant_policy_<table>.sql}) applies the update forward cleanly without
 *       altering applied migrations.</li>
 *   <li><b>Ordering:</b> Tier 5 sorts after tier 1 and 2 (CREATE TABLE), tier 3 ({@code V3000000}
 *       foreign keys), and tier 4 ({@code V4...} shared-scope column and index migration),
 *       ensuring the table and any shared-scope column exist before this policy is created.</li>
 *   <li><b>Convergence:</b> Fresh deployments and existing databases reach the exact same
 *       final schema state.</li>
 * </ul>
 *
 * <h2>Load-bearing clauses</h2>
 * <ul>
 *   <li><b>Unguarded {@code ::uuid} cast</b> — an absent tenant key (or a {@code RESET}
 *       connection) publishes the empty string {@code ''}. The unguarded cast {@code ''::uuid}
 *       raises {@code 22P02} (reported by the kernel as {@code EX-PERS-5003}), ensuring that a
 *       tenant-less request fails closed and loudly rather than silently reading an empty table
 *       (kernel ADR-012 §4c.3).</li>
 *   <li><b>Tag pinning in {@code WITH CHECK}</b> — for tables with a shared-scope column,
 *       {@code WITH CHECK} ensures written rows are either tenant-private or tagged with the
 *       verified scope the session publishes, preventing a tenant from writing rows into an
 *       unheld scope (kernel ADR-012 §4c.2).</li>
 *   <li><b>Idempotent</b> — {@code DROP POLICY IF EXISTS} before {@code CREATE POLICY} ensures
 *       the migration can be safely re-run or applied on databases whether the initial policy exists
 *       or not.</li>
 * </ul>
 *
 * @implNote Emission is a Java text block (ADR-015). Returns {@code null} for entities that are
 *     not tenant-partitioned (e.g. {@code GLOBAL}).
 *
 * @since 0.11.0
 */
public class KernelTenantPolicyMigrationGenerator implements KernelArtifactGenerator {

    /** Migration tier: after CREATEs (1, 2), foreign keys (3), and shared-scope widening (4). */
    private static final long TIER = 5L;

    private static final String TEMPLATE_TENANT = """
            -- Flyway migration: tenant policy update for %1$s
            -- Generated from @ExerisDomain: %2$s
            -- An absent tenant key publishes '', and ''::uuid raises.

            DROP POLICY IF EXISTS %1$s_tenant_policy ON %1$s;
            CREATE POLICY %1$s_tenant_policy ON %1$s
                USING (%3$s = current_setting('exeris.tenant_id', true)::uuid)
                WITH CHECK (%3$s = current_setting('exeris.tenant_id', true)::uuid);
            """;

    private static final String TEMPLATE_SHARED_SCOPE_UUID = """
            -- Flyway migration: tenant policy update for %1$s
            -- Generated from @ExerisDomain: %2$s
            -- An absent tenant key publishes '', and ''::uuid raises. Writes pin the shared-scope tag.

            DROP POLICY IF EXISTS %1$s_tenant_policy ON %1$s;
            CREATE POLICY %1$s_tenant_policy ON %1$s
                USING (%3$s = current_setting('exeris.tenant_id', true)::uuid)
                WITH CHECK (%3$s = current_setting('exeris.tenant_id', true)::uuid
                    AND (%4$s IS NULL
                         OR %4$s = NULLIF(current_setting('exeris.shared_scope', true), '')::uuid));
            """;

    private static final String TEMPLATE_SHARED_SCOPE_TEXT = """
            -- Flyway migration: tenant policy update for %1$s
            -- Generated from @ExerisDomain: %2$s
            -- An absent tenant key publishes '', and ''::uuid raises. Writes pin the shared-scope tag.

            DROP POLICY IF EXISTS %1$s_tenant_policy ON %1$s;
            CREATE POLICY %1$s_tenant_policy ON %1$s
                USING (%3$s = current_setting('exeris.tenant_id', true)::uuid)
                WITH CHECK (%3$s = current_setting('exeris.tenant_id', true)::uuid
                    AND COALESCE(%4$s, '') IN ('', current_setting('exeris.shared_scope', true)));
            """;

    /** The CREATE-migration generator, consulted for the column's name and SQL type. */
    private final KernelFlywayGenerator tableMigration = new KernelFlywayGenerator();

    @Override
    public GeneratedFile generate(DomainMetadata metadata) {
        if (!DataScopeSupport.isTenantPartitioned(metadata)) {
            return null;
        }

        String table = KernelTableNaming.effectiveTable(metadata);
        String tenantCol = tableMigration.tenantColumn(metadata);
        String fqn = metadata.fullyQualifiedName();

        Optional<FieldMetadata> sharedScope = DataScopeSupport.sharedScopeField(metadata);
        String sql;
        if (sharedScope.isEmpty()) {
            sql = TEMPLATE_TENANT.formatted(table, fqn, tenantCol);
        } else {
            FieldMetadata scopeField = sharedScope.get();
            String scopeCol = tableMigration.domainColumn(scopeField.name());
            boolean isUuid = "UUID".equals(tableMigration.domainColumnType(scopeField.type()));
            sql = (isUuid ? TEMPLATE_SHARED_SCOPE_UUID : TEMPLATE_SHARED_SCOPE_TEXT)
                    .formatted(table, fqn, tenantCol, scopeCol);
        }

        String version = "V" + (TIER * 1_000_000L + KernelFlywayGenerator.migrationDiscriminator(metadata))
                + "__tenant_policy_" + table;
        return new GeneratedFile("db/migration", version, sql, ArtifactType.CONFIGURATION, "sql");
    }

    @Override
    public ArtifactType artifactType() {
        return ArtifactType.CONFIGURATION;
    }
}
