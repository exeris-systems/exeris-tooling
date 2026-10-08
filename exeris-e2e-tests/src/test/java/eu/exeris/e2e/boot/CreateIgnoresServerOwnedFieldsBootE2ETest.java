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
 * A {@code POST} ignores the server-owned fields its body names, and keeps the ones the entity's
 * service sets, on a <b>real</b> kernel boot.
 *
 * <p>The entity is audited, versioned and soft-deletable and declares its author as a field. The
 * create body is the published {@code NoteCreateDto}, which lists none of those; a body that names
 * them anyway must leave the row with the creation and update times the server stamps, the initial
 * version, a live flag and no author of the caller's choosing, while an author the consumer's own
 * service sets before the save is stored.
 *
 * <pre>
 *   @ExerisDomain(audited, versioned, softDelete) source
 *        → javac + ExerisDomainProcessor      (real metadata)
 *        → CodegenPipeline.run                (repository, handler, router)
 *        → javac over the emitted tree + a consumer-style Application subclass
 *        → KernelBootstrap                    (http + persistence on in-memory H2, PostgreSQL mode)
 *        → a real socket                      (POST, GET /notes)
 * </pre>
 *
 * <p>The table is created by the harness, as in {@link UpdateKeepsServerOwnedColumnsBootE2ETest}.
 */
@Tag("e2e")
@Tag("boot")
@DisplayName("A POST ignores the server-owned fields on a real kernel boot")
class CreateIgnoresServerOwnedFieldsBootE2ETest {

    private static final String BASE_PACKAGE = "eu.exeris.e2e.notes";
    private static final String NOTES = "/notes";
    private static final String FORGED_AT = "1999-01-01T00:00:00Z";
    private static final String CREATED_BY = "createdBy";
    private static final String SERVICE = "\"service\"";

    @TempDir
    static Path workspace;

    private static GeneratedTree app;
    private static BootedApplication booted;

    @BeforeAll
    static void boot() throws IOException {
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
    @DisplayName("a forged createdAt, updatedAt, version, deleted and createdBy are not stored; the "
            + "author the service set is")
    void forgedServerOwnedFieldsAreIgnored() {
        String created = RawHttp.request(booted.port(), "POST", NOTES,
                "{\"title\":\"first\",\"createdAt\":\"" + FORGED_AT + "\",\"updatedAt\":\"" + FORGED_AT
                        + "\",\"version\":7,\"deleted\":true,\"createdBy\":\"forger\"}");

        assertThat(created).startsWith("HTTP/1.1 201");
        assertThat(member(created, "createdAt")).doesNotContain("1999");
        assertThat(member(created, "updatedAt")).doesNotContain("1999");
        assertThat(member(created, "version")).isEqualTo("0");
        assertThat(member(created, "deleted")).isEqualTo("false");
        assertThat(member(created, CREATED_BY)).isEqualTo(SERVICE);

        String id = member(created, "id").replace("\"", "");
        String stored = RawHttp.request(booted.port(), "GET", NOTES + "/" + id);
        assertThat(stored).startsWith("HTTP/1.1 200");
        assertThat(member(stored, "createdAt")).doesNotContain("1999");
        assertThat(member(stored, "version")).isEqualTo("0");
        assertThat(member(stored, "deleted")).isEqualTo("false");
        assertThat(member(stored, CREATED_BY)).isEqualTo(SERVICE);
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

                import java.time.Instant;
                import java.util.UUID;

                @ExerisDomain(module = "notes", path = "/notes", audited = true, versioned = true,
                        softDelete = true)
                public class Note {

                    private UUID id;

                    @Field(label = "Title")
                    private String title;

                    private String createdBy;
                    private Instant createdAt;
                    private Instant updatedAt;
                    private boolean deleted;
                    private Long version;

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
                    public Long getVersion() { return version; }
                    public void setVersion(Long version) { this.version = version; }
                }
                """);
        return sources;
    }

    /** {@code POST /seed} creates the table; the service sets the author, as a consumer's would. */
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
                import eu.exeris.e2e.notes.repository.NoteRepository;
                import eu.exeris.e2e.notes.service.NoteService;
                import eu.exeris.kernel.core.http.routing.HttpRouter;
                import eu.exeris.kernel.spi.http.HttpMethod;
                import eu.exeris.kernel.spi.http.HttpStatus;
                import eu.exeris.kernel.spi.persistence.TransactionalExecutor;

                public class NotesComponents extends RuntimeComponents {

                    private final TransactionalExecutor executor;

                    public NotesComponents(TransactionalExecutor transactionalExecutor) {
                        super(transactionalExecutor);
                        this.executor = transactionalExecutor;
                    }

                    @Override
                    protected NoteService createNoteService() {
                        return new AuthoringService(noteRepository());
                    }

                    /** A service of the consumer's own: it records the author, which the body cannot carry. */
                    static final class AuthoringService extends NoteService {

                        AuthoringService(NoteRepository repository) {
                            super(repository);
                        }

                        @Override
                        public Note save(Note note) {
                            note.setCreatedBy("service");
                            return super.save(note);
                        }
                    }

                    @Override
                    public void configureRoutes(HttpRouter.Builder routes) {
                        routes.route(HttpMethod.GET, "/probe", exchange -> exchange.respond(HttpStatus.OK));
                        routes.route(HttpMethod.POST, "/seed", exchange -> {
                            executor.executeManaged(conn -> conn.executeUpdate(
                                    "CREATE TABLE notes (id UUID PRIMARY KEY, title VARCHAR(255), "
                                            + "created_by VARCHAR(255), created_at TIMESTAMP WITH TIME ZONE, "
                                            + "updated_at TIMESTAMP WITH TIME ZONE, deleted BOOLEAN, "
                                            + "version BIGINT)"));
                            exchange.respond(HttpStatus.NO_CONTENT);
                        });
                    }
                }
                """);
        return sources;
    }
}
