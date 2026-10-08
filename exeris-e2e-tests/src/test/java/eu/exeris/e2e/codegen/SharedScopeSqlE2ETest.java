package eu.exeris.e2e.codegen;

import eu.exeris.e2e.codegen.compile.ProcessorCompiler;
import eu.exeris.kernel.spi.persistence.ConnectionInterceptor;
import eu.exeris.tooling.codegen.java.CodegenPipeline;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Annotation → SQL and repository for {@code DataScope.UNIVERSE}: an owned entity
 * with a {@code @SharedScope} field goes through the real processor and the real pipeline, and the
 * three artefacts the tier touches are read back from disk.
 *
 * <p>Sibling of {@link SystemFieldSqlE2ETest}, for the same reason: processor tests assert JSON and
 * generator tests run on hand-built metadata, which leaves the seam between them — does the
 * annotated field become the column the policy compares? — covered by neither.
 *
 * <p>It also carries the one guard that can only live here. The migrations name two PostgreSQL
 * session settings as string literals, because SQL cannot reference a Java constant and
 * {@code exeris-codegen-java} has no kernel dependency. Both names are SPI
 * constants on {@code ConnectionInterceptor}, and this module has the kernel SPI on its test
 * classpath — so the literal the generator writes is compared with the constant the kernel
 * publishes. A policy naming a key the runtime never sets fails in the worst way: every read
 * returns no rows, with nothing pointing at the characters responsible.
 */
@Tag("e2e")
@Tag("codegen")
@DisplayName("Shared scope → SQL e2e: @SharedScope reaches the widening migration and the repository")
class SharedScopeSqlE2ETest {

    @TempDir
    static Path workspace;

    private static Path generated;
    private static String create;
    private static String widen;
    private static String repository;

    @BeforeAll
    static void generate() throws IOException {
        Path classes = workspace.resolve("target/classes");
        generated = workspace.resolve("src/main/generated/java");

        ProcessorCompiler.compile(workspace.resolve("src"), classes, null, sources());
        CodegenPipeline.createDefault()
                .run(classes.resolve("exeris-metadata"), generated, "com.world");

        create = migration("__create_galaxy_presences");
        widen = migration("__shared_scope_galaxy_presences");
        repository = Files.readString(generated.resolve("com/world/repository/GalaxyPresenceRepository.java"));
    }

    @Test
    @DisplayName("session-key drift guard: the emitted literals are the kernel 0.12 SPI constants")
    void sessionKeysAreTheKernelConstants() {
        assertThat(widen).contains("current_setting('" + ConnectionInterceptor.SESSION_KEY_SHARED_SCOPE + "', true)");
        assertThat(create).contains("current_setting('" + ConnectionInterceptor.SESSION_KEY_TENANT_ID + "', true)");
    }

    @Test
    @DisplayName("the CREATE migration is the owned TENANT shape: owner column, FORCE, owner-pinned policy")
    void createMigrationIsTheOwnedShape() {
        assertThat(create)
                .contains("owner_tenant_id UUID NOT NULL")
                .contains("universe_id UUID")
                .doesNotContain("universe_id UUID NOT NULL")
                .contains("ALTER TABLE galaxy_presences FORCE ROW LEVEL SECURITY;")
                .contains("CREATE POLICY galaxy_presences_tenant_policy ON galaxy_presences")
                .contains("WITH CHECK (owner_tenant_id = NULLIF(current_setting('exeris.tenant_id', true), '')::uuid)")
                // The widening is not folded in here — that would make a TENANT→UNIVERSE change an
                // edit of an applied migration.
                .doesNotContain("shared_scope");
    }

    @Test
    @DisplayName("the widening migration reads the annotated column, SELECT only, fail-closed on an unset key")
    void wideningMigrationReadsTheAnnotatedColumn() {
        assertThat(widen)
                .contains("CREATE INDEX IF NOT EXISTS idx_galaxy_presences_universe_id ON galaxy_presences(universe_id);")
                .contains("CREATE POLICY galaxy_presences_shared_scope_policy ON galaxy_presences FOR SELECT")
                .contains("USING (universe_id = NULLIF(current_setting('exeris.shared_scope', true), '')::uuid);")
                .doesNotContain("WITH CHECK");
    }

    @Test
    @DisplayName("the two migrations of one table pair on their six digits, and the widening sorts last")
    void migrationsPairAndOrder() throws IOException {
        List<String> names = migrationNames();
        String createName = names.stream().filter(n -> n.contains("__create_galaxy_presences")).findFirst().orElseThrow();
        String widenName = names.stream().filter(n -> n.contains("__shared_scope_galaxy_presences")).findFirst().orElseThrow();

        assertThat(createName).startsWith("V2");
        assertThat(widenName).startsWith("V4");
        assertThat(widenName.substring(2, 8)).isEqualTo(createName.substring(2, 8));
    }

    @Test
    @DisplayName("the repository stamps the owner, then the shared scope, on both write paths")
    void repositoryStampsTheSharedScope() {
        assertThat(repository)
                .containsSubsequence(
                        "public GalaxyPresence save(GalaxyPresence entity)",
                        "if (entity.getOwnerTenantId() == null) entity.setOwnerTenantId(actingTenantId());",
                        "if (entity.getUniverseId() == null) entity.setUniverseId(actingSharedScope());",
                        "INSERT INTO galaxy_presences",
                        "public GalaxyPresence update(UUID id, GalaxyPresence entity)",
                        "if (entity.getUniverseId() == null) entity.setUniverseId(actingSharedScope());",
                        "UPDATE galaxy_presences SET")
                .contains("private static UUID actingSharedScope()");
    }

    @Test
    @DisplayName("the repository refuses a foreign owner or scope, the handler answers 400, "
            + "and the spec marks both fields readOnly")
    void foreignOwnerOrScopeIsACallerFaultEndToEnd() throws IOException {
        String handler = Files.readString(generated.resolve("com/world/handler/GalaxyPresenceHandler.java"));
        String spec = Files.readString(generated.resolve("openapi/galaxy-presence-api.yaml"));

        assertThat(repository)
                .contains("refuseForeignTenant(entity.getOwnerTenantId());")
                .contains("refuseForeignSharedScope(entity.getUniverseId());")
                // The owner is written by the INSERT and never by the UPDATE.
                .contains("INSERT INTO galaxy_presences (id, x, universe_id, owner_tenant_id)")
                .contains("UPDATE galaxy_presences SET x = ?, universe_id = ? WHERE id = ?");
        assertThat(handler)
                .contains("catch (GalaxyPresenceTenantMismatchException | GalaxyPresenceSharedScopeMismatchException e)")
                .contains("exchange.respond(HttpStatus.BAD_REQUEST);");
        assertThat(Files.exists(generated.resolve("com/world/repository/GalaxyPresenceTenantMismatchException.java")))
                .isTrue();
        // Both server-owned fields, readOnly in the entity schema; the create DTO carries only x.
        assertThat(spec)
                .containsPattern("ownerTenantId:\\s+type: string\\s+format: uuid\\s+readOnly: true")
                .containsPattern("universeId:\\s+type: string\\s+format: uuid\\s+readOnly: true");
        String createDto = spec.substring(spec.indexOf("GalaxyPresenceCreateDto:"),
                spec.indexOf("GalaxyPresenceUpdateDto:"));
        assertThat(createDto).contains("x:").doesNotContain("ownerTenantId").doesNotContain("universeId");
        // The update body carries what the UPDATE writes: x and the shared scope, never the owner.
        String updateDto = spec.substring(spec.indexOf("GalaxyPresenceUpdateDto:"),
                spec.indexOf("GalaxyPresencePage:"));
        assertThat(updateDto).contains("x:").contains("universeId:").doesNotContain("ownerTenantId");
    }

    @Test
    @DisplayName("a TENANT entity in the same build gets no widening migration")
    void tenantEntityGetsNoWidening() throws IOException {
        assertThat(migrationNames())
                .anyMatch(n -> n.contains("__create_fleets"))
                .noneMatch(n -> n.contains("__shared_scope_fleets"));
    }

    private static List<String> migrationNames() throws IOException {
        try (Stream<Path> files = Files.list(generated.resolve("db/migration"))) {
            return files.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    /** Reads the one emitted migration whose file name contains {@code fragment}. */
    private static String migration(String fragment) throws IOException {
        try (Stream<Path> files = Files.walk(generated.resolve("db/migration"))) {
            Path file = files.filter(p -> p.getFileName().toString().contains(fragment))
                    .filter(p -> p.toString().endsWith(".sql"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no migration matching '" + fragment + "'"));
            return Files.readString(file);
        }
    }

    /** The annotated sources — shared with {@link SharedScopePostgresE2ETest}, which applies their SQL. */
    static Map<String, String> sources() {
        Map<String, String> sources = new LinkedHashMap<>();
        // A shared-world presence, shaped the way ADR-012 §4b.2 requires: an owner beside the
        // shared-scope key.
        sources.put("com/world/domain/GalaxyPresence.java",
                """
                package com.world.domain;

                import eu.exeris.sdk.annotation.ExerisDomain;
                import eu.exeris.sdk.annotation.Field;
                import eu.exeris.sdk.annotation.system.SharedScope;
                import eu.exeris.sdk.annotation.system.TenantId;
                import java.util.UUID;

                @ExerisDomain(module = "universe", path = "/presences",
                              dataScope = ExerisDomain.DataScope.UNIVERSE)
                public class GalaxyPresence {

                    private java.util.UUID id;

                    @Field(label = "X")
                    private int x;

                    @Field(label = "Owner")
                    @TenantId
                    private UUID ownerTenantId;

                    @Field(label = "Universe")
                    @SharedScope
                    private UUID universeId;
                }
                """);
        sources.put("com/world/domain/Fleet.java",
                """
                package com.world.domain;

                import eu.exeris.sdk.annotation.ExerisDomain;
                import eu.exeris.sdk.annotation.Field;
                import java.util.UUID;

                @ExerisDomain(module = "universe", path = "/fleets",
                              dataScope = ExerisDomain.DataScope.TENANT)
                public class Fleet {

                    private java.util.UUID id;

                    @Field(label = "Name")
                    private String name;

                    @Field(label = "Tenant")
                    private UUID tenantId;
                }
                """);
        return sources;
    }
}
