package eu.exeris.e2e.boot;

import eu.exeris.e2e.codegen.compile.GeneratedTree;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A {@code PUT} replaces the domain fields and leaves every server-owned column as stored, on a
 * <b>real</b> kernel boot.
 *
 * <p>Generator tests assert the emitted {@code UPDATE} and the generated repository test replays
 * binds against a double; only a statement run against an engine shows that a body naming a
 * creation stamp, an author and a soft-delete flag changes none of them in the row, and that the
 * response is the row as stored. So the whole path runs:
 *
 * <pre>
 *   @ExerisDomain(audited, softDelete) + @AuditCreatedBy source
 *        → javac + ExerisDomainProcessor      (real metadata)
 *        → CodegenPipeline.run                (repository, handler, router)
 *        → javac over the emitted tree + a consumer-style Application subclass
 *        → KernelBootstrap                    (http + persistence on in-memory H2, PostgreSQL mode)
 *        → a real socket                      (GET, PUT, DELETE /notes/{id})
 * </pre>
 *
 * <p>The table is created by the harness, as in {@link ListRouteBootE2ETest}: the kernel applies its
 * own migrations only. The row is written through the emitted repository's {@code save}, so its
 * creation stamp is the one the application stores.
 */
@Tag("e2e")
@Tag("boot")
@DisplayName("A PUT keeps the server-owned columns on a real kernel boot")
class UpdateKeepsServerOwnedColumnsBootE2ETest {

    private static final String BASE_PACKAGE = "eu.exeris.e2e.notes";
    private static final String NOTE_ID = "00000000-0000-4000-8000-000000000001";
    private static final String NOTE = "/notes/" + NOTE_ID;
    private static final String FORGED_CREATED_AT = "1999-01-01T00:00:00Z";

    @TempDir
    static Path workspace;

    private static GeneratedTree app;
    private static BootedApplication booted;

    @BeforeAll
    static void bootWithASeededRow() throws IOException {
        app = GeneratedTree.build(workspace, BASE_PACKAGE, domainSources(), harnessSources());
        booted = BootedApplication.start(app.loader(), BASE_PACKAGE + ".NotesApplication");
        assertThat(RawHttp.request(booted.port(), "POST", "/seed")).startsWith("HTTP/1.1 204");
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
    @DisplayName("a forged createdAt, createdBy and deleted leave the stored row unchanged; the title "
            + "is replaced; the response and a later GET agree; a soft-deleted row answers 404")
    void putKeepsServerOwnedColumns() {
        String before = RawHttp.request(booted.port(), "GET", NOTE);
        assertThat(before).startsWith("HTTP/1.1 200");
        String storedCreatedAt = member(before, "createdAt");
        assertThat(storedCreatedAt).isNotEqualTo("null").doesNotContain("1999");
        assertThat(member(before, "createdBy")).isEqualTo("\"author\"");

        String put = RawHttp.request(booted.port(), "PUT", NOTE,
                "{\"title\":\"renamed\",\"createdAt\":\"" + FORGED_CREATED_AT + "\","
                        + "\"createdBy\":\"forger\",\"deleted\":true}");

        assertThat(put).startsWith("HTTP/1.1 200");
        assertThat(member(put, "title")).isEqualTo("\"renamed\"");
        assertThat(member(put, "createdAt")).isEqualTo(storedCreatedAt);
        assertThat(member(put, "createdBy")).isEqualTo("\"author\"");
        assertThat(member(put, "deleted")).isEqualTo("false");

        String after = RawHttp.request(booted.port(), "GET", NOTE);
        assertThat(after).startsWith("HTTP/1.1 200");
        assertThat(member(after, "title")).isEqualTo("\"renamed\"");
        assertThat(member(after, "createdAt")).isEqualTo(storedCreatedAt);
        assertThat(member(after, "createdBy")).isEqualTo("\"author\"");
        // The update stamp is the server's, and it moved.
        assertThat(member(after, "updatedAt")).isNotEqualTo(member(before, "updatedAt"));

        assertThat(RawHttp.request(booted.port(), "DELETE", NOTE)).startsWith("HTTP/1.1 204");
        assertThat(RawHttp.request(booted.port(), "PUT", NOTE,
                "{\"title\":\"revived\",\"deleted\":false}")).startsWith("HTTP/1.1 404");
        assertThat(RawHttp.request(booted.port(), "GET", NOTE)).startsWith("HTTP/1.1 404");
    }

    /** The raw JSON value of a top-level member of the response body: a quoted string, or a literal. */
    private static String member(String response, String name) {
        String body = response.substring(response.indexOf("\r\n\r\n") + 4);
        Matcher m = Pattern.compile("\"" + name + "\":(\"[^\"]*\"|[^,}]*)").matcher(body);
        assertThat(m.find()).as("member %s in %s", name, body).isTrue();
        return m.group(1);
    }

    private static Map<String, String> domainSources() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("eu/exeris/e2e/notes/domain/Note.java",
                """
                package eu.exeris.e2e.notes.domain;

                import eu.exeris.sdk.annotation.ExerisDomain;
                import eu.exeris.sdk.annotation.Field;
                import eu.exeris.sdk.annotation.system.AuditCreatedBy;

                import java.time.Instant;
                import java.util.UUID;

                @ExerisDomain(module = "notes", path = "/notes", audited = true, softDelete = true)
                public class Note {

                    private UUID id;

                    @Field(label = "Title")
                    private String title;

                    @AuditCreatedBy
                    private String createdBy;

                    private Instant createdAt;
                    private Instant updatedAt;
                    private boolean deleted;

                    public UUID getId() { return id; }
                    public void setId(UUID id) { this.id = id; }
                    public String getTitle() { return title; }
                    public void setTitle(String title) { this.title = title; }
                    public String getCreatedBy() { return createdBy; }
                    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
                    public Instant getCreatedAt() { return createdAt; }
                    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
                    public Instant getUpdatedAt() { return updatedAt; }
                    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
                    public boolean isDeleted() { return deleted; }
                    public void setDeleted(boolean deleted) { this.deleted = deleted; }
                }
                """);
        return sources;
    }

    /**
     * {@code POST /seed} creates the table and writes one note through the emitted repository's
     * {@code save}, with an author, so the stored creation stamp and author are the application's.
     */
    private static Map<String, String> harnessSources() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("eu/exeris/e2e/notes/NotesApplication.java",
                """
                package eu.exeris.e2e.notes;

                import eu.exeris.kernel.spi.persistence.TransactionalExecutor;

                public class NotesApplication extends Application {

                    @Override
                    protected RuntimeComponents components(TransactionalExecutor transactionalExecutor) {
                        return new NotesComponents(transactionalExecutor);
                    }
                }
                """);
        sources.put("eu/exeris/e2e/notes/NotesComponents.java",
                """
                package eu.exeris.e2e.notes;

                import eu.exeris.e2e.notes.domain.Note;
                import eu.exeris.kernel.core.http.routing.HttpRouter;
                import eu.exeris.kernel.spi.http.HttpMethod;
                import eu.exeris.kernel.spi.http.HttpStatus;
                import eu.exeris.kernel.spi.persistence.TransactionalExecutor;

                import java.util.UUID;

                public class NotesComponents extends RuntimeComponents {

                    private final TransactionalExecutor executor;

                    public NotesComponents(TransactionalExecutor transactionalExecutor) {
                        super(transactionalExecutor);
                        this.executor = transactionalExecutor;
                    }

                    @Override
                    public void configureRoutes(HttpRouter.Builder routes) {
                        routes.route(HttpMethod.GET, "/probe", exchange -> exchange.respond(HttpStatus.OK));
                        routes.route(HttpMethod.POST, "/seed", exchange -> {
                            executor.executeManaged(conn -> conn.executeUpdate(
                                    "CREATE TABLE notes (id UUID PRIMARY KEY, title VARCHAR(255), "
                                            + "created_by VARCHAR(255), created_at TIMESTAMP WITH TIME ZONE, "
                                            + "updated_at TIMESTAMP WITH TIME ZONE, deleted BOOLEAN)"));
                            Note note = new Note();
                            note.setId(UUID.fromString("00000000-0000-4000-8000-000000000001"));
                            note.setTitle("first");
                            note.setCreatedBy("author");
                            noteRepository().save(note);
                            exchange.respond(HttpStatus.NO_CONTENT);
                        });
                    }
                }
                """);
        return sources;
    }
}
