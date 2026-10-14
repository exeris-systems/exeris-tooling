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
 * A foreign-key violation answers the caller's status on a <b>real</b> kernel boot against a real
 * PostgreSQL (ADR-076 Amendment 1): a write naming a missing row through a relationship is
 * {@code 422} with a problem body naming the relationship, and a delete of a row other rows still
 * reference is {@code 409}.
 *
 * <p>PostgreSQL reports both directions as SQLState {@code 23503} and names the constraint in lower
 * case; {@link ForeignKeyRefusalBootE2ETest} covers H2, which reports a missing parent as
 * {@code 23506} and names it in upper case. The emitted migrations — the foreign-key migration
 * included — are applied verbatim by an administrative role, as a consumer's Flyway runs them, so the
 * constraint names the repository reads are the ones the database holds.
 */
@Tag("e2e")
@Tag("boot")
@Tag("postgres")
@DisplayName("ADR-076 Amendment 1 — a foreign-key violation answers 422 or 409 on PostgreSQL")
class ForeignKeyRefusalPostgresBootE2ETest {

    private static final String BASE_PACKAGE = "eu.exeris.e2e.catalog";
    private static final String SCHEMA = "exeris_foreign_key_e2e";
    private static final String APP_ROLE = "exeris_foreign_key_app";
    private static final String APP_PASSWORD = "foreign-key-e2e";
    private static final String MISSING = "00000000-0000-4000-8000-0000000000ff";
    private static final Pattern ID = Pattern.compile("\"id\":\"([0-9a-f-]{36})\"");
    private static final String STATUS_204 = "HTTP/1.1 204";
    private static final String STATUS_409 = "HTTP/1.1 409";
    private static final String STATUS_422 = "HTTP/1.1 422";

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

        booted = BootedApplication.start(app.loader(), BASE_PACKAGE + ".CatalogApplication",
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
    @DisplayName("a create naming a missing author and a dangling self reference answer 422 naming the relationship")
    void createWithAMissingReference() {
        String missingAuthor = post("/books", "{\"title\":\"Dangling\",\"authorId\":\"" + MISSING + "\"}");
        assertThat(missingAuthor).startsWith(STATUS_422)
                .contains("\"status\":422")
                .contains("\"field\":\"authorId\"");

        String author = created(post("/authors", "{\"name\":\"self\"}"));
        String missingPrequel = post("/books", "{\"title\":\"Orphan\",\"authorId\":\"" + author
                + "\",\"sequelOfId\":\"" + MISSING + "\"}");
        assertThat(missingPrequel).startsWith(STATUS_422).contains("\"field\":\"sequelOfId\"");
    }

    @Test
    @DisplayName("an update pointing a relationship at a missing row answers 422 and changes nothing")
    void updateWithAMissingReference() {
        String author = created(post("/authors", "{\"name\":\"kept\"}"));
        String book = created(post("/books", "{\"title\":\"Kept\",\"authorId\":\"" + author + "\"}"));

        String put = RawHttp.request(booted.port(), "PUT", "/books/" + book,
                "{\"title\":\"Kept\",\"authorId\":\"" + MISSING + "\"}");
        assertThat(put).startsWith(STATUS_422).contains("\"field\":\"authorId\"");
        assertThat(RawHttp.request(booted.port(), "GET", "/books/" + book))
                .startsWith("HTTP/1.1 200")
                .contains("\"authorId\":\"" + author + "\"");
    }

    @Test
    @DisplayName("deleting a row another entity's rows or its own rows reference answers 409; once they go, 204")
    void deleteOfAReferencedRow() {
        String author = created(post("/authors", "{\"name\":\"held\"}"));
        String first = created(post("/books", "{\"title\":\"First\",\"authorId\":\"" + author + "\"}"));
        String sequel = created(post("/books", "{\"title\":\"Sequel\",\"authorId\":\"" + author
                + "\",\"sequelOfId\":\"" + first + "\"}"));

        assertThat(delete("/authors/" + author)).startsWith(STATUS_409);
        assertThat(delete("/books/" + first)).startsWith(STATUS_409);

        assertThat(delete("/books/" + sequel)).startsWith(STATUS_204);
        assertThat(delete("/books/" + first)).startsWith(STATUS_204);
        assertThat(delete("/authors/" + author)).startsWith(STATUS_204);
    }

    private static String post(String path, String body) {
        return RawHttp.request(booted.port(), "POST", path, body);
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
        sources.put("eu/exeris/e2e/catalog/domain/Author.java",
                """
                package eu.exeris.e2e.catalog.domain;

                import eu.exeris.sdk.annotation.ExerisDomain;
                import eu.exeris.sdk.annotation.Field;

                import java.util.UUID;

                @ExerisDomain(module = "catalog", path = "/authors")
                public class Author {

                    private UUID id;

                    @Field(label = "Name")
                    private String name;

                    public UUID getId() { return id; }
                    public void setId(UUID id) { this.id = id; }
                    public String getName() { return name; }
                    public void setName(String name) { this.name = name; }
                }
                """);
        sources.put("eu/exeris/e2e/catalog/domain/Book.java",
                """
                package eu.exeris.e2e.catalog.domain;

                import eu.exeris.sdk.annotation.ExerisDomain;
                import eu.exeris.sdk.annotation.Field;
                import eu.exeris.sdk.annotation.Relationship;

                import java.util.UUID;

                @ExerisDomain(module = "catalog", path = "/books")
                public class Book {

                    private UUID id;

                    @Field(label = "Title")
                    private String title;

                    @Relationship(targetEntity = Author.class, displayField = "name")
                    private UUID authorId;

                    @Relationship(targetEntity = Book.class, displayField = "title")
                    private UUID sequelOfId;

                    public UUID getId() { return id; }
                    public void setId(UUID id) { this.id = id; }
                    public String getTitle() { return title; }
                    public void setTitle(String title) { this.title = title; }
                    public UUID getAuthorId() { return authorId; }
                    public void setAuthorId(UUID authorId) { this.authorId = authorId; }
                    public UUID getSequelOfId() { return sequelOfId; }
                    public void setSequelOfId(UUID sequelOfId) { this.sequelOfId = sequelOfId; }
                }
                """);
        return sources;
    }

    private static Map<String, String> harnessSources() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("eu/exeris/e2e/catalog/CatalogApplication.java",
                """
                package eu.exeris.e2e.catalog;

                import eu.exeris.kernel.spi.persistence.TransactionalExecutor;

                public class CatalogApplication extends Application {

                    @Override
                    protected RuntimeComponents components(TransactionalExecutor transactionalExecutor) {
                        return new CatalogComponents(transactionalExecutor);
                    }
                }
                """);
        sources.put("eu/exeris/e2e/catalog/CatalogComponents.java",
                """
                package eu.exeris.e2e.catalog;

                import eu.exeris.kernel.core.http.routing.HttpRouter;
                import eu.exeris.kernel.spi.http.HttpMethod;
                import eu.exeris.kernel.spi.http.HttpStatus;
                import eu.exeris.kernel.spi.persistence.TransactionalExecutor;

                public class CatalogComponents extends RuntimeComponents {

                    public CatalogComponents(TransactionalExecutor transactionalExecutor) {
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
