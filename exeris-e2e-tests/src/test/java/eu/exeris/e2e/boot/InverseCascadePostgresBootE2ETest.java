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
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code cascadeDelete} of an inverse collection on a <b>real</b> kernel boot against a real
 * PostgreSQL (ADR-076): the rows of a {@code ONE_TO_MANY} with {@code mappedBy} are the target's
 * rows, listed on the target's list route by the back-reference's key, and the collection's
 * {@code cascadeDelete} is the {@code ON DELETE CASCADE} of the target's foreign key.
 *
 * <p>{@code Invoice.lines} cascades: deleting an invoice removes its lines. {@code Basket.items}
 * does not: deleting a basket that still has items answers {@code 409}, and once the items are
 * deleted it answers {@code 204}. The emitted migrations, the foreign-key migration included, are
 * applied verbatim by an administrative role, as a consumer's Flyway runs them.
 */
@Tag("e2e")
@Tag("boot")
@Tag("postgres")
@DisplayName("ADR-076 — an inverse collection's cascadeDelete removes the children on PostgreSQL")
class InverseCascadePostgresBootE2ETest {

    private static final String BASE_PACKAGE = "eu.exeris.e2e.billing";
    private static final String SCHEMA = "exeris_inverse_cascade_e2e";
    private static final String APP_ROLE = "exeris_inverse_cascade_app";
    private static final String APP_PASSWORD = "inverse-cascade-e2e";
    private static final Pattern ID = Pattern.compile("\"id\":\"([0-9a-f-]{36})\"");
    private static final String STATUS_200 = "HTTP/1.1 200";
    private static final String STATUS_204 = "HTTP/1.1 204";
    private static final String STATUS_404 = "HTTP/1.1 404";
    private static final String STATUS_409 = "HTTP/1.1 409";

    @TempDir
    static Path workspace;

    private static PostgresTarget target;
    private static GeneratedTree app;
    private static BootedApplication booted;

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
            for (Path migration : migrations(app.generatedRoot())) {
                st.execute(Files.readString(migration));
            }
            st.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA " + SCHEMA + " TO " + APP_ROLE);
            st.execute("GRANT USAGE, CREATE ON SCHEMA " + SCHEMA + " TO " + APP_ROLE);
            st.execute("ALTER ROLE " + APP_ROLE + " SET search_path TO " + SCHEMA);
        }

        booted = BootedApplication.start(app.loader(), BASE_PACKAGE + ".BillingApplication",
                new BootedApplication.Database(target.urlWithoutCredentials(), APP_ROLE, APP_PASSWORD, false));
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
    @DisplayName("deleting an invoice whose lines cascade answers 204 and removes the lines")
    void cascadeDeleteRemovesTheChildren() {
        String invoice = created(post("/invoices", "{\"number\":\"INV-1\"}"));
        String first = created(post("/invoice-lines", "{\"description\":\"first\",\"invoiceId\":\"" + invoice + "\"}"));
        String second = created(post("/invoice-lines", "{\"description\":\"second\",\"invoiceId\":\"" + invoice + "\"}"));

        assertThat(get("/invoice-lines?invoiceId=" + invoice))
                .as("the rows of the collection are listed on the target's route")
                .startsWith(STATUS_200).contains(first).contains(second);

        assertThat(delete("/invoices/" + invoice)).startsWith(STATUS_204);

        assertThat(get("/invoice-lines/" + first)).startsWith(STATUS_404);
        assertThat(get("/invoice-lines/" + second)).startsWith(STATUS_404);
        assertThat(get("/invoice-lines?invoiceId=" + invoice))
                .startsWith(STATUS_200).doesNotContain(first).doesNotContain(second);
    }

    @Test
    @DisplayName("deleting a basket whose items do not cascade answers 409 while an item holds it; once it goes, 204")
    void withoutCascadeTheDeleteIsRefused() {
        String basket = created(post("/baskets", "{\"label\":\"held\"}"));
        String item = created(post("/basket-items", "{\"sku\":\"A-1\",\"basketId\":\"" + basket + "\"}"));

        assertThat(delete("/baskets/" + basket)).startsWith(STATUS_409);
        assertThat(get("/basket-items/" + item)).startsWith(STATUS_200);

        assertThat(delete("/basket-items/" + item)).startsWith(STATUS_204);
        assertThat(delete("/baskets/" + basket)).startsWith(STATUS_204);
    }

    private static String post(String path, String body) {
        return RawHttp.request(booted.port(), "POST", path, body);
    }

    private static String get(String path) {
        return RawHttp.request(booted.port(), "GET", path);
    }

    private static String delete(String path) {
        return RawHttp.request(booted.port(), "DELETE", path);
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
        sources.put("eu/exeris/e2e/billing/domain/Invoice.java",
                parent("Invoice", "invoices", "number", "InvoiceLine", "description", "lines", "invoiceId", true));
        sources.put("eu/exeris/e2e/billing/domain/InvoiceLine.java",
                child("InvoiceLine", "invoice-lines", "description", "Invoice", "number", "invoiceId"));
        sources.put("eu/exeris/e2e/billing/domain/Basket.java",
                parent("Basket", "baskets", "label", "BasketItem", "sku", "items", "basketId", false));
        sources.put("eu/exeris/e2e/billing/domain/BasketItem.java",
                child("BasketItem", "basket-items", "sku", "Basket", "label", "basketId"));
        return sources;
    }

    /**
     * An entity with one {@code String} field and the inverse collection {@code collection} of
     * {@code child}, shown by the child's {@code childText}, whose {@code mappedBy} names
     * {@code key}, the child's reference to it.
     */
    private static String parent(String entity, String path, String text, String child, String childText,
                                 String collection, String key, boolean cascade) {
        return """
                package eu.exeris.e2e.billing.domain;

                import eu.exeris.sdk.annotation.ExerisDomain;
                import eu.exeris.sdk.annotation.Field;
                import eu.exeris.sdk.annotation.Relationship;

                import java.util.List;
                import java.util.UUID;

                @ExerisDomain(module = "billing", path = "/%2$s")
                public class %1$s {

                    private UUID id;

                    @Field(label = "Text")
                    private String %3$s;

                    @Relationship(targetEntity = %4$s.class, displayField = "%10$s", cascadeDelete = %7$s,
                            relationshipType = Relationship.RelationshipType.ONE_TO_MANY, mappedBy = "%6$s")
                    private List<%4$s> %5$s;

                    public UUID getId() { return id; }
                    public void setId(UUID id) { this.id = id; }
                    public String get%8$s() { return %3$s; }
                    public void set%8$s(String %3$s) { this.%3$s = %3$s; }
                    public List<%4$s> get%9$s() { return %5$s; }
                    public void set%9$s(List<%4$s> %5$s) { this.%5$s = %5$s; }
                }
                """.formatted(entity, path, text, child, collection, key, cascade, capitalize(text),
                        capitalize(collection), childText);
    }

    /**
     * An entity with one {@code String} field and the {@code MANY_TO_ONE} key {@code key} to
     * {@code parent}, shown by the parent's {@code parentText}.
     */
    private static String child(String entity, String path, String text, String parent, String parentText,
                                String key) {
        return """
                package eu.exeris.e2e.billing.domain;

                import eu.exeris.sdk.annotation.ExerisDomain;
                import eu.exeris.sdk.annotation.Field;
                import eu.exeris.sdk.annotation.Relationship;

                import java.util.UUID;

                @ExerisDomain(module = "billing", path = "/%2$s")
                public class %1$s {

                    private UUID id;

                    @Field(label = "Text")
                    private String %3$s;

                    @Relationship(targetEntity = %4$s.class, displayField = "%8$s",
                            relationshipType = Relationship.RelationshipType.MANY_TO_ONE)
                    private UUID %5$s;

                    public UUID getId() { return id; }
                    public void setId(UUID id) { this.id = id; }
                    public String get%6$s() { return %3$s; }
                    public void set%6$s(String %3$s) { this.%3$s = %3$s; }
                    public UUID get%7$s() { return %5$s; }
                    public void set%7$s(UUID %5$s) { this.%5$s = %5$s; }
                }
                """.formatted(entity, path, text, parent, key, capitalize(text), capitalize(key),
                        parentText);
    }

    private static String capitalize(String name) {
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    private static Map<String, String> harnessSources() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("eu/exeris/e2e/billing/BillingApplication.java",
                """
                package eu.exeris.e2e.billing;

                import eu.exeris.kernel.spi.persistence.TransactionalExecutor;

                public class BillingApplication extends Application {

                    @Override
                    protected RuntimeComponents components(TransactionalExecutor transactionalExecutor) {
                        return new BillingComponents(transactionalExecutor);
                    }
                }
                """);
        sources.put("eu/exeris/e2e/billing/BillingComponents.java",
                """
                package eu.exeris.e2e.billing;

                import eu.exeris.kernel.core.http.routing.HttpRouter;
                import eu.exeris.kernel.spi.http.HttpMethod;
                import eu.exeris.kernel.spi.http.HttpStatus;
                import eu.exeris.kernel.spi.persistence.TransactionalExecutor;

                public class BillingComponents extends RuntimeComponents {

                    public BillingComponents(TransactionalExecutor transactionalExecutor) {
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
