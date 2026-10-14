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
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A versioned, soft-deleted, audited, real-time entity with a declared {@code Integer version}
 * field keeps its optimistic lock on every write route, and a deleted row is gone from every read
 * route, on a <b>real</b> kernel boot.
 *
 * <pre>
 *   @ExerisDomain(versioned, softDelete, audited, realTimeApi) + @Action source
 *        → javac + ExerisDomainProcessor      (real metadata)
 *        → CodegenPipeline.run                (repository, handlers, stream handlers, router)
 *        → javac over the emitted tree + a consumer-style Application subclass
 *        → KernelBootstrap                    (http + persistence on in-memory H2 + events)
 *        → a real socket                      (GET, PUT, DELETE /ledgers, POST …/actions/post and …/track)
 * </pre>
 *
 * <p>The entity is {@code GLOBAL}: H2 implements no row-level security, and the processor refuses
 * {@code realTimeApi} on a tenant-partitioned entity. A stale read is made by the harness's
 * service, which hands the handlers the row one version behind while the switch is on, as a write
 * from another caller between the handler's read and its write would. No test waits for a stream
 * deadline: each stream below ends with a {@code stream-error} frame.
 */
@Tag("e2e")
@Tag("boot")
@DisplayName("A versioned, soft-deleted entity keeps its lock and hides its deleted row on a real kernel boot")
class VersionedSoftDeleteBootE2ETest {

    private static final String BASE_PACKAGE = "eu.exeris.e2e.ledgers";
    private static final String ALPHA = "00000000-0000-4000-8000-00000000000a";
    private static final String BRAVO = "00000000-0000-4000-8000-00000000000b";
    private static final String CHARLIE = "00000000-0000-4000-8000-00000000000c";
    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(20);
    private static final String CONFLICT_FRAME =
            "data: {\"type\":\"about:blank\",\"title\":\"Conflict\",\"status\":409}";
    private static final String NOT_FOUND_FRAME =
            "data: {\"type\":\"about:blank\",\"title\":\"Not Found\",\"status\":404}";

    @TempDir
    static Path workspace;

    private static GeneratedTree app;
    private static BootedApplication booted;

    @BeforeAll
    static void bootWithSeededRows() throws IOException {
        app = GeneratedTree.build(workspace, BASE_PACKAGE, domainSources(), harnessSources());
        booted = BootedApplication.start(app.loader(), BASE_PACKAGE + ".LedgerApplication");
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
    @DisplayName("a PUT carrying the stored version succeeds and increments it; one carrying the old "
            + "version is a 409 and leaves the row as the first PUT wrote it")
    void putIncrementsTheVersionAndRefusesAStaleOne() {
        String path = "/ledgers/" + ALPHA;
        assertThat(member(RawHttp.request(booted.port(), "GET", path), "version")).isEqualTo("0");

        String first = RawHttp.request(booted.port(), "PUT", path, "{\"name\":\"first\",\"version\":0}");
        assertThat(first).startsWith("HTTP/1.1 200");
        assertThat(member(first, "name")).isEqualTo("\"first\"");
        assertThat(member(first, "version")).isEqualTo("1");

        assertThat(RawHttp.request(booted.port(), "PUT", path, "{\"name\":\"stale\",\"version\":0}"))
                .startsWith("HTTP/1.1 409");

        String stored = RawHttp.request(booted.port(), "GET", path);
        assertThat(member(stored, "name")).isEqualTo("\"first\"");
        assertThat(member(stored, "version")).isEqualTo("1");

        String second = RawHttp.request(booted.port(), "PUT", path, "{\"name\":\"second\",\"version\":1}");
        assertThat(second).startsWith("HTTP/1.1 200");
        assertThat(member(second, "version")).isEqualTo("2");
    }

    @Test
    @DisplayName("the int, long and boolean action parameters arrive with their values, and the "
            + "action's write increments the version")
    void primitiveActionParametersArrive() {
        String path = "/ledgers/" + BRAVO;
        String before = RawHttp.request(booted.port(), "GET", path);
        int version = Integer.parseInt(member(before, "version"));

        String posted = RawHttp.request(booted.port(), "POST", path + "/actions/post",
                "{\"count\":7,\"amount\":9000000000,\"flag\":true}");

        assertThat(posted).startsWith("HTTP/1.1 200");
        assertThat(member(posted, "memo")).isEqualTo("\"7:9000000000:true\"");
        assertThat(member(posted, "version")).isEqualTo(String.valueOf(version + 1));
        assertThat(member(RawHttp.request(booted.port(), "GET", path), "memo")).isEqualTo("\"7:9000000000:true\"");

        String defaults = RawHttp.request(booted.port(), "POST", path + "/actions/post", "{}");
        assertThat(defaults).startsWith("HTTP/1.1 200");
        assertThat(member(defaults, "memo")).isEqualTo("\"0:0:false\"");
    }

    @Test
    @DisplayName("an action whose read of the row is a version behind is a 409 on the respond-once "
            + "route, and one stream-error frame carrying 409, then close, on the stream route")
    void staleActionsAreConflicts() throws Exception {
        String path = "/ledgers/" + BRAVO;
        String before = RawHttp.request(booted.port(), "GET", path);
        assertThat(RawHttp.request(booted.port(), "POST", "/stale-on")).startsWith("HTTP/1.1 204");
        try {
            assertThat(RawHttp.request(booted.port(), "POST", path + "/actions/post",
                    "{\"count\":1,\"amount\":2,\"flag\":false}")).startsWith("HTTP/1.1 409");
            assertThat(frames("POST", path + "/actions/track", null))
                    .containsExactly("event: stream-error", CONFLICT_FRAME, "");
        } finally {
            assertThat(RawHttp.request(booted.port(), "POST", "/stale-off")).startsWith("HTTP/1.1 204");
        }
        String after = RawHttp.request(booted.port(), "GET", path);
        assertThat(member(after, "version")).isEqualTo(member(before, "version"));
        assertThat(member(after, "memo")).isEqualTo(member(before, "memo"));
    }

    @Test
    @DisplayName("after a DELETE the row answers 404 on the by-id read, the respond-once action, the "
            + "action stream and the spectate stream, and the list no longer carries it")
    void deletedRowIsGoneEverywhere() throws Exception {
        String path = "/ledgers/" + CHARLIE;
        assertThat(RawHttp.request(booted.port(), "GET", "/ledgers")).contains(CHARLIE);
        assertThat(RawHttp.request(booted.port(), "GET", path)).startsWith("HTTP/1.1 200");

        assertThat(RawHttp.request(booted.port(), "DELETE", path)).startsWith("HTTP/1.1 204");

        assertThat(RawHttp.request(booted.port(), "GET", path)).startsWith("HTTP/1.1 404");
        assertThat(RawHttp.request(booted.port(), "POST", path + "/actions/post",
                "{\"count\":1,\"amount\":2,\"flag\":true}")).startsWith("HTTP/1.1 404");
        assertThat(frames("POST", path + "/actions/track", null))
                .containsExactly("event: stream-error", NOT_FOUND_FRAME, "");
        assertThat(frames("GET", path + "/stream", null))
                .containsExactly("event: stream-error", NOT_FOUND_FRAME, "");
        String list = RawHttp.request(booted.port(), "GET", "/ledgers");
        assertThat(list).startsWith("HTTP/1.1 200").doesNotContain(CHARLIE).contains(ALPHA);
    }

    private static List<String> frames(String method, String path, String body) throws Exception {
        try (SseConnection sse = SseConnection.open(booted.port(), method, path, body)) {
            assertThat(sse.head()).startsWith("HTTP/1.1 200");
            return sse.awaitServerClose(CLOSE_TIMEOUT);
        }
    }

    /** The raw JSON value of a top-level member of the response body: a quoted string, or a literal. */
    private static String member(String response, String name) {
        String body = response.substring(response.indexOf("\r\n\r\n") + 4);
        Matcher m = Pattern.compile("\"" + name + "\":(\"[^\"]*\"|[^,}]*)").matcher(body);
        assertThat(m.find()).as("member %s in %s", name, body).isTrue();
        return m.group(1);
    }

    // ------------------------------------------------------------------ harness

    private static Map<String, String> domainSources() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("eu/exeris/e2e/ledgers/domain/Ledger.java",
                """
                package eu.exeris.e2e.ledgers.domain;

                import eu.exeris.sdk.annotation.Action;
                import eu.exeris.sdk.annotation.ActionParam;
                import eu.exeris.sdk.annotation.DomainEvent;
                import eu.exeris.sdk.annotation.DomainEvent.Trigger;
                import eu.exeris.sdk.annotation.ExerisDomain;
                import eu.exeris.sdk.annotation.Field;

                import java.time.Instant;
                import java.util.UUID;

                @ExerisDomain(module = "ledgers", path = "/ledgers", audited = true, versioned = true,
                        softDelete = true, realTimeApi = true)
                @DomainEvent(name = "LedgerTracked", trigger = Trigger.ACTION, action = "track",
                        topic = "ledgers.tracked", includeFields = {"name"})
                public class Ledger {

                    private UUID id;

                    @Field(label = "Name")
                    private String name;

                    @Field(label = "Memo", readOnly = true)
                    private String memo;

                    private Instant createdAt;
                    private Instant updatedAt;
                    private boolean deleted;
                    private Integer version;

                    public UUID getId() { return id; }
                    public void setId(UUID id) { this.id = id; }
                    public String getName() { return name; }
                    public void setName(String name) { this.name = name; }
                    public String getMemo() { return memo; }
                    public void setMemo(String memo) { this.memo = memo; }
                    public Instant getCreatedAt() { return createdAt; }
                    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
                    public Instant getUpdatedAt() { return updatedAt; }
                    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
                    public boolean isDeleted() { return deleted; }
                    public void setDeleted(boolean deleted) { this.deleted = deleted; }
                    public Integer getVersion() { return version; }
                    public void setVersion(Integer version) { this.version = version; }

                    @Action(name = "track", label = "Track", streaming = true)
                    public void track() { }

                    @Action(name = "post", label = "Post")
                    public void post(@ActionParam(label = "Count") int count,
                                     @ActionParam(label = "Amount") long amount,
                                     @ActionParam(label = "Flag") boolean flag) {
                        this.memo = count + ":" + amount + ":" + flag;
                    }
                }
                """);
        return sources;
    }

    /**
     * {@code POST /seed} creates the table and writes three ledgers through the emitted
     * repository, each at version 0. {@code POST /stale-on} makes the service return every row one
     * version behind its stored one until {@code POST /stale-off}.
     */
    private static Map<String, String> harnessSources() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("eu/exeris/e2e/ledgers/LedgerApplication.java",
                """
                package eu.exeris.e2e.ledgers;

                import eu.exeris.kernel.spi.persistence.TransactionalExecutor;

                public class LedgerApplication extends Application {

                    @Override
                    protected RuntimeComponents components(TransactionalExecutor transactionalExecutor) {
                        return new LedgerComponents(transactionalExecutor);
                    }
                }
                """);
        sources.put("eu/exeris/e2e/ledgers/LedgerComponents.java",
                """
                package eu.exeris.e2e.ledgers;

                import eu.exeris.e2e.ledgers.domain.Ledger;
                import eu.exeris.e2e.ledgers.repository.LedgerRepository;
                import eu.exeris.e2e.ledgers.service.LedgerService;
                import eu.exeris.kernel.core.http.routing.HttpRouter;
                import eu.exeris.kernel.spi.http.HttpMethod;
                import eu.exeris.kernel.spi.http.HttpStatus;
                import eu.exeris.kernel.spi.persistence.TransactionalExecutor;

                import java.util.Optional;
                import java.util.UUID;
                import java.util.concurrent.atomic.AtomicBoolean;

                public class LedgerComponents extends RuntimeComponents {

                    static final AtomicBoolean STALE = new AtomicBoolean();

                    private final TransactionalExecutor executor;

                    public LedgerComponents(TransactionalExecutor transactionalExecutor) {
                        super(transactionalExecutor);
                        this.executor = transactionalExecutor;
                    }

                    @Override
                    protected LedgerService createLedgerService() {
                        return new StaleReadingService(ledgerRepository());
                    }

                    /** Hands out the row one version behind, as a write between the read and the write would. */
                    static final class StaleReadingService extends LedgerService {

                        StaleReadingService(LedgerRepository repository) {
                            super(repository);
                        }

                        @Override
                        public Optional<Ledger> findById(UUID id) {
                            Optional<Ledger> found = super.findById(id);
                            if (STALE.get()) {
                                found.ifPresent(ledger -> ledger.setVersion(ledger.getVersion() - 1));
                            }
                            return found;
                        }
                    }

                    @Override
                    public void configureRoutes(HttpRouter.Builder routes) {
                        routes.route(HttpMethod.GET, "/probe", exchange -> exchange.respond(HttpStatus.OK));
                        routes.route(HttpMethod.POST, "/stale-on", exchange -> {
                            STALE.set(true);
                            exchange.respond(HttpStatus.NO_CONTENT);
                        });
                        routes.route(HttpMethod.POST, "/stale-off", exchange -> {
                            STALE.set(false);
                            exchange.respond(HttpStatus.NO_CONTENT);
                        });
                        routes.route(HttpMethod.POST, "/seed", exchange -> {
                            executor.executeManaged(conn -> conn.executeUpdate(
                                    "CREATE TABLE ledgers (id UUID PRIMARY KEY, name VARCHAR(255), memo VARCHAR(255), "
                                            + "created_at TIMESTAMP WITH TIME ZONE, updated_at TIMESTAMP WITH TIME ZONE, "
                                            + "deleted BOOLEAN, version INTEGER)"));
                            seed("%s", "alpha");
                            seed("%s", "bravo");
                            seed("%s", "charlie");
                            exchange.respond(HttpStatus.NO_CONTENT);
                        });
                    }

                    private void seed(String id, String name) {
                        Ledger ledger = new Ledger();
                        ledger.setId(UUID.fromString(id));
                        ledger.setName(name);
                        ledger.setVersion(0);
                        ledgerRepository().save(ledger);
                    }
                }
                """.formatted(ALPHA, BRAVO, CHARLIE));
        return sources;
    }
}
