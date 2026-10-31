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

/**
 * Snapshots and contracts for {@link KernelTenantPolicyMigrationGenerator}.
 */
@DisplayName("KernelTenantPolicyMigrationGenerator SQL Snapshot")
class KernelTenantPolicyMigrationGeneratorTest {

    private final KernelTenantPolicyMigrationGenerator generator = new KernelTenantPolicyMigrationGenerator();

    private static DomainMetadata tenantEntity(String tenantField) {
        return DomainMetadata.builder("Order", "com.shop.domain")
                .dataScope(DataScope.TENANT)
                .systemFields(SystemFieldsMetadata.builder().tenantIdField(tenantField).build())
                .fields(List.of(
                        FieldMetadata.builder(tenantField, "java.util.UUID").build(),
                        FieldMetadata.builder("total", "java.math.BigDecimal").build()))
                .build();
    }

    private static DomainMetadata universeEntity(String scopeType, String scopeField) {
        return DomainMetadata.builder("GalaxyPresence", "dev.arkstack.universe.domain")
                .dataScope(DataScope.UNIVERSE)
                .systemFields(SystemFieldsMetadata.builder()
                        .tenantIdField("ownerTenantId").sharedScopeField(scopeField).build())
                .fields(List.of(
                        FieldMetadata.builder("x", "int").build(),
                        FieldMetadata.builder("ownerTenantId", "java.util.UUID").build(),
                        FieldMetadata.builder(scopeField, scopeType).build()))
                .build();
    }

    @Test
    @DisplayName("null for GLOBAL entities: only tenant-partitioned tables receive tenant policies")
    void nullForGlobal() {
        DomainMetadata global = DomainMetadata.builder("Currency", "com.shop.domain")
                .dataScope(DataScope.GLOBAL)
                .fields(List.of(FieldMetadata.builder("code", "String").build()))
                .build();

        assertThat(generator.generate(global)).isNull();
    }

    @Test
    @DisplayName("TENANT entity: unguarded cast on tenant in USING and WITH CHECK, with no tag pin")
    void tenantPolicyGolden() {
        GeneratedFile file = generator.generate(tenantEntity("orgId"));

        assertThat(file).isNotNull();
        assertThat(file.artifactType()).isEqualTo(ArtifactType.CONFIGURATION);
        assertThat(file.packageName()).isEqualTo("db/migration");
        assertThat(file.extension()).isEqualTo("sql");
        assertThat(file.className()).startsWith("V5").endsWith("__tenant_policy_orders");
        assertThat(file.content()).isEqualTo("""
                -- Flyway migration: tenant policy update for orders
                -- Generated from @ExerisDomain: com.shop.domain.Order
                -- An absent tenant key publishes '', and ''::uuid raises.

                DROP POLICY IF EXISTS orders_tenant_policy ON orders;
                CREATE POLICY orders_tenant_policy ON orders
                    USING (org_id = current_setting('exeris.tenant_id', true)::uuid)
                    WITH CHECK (org_id = current_setting('exeris.tenant_id', true)::uuid);
                """);
    }

    @Test
    @DisplayName("UNIVERSE with UUID tag: unguarded tenant cast and UUID-cast tag pin in WITH CHECK")
    void universeUuidGolden() {
        GeneratedFile file = generator.generate(universeEntity("java.util.UUID", "universeId"));

        assertThat(file).isNotNull();
        assertThat(file.className()).isEqualTo("V5331763__tenant_policy_galaxy_presences");
        assertThat(file.content()).isEqualTo("""
                -- Flyway migration: tenant policy update for galaxy_presences
                -- Generated from @ExerisDomain: dev.arkstack.universe.domain.GalaxyPresence
                -- An absent tenant key publishes '', and ''::uuid raises. Writes pin the shared-scope tag.

                DROP POLICY IF EXISTS galaxy_presences_tenant_policy ON galaxy_presences;
                CREATE POLICY galaxy_presences_tenant_policy ON galaxy_presences
                    USING (owner_tenant_id = current_setting('exeris.tenant_id', true)::uuid)
                    WITH CHECK (owner_tenant_id = current_setting('exeris.tenant_id', true)::uuid
                        AND (universe_id IS NULL
                             OR universe_id = NULLIF(current_setting('exeris.shared_scope', true), '')::uuid));
                """);
    }

    @Test
    @DisplayName("UNIVERSE with String tag: COALESCE tag check for TEXT column in WITH CHECK")
    void universeTextGolden() {
        GeneratedFile file = generator.generate(universeEntity("java.lang.String", "worldKey"));

        assertThat(file).isNotNull();
        assertThat(file.className()).isEqualTo("V5331763__tenant_policy_galaxy_presences");
        assertThat(file.content()).isEqualTo("""
                -- Flyway migration: tenant policy update for galaxy_presences
                -- Generated from @ExerisDomain: dev.arkstack.universe.domain.GalaxyPresence
                -- An absent tenant key publishes '', and ''::uuid raises. Writes pin the shared-scope tag.

                DROP POLICY IF EXISTS galaxy_presences_tenant_policy ON galaxy_presences;
                CREATE POLICY galaxy_presences_tenant_policy ON galaxy_presences
                    USING (owner_tenant_id = current_setting('exeris.tenant_id', true)::uuid)
                    WITH CHECK (owner_tenant_id = current_setting('exeris.tenant_id', true)::uuid
                        AND COALESCE(world_key, '') IN ('', current_setting('exeris.shared_scope', true)));
                """);
    }

    @Test
    @DisplayName("idempotent: emits DROP POLICY IF EXISTS before CREATE POLICY")
    void idempotentDropIfExists() {
        String sql = generator.generate(tenantEntity("tenantId")).content();
        assertThat(sql).contains("DROP POLICY IF EXISTS orders_tenant_policy ON orders;\nCREATE POLICY orders_tenant_policy ON orders");
    }

    @Test
    @DisplayName("deterministic: generating twice produces identical filename and content")
    void determinism() {
        DomainMetadata metadata = universeEntity("java.util.UUID", "universeId");

        GeneratedFile first = generator.generate(metadata);
        GeneratedFile second = generator.generate(metadata);

        assertThat(second.className()).isEqualTo(first.className());
        assertThat(second.content()).isEqualTo(first.content());
    }

    @Test
    @DisplayName("the version reuses the CREATE migration's six digits on tier 5")
    void versionPairsWithTheCreateMigration() {
        DomainMetadata metadata = universeEntity("java.util.UUID", "universeId");

        String create = new KernelFlywayGenerator().generate(metadata).className();
        String policy = generator.generate(metadata).className();

        assertThat(create).isEqualTo("V2331763__create_galaxy_presences");
        assertThat(policy).isEqualTo("V5331763__tenant_policy_galaxy_presences");
        assertThat(policy.substring(2, 8)).isEqualTo(create.substring(2, 8));
    }
}
