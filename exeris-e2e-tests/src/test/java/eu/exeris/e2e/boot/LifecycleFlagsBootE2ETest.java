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
 * A create validates the fields its body carries, and an update leaves a field marked
 * {@code inUpdate = false} as stored, on a <b>real</b> kernel boot.
 *
 * <p>The entity declares a {@code required} read-only field and a {@code required} field with
 * {@code inCreate = false}, neither of which the published create schema lists, and a
 * {@code required} field with {@code inUpdate = false}, which the published update schema does not
 * list. A create and an update that follow the schemas must be accepted, a forged value of the
 * update-fixed field must not reach the row, and an action that changes it must.
 *
 * <pre>
 *   @ExerisDomain + @Field(readOnly | inCreate | inUpdate) source
 *        → javac + ExerisDomainProcessor      (real metadata)
 *        → CodegenPipeline.run                (repository, handler, router)
 *        → javac over the emitted tree + a consumer-style Application subclass
 *        → KernelBootstrap                    (http + persistence on in-memory H2, PostgreSQL mode)
 *        → a real socket                      (POST, PUT, GET /tickets, POST …/actions/recode)
 * </pre>
 *
 * <p>The table is created by the harness, as in {@link UpdateKeepsServerOwnedColumnsBootE2ETest}.
 */
@Tag("e2e")
@Tag("boot")
@DisplayName("A create and an update that follow the published schemas are accepted on a real kernel boot")
class LifecycleFlagsBootE2ETest {

    private static final String BASE_PACKAGE = "eu.exeris.e2e.tickets";
    private static final String TICKETS = "/tickets";
    private static final String HTTP_OK = "HTTP/1.1 200";
    private static final String HTTP_CREATED = "HTTP/1.1 201";
    private static final String NULL = "null";
    private static final String CODE = "code";
    private static final String STORED_CODE = "\"c1\"";
    private static final String RECODED = "\"recoded\"";

    @TempDir
    static Path workspace;

    private static GeneratedTree app;
    private static BootedApplication booted;

    @BeforeAll
    static void boot() throws IOException {
        app = GeneratedTree.build(workspace, BASE_PACKAGE, domainSources(), harnessSources());
        booted = BootedApplication.start(app.loader(), BASE_PACKAGE + ".TicketsApplication");
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
    @DisplayName("a create without the required read-only and inCreate = false fields is accepted, and one "
            + "without the required code is refused")
    void createChecksOnlyTheFieldsItsBodyCarries() {
        String created = RawHttp.request(booted.port(), "POST", TICKETS,
                "{\"title\":\"first\",\"code\":\"c1\"}");

        assertThat(created).startsWith(HTTP_CREATED);
        assertThat(member(created, CODE)).isEqualTo(STORED_CODE);
        assertThat(member(created, "status")).isEqualTo(NULL);
        assertThat(RawHttp.request(booted.port(), "POST", TICKETS, "{\"title\":\"no code\"}"))
                .startsWith("HTTP/1.1 400");
    }

    @Test
    @DisplayName("a PUT leaves an inUpdate = false field as stored, an action writes it, and a PUT without "
            + "the required field is accepted")
    void updateKeepsTheFieldsItsBodyDoesNotCarry() {
        String created = RawHttp.request(booted.port(), "POST", TICKETS,
                "{\"title\":\"second\",\"code\":\"c1\"}");
        assertThat(created).startsWith(HTTP_CREATED);
        String ticket = TICKETS + "/" + member(created, "id").replace("\"", "");

        String forged = RawHttp.request(booted.port(), "PUT", ticket,
                "{\"title\":\"renamed\",\"slug\":\"s2\",\"code\":\"forged\"}");
        assertThat(forged).startsWith(HTTP_OK);
        assertThat(member(forged, "title")).isEqualTo("\"renamed\"");
        assertThat(member(forged, "slug")).isEqualTo("\"s2\"");
        assertThat(member(forged, CODE)).isEqualTo(STORED_CODE);

        String withoutCode = RawHttp.request(booted.port(), "PUT", ticket,
                "{\"title\":\"again\",\"slug\":\"s3\"}");
        assertThat(withoutCode).startsWith(HTTP_OK);
        assertThat(member(withoutCode, CODE)).isEqualTo(STORED_CODE);
        assertThat(member(RawHttp.request(booted.port(), "GET", ticket), CODE)).isEqualTo(STORED_CODE);

        String recoded = RawHttp.request(booted.port(), "POST", ticket + "/actions/recode");
        assertThat(recoded).startsWith(HTTP_OK);
        assertThat(member(recoded, CODE)).isEqualTo(RECODED);
        assertThat(member(RawHttp.request(booted.port(), "GET", ticket), CODE)).isEqualTo(RECODED);

        String reforged = RawHttp.request(booted.port(), "PUT", ticket,
                "{\"title\":\"last\",\"slug\":\"s4\",\"code\":\"c1\"}");
        assertThat(member(reforged, CODE)).isEqualTo(RECODED);
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
        sources.put("eu/exeris/e2e/tickets/domain/Ticket.java",
                """
                package eu.exeris.e2e.tickets.domain;

                import eu.exeris.sdk.annotation.Action;
                import eu.exeris.sdk.annotation.ExerisDomain;
                import eu.exeris.sdk.annotation.Field;

                import java.util.UUID;

                @ExerisDomain(module = "tickets", path = "/tickets")
                public class Ticket {

                    private UUID id;

                    @Field(label = "Title")
                    private String title;

                    @Field(label = "Code", required = true, inUpdate = false)
                    private String code;

                    @Field(label = "Slug", required = true, inCreate = false)
                    private String slug;

                    @Field(label = "Status", required = true, readOnly = true)
                    private String status;

                    public UUID getId() { return id; }
                    public void setId(UUID id) { this.id = id; }
                    public String getTitle() { return title; }
                    public void setTitle(String title) { this.title = title; }
                    public String getCode() { return code; }
                    public void setCode(String code) { this.code = code; }
                    public String getSlug() { return slug; }
                    public void setSlug(String slug) { this.slug = slug; }
                    public String getStatus() { return status; }
                    public void setStatus(String status) { this.status = status; }

                    @Action(name = "recode", label = "Recode")
                    public void recode() { this.code = "recoded"; }
                }
                """);
        return sources;
    }

    /** {@code POST /seed} creates the table. */
    private static Map<String, String> harnessSources() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("eu/exeris/e2e/tickets/TicketsApplication.java",
                """
                package eu.exeris.e2e.tickets;

                import eu.exeris.kernel.spi.persistence.TransactionalExecutor;

                public class TicketsApplication extends Application {

                    @Override
                    protected RuntimeComponents components(TransactionalExecutor transactionalExecutor) {
                        return new TicketsComponents(transactionalExecutor);
                    }
                }
                """);
        sources.put("eu/exeris/e2e/tickets/TicketsComponents.java",
                """
                package eu.exeris.e2e.tickets;

                import eu.exeris.kernel.core.http.routing.HttpRouter;
                import eu.exeris.kernel.spi.http.HttpMethod;
                import eu.exeris.kernel.spi.http.HttpStatus;
                import eu.exeris.kernel.spi.persistence.TransactionalExecutor;

                public class TicketsComponents extends RuntimeComponents {

                    private final TransactionalExecutor executor;

                    public TicketsComponents(TransactionalExecutor transactionalExecutor) {
                        super(transactionalExecutor);
                        this.executor = transactionalExecutor;
                    }

                    @Override
                    public void configureRoutes(HttpRouter.Builder routes) {
                        routes.route(HttpMethod.GET, "/probe", exchange -> exchange.respond(HttpStatus.OK));
                        routes.route(HttpMethod.POST, "/seed", exchange -> {
                            executor.executeManaged(conn -> conn.executeUpdate(
                                    "CREATE TABLE tickets (id UUID PRIMARY KEY, title VARCHAR(255), "
                                            + "code VARCHAR(255), slug VARCHAR(255), status VARCHAR(32))"));
                            exchange.respond(HttpStatus.NO_CONTENT);
                        });
                    }
                }
                """);
        return sources;
    }
}
