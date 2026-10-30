package eu.exeris.e2e.boot;

import eu.exeris.e2e.codegen.compile.GeneratedTree;
import eu.exeris.e2e.postgres.PostgresTarget;
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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tenant isolation of the emitted application, over HTTP, on a <b>real</b> kernel boot against a
 * real PostgreSQL: the T41 tenant guard, the ADR-090 refusal of a foreign tenant, and row-level
 * security on every read, the per-action stream's {@code findById} included (ADR-044 Amendment 2).
 *
 * <pre>
 *   @ExerisDomain(TENANT) + @ExerisDomain(UNIVERSE), each with a streaming and a respond-once @Action
 *        → javac + ExerisDomainProcessor      (real metadata)
 *        → CodegenPipeline.run                (handlers, repositories, migrations, Application)
 *        → the emitted migrations, applied by an administrative role, as a consumer's Flyway would
 *        → KernelBootstrap                    (http + events + persistence on PostgreSQL, RLS interceptor on)
 *        → a real socket
 * </pre>
 *
 * <p><b>Who binds the tenant.</b> The kernel binds {@code STORAGE_CONTEXT} only on a route whose
 * requirement is not {@code permitAll()}, and the emitted application binds no such requirement
 * (0.10.0 ships the route-policy seam, J3a). So without help every request reaches a tenant-scoped
 * handler unbound, and the guard answers {@code 500}: that is the first assertion. The application
 * here then does what ADR-079 and ADR-090 leave to a consumer: a {@code decorate} wrapper binds
 * {@code STORAGE_CONTEXT} from the request, here from two test headers, around requests and streams
 * alike. The kernel's row-level-security interceptor publishes it on each connection.
 *
 * <p><b>The acting role is what makes this non-vacuous.</b> The application connects as a
 * {@code NOSUPERUSER NOBYPASSRLS} role that owns none of the entity tables (ADR-090, "The required
 * database role model"); a superuser or a {@code BYPASSRLS} role would pass every cross-tenant cell
 * for the wrong reason. A precondition asserts the attributes.
 */
@Tag("e2e")
@Tag("boot")
@Tag("postgres")
@DisplayName("Tenant isolation on PostgreSQL: guard, foreign-tenant refusal and RLS findById, over HTTP")
class TenantIsolationPostgresBootE2ETest {

    private static final String BASE_PACKAGE = "eu.exeris.e2e.tenancy";
    private static final String SCHEMA = "exeris_tenant_isolation_e2e";
    private static final String APP_ROLE = "exeris_tenant_isolation_app";
    private static final String APP_PASSWORD = "tenant-isolation-e2e";

    private static final String TENANT_HEADER = "X-Test-Tenant";
    private static final String SCOPE_HEADER = "X-Test-Shared-Scope";

    private static final String TENANT_A = "00000000-0000-4000-8000-00000000000a";
    private static final String TENANT_B = "00000000-0000-4000-8000-00000000000b";
    private static final String WORLD = "00000000-0000-4000-8000-00000000000f";

    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(20);
    private static final Pattern ID = Pattern.compile("\"id\":\"([0-9a-f-]{36})\"");

    @TempDir
    static Path workspace;

    private static PostgresTarget target;
    private static GeneratedTree app;
    private static BootedApplication booted;

    /** Tenant A's ledger, and tenant B's. */
    private static String ledgerA;
    private static String ledgerB;
    /** Tenant A's outpost tagged with the shared scope, and its private one. */
    private static String outpostShared;
    private static String outpostPrivate;

    @BeforeAll
    static void bootOnPostgres() throws IOException, SQLException {
        target = PostgresTarget.required();
        app = GeneratedTree.build(workspace, BASE_PACKAGE, domainSources(), harnessSources());

        try (Connection admin = target.adminConnection(); Statement st = admin.createStatement()) {
            dropSchemaAndRole(st);
            st.execute("CREATE ROLE " + APP_ROLE + " LOGIN PASSWORD '" + APP_PASSWORD
                    + "' NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE");
            st.execute("CREATE SCHEMA " + SCHEMA);
            st.execute("SET search_path TO " + SCHEMA);
            // Verbatim and in version order, as a consumer's Flyway runs them, by the administrative
            // role: the application role owns none of the entity tables.
            for (Path migration : migrations(app.generatedRoot())) {
                st.execute(Files.readString(migration));
            }
            st.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA " + SCHEMA + " TO " + APP_ROLE);
            // CREATE for the kernel's own migrations (outbox, saga state, event log), which the boot
            // applies as the role it connects with.
            st.execute("GRANT USAGE, CREATE ON SCHEMA " + SCHEMA + " TO " + APP_ROLE);
            // A role default rather than a URL parameter: the RLS interceptor issues RESET search_path
            // on every acquire, which returns to this default.
            st.execute("ALTER ROLE " + APP_ROLE + " SET search_path TO " + SCHEMA);
        }

        booted = BootedApplication.start(app.loader(), BASE_PACKAGE + ".TenancyApplication",
                new BootedApplication.Database(target.urlWithoutCredentials(), APP_ROLE, APP_PASSWORD, true));

        ledgerA = created(post("/ledgers", "{\"label\":\"alpha\"}", as(TENANT_A)));
        ledgerB = created(post("/ledgers", "{\"label\":\"bravo\"}", as(TENANT_B)));
        outpostShared = created(post("/outposts", "{\"label\":\"shared\",\"universeId\":\"" + WORLD + "\"}",
                as(TENANT_A, WORLD)));
        outpostPrivate = created(post("/outposts", "{\"label\":\"private\"}", as(TENANT_A)));
    }

    @AfterAll
    static void stopAndDrop() throws Exception {
        try {
            if (booted != null) {
                booted.close();
            }
            if (app != null) {
                app.close();
            }
        } finally {
            if (target != null) {
                try (Connection admin = target.adminConnection(); Statement st = admin.createStatement()) {
                    dropSchemaAndRole(st);
                }
            }
        }
    }

    @Test
    @DisplayName("precondition: the application role can neither bypass RLS nor owns a table, and the "
            + "tables force row-level security")
    void theApplicationRoleIsSubjectToRowLevelSecurity() throws SQLException {
        try (Connection admin = target.adminConnection(); Statement st = admin.createStatement();
             ResultSet rs = st.executeQuery("SELECT r.rolsuper, r.rolbypassrls, t.tablename, t.tableowner, "
                     + "c.relforcerowsecurity FROM pg_roles r, pg_tables t JOIN pg_class c "
                     + "ON c.oid = (quote_ident(t.schemaname) || '.' || quote_ident(t.tablename))::regclass "
                     + "WHERE r.rolname = '" + APP_ROLE + "' AND t.schemaname = '" + SCHEMA
                     + "' AND t.tablename IN ('ledgers', 'outposts') ORDER BY t.tablename")) {
            int tables = 0;
            while (rs.next()) {
                tables++;
                assertThat(rs.getBoolean(1)).as("superuser").isFalse();
                assertThat(rs.getBoolean(2)).as("bypassrls").isFalse();
                assertThat(rs.getString(4)).as(rs.getString(3) + " owner").isNotEqualTo(APP_ROLE);
                assertThat(rs.getBoolean(5)).as(rs.getString(3) + " FORCE ROW LEVEL SECURITY").isTrue();
            }
            assertThat(tables).as("entity tables found").isEqualTo(2);
        }
    }

    @Test
    @DisplayName("unbound (the 0.10 default): a CRUD request answers 500 and the action stream a 500 "
            + "stream-error frame, before any row is read")
    void unboundRequestsAreRefusedByTheTenantGuard() throws Exception {
        assertThat(get("/ledgers/" + ledgerA, Map.of())).startsWith("HTTP/1.1 500");
        assertThat(get("/ledgers", Map.of())).startsWith("HTTP/1.1 500");
        assertThat(put("/ledgers/" + ledgerA, "{\"label\":\"unbound\"}", Map.of())).startsWith("HTTP/1.1 500");
        assertThat(post("/ledgers", "{\"label\":\"unbound\"}", Map.of())).startsWith("HTTP/1.1 500");
        assertThat(post("/ledgers/" + ledgerA + "/actions/rename", "{\"label\":\"unbound\"}", Map.of()))
                .startsWith("HTTP/1.1 500");
        assertThat(stream("/ledgers/" + ledgerA + "/actions/close", Map.of()))
                .containsExactly("event: stream-error",
                        "data: {\"type\":\"about:blank\",\"title\":\"Internal Server Error\",\"status\":500}", "");
        assertThat(stream("/outposts/" + outpostShared + "/actions/ping", Map.of()))
                .containsExactly("event: stream-error",
                        "data: {\"type\":\"about:blank\",\"title\":\"Internal Server Error\",\"status\":500}", "");

        assertThat(get("/ledgers/" + ledgerA, as(TENANT_A))).as("nothing was written")
                .startsWith("HTTP/1.1 200").contains("\"label\":\"alpha\"");
    }

    @Test
    @DisplayName("a body naming another tenant is refused with 400 on create and update")
    void aForeignTenantInTheBodyIsRefused() {
        assertThat(post("/ledgers", "{\"label\":\"intruder\",\"tenantId\":\"" + TENANT_B + "\"}", as(TENANT_A)))
                .startsWith("HTTP/1.1 400");
        assertThat(put("/ledgers/" + ledgerA, "{\"label\":\"moved\",\"tenantId\":\"" + TENANT_B + "\"}",
                as(TENANT_A))).startsWith("HTTP/1.1 400");
        assertThat(post("/outposts", "{\"label\":\"intruder\",\"ownerTenantId\":\"" + TENANT_B + "\"}",
                as(TENANT_A))).startsWith("HTTP/1.1 400");

        assertThat(get("/ledgers/" + ledgerA, as(TENANT_A))).contains("\"label\":\"alpha\"")
                .contains("\"tenantId\":\"" + TENANT_A + "\"");
        assertThat(get("/ledgers", as(TENANT_B))).doesNotContain("intruder");
    }

    @Test
    @DisplayName("another tenant's row is 404 on GET, PUT, the respond-once @Action and the action stream's "
            + "frame, and absent from the list")
    void anotherTenantsRowIsInvisible() throws Exception {
        assertThat(get("/ledgers/" + ledgerA, as(TENANT_B))).startsWith("HTTP/1.1 404");
        assertThat(put("/ledgers/" + ledgerA, "{\"label\":\"overwritten\"}", as(TENANT_B))).startsWith("HTTP/1.1 404");
        assertThat(post("/ledgers/" + ledgerA + "/actions/rename", "{\"label\":\"renamed\"}", as(TENANT_B)))
                .startsWith("HTTP/1.1 404");
        assertThat(stream("/ledgers/" + ledgerA + "/actions/close", as(TENANT_B)))
                .containsExactly("event: stream-error",
                        "data: {\"type\":\"about:blank\",\"title\":\"Not Found\",\"status\":404}", "");
        assertThat(get("/ledgers", as(TENANT_B))).contains(ledgerB).doesNotContain(ledgerA);

        assertThat(get("/ledgers/" + ledgerA, as(TENANT_A))).as("tenant A's row is untouched")
                .startsWith("HTTP/1.1 200").contains("\"label\":\"alpha\"");
    }

    @Test
    @DisplayName("the owner's own action stream runs the action and streams its event")
    void theOwnersStreamRunsTheAction() throws Exception {
        assertThat(stream("/ledgers/" + ledgerB + "/actions/close", as(TENANT_B)))
                .containsExactly("event: LedgerClosed", "data: {\"label\":\"bravo closed\"}", "");
        assertThat(get("/ledgers/" + ledgerB, as(TENANT_B))).contains("\"label\":\"bravo closed\"");
    }

    @Test
    @DisplayName("UNIVERSE: reads widen to a partition-mate in the shared scope, and only there")
    void sharedScopeReadsWiden() {
        assertThat(get("/outposts/" + outpostShared, as(TENANT_B, WORLD)))
                .startsWith("HTTP/1.1 200").contains("\"label\":\"shared\"");
        assertThat(get("/outposts/" + outpostPrivate, as(TENANT_B, WORLD))).startsWith("HTTP/1.1 404");
        assertThat(get("/outposts", as(TENANT_B, WORLD))).contains(outpostShared).doesNotContain(outpostPrivate);

        assertThat(get("/outposts/" + outpostShared, as(TENANT_B))).as("no scope declared")
                .startsWith("HTTP/1.1 404");
        assertThat(get("/outposts", as(TENANT_B))).doesNotContain(outpostShared);
    }

    @Test
    @DisplayName("UNIVERSE: writes stay pinned to the owner; a partition-mate that can read the row "
            + "cannot update, act on, re-own or delete it")
    void sharedScopeWritesStayPinned() throws Exception {
        String update = put("/outposts/" + outpostShared,
                "{\"label\":\"taken\",\"universeId\":\"" + WORLD + "\"}", as(TENANT_B, WORLD));
        String action = post("/outposts/" + outpostShared + "/actions/relabel", "{\"label\":\"taken\"}",
                as(TENANT_B, WORLD));
        List<String> frames = stream("/outposts/" + outpostShared + "/actions/ping", as(TENANT_B, WORLD));
        String reown = put("/outposts/" + outpostShared,
                "{\"label\":\"taken\",\"ownerTenantId\":\"" + TENANT_B + "\",\"universeId\":\"" + WORLD + "\"}",
                as(TENANT_B, WORLD));
        String delete = request("DELETE", "/outposts/" + outpostShared, null, as(TENANT_B, WORLD));

        // The UPDATE and DELETE see the owner policy alone, so they match no row. The owner is
        // not in the SET list, so naming the partition-mate as owner moves nothing either.
        assertThat(update).as("PUT").startsWith("HTTP/1.1 404");
        assertThat(reown).as("PUT naming the partition-mate as owner").startsWith("HTTP/1.1 404");
        assertThat(delete).as("DELETE").startsWith("HTTP/1.1 404");
        // An action loads the row through the widened read, then saves it under its owner, which
        // the repository refuses for a request bound to another tenant (ADR-090, trade-offs).
        assertThat(action).as("respond-once @Action").startsWith("HTTP/1.1 400");
        assertThat(frames).as("action stream").containsExactly("event: stream-error",
                "data: {\"type\":\"about:blank\",\"title\":\"Bad Request\",\"status\":400}", "");

        assertThat(get("/outposts/" + outpostShared, as(TENANT_A, WORLD))).as("the owner's row is untouched")
                .startsWith("HTTP/1.1 200").contains("\"label\":\"shared\"")
                .contains("\"ownerTenantId\":\"" + TENANT_A + "\"");
    }

    // ------------------------------------------------------------------ HTTP

    private static Map<String, String> as(String tenant) {
        return Map.of(TENANT_HEADER, tenant);
    }

    private static Map<String, String> as(String tenant, String sharedScope) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(TENANT_HEADER, tenant);
        headers.put(SCOPE_HEADER, sharedScope);
        return headers;
    }

    private static String get(String path, Map<String, String> headers) {
        return request("GET", path, null, headers);
    }

    private static String put(String path, String body, Map<String, String> headers) {
        return request("PUT", path, body, headers);
    }

    private static String post(String path, String body, Map<String, String> headers) {
        return request("POST", path, body, headers);
    }

    private static String request(String method, String path, String body, Map<String, String> headers) {
        return RawHttp.request(booted.port(), method, path, body, headers);
    }

    /** Opens a body-less action stream, asserts the SSE head, and returns its lines once the server closes it. */
    private static List<String> stream(String path, Map<String, String> headers) throws InterruptedException {
        try (SseConnection sse = SseConnection.open(booted.port(), "POST", path, null, headers)) {
            assertThat(sse.head()).startsWith("HTTP/1.1 200").containsIgnoringCase("text/event-stream");
            return sse.awaitServerClose(CLOSE_TIMEOUT);
        } catch (IOException e) {
            throw new AssertionError("closing the stream " + path + " failed", e);
        }
    }

    private static String created(String response) {
        assertThat(response).startsWith("HTTP/1.1 201");
        Matcher id = ID.matcher(response);
        assertThat(id.find()).as("an id in " + response).isTrue();
        return id.group(1);
    }

    // ------------------------------------------------------------------ database

    private static void dropSchemaAndRole(Statement st) throws SQLException {
        st.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        st.execute("DO $$ BEGIN IF EXISTS (SELECT FROM pg_roles WHERE rolname = '" + APP_ROLE + "') THEN "
                + "EXECUTE 'DROP OWNED BY " + APP_ROLE + " CASCADE'; EXECUTE 'DROP ROLE " + APP_ROLE + "'; "
                + "END IF; END $$");
    }

    private static List<Path> migrations(Path generated) throws IOException {
        try (Stream<Path> files = Files.walk(generated.resolve("db/migration"))) {
            return files.filter(p -> p.toString().endsWith(".sql"))
                    .sorted((x, y) -> x.getFileName().toString().compareTo(y.getFileName().toString()))
                    .toList();
        }
    }

    // ------------------------------------------------------------------ sources

    private static Map<String, String> domainSources() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("eu/exeris/e2e/tenancy/domain/Ledger.java",
                """
                package eu.exeris.e2e.tenancy.domain;

                import eu.exeris.sdk.annotation.Action;
                import eu.exeris.sdk.annotation.ActionParam;
                import eu.exeris.sdk.annotation.DomainEvent;
                import eu.exeris.sdk.annotation.DomainEvent.Trigger;
                import eu.exeris.sdk.annotation.ExerisDomain;
                import eu.exeris.sdk.annotation.Field;
                import eu.exeris.sdk.annotation.system.TenantId;

                import java.util.UUID;

                @ExerisDomain(module = "tenancy", path = "/ledgers", dataScope = ExerisDomain.DataScope.TENANT)
                @DomainEvent(name = "LedgerClosed", trigger = Trigger.ACTION, action = "close",
                        topic = "ledgers.closed", includeFields = {"label"})
                public class Ledger {

                    private UUID id;

                    @Field(label = "Label", required = true)
                    private String label;

                    @Field(label = "Tenant")
                    @TenantId
                    private UUID tenantId;

                    public UUID getId() { return id; }
                    public void setId(UUID id) { this.id = id; }
                    public String getLabel() { return label; }
                    public void setLabel(String label) { this.label = label; }
                    public UUID getTenantId() { return tenantId; }
                    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }

                    @Action(name = "close", label = "Close", streaming = true)
                    public void close() { this.label = this.label + " closed"; }

                    @Action(name = "rename", label = "Rename")
                    public void rename(@ActionParam(label = "Label") String label) { this.label = label; }
                }
                """);
        sources.put("eu/exeris/e2e/tenancy/domain/Outpost.java",
                """
                package eu.exeris.e2e.tenancy.domain;

                import eu.exeris.sdk.annotation.Action;
                import eu.exeris.sdk.annotation.ActionParam;
                import eu.exeris.sdk.annotation.DomainEvent;
                import eu.exeris.sdk.annotation.DomainEvent.Trigger;
                import eu.exeris.sdk.annotation.ExerisDomain;
                import eu.exeris.sdk.annotation.Field;
                import eu.exeris.sdk.annotation.system.SharedScope;
                import eu.exeris.sdk.annotation.system.TenantId;

                import java.util.UUID;

                @ExerisDomain(module = "tenancy", path = "/outposts", dataScope = ExerisDomain.DataScope.UNIVERSE)
                @DomainEvent(name = "OutpostPinged", trigger = Trigger.ACTION, action = "ping",
                        topic = "outposts.pinged", includeFields = {"label"})
                public class Outpost {

                    private UUID id;

                    @Field(label = "Label", required = true)
                    private String label;

                    @Field(label = "Owner")
                    @TenantId
                    private UUID ownerTenantId;

                    @Field(label = "Universe")
                    @SharedScope
                    private UUID universeId;

                    public UUID getId() { return id; }
                    public void setId(UUID id) { this.id = id; }
                    public String getLabel() { return label; }
                    public void setLabel(String label) { this.label = label; }
                    public UUID getOwnerTenantId() { return ownerTenantId; }
                    public void setOwnerTenantId(UUID ownerTenantId) { this.ownerTenantId = ownerTenantId; }
                    public UUID getUniverseId() { return universeId; }
                    public void setUniverseId(UUID universeId) { this.universeId = universeId; }

                    @Action(name = "ping", label = "Ping", streaming = true)
                    public void ping() { this.label = this.label + " pinged"; }

                    @Action(name = "relabel", label = "Relabel")
                    public void relabel(@ActionParam(label = "Label") String label) { this.label = label; }
                }
                """);
        return sources;
    }

    /**
     * The consumer's side: an {@code Application} subclass, and a {@code decorate} wrapper that binds
     * {@code STORAGE_CONTEXT} from the test headers around requests and streams. A request without
     * the tenant header is left unbound, as the kernel leaves it.
     */
    private static Map<String, String> harnessSources() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("eu/exeris/e2e/tenancy/TenancyApplication.java",
                """
                package eu.exeris.e2e.tenancy;

                import eu.exeris.kernel.spi.persistence.TransactionalExecutor;

                public class TenancyApplication extends Application {

                    @Override
                    protected RuntimeComponents components(TransactionalExecutor transactionalExecutor) {
                        return new TenancyComponents(transactionalExecutor);
                    }
                }
                """);
        sources.put("eu/exeris/e2e/tenancy/TenancyComponents.java",
                """
                package eu.exeris.e2e.tenancy;

                import eu.exeris.kernel.core.http.routing.HttpRouter;
                import eu.exeris.kernel.spi.context.KernelProviders;
                import eu.exeris.kernel.spi.events.IsolationKeySpec;
                import eu.exeris.kernel.spi.events.IsolationKeyRegistry;
                import eu.exeris.kernel.spi.http.HttpHandler;
                import eu.exeris.kernel.spi.http.HttpMethod;
                import eu.exeris.kernel.spi.http.HttpStatus;
                import eu.exeris.kernel.spi.persistence.TransactionalExecutor;

                public class TenancyComponents extends RuntimeComponents {

                    public TenancyComponents(TransactionalExecutor transactionalExecutor) {
                        super(transactionalExecutor);
                    }

                    @Override
                    public void configureRoutes(HttpRouter.Builder routes) {
                        routes.route(HttpMethod.GET, "/probe", exchange -> exchange.respond(HttpStatus.OK));
                    }

                    /**
                     * Registers both tenants' isolation keys while the engine is in scope: the event bus
                     * refuses a publish under a bound key it does not hold.
                     */
                    @Override
                    public HttpHandler decorate(HttpRouter router) {
                        IsolationKeyRegistry isolation = KernelProviders.eventEngine().isolationRegistry();
                        isolation.register(IsolationKeySpec.of("%s", 1));
                        isolation.register(IsolationKeySpec.of("%s", 2));
                        return new TenantHeaderBinding(router);
                    }
                }
                """.formatted(TENANT_A, TENANT_B));
        sources.put("eu/exeris/e2e/tenancy/TenantHeaderBinding.java",
                """
                package eu.exeris.e2e.tenancy;

                import eu.exeris.kernel.core.http.routing.HttpRouter;
                import eu.exeris.kernel.spi.context.KernelProviders;
                import eu.exeris.kernel.spi.http.HttpExchange;
                import eu.exeris.kernel.spi.http.HttpHandler;
                import eu.exeris.kernel.spi.http.HttpMethod;
                import eu.exeris.kernel.spi.http.HttpRequest;
                import eu.exeris.kernel.spi.http.HttpStreamHandler;
                import eu.exeris.kernel.spi.http.StreamMatch;
                import eu.exeris.kernel.spi.http.StreamRouteResolver;
                import eu.exeris.kernel.spi.security.ImmutableStorageContext;
                import eu.exeris.kernel.spi.security.StorageContext;

                /** Binds STORAGE_CONTEXT from %s (and %s, when present) around requests and streams. */
                public record TenantHeaderBinding(HttpRouter router) implements HttpHandler, StreamRouteResolver {

                    @Override
                    public void handle(HttpExchange exchange) {
                        StorageContext context = contextOf(exchange.request());
                        if (context == null) {
                            router.handle(exchange);
                        } else {
                            ScopedValue.where(KernelProviders.STORAGE_CONTEXT, context).run(() -> router.handle(exchange));
                        }
                    }

                    @Override
                    public StreamMatch resolveStream(HttpMethod method, String path) {
                        StreamMatch match = router.resolveStream(method, path);
                        if (match == null) {
                            return null;
                        }
                        HttpStreamHandler route = match.handler();
                        return new StreamMatch(exchange -> {
                            StorageContext context = contextOf(exchange.request());
                            if (context == null) {
                                route.handle(exchange);
                            } else {
                                ScopedValue.where(KernelProviders.STORAGE_CONTEXT, context).run(() -> route.handle(exchange));
                            }
                        }, match.params());
                    }

                    private static StorageContext contextOf(HttpRequest request) {
                        String tenant = request.firstHeader("%s").orElse(null);
                        if (tenant == null) {
                            return null;
                        }
                        ImmutableStorageContext context = ImmutableStorageContext.shared(tenant);
                        return request.firstHeader("%s").map(context::withSharedScope).orElse(context);
                    }
                }
                """.formatted(TENANT_HEADER, SCOPE_HEADER, TENANT_HEADER, SCOPE_HEADER));
        return sources;
    }
}
