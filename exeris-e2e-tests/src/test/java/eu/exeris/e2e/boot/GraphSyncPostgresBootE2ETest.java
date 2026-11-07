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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The generated application keeps the Community SQL graph in step with its rows on a <b>real</b>
 * kernel boot against a real PostgreSQL (ADR-070 Amendment 4).
 *
 * <p>{@code RuntimeLifecycle.run()} registers the graph sync's descriptors at boot, which is what
 * creates {@code graph_nodes} and the edge table; the generated service projects each create and
 * update and removes the node on delete. Read back from the graph tables directly:
 *
 * <ul>
 *   <li>a create writes the node, its property and its edge;</li>
 *   <li>an update that changes the edge's target deletes the stale edge and writes the new one;</li>
 *   <li>a delete removes the node and, through the registered edge, its edges.</li>
 * </ul>
 *
 * <p>Without the registration (an application whose graph-sync factory registers nothing) the same
 * create fails the route with {@code 500}, since the graph tables do not exist, while the row it
 * wrote has committed: the graph failure propagates after the commit.
 *
 * <p>PostgreSQL only: the Community SQL graph writes {@code jsonb} and {@code ON CONFLICT}, which
 * the in-memory H2 database of the other boot tests does not run.
 */
@Tag("e2e")
@Tag("boot")
@Tag("postgres")
@DisplayName("ADR-070 Amendment 4 — the generated service keeps the graph in step with its rows on PostgreSQL")
class GraphSyncPostgresBootE2ETest {

    private static final String BASE_PACKAGE = "eu.exeris.e2e.graphsync";
    private static final String SCHEMA = "exeris_graph_sync_e2e";
    private static final String UNREGISTERED_SCHEMA = "exeris_graph_sync_unregistered_e2e";
    private static final String APP_ROLE = "exeris_graph_sync_app";
    private static final String APP_PASSWORD = "graph-sync-e2e";
    private static final String EDGE_TABLE = "belongs_to_edges";
    private static final Pattern ID = Pattern.compile("\"id\":\"([0-9a-f-]{36})\"");
    private static final String CATEGORIES = "/categories";
    private static final String PRODUCTS = "/products";

    @TempDir
    static Path workspace;

    private static PostgresTarget target;
    private static GeneratedTree app;

    @BeforeAll
    static void generate() throws IOException, SQLException {
        target = PostgresTarget.required();
        app = GeneratedTree.build(workspace, BASE_PACKAGE, domainSources(), harnessSources());
        try (Connection admin = target.adminConnection(); Statement st = admin.createStatement()) {
            dropSchemasAndRole(st);
            st.execute("CREATE ROLE " + APP_ROLE + " LOGIN PASSWORD '" + APP_PASSWORD
                    + "' NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE");
        }
    }

    @AfterAll
    static void drop() throws Exception {
        try {
            if (app != null) {
                app.close();
            }
        } finally {
            if (target != null) {
                try (Connection admin = target.adminConnection(); Statement st = admin.createStatement()) {
                    dropSchemasAndRole(st);
                }
            }
        }
    }

    @Test
    @DisplayName("create, an update that moves the edge, and delete are each projected into the graph tables")
    void writesAreProjected() throws Exception {
        createSchema(SCHEMA);
        try (BootedApplication booted = boot(SCHEMA, "GraphApplication")) {
            assertThat(tables(SCHEMA)).as("registered at boot").contains("graph_nodes", EDGE_TABLE);

            String kitchen = created(post(booted, CATEGORIES, "{\"name\":\"Kitchen\"}"));
            String garden = created(post(booted, CATEGORIES, "{\"name\":\"Garden\"}"));
            String product = created(post(booted, PRODUCTS,
                    "{\"name\":\"Kettle\",\"categoryId\":\"" + kitchen + "\"}"));

            assertThat(node(product)).containsExactly("Product", "Kettle");
            assertThat(edgeTargets(product)).containsExactly(kitchen);

            String put = RawHttp.request(booted.port(), "PUT", PRODUCTS + "/" + product,
                    "{\"name\":\"Hose\",\"categoryId\":\"" + garden + "\"}");
            assertThat(put).startsWith("HTTP/1.1 200");
            assertThat(node(product)).containsExactly("Product", "Hose");
            assertThat(edgeTargets(product)).as("the stale edge is gone").containsExactly(garden);

            assertThat(RawHttp.request(booted.port(), "DELETE", PRODUCTS + "/" + product))
                    .startsWith("HTTP/1.1 204");
            assertThat(node(product)).as("the node is gone").isEmpty();
            assertThat(edgeTargets(product)).as("its edges went with it").isEmpty();
        }
    }

    @Test
    @DisplayName("without the boot registration a create answers 500, and the row it wrote has committed")
    void unregisteredGraphFailsTheRouteAfterTheCommit() throws Exception {
        createSchema(UNREGISTERED_SCHEMA);
        try (BootedApplication booted = boot(UNREGISTERED_SCHEMA, "UnregisteredGraphApplication")) {
            assertThat(tables(UNREGISTERED_SCHEMA)).doesNotContain("graph_nodes", EDGE_TABLE);

            String category = created(post(booted, CATEGORIES, "{\"name\":\"Hall\"}"));
            String response = post(booted, PRODUCTS, "{\"name\":\"Lamp\",\"categoryId\":\"" + category + "\"}");

            assertThat(response).startsWith("HTTP/1.1 500");
            assertThat(RawHttp.request(booted.port(), "GET", PRODUCTS))
                    .startsWith("HTTP/1.1 200")
                    .contains("\"name\":\"Lamp\"");
        }
    }

    private static BootedApplication boot(String schema, String applicationClass) throws SQLException {
        try (Connection admin = target.adminConnection(); Statement st = admin.createStatement()) {
            st.execute("ALTER ROLE " + APP_ROLE + " SET search_path TO " + schema);
        }
        return BootedApplication.start(app.loader(), BASE_PACKAGE + "." + applicationClass,
                new BootedApplication.Database(target.urlWithoutCredentials(), APP_ROLE, APP_PASSWORD, false));
    }

    private static String post(BootedApplication booted, String path, String body) {
        return RawHttp.request(booted.port(), "POST", path, body);
    }

    private static String created(String response) {
        assertThat(response).startsWith("HTTP/1.1 201");
        Matcher id = ID.matcher(response);
        assertThat(id.find()).as("an id in " + response).isTrue();
        return id.group(1);
    }

    // ------------------------------------------------------------------ database

    private static void createSchema(String schema) throws SQLException, IOException {
        try (Connection admin = target.adminConnection(); Statement st = admin.createStatement()) {
            st.execute("CREATE SCHEMA " + schema);
            st.execute("SET search_path TO " + schema);
            for (Path migration : migrations(app.generatedRoot())) {
                st.execute(Files.readString(migration));
            }
            st.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA " + schema + " TO " + APP_ROLE);
            st.execute("GRANT USAGE, CREATE ON SCHEMA " + schema + " TO " + APP_ROLE);
        }
    }

    private static void dropSchemasAndRole(Statement st) throws SQLException {
        st.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        st.execute("DROP SCHEMA IF EXISTS " + UNREGISTERED_SCHEMA + " CASCADE");
        st.execute("DO $$ BEGIN IF EXISTS (SELECT FROM pg_roles WHERE rolname = '" + APP_ROLE + "') THEN "
                + "EXECUTE 'DROP OWNED BY " + APP_ROLE + " CASCADE'; EXECUTE 'DROP ROLE " + APP_ROLE + "'; "
                + "END IF; END $$");
    }

    private static List<String> tables(String schema) throws SQLException {
        List<String> names = new ArrayList<>();
        try (Connection admin = target.adminConnection();
             PreparedStatement st = admin.prepareStatement(
                     "SELECT table_name FROM information_schema.tables WHERE table_schema = ? ORDER BY table_name")) {
            st.setString(1, schema);
            try (ResultSet rows = st.executeQuery()) {
                while (rows.next()) {
                    names.add(rows.getString(1));
                }
            }
        }
        return names;
    }

    /** The node's label and its {@code name} property, or nothing when there is no node. */
    private static List<String> node(String id) throws SQLException {
        List<String> node = new ArrayList<>();
        try (Connection admin = target.adminConnection();
             PreparedStatement st = admin.prepareStatement(
                     "SELECT label, properties->>'name' FROM " + SCHEMA + ".graph_nodes WHERE id = ?")) {
            st.setObject(1, UUID.fromString(id));
            try (ResultSet rows = st.executeQuery()) {
                while (rows.next()) {
                    node.add(rows.getString(1));
                    node.add(rows.getString(2));
                }
            }
        }
        return node;
    }

    private static List<String> edgeTargets(String source) throws SQLException {
        List<String> targets = new ArrayList<>();
        try (Connection admin = target.adminConnection();
             PreparedStatement st = admin.prepareStatement(
                     "SELECT target_id FROM " + SCHEMA + "." + EDGE_TABLE + " WHERE source_id = ? ORDER BY target_id")) {
            st.setObject(1, UUID.fromString(source));
            try (ResultSet rows = st.executeQuery()) {
                while (rows.next()) {
                    targets.add(rows.getString(1));
                }
            }
        }
        return targets;
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
        sources.put("eu/exeris/e2e/graphsync/domain/Category.java",
                """
                package eu.exeris.e2e.graphsync.domain;

                import eu.exeris.sdk.annotation.ExerisDomain;
                import eu.exeris.sdk.annotation.Field;

                import java.util.UUID;

                @ExerisDomain(module = "catalog", path = "/categories")
                public class Category {

                    private UUID id;

                    @Field(label = "Name")
                    private String name;

                    public UUID getId() { return id; }
                    public void setId(UUID id) { this.id = id; }
                    public String getName() { return name; }
                    public void setName(String name) { this.name = name; }
                }
                """);
        sources.put("eu/exeris/e2e/graphsync/domain/Product.java",
                """
                package eu.exeris.e2e.graphsync.domain;

                import eu.exeris.sdk.annotation.ExerisDomain;
                import eu.exeris.sdk.annotation.Field;
                import eu.exeris.sdk.annotation.Graph;
                import eu.exeris.sdk.annotation.GraphEdge;
                import eu.exeris.sdk.annotation.GraphProperty;

                import java.util.UUID;

                @ExerisDomain(module = "catalog", path = "/products")
                @Graph
                public class Product {

                    private UUID id;

                    @Field(label = "Name")
                    @GraphProperty
                    private String name;

                    @GraphEdge(type = "BELONGS_TO", target = Category.class)
                    private UUID categoryId;

                    public UUID getId() { return id; }
                    public void setId(UUID id) { this.id = id; }
                    public String getName() { return name; }
                    public void setName(String name) { this.name = name; }
                    public UUID getCategoryId() { return categoryId; }
                    public void setCategoryId(UUID categoryId) { this.categoryId = categoryId; }
                }
                """);
        return sources;
    }

    private static Map<String, String> harnessSources() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("eu/exeris/e2e/graphsync/GraphApplication.java",
                """
                package eu.exeris.e2e.graphsync;

                import eu.exeris.kernel.spi.persistence.TransactionalExecutor;

                public class GraphApplication extends Application {

                    @Override
                    protected RuntimeComponents components(TransactionalExecutor transactionalExecutor) {
                        return new ProbeComponents(transactionalExecutor);
                    }
                }
                """);
        sources.put("eu/exeris/e2e/graphsync/UnregisteredGraphApplication.java",
                """
                package eu.exeris.e2e.graphsync;

                import eu.exeris.e2e.graphsync.graph.ProductGraphSync;
                import eu.exeris.kernel.spi.context.KernelProviders;
                import eu.exeris.kernel.spi.graph.GraphKernelProviders;
                import eu.exeris.kernel.spi.persistence.TransactionalExecutor;

                public class UnregisteredGraphApplication extends Application {

                    @Override
                    protected RuntimeComponents components(TransactionalExecutor transactionalExecutor) {
                        return new ProbeComponents(transactionalExecutor) {
                            @Override
                            protected ProductGraphSync createProductGraphSync() {
                                return new ProductGraphSync(GraphKernelProviders.GRAPH_ENGINE.get(),
                                        KernelProviders.MEMORY_ALLOCATOR.get()) {
                                    @Override
                                    public void registerDescriptors() {
                                        // registers nothing, so the graph tables are never created
                                    }
                                };
                            }
                        };
                    }
                }
                """);
        sources.put("eu/exeris/e2e/graphsync/ProbeComponents.java",
                """
                package eu.exeris.e2e.graphsync;

                import eu.exeris.kernel.core.http.routing.HttpRouter;
                import eu.exeris.kernel.spi.http.HttpMethod;
                import eu.exeris.kernel.spi.http.HttpStatus;
                import eu.exeris.kernel.spi.persistence.TransactionalExecutor;

                public class ProbeComponents extends RuntimeComponents {

                    public ProbeComponents(TransactionalExecutor transactionalExecutor) {
                        super(transactionalExecutor);
                    }

                    @Override
                    public void configureRoutes(HttpRouter.Builder routes) {
                        routes.route(HttpMethod.GET, "/probe", exchange -> exchange.respond(HttpStatus.OK));
                    }
                }
                """);
        return sources;
    }
}
