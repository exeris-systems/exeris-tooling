package eu.exeris.e2e.boot;

import eu.exeris.e2e.codegen.compile.GeneratedTree;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A foreign-key violation answers the caller's status on a <b>real</b> kernel boot (ADR-076
 * Amendment 1): a write naming a missing row through a relationship is {@code 422} with a problem
 * body naming the relationship, and a delete of a row other rows still reference is {@code 409}.
 *
 * <pre>
 *   @ExerisDomain + @Relationship sources (another entity, and the entity itself)
 *        → javac + ExerisDomainProcessor      (real metadata)
 *        → CodegenPipeline.run                (repository, error types, handler, router)
 *        → javac over the emitted tree + a consumer-style Application subclass
 *        → KernelBootstrap                    (http + persistence on in-memory H2, PostgreSQL mode)
 *        → a real socket                      (POST, PUT, an action and DELETE)
 * </pre>
 *
 * <p>H2 is the engine whose SQLStates differ from PostgreSQL's: it reports a written row naming a
 * missing one as {@code 23506}, where PostgreSQL reports {@code 23503} both ways. The tables and
 * constraints are created by the harness with the names the emitted foreign-key migration declares,
 * which {@link #theHarnessConstraintsAreTheEmittedOnes()} asserts: the kernel applies its own
 * migrations only, and the emitted ones carry PostgreSQL row-level security that H2 does not
 * implement.
 */
@Tag("e2e")
@Tag("boot")
@DisplayName("ADR-076 Amendment 1 — a foreign-key violation answers 422 or 409 on a real kernel boot")
class ForeignKeyRefusalBootE2ETest {

    private static final String BASE_PACKAGE = "eu.exeris.e2e.library";
    private static final String MISSING = "00000000-0000-4000-8000-0000000000ff";
    private static final String STATUS_201 = "HTTP/1.1 201";
    private static final String STATUS_204 = "HTTP/1.1 204";
    private static final String STATUS_409 = "HTTP/1.1 409";
    private static final String STATUS_422 = "HTTP/1.1 422";

    @TempDir
    static Path workspace;

    private static GeneratedTree app;
    private static BootedApplication booted;

    @BeforeAll
    static void bootWithTables() throws IOException {
        app = GeneratedTree.build(workspace, BASE_PACKAGE, domainSources(), harnessSources());
        booted = BootedApplication.start(app.loader(), BASE_PACKAGE + ".LibraryApplication");
        assertThat(RawHttp.request(booted.port(), "POST", "/seed")).startsWith(STATUS_204);
    }

    @AfterAll
    static void stop() throws Exception {
        if (booted != null) {
            booted.close();
        }
        if (app != null) {
            app.close();
        }
    }

    @Test
    @DisplayName("the harness's constraints are the emitted migration's ones")
    void theHarnessConstraintsAreTheEmittedOnes() throws IOException {
        assertThat(migration("foreign_keys"))
                .contains("ALTER TABLE books ADD CONSTRAINT fk_books_author_id FOREIGN KEY (author_id) "
                        + "REFERENCES authors(id) ON DELETE RESTRICT;")
                .contains("ALTER TABLE books ADD CONSTRAINT fk_books_sequel_of_id FOREIGN KEY (sequel_of_id) "
                        + "REFERENCES books(id) ON DELETE RESTRICT;");
    }

    @Test
    @DisplayName("a create naming a missing author answers 422 with a problem body naming the relationship")
    void createWithAMissingReference() {
        String response = book("POST", "/books", "Dangling", MISSING, null);

        assertThat(response).startsWith(STATUS_422);
        assertThat(body(response))
                .contains("\"type\":\"about:blank\"")
                .contains("\"title\":\"Unprocessable Content\"")
                .contains("\"status\":422")
                .contains("\"field\":\"authorId\"");
    }

    @Test
    @DisplayName("a dangling self reference names the self relationship")
    void createWithAMissingSelfReference() {
        String author = id(created(RawHttp.request(booted.port(), "POST", "/authors", "{\"name\":\"self\"}")));

        String response = book("POST", "/books", "Orphan sequel", author, MISSING);

        assertThat(response).startsWith(STATUS_422);
        assertThat(body(response)).contains("\"field\":\"sequelOfId\"");
    }

    @Test
    @DisplayName("an update and an action pointing a relationship at a missing row answer 422 and change nothing")
    void updateAndActionWithAMissingReference() {
        String author = id(created(RawHttp.request(booted.port(), "POST", "/authors", "{\"name\":\"kept\"}")));
        String book = id(created(book("POST", "/books", "Kept", author, null)));

        String put = book("PUT", "/books/" + book, "Kept", MISSING, null);
        assertThat(put).startsWith(STATUS_422);
        assertThat(body(put)).contains("\"field\":\"authorId\"");

        String action = RawHttp.request(booted.port(), "POST", "/books/" + book + "/actions/reassign",
                "{\"authorId\":\"" + MISSING + "\"}");
        assertThat(action).startsWith(STATUS_422);
        assertThat(body(action)).contains("\"field\":\"authorId\"");

        assertThat(RawHttp.request(booted.port(), "GET", "/books/" + book))
                .startsWith("HTTP/1.1 200")
                .contains("\"authorId\":\"" + author + "\"");
    }

    @Test
    @DisplayName("deleting a row another entity's rows reference, or its own rows reference, answers 409; "
            + "once they are gone the delete answers 204")
    void deleteOfAReferencedRow() {
        String author = id(created(RawHttp.request(booted.port(), "POST", "/authors", "{\"name\":\"held\"}")));
        String first = id(created(book("POST", "/books", "First", author, null)));
        String sequel = id(created(book("POST", "/books", "Sequel", author, first)));

        assertThat(RawHttp.request(booted.port(), "DELETE", "/authors/" + author)).startsWith(STATUS_409);
        assertThat(RawHttp.request(booted.port(), "DELETE", "/books/" + first)).startsWith(STATUS_409);
        assertThat(RawHttp.request(booted.port(), "GET", "/authors/" + author)).startsWith("HTTP/1.1 200");

        assertThat(RawHttp.request(booted.port(), "DELETE", "/books/" + sequel)).startsWith(STATUS_204);
        assertThat(RawHttp.request(booted.port(), "DELETE", "/books/" + first)).startsWith(STATUS_204);
        assertThat(RawHttp.request(booted.port(), "DELETE", "/authors/" + author)).startsWith(STATUS_204);
    }

    private static String book(String method, String path, String title, String authorId, String sequelOfId) {
        StringBuilder json = new StringBuilder("{\"title\":\"").append(title).append('"');
        if (authorId != null) {
            json.append(",\"authorId\":\"").append(authorId).append('"');
        }
        if (sequelOfId != null) {
            json.append(",\"sequelOfId\":\"").append(sequelOfId).append('"');
        }
        return RawHttp.request(booted.port(), method, path, json.append('}').toString());
    }

    private static String created(String response) {
        assertThat(response).startsWith(STATUS_201);
        return response;
    }

    private static String id(String response) {
        Matcher m = Pattern.compile("\"id\":\"([^\"]*)\"").matcher(body(response));
        assertThat(m.find()).as("id in %s", body(response)).isTrue();
        return m.group(1);
    }

    private static String body(String response) {
        return response.substring(response.indexOf("\r\n\r\n") + 4);
    }

    private static String migration(String fragment) throws IOException {
        try (Stream<Path> files = Files.walk(app.generatedRoot().resolve("db/migration"))) {
            Path file = files.filter(p -> p.getFileName().toString().contains(fragment))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no migration matching '" + fragment + "'"));
            return Files.readString(file);
        }
    }

    // ------------------------------------------------------------------ harness

    private static Map<String, String> domainSources() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("eu/exeris/e2e/library/domain/Author.java",
                """
                package eu.exeris.e2e.library.domain;

                import eu.exeris.sdk.annotation.ExerisDomain;
                import eu.exeris.sdk.annotation.Field;

                import java.util.UUID;

                @ExerisDomain(module = "library", path = "/authors")
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
        sources.put("eu/exeris/e2e/library/domain/Book.java",
                """
                package eu.exeris.e2e.library.domain;

                import eu.exeris.sdk.annotation.Action;
                import eu.exeris.sdk.annotation.ActionParam;
                import eu.exeris.sdk.annotation.ExerisDomain;
                import eu.exeris.sdk.annotation.Field;
                import eu.exeris.sdk.annotation.Relationship;

                import java.util.UUID;

                @ExerisDomain(module = "library", path = "/books")
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

                    @Action(name = "reassign", label = "Reassign")
                    public void reassign(@ActionParam(label = "Author") UUID authorId) {
                        this.authorId = authorId;
                    }
                }
                """);
        return sources;
    }

    /** {@code POST /seed} creates both tables with the emitted key columns and constraint names. */
    private static Map<String, String> harnessSources() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("eu/exeris/e2e/library/LibraryApplication.java",
                """
                package eu.exeris.e2e.library;

                import eu.exeris.kernel.spi.persistence.TransactionalExecutor;

                public class LibraryApplication extends Application {

                    @Override
                    protected RuntimeComponents components(TransactionalExecutor transactionalExecutor) {
                        return new LibraryComponents(transactionalExecutor);
                    }
                }
                """);
        sources.put("eu/exeris/e2e/library/LibraryComponents.java",
                """
                package eu.exeris.e2e.library;

                import eu.exeris.kernel.core.http.routing.HttpRouter;
                import eu.exeris.kernel.spi.http.HttpMethod;
                import eu.exeris.kernel.spi.http.HttpStatus;
                import eu.exeris.kernel.spi.persistence.TransactionalExecutor;

                public class LibraryComponents extends RuntimeComponents {

                    private final TransactionalExecutor executor;

                    public LibraryComponents(TransactionalExecutor transactionalExecutor) {
                        super(transactionalExecutor);
                        this.executor = transactionalExecutor;
                    }

                    @Override
                    public void configureRoutes(HttpRouter.Builder routes) {
                        routes.route(HttpMethod.GET, "/probe", exchange -> exchange.respond(HttpStatus.OK));
                        routes.route(HttpMethod.POST, "/seed", exchange -> {
                            executor.executeManaged(conn -> {
                                conn.executeUpdate("CREATE TABLE authors (id UUID PRIMARY KEY, name VARCHAR(255))");
                                conn.executeUpdate("CREATE TABLE books (id UUID PRIMARY KEY, title VARCHAR(255), "
                                        + "author_id UUID, sequel_of_id UUID)");
                                conn.executeUpdate("ALTER TABLE books ADD CONSTRAINT fk_books_author_id "
                                        + "FOREIGN KEY (author_id) REFERENCES authors(id) ON DELETE RESTRICT");
                                conn.executeUpdate("ALTER TABLE books ADD CONSTRAINT fk_books_sequel_of_id "
                                        + "FOREIGN KEY (sequel_of_id) REFERENCES books(id) ON DELETE RESTRICT");
                            });
                            exchange.respond(HttpStatus.NO_CONTENT);
                        });
                    }
                }
                """);
        return sources;
    }
}
