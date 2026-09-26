package eu.exeris.tooling.codegen.java.kernel;

import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.FieldMetadata;
import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator;
import eu.exeris.tooling.codegen.java.support.DataScopeSupport;

import java.util.Optional;

/**
 * Emits the shared-scope read widening of a {@code DataScope.UNIVERSE} entity as its own Flyway
 * migration (T29 slice B, ADR-059 obligation 5).
 *
 * <p>A UNIVERSE row is owned by a tenant and readable by every tenant sharing its scope (kernel
 * ADR-012 §4b). {@link KernelFlywayGenerator} already emits everything the ownership half needs —
 * owner column, owner index, {@code ENABLE}/{@code FORCE ROW LEVEL SECURITY} and the owner-pinned
 * {@code <table>_tenant_policy} — byte-identical to the entity's TENANT twin. This generator adds
 * the other half and nothing else: an index on the {@code @SharedScope} column and one
 * {@code FOR SELECT} policy that admits a row whose shared-scope column equals the scope the kernel
 * publishes for the session.
 *
 * <h2>Why an additive {@code FOR SELECT} policy, and not the kernel's single policy</h2>
 * <p>The reference policy in the kernel's {@code RlsConnectionInterceptor} Javadoc is one
 * {@code FOR ALL} policy whose {@code USING} is widened and whose {@code WITH CHECK} is pinned to the
 * owner. In PostgreSQL a {@code FOR ALL} policy's {@code USING} also decides which rows
 * {@code UPDATE} and {@code DELETE} may target, and {@code DELETE} consults nothing else. Applied to a
 * real PostgreSQL 16 as a non-owner {@code NOSUPERUSER NOBYPASSRLS} role, that shape let a tenant
 * <b>delete</b> a partition-mate's shared row, and <b>take it over</b> with
 * {@code UPDATE … SET owner = self} — both "cross-tenant mutation", which ADR-012 §4b.4 puts out of
 * scope and the kernel's own access-matrix TCK says must stay a denial.
 *
 * <p>Permissive policies are OR-ed per command. Keeping the owner-only {@code FOR ALL} policy and
 * adding a {@code FOR SELECT} one widens reads by exactly the reference's read predicate —
 * {@code owner = tenant OR scope = shared scope} — while {@code INSERT}, {@code UPDATE} and
 * {@code DELETE} keep seeing only the owner predicate. Measured on the same database: every read
 * cell identical to the reference; the delete, the takeover and a plain content overwrite of a
 * partition-mate's row each affect zero rows. It is also the smaller change — nothing the table's
 * own migration created is dropped.
 *
 * <h2>Why a separate file</h2>
 * <ul>
 *   <li>Declaring UNIVERSE on an existing TENANT table becomes a new forward migration instead of a
 *       checksum-breaking edit of an applied one (committed-generated-code policy), and the CREATE
 *       migration's snapshot never moves because of the tier.</li>
 *   <li>The version reuses the CREATE's six-digit discriminator on tier 4 —
 *       {@code V4<nnnnnn>__shared_scope_<table>.sql} — so it sorts after every CREATE and after the
 *       project's {@code V3000000} foreign-key migration, and pairs visibly with its table.</li>
 *   <li>It is tracked by the output manifest like any emitted file, so reverting the tier prunes it
 *       on the next run (T13). The policy it created stays in a database that applied it; that is
 *       a hand-written {@code DROP POLICY}, as the MIGRATION guide says.</li>
 * </ul>
 *
 * <h2>Load-bearing details</h2>
 * <ul>
 *   <li><b>{@code NULLIF(…, '')}</b> — the kernel publishes the shared-scope key on every
 *       connection, as {@code ''} when the context declares none, and a {@code RESET} key reads
 *       {@code ''} as well. It must match no row, and {@code ''::uuid} would raise on every read.
 *       {@code col = NULL} is never true, so an absent scope widens nothing and an untagged row stays
 *       owner-private — the reference's explicit {@code IS NOT NULL} guard is subsumed.</li>
 *   <li><b>{@code exeris.shared_scope}</b> — {@code ConnectionInterceptor.SESSION_KEY_SHARED_SCOPE}
 *       since kernel 0.12. Emitted as a literal (this module has no kernel dependency) and pinned to
 *       the constant by {@code SharedScopeSqlE2ETest}.</li>
 *   <li><b>The column name and its SQL type come from {@link KernelFlywayGenerator}</b>, the same
 *       helpers that wrote the {@code CREATE TABLE}, so the policy can never name a column, or cast
 *       to a type, the table does not have.</li>
 *   <li><b>Idempotent.</b> {@code CREATE INDEX IF NOT EXISTS} under the exact name the CREATE
 *       migration uses for an indexed field (so a filterable scope column is not indexed twice), and
 *       {@code DROP POLICY IF EXISTS} before {@code CREATE POLICY}, so a runner that replays
 *       migrations can apply it twice.</li>
 * </ul>
 *
 * @implNote Emission is a Java text block (ADR-015 — JavaPoet does not apply to SQL). Returns
 * {@code null} — the registry's "nothing to emit" sentinel — for every entity that is not a
 * transcribable UNIVERSE entity.
 *
 * @author Exeris Team
 * @since 0.9.0
 */
public class KernelSharedScopeMigrationGenerator implements KernelArtifactGenerator {

    /** Migration tier: after the CREATEs (1, 2) and the project's foreign-key migration (3). */
    private static final long TIER = 4L;

    // %1$s table, %2$s FQN, %3$s shared-scope column, %4$s the compared session value — the setting
    // itself, cast to uuid when the column is UUID. All but the FQN are snake-cased identifiers, and
    // the FQN is a Java name, so no '%' can reach the template.
    private static final String TEMPLATE = """
            -- Flyway migration: shared-scope read widening for %1$s
            -- Generated from @ExerisDomain(dataScope = UNIVERSE): %2$s
            -- Reads widen to rows tagged with the session's shared scope; writes stay pinned to the owner.

            CREATE INDEX IF NOT EXISTS idx_%1$s_%3$s ON %1$s(%3$s);

            -- Additive: %1$s_tenant_policy still decides every write. Permissive policies are OR-ed
            -- per command, so this one widens SELECT only - a partition-mate can read a row, never
            -- update or delete it.
            -- Session key set by the kernel's RlsConnectionInterceptor (SESSION_KEY_SHARED_SCOPE).
            -- NULLIF: an unset or RESET key reads '' - it must match no row, and ''::uuid would raise.
            DROP POLICY IF EXISTS %1$s_shared_scope_policy ON %1$s;
            CREATE POLICY %1$s_shared_scope_policy ON %1$s FOR SELECT
                USING (%3$s = %4$s);
            """;

    private static final String SESSION_SHARED_SCOPE = "NULLIF(current_setting('exeris.shared_scope', true), '')";

    /** The CREATE-migration generator, consulted for the column's name and SQL type. */
    private final KernelFlywayGenerator tableMigration = new KernelFlywayGenerator();

    @Override
    public GeneratedFile generate(DomainMetadata metadata) {
        Optional<FieldMetadata> sharedScope = DataScopeSupport.sharedScopeField(metadata);
        if (sharedScope.isEmpty()) {
            return null;
        }
        FieldMetadata field = sharedScope.get();
        String table = KernelTableNaming.effectiveTable(metadata);
        String column = tableMigration.domainColumn(field.name());
        String sessionValue = "UUID".equals(tableMigration.domainColumnType(field.type()))
                ? SESSION_SHARED_SCOPE + "::uuid"
                : SESSION_SHARED_SCOPE;

        String sql = TEMPLATE.formatted(table, metadata.fullyQualifiedName(), column, sessionValue);
        String version = "V" + (TIER * 1_000_000L + KernelFlywayGenerator.migrationDiscriminator(metadata))
                + "__shared_scope_" + table;
        return new GeneratedFile("db/migration", version, sql, ArtifactType.CONFIGURATION, "sql");
    }

    @Override
    public ArtifactType artifactType() {
        return ArtifactType.CONFIGURATION;
    }
}
