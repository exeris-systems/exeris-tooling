package eu.exeris.e2e.codegen;

import eu.exeris.e2e.codegen.compile.ProcessorCompiler;
import eu.exeris.kernel.spi.persistence.ConnectionInterceptor;
import eu.exeris.tooling.codegen.java.CodegenPipeline;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The shared-scope access matrix, run against a real PostgreSQL over the migrations the pipeline
 * actually emits. <b>Opt-in</b>: skipped unless {@code -Dexeris.e2e.postgres.url} names
 * a JDBC URL (credentials included) for a role that may create a schema and a role — this
 * repository's CI has no PostgreSQL, and H2 implements no row-level security, so no in-process
 * engine could make these cells mean anything.
 *
 * {@snippet lang="shell" :
 * mvn -pl exeris-e2e-tests -am test -Dtest=SharedScopePostgresE2ETest \
 *     -Dexeris.e2e.postgres.url='jdbc:postgresql://localhost:5432/postgres?user=postgres&password=…'
 * }
 *
 * <p>The kernel's {@code AbstractSharedScopeAccessMatrixTck} cannot be bound verbatim: its keys are
 * non-UUID text ({@code "tenant-a"}, {@code "world-alpha"}), while every emitted tenant column is a
 * UUID. So its four cells are restated here over UUID keys, plus the one cell the TCK leaves out
 * and the emitted policy shape exists to hold: a partition-mate can <em>read</em> a shared row but
 * cannot update, re-own or delete it (ADR-012 §4b.4).
 *
 * <p><b>The acting role is what makes this non-vacuous.</b> A superuser or a {@code BYPASSRLS} role
 * skips row-level security entirely, even on a {@code FORCE}d table, and every cell below would pass
 * for the wrong reason. The cells therefore run under {@code SET ROLE} to a dedicated
 * {@code NOSUPERUSER NOBYPASSRLS} role that does not own the table, and a precondition asserts those
 * attributes before any cell runs.
 */
@Tag("e2e")
@Tag("postgres")
@DisplayName("Shared scope on PostgreSQL (opt-in): the emitted migrations hold the access matrix")
class SharedScopePostgresE2ETest {

    private static final String URL_PROPERTY = "exeris.e2e.postgres.url";
    private static final String SCHEMA = "exeris_shared_scope_e2e";
    private static final String APP_ROLE = "exeris_shared_scope_app";

    private static final String OWNER_A = "00000000-0000-4000-8000-00000000000a";
    private static final String OWNER_B = "00000000-0000-4000-8000-00000000000b";
    private static final String WORLD = "00000000-0000-4000-8000-00000000000f";
    private static final String NO_SCOPE = "";

    /** Row payloads ({@code x}), one per seeded row. */
    private static final int A_IN_SCOPE = 1;
    private static final int B_IN_SCOPE = 2;
    private static final int A_PRIVATE = 3;

    @TempDir
    static Path workspace;

    private static Connection admin;

    @BeforeAll
    static void applyTheEmittedMigrations() throws IOException, SQLException {
        String url = System.getProperty(URL_PROPERTY);
        assumeTrue(url != null && !url.isBlank(),
                "opt-in: set -D" + URL_PROPERTY + "=<jdbc:postgresql://…> to run the PostgreSQL matrix");

        Path classes = workspace.resolve("target/classes");
        Path generated = workspace.resolve("src/main/generated/java");
        ProcessorCompiler.compile(workspace.resolve("src"), classes, null, SharedScopeSqlE2ETest.sources());
        CodegenPipeline.createDefault().run(classes.resolve("exeris-metadata"), generated, "com.world");
        String create = migration(generated, "__create_galaxy_presences");
        String widen = migration(generated, "__shared_scope_galaxy_presences");

        admin = DriverManager.getConnection(url);
        try (Statement st = admin.createStatement()) {
            st.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
            st.execute("DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '" + APP_ROLE
                    + "') THEN CREATE ROLE " + APP_ROLE + " NOLOGIN NOSUPERUSER NOBYPASSRLS; END IF; END $$");
            st.execute("CREATE SCHEMA " + SCHEMA);
            st.execute("SET search_path TO " + SCHEMA);
            // Verbatim, as a consumer's Flyway would run them, and in version order.
            st.execute(create);
            st.execute(widen);
            // Replaying the widening must be harmless (DROP POLICY IF EXISTS / CREATE INDEX IF NOT EXISTS).
            st.execute(widen);
            st.execute("GRANT USAGE ON SCHEMA " + SCHEMA + " TO " + APP_ROLE);
            st.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON galaxy_presences TO " + APP_ROLE);
        }

        asTenant(OWNER_A, WORLD, () -> insert(OWNER_A, WORLD, A_IN_SCOPE));
        asTenant(OWNER_B, WORLD, () -> insert(OWNER_B, WORLD, B_IN_SCOPE));
        asTenant(OWNER_A, NO_SCOPE, () -> insert(OWNER_A, null, A_PRIVATE));
    }

    @AfterAll
    static void dropEverything() throws SQLException {
        if (admin == null) {
            return;
        }
        try (Statement st = admin.createStatement()) {
            st.execute("RESET ROLE");
            st.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
            st.execute("DROP ROLE IF EXISTS " + APP_ROLE);
        } finally {
            admin.close();
        }
    }

    @Test
    @DisplayName("precondition: the acting role can neither bypass RLS nor owns the table")
    void theActingRoleIsSubjectToRowLevelSecurity() throws SQLException {
        try (Statement st = admin.createStatement();
             ResultSet rs = st.executeQuery("SELECT r.rolsuper, r.rolbypassrls, t.tableowner <> r.rolname, "
                     + "c.relforcerowsecurity FROM pg_roles r, pg_tables t, pg_class c "
                     + "WHERE r.rolname = '" + APP_ROLE + "' AND t.schemaname = '" + SCHEMA
                     + "' AND t.tablename = 'galaxy_presences' AND c.oid = '" + SCHEMA
                     + ".galaxy_presences'::regclass")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getBoolean(1)).as("superuser").isFalse();
            assertThat(rs.getBoolean(2)).as("bypassrls").isFalse();
            assertThat(rs.getBoolean(3)).as("not the table owner").isTrue();
            assertThat(rs.getBoolean(4)).as("FORCE ROW LEVEL SECURITY").isTrue();
        }
    }

    @Test
    @DisplayName("read widens: both partition-mates see each other's in-scope rows, symmetrically")
    void readWidensWithinTheSharedScope() throws SQLException {
        assertThat(visibleAs(OWNER_A, WORLD)).contains(A_IN_SCOPE, B_IN_SCOPE, A_PRIVATE);
        assertThat(visibleAs(OWNER_B, WORLD)).contains(A_IN_SCOPE, B_IN_SCOPE).doesNotContain(A_PRIVATE);
    }

    @Test
    @DisplayName("write stays pinned: an insert owned by a partition-mate is refused by the store")
    void writeStaysPinnedToTheOwner() throws SQLException {
        assertThatThrownBy(() -> asTenant(OWNER_A, WORLD, () -> insert(OWNER_B, WORLD, 99)))
                .isInstanceOf(SQLException.class)
                .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("42501"));
        assertThat(visibleAs(OWNER_B, WORLD)).doesNotContain(99);
    }

    @Test
    @DisplayName("no bleed: the same tenant declaring no scope next is tenant-private again")
    void sharedScopeDoesNotLeakOntoAnUnscopedRequest() throws SQLException {
        assertThat(visibleAs(OWNER_A, WORLD)).contains(B_IN_SCOPE);
        assertThat(visibleAs(OWNER_A, NO_SCOPE)).doesNotContain(B_IN_SCOPE);
    }

    @Test
    @DisplayName("tenant-private is unchanged: absent a scope, a tenant sees exactly its own rows")
    void absentSharedScopeIsTenantPrivate() throws SQLException {
        assertThat(visibleAs(OWNER_A, NO_SCOPE)).containsExactlyInAnyOrder(A_IN_SCOPE, A_PRIVATE);
    }

    @Test
    @DisplayName("§4b.4: a partition-mate can read a shared row, but not overwrite, re-own or delete it")
    void aPartitionMateCannotMutateASharedRow() throws SQLException {
        int[] affected = new int[3];
        asTenant(OWNER_A, WORLD, () -> {
            affected[0] = update("UPDATE galaxy_presences SET x = 42 WHERE x = " + B_IN_SCOPE);
            affected[1] = update("UPDATE galaxy_presences SET owner_tenant_id = '" + OWNER_A
                    + "'::uuid WHERE x = " + B_IN_SCOPE);
            affected[2] = update("DELETE FROM galaxy_presences WHERE x = " + B_IN_SCOPE);
        });

        // The kernel's single FOR ALL reference policy admits the last two — its widened USING is
        // also what UPDATE and DELETE target. The emitted SELECT-only widening admits none.
        assertThat(affected).containsExactly(0, 0, 0);
        assertThat(visibleAs(OWNER_B, WORLD)).contains(B_IN_SCOPE);
    }

    @Test
    @DisplayName("fail-closed: unset session keys match no row and raise no cast error")
    void unsetSessionKeysMatchNothing() throws SQLException {
        assertThat(visibleAs(NO_SCOPE, NO_SCOPE)).isEmpty();
    }

    // ── harness ──────────────────────────────────────────────────────────────────────────────

    @FunctionalInterface
    private interface SqlWork {
        void run() throws SQLException;
    }

    /**
     * Runs {@code work} the way a request reaches the database: as the non-owner application role,
     * with both session keys published under the names the kernel publishes them, {@code ''} for
     * absent — {@code set_config(…, false)}, session-scoped, exactly like the interceptor.
     */
    private static void asTenant(String tenant, String scope, SqlWork work) throws SQLException {
        try (Statement st = admin.createStatement();
             PreparedStatement keys = admin.prepareStatement("SELECT set_config(?, ?, false), set_config(?, ?, false)")) {
            st.execute("SET ROLE " + APP_ROLE);
            keys.setString(1, ConnectionInterceptor.SESSION_KEY_TENANT_ID);
            keys.setString(2, tenant);
            keys.setString(3, ConnectionInterceptor.SESSION_KEY_SHARED_SCOPE);
            keys.setString(4, scope);
            keys.execute();
            try {
                work.run();
            } finally {
                st.execute("RESET ROLE");
            }
        }
    }

    private static List<Integer> visibleAs(String tenant, String scope) throws SQLException {
        List<Integer> visible = new ArrayList<>();
        asTenant(tenant, scope, () -> {
            try (Statement st = admin.createStatement();
                 ResultSet rs = st.executeQuery("SELECT x FROM galaxy_presences ORDER BY x")) {
                while (rs.next()) {
                    visible.add(rs.getInt(1));
                }
            }
        });
        return visible;
    }

    private static void insert(String owner, String scope, int x) throws SQLException {
        try (PreparedStatement ps = admin.prepareStatement(
                "INSERT INTO galaxy_presences (owner_tenant_id, universe_id, x) VALUES (?::uuid, ?::uuid, ?)")) {
            ps.setString(1, owner);
            ps.setString(2, scope);
            ps.setInt(3, x);
            ps.executeUpdate();
        }
    }

    private static int update(String sql) throws SQLException {
        try (Statement st = admin.createStatement()) {
            return st.executeUpdate(sql);
        }
    }

    private static String migration(Path generated, String fragment) throws IOException {
        try (Stream<Path> files = Files.list(generated.resolve("db/migration"))) {
            Path file = files.filter(p -> p.getFileName().toString().contains(fragment))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no migration matching '" + fragment + "'"));
            return Files.readString(file);
        }
    }
}
