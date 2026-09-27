package eu.exeris.tooling.codegen.java.kernel;

import eu.exeris.sdk.sourcemodel.ast.DataScope;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.FieldMetadata;
import eu.exeris.sdk.sourcemodel.ast.SystemFieldsMetadata;
import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator.ArtifactType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Golden snapshots for the additive shared-scope migration of a {@code DataScope.UNIVERSE} entity.
 * SQL has no compiler to lean on, so the snapshot is the regression gate; the PostgreSQL
 * semantics it relies on are argued in the generator's Javadoc and exercised by the opt-in
 * {@code SharedScopePostgresE2ETest}.
 */
@DisplayName("KernelSharedScopeMigrationGenerator SQL Snapshot")
class KernelSharedScopeMigrationGeneratorTest {

    private final KernelSharedScopeMigrationGenerator generator = new KernelSharedScopeMigrationGenerator();

    /** The archetypal shape: an owner and a UUID shared-scope key. */
    private static DomainMetadata galaxyPresence(String scopeType, String scopeField) {
        return DomainMetadata.builder("GalaxyPresence", "dev.arkstack.universe.domain")
                .dataScope(DataScope.UNIVERSE)
                .systemFields(systemFields("ownerTenantId", scopeField))
                .fields(List.of(
                        FieldMetadata.builder("x", "int").build(),
                        FieldMetadata.builder("ownerTenantId", "java.util.UUID").build(),
                        FieldMetadata.builder(scopeField, scopeType).build()))
                .build();
    }

    private static SystemFieldsMetadata systemFields(String tenantIdField, String sharedScopeField) {
        return new SystemFieldsMetadata("id", "createdAt", "createdBy", "updatedAt", "updatedBy",
                tenantIdField, "version", null, null, null, sharedScopeField);
    }

    @Test
    @DisplayName("UUID key: an index and one additive FOR SELECT policy, cast to uuid, fail-closed on ''")
    void uuidGolden() {
        GeneratedFile file = generator.generate(galaxyPresence("java.util.UUID", "universeId"));

        assertThat(file.artifactType()).isEqualTo(ArtifactType.CONFIGURATION);
        assertThat(file.packageName()).isEqualTo("db/migration");
        assertThat(file.extension()).isEqualTo("sql");
        assertThat(file.className()).isEqualTo("V4331763__shared_scope_galaxy_presences");
        assertThat(file.content()).isEqualTo("""
                -- Flyway migration: shared-scope read widening for galaxy_presences
                -- Generated from @ExerisDomain(dataScope = UNIVERSE): dev.arkstack.universe.domain.GalaxyPresence
                -- Reads widen to rows tagged with the session's shared scope; writes stay pinned to the owner.

                CREATE INDEX IF NOT EXISTS idx_galaxy_presences_universe_id ON galaxy_presences(universe_id);

                -- Additive: galaxy_presences_tenant_policy still decides every write. Permissive policies are OR-ed
                -- per command, so this one widens SELECT only - a partition-mate can read a row, never
                -- update or delete it.
                -- Session key set by the kernel's RlsConnectionInterceptor (SESSION_KEY_SHARED_SCOPE).
                -- NULLIF: an unset or RESET key reads '' - it must match no row, and ''::uuid would raise.
                DROP POLICY IF EXISTS galaxy_presences_shared_scope_policy ON galaxy_presences;
                CREATE POLICY galaxy_presences_shared_scope_policy ON galaxy_presences FOR SELECT
                    USING (universe_id = NULLIF(current_setting('exeris.shared_scope', true), '')::uuid);
                """);
    }

    @Test
    @DisplayName("String key: the same policy with no cast, because the column is VARCHAR")
    void stringGolden() {
        GeneratedFile file = generator.generate(galaxyPresence("java.lang.String", "worldKey"));

        assertThat(file.className()).isEqualTo("V4331763__shared_scope_galaxy_presences");
        assertThat(file.content())
                .contains("CREATE INDEX IF NOT EXISTS idx_galaxy_presences_world_key ON galaxy_presences(world_key);")
                .contains("CREATE POLICY galaxy_presences_shared_scope_policy ON galaxy_presences FOR SELECT\n"
                        + "    USING (world_key = NULLIF(current_setting('exeris.shared_scope', true), ''));\n")
                .doesNotContain("::uuid);");
    }

    @Test
    @DisplayName("writes are untouched: no FOR ALL policy, no WITH CHECK, and the owner policy is never dropped")
    void onlyWidensReads() {
        String sql = generator.generate(galaxyPresence("java.util.UUID", "universeId")).content();

        // The kernel's single FOR ALL reference policy would let a tenant delete and re-own a
        // partition-mate's shared row: a FOR ALL USING also decides what UPDATE and DELETE may
        // target. Keeping the owner policy and adding a SELECT-only one widens reads identically and
        // admits none of that.
        assertThat(sql)
                .contains("FOR SELECT")
                .doesNotContain("WITH CHECK")
                .doesNotContain("FOR ALL")
                .doesNotContain("DROP POLICY IF EXISTS galaxy_presences_tenant_policy")
                // ENABLE / FORCE belong to the table's own migration and stay there.
                .doesNotContain("ROW LEVEL SECURITY;");
    }

    @Test
    @DisplayName("the version reuses the CREATE migration's six digits on tier 4")
    void versionPairsWithTheCreateMigration() {
        DomainMetadata metadata = galaxyPresence("java.util.UUID", "universeId");

        String create = new KernelFlywayGenerator().generate(metadata).className();
        String widen = generator.generate(metadata).className();

        assertThat(create).isEqualTo("V2331763__create_galaxy_presences");
        assertThat(widen.substring(2, 8)).isEqualTo(create.substring(2, 8));
        // Sorts after every CREATE (tier 1, 2) and after the project's V3000000 foreign keys.
        assertThat(Long.parseLong(widen.substring(1, widen.indexOf("__")))).isGreaterThan(3_999_999L);
    }

    @Test
    @DisplayName("a filterable scope column is indexed under the CREATE migration's own name — never twice")
    void indexNameMatchesTheCreateMigration() {
        DomainMetadata metadata = DomainMetadata.builder("GalaxyPresence", "dev.arkstack.universe.domain")
                .dataScope(DataScope.UNIVERSE)
                .systemFields(systemFields("ownerTenantId", "universeId"))
                .fields(List.of(
                        FieldMetadata.builder("ownerTenantId", "java.util.UUID").build(),
                        FieldMetadata.builder("universeId", "java.util.UUID").filterable(true).build()))
                .build();

        String create = new KernelFlywayGenerator().generate(metadata).content();
        String widen = generator.generate(metadata).content();

        String index = "CREATE INDEX IF NOT EXISTS idx_galaxy_presences_universe_id ON galaxy_presences(universe_id);";
        assertThat(create).contains(index);
        assertThat(widen).contains(index);
    }

    @Test
    @DisplayName("nothing for GLOBAL, TENANT, or a UNIVERSE entity whose metadata carries no shared-scope field")
    void nothingForAnyOtherEntity() {
        List<FieldMetadata> fields = List.of(FieldMetadata.builder("universeId", "java.util.UUID").build());

        assertThat(generator.generate(DomainMetadata.builder("A", "eu.exeris.app.domain")
                .dataScope(DataScope.GLOBAL).fields(fields).build())).isNull();
        assertThat(generator.generate(DomainMetadata.builder("B", "eu.exeris.app.domain")
                .dataScope(DataScope.TENANT).systemFields(systemFields("tenantId", "universeId"))
                .fields(fields).build())).isNull();
        assertThat(generator.generate(DomainMetadata.builder("C", "eu.exeris.app.domain")
                .dataScope(DataScope.UNIVERSE).fields(fields).build())).isNull();
        assertThat(generator.generateMultiple(DomainMetadata.builder("D", "eu.exeris.app.domain")
                .tenantScoped(true).fields(fields).build())).isEmpty();
    }

    @Test
    @DisplayName("a sharedScopeField naming no declared field fails generation instead of a policy over nothing")
    void missingColumnFailsGeneration() {
        DomainMetadata metadata = DomainMetadata.builder("GalaxyPresence", "dev.arkstack.universe.domain")
                .dataScope(DataScope.UNIVERSE)
                .systemFields(systemFields("ownerTenantId", "universeId"))
                .fields(List.of(FieldMetadata.builder("ownerTenantId", "java.util.UUID").build()))
                .build();

        assertThatThrownBy(() -> generator.generate(metadata))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sharedScopeField names 'universeId'");
    }

    @Test
    @DisplayName("deterministic: generating twice is byte-identical")
    void deterministic() {
        DomainMetadata metadata = galaxyPresence("java.util.UUID", "universeId");

        GeneratedFile first = generator.generate(metadata);
        GeneratedFile second = generator.generate(metadata);

        assertThat(second.className()).isEqualTo(first.className());
        assertThat(second.content()).isEqualTo(first.content());
    }
}
