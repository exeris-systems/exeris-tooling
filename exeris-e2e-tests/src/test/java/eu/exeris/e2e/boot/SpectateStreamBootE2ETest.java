package eu.exeris.e2e.boot;

import eu.exeris.e2e.codegen.compile.GeneratedTree;
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
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The spectate route streams one row's events on a <b>real</b> kernel boot (ADR-044 Amendment 2,
 * decision 7).
 *
 * <pre>
 *   @ExerisDomain(realTimeApi = true) + @DomainEvent(trigger = UPDATE) source
 *        → javac + ExerisDomainProcessor      (real metadata)
 *        → CodegenPipeline.run                (spectate handler, live view, publisher, router)
 *        → javac over the emitted tree + a consumer-style Application subclass
 *        → KernelBootstrap                    (http + persistence on in-memory H2 + events)
 *        → a real socket                      (GET /buoys/{id}/stream as SSE)
 * </pre>
 *
 * <p>The entity is {@code GLOBAL}: the processor refuses {@code realTimeApi} on a
 * tenant-partitioned one ({@code EXT-PROC-1014}). The events are published by the generated
 * update route, which passes the path id as the stream id, so the row a spectator sees an event
 * for is the row that was updated.
 */
@Tag("e2e")
@Tag("boot")
@DisplayName("ADR-044 Amendment 2 — GET {base}/{id}/stream spectates one row on a real kernel boot")
class SpectateStreamBootE2ETest {

    private static final String BASE_PACKAGE = "eu.exeris.e2e.buoys";
    private static final String ALPHA = "00000000-0000-4000-8000-00000000000a";
    private static final String BRAVO = "00000000-0000-4000-8000-00000000000b";
    private static final String MISSING = "00000000-0000-4000-8000-0000000000ff";
    private static final Duration FRAME_TIMEOUT = Duration.ofSeconds(20);
    private static final String EVENT_LINE = "event: BuoyMoved";

    @TempDir
    static Path workspace;

    private static GeneratedTree app;
    private static BootedApplication booted;

    /** Makes each label a PUT writes distinct, so a frame names the update that produced it. */
    private static final AtomicInteger REVISION = new AtomicInteger();

    @BeforeAll
    static void bootWithSeededRows() throws IOException {
        app = GeneratedTree.build(workspace, BASE_PACKAGE, domainSources(), harnessSources());
        booted = BootedApplication.start(app.loader(), BASE_PACKAGE + ".BuoyApplication");
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
    @DisplayName("the spectate handler is emitted and registered at GET /buoys/{id}/stream")
    void spectateHandlerIsRegistered() throws IOException {
        String handler = Files.readString(app.generatedRoot()
                .resolve("eu/exeris/e2e/buoys/handler/BuoySpectateStreamHandler.java"));
        assertThat(handler)
                .contains("found = service.findById(id)")
                .contains("tokens.add(bus.subscribe(\"BuoyMovedEvent\"");
        String lifecycle = Files.readString(app.generatedRoot().resolve("eu/exeris/e2e/buoys/RuntimeLifecycle.java"));
        assertThat(lifecycle)
                .contains("routerBuilder.streamRoute(HttpMethod.GET, \"/buoys/stream\", buoyStreamHandler);")
                .contains("routerBuilder.streamRoute(HttpMethod.GET, \"/buoys/{id}/stream\", "
                        + "buoySpectateStreamHandler);");
    }

    @Test
    @DisplayName("regenerating from the same metadata writes byte-identical sources")
    void regenerationIsByteIdentical() throws IOException {
        Path again = workspace.resolve("regenerated");
        CodegenPipeline.createDefault().run(workspace.resolve("target/classes/exeris-metadata"), again, BASE_PACKAGE);

        for (Path first : app.javaSources()) {
            Path second = again.resolve(app.generatedRoot().relativize(first));
            assertThat(Files.readAllBytes(second)).as(second.toString()).isEqualTo(Files.readAllBytes(first));
        }
        try (var regenerated = Files.walk(again)) {
            assertThat(regenerated.filter(f -> f.toString().endsWith(".java")).count())
                    .isEqualTo(app.javaSources().size());
        }
    }

    @Test
    @DisplayName("each spectator receives its own row's update, published after another row's, and the "
            + "stream stays open")
    void spectatorReceivesOnlyItsRow() throws Exception {
        try (SseConnection alpha = SseConnection.open(booted.port(), "/buoys/" + ALPHA + "/stream");
             SseConnection bravo = SseConnection.open(booted.port(), "/buoys/" + BRAVO + "/stream")) {
            assertThat(alpha.head()).startsWith("HTTP/1.1 200").containsIgnoringCase("text/event-stream");
            assertThat(bravo.head()).startsWith("HTTP/1.1 200");

            // The handler subscribes after the head is on the wire, so one update could race the
            // subscription: update until a frame arrives. Bravo is updated first each time, so a
            // stream that forwarded another row's event would see bravo's before its own.
            List<String> alphaFrame = alpha.awaitFrame(() -> {
                int revision = REVISION.incrementAndGet();
                assertThat(put(BRAVO, "bravo-" + revision)).startsWith("HTTP/1.1 200");
                assertThat(put(ALPHA, "alpha-" + revision)).startsWith("HTTP/1.1 200");
            }, FRAME_TIMEOUT);
            List<String> bravoFrame = bravo.awaitFrame(() -> { }, FRAME_TIMEOUT);

            assertThat(alphaFrame).hasSize(2);
            assertThat(alphaFrame.get(0)).isEqualTo(EVENT_LINE);
            assertThat(alphaFrame.get(1)).startsWith("data: {\"label\":\"alpha-");
            assertThat(bravoFrame).hasSize(2);
            assertThat(bravoFrame.get(0)).isEqualTo(EVENT_LINE);
            assertThat(bravoFrame.get(1)).startsWith("data: {\"label\":\"bravo-");

            // Open-ended: the next update of the row reaches the same stream.
            int revision = REVISION.incrementAndGet();
            List<String> next = alpha.awaitFrame(
                    () -> assertThat(put(ALPHA, "alpha-" + revision)).startsWith("HTTP/1.1 200"), FRAME_TIMEOUT);
            assertThat(next).contains(EVENT_LINE).noneMatch(line -> line.contains("bravo"));
        }
    }

    @Test
    @DisplayName("refusals after the 200 head are one stream-error frame with the by-id GET's status, then close")
    void refusalsAreStreamErrorFrames() throws Exception {
        assertThat(frames("/buoys/" + MISSING + "/stream"))
                .containsExactly("event: stream-error",
                        "data: {\"type\":\"about:blank\",\"title\":\"Not Found\",\"status\":404}", "");
        assertThat(frames("/buoys/not-a-uuid/stream"))
                .containsExactly("event: stream-error",
                        "data: {\"type\":\"about:blank\",\"title\":\"Bad Request\",\"status\":400}", "");
        assertThat(RawHttp.request(booted.port(), "GET", "/buoys/" + MISSING)).startsWith("HTTP/1.1 404");
        assertThat(RawHttp.request(booted.port(), "GET", "/buoys/not-a-uuid")).startsWith("HTTP/1.1 400");
    }

    @Test
    @DisplayName("GET /buoys/stream is the live view and GET /buoys/{id} the respond-once read: "
            + "neither is taken by the spectate template")
    void routesDoNotShadowEachOther() throws Exception {
        try (SseConnection live = SseConnection.open(booted.port(), "/buoys/stream")) {
            assertThat(live.head()).startsWith("HTTP/1.1 200");
            // Taken by the spectate template, "stream" would be a malformed id: a 400 frame. The
            // live view instead forwards any row's event.
            List<String> frame = live.awaitFrame(() -> {
                int revision = REVISION.incrementAndGet();
                assertThat(put(BRAVO, "bravo-" + revision)).startsWith("HTTP/1.1 200");
            }, FRAME_TIMEOUT);
            assertThat(frame).hasSize(2).first().isEqualTo(EVENT_LINE);
            assertThat(frame.get(1)).startsWith("data: {\"label\":\"bravo-");
        }
        assertThat(RawHttp.request(booted.port(), "GET", "/buoys/" + ALPHA))
                .startsWith("HTTP/1.1 200")
                .contains("\"label\":\"");
    }

    private static String put(String id, String label) {
        return RawHttp.request(booted.port(), "PUT", "/buoys/" + id, "{\"label\":\"" + label + "\"}");
    }

    private static List<String> frames(String path) throws Exception {
        try (SseConnection sse = SseConnection.open(booted.port(), path)) {
            assertThat(sse.head()).startsWith("HTTP/1.1 200");
            return sse.awaitServerClose(FRAME_TIMEOUT);
        }
    }

    // ------------------------------------------------------------------ harness

    private static Map<String, String> domainSources() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("eu/exeris/e2e/buoys/domain/Buoy.java",
                """
                package eu.exeris.e2e.buoys.domain;

                import eu.exeris.sdk.annotation.DomainEvent;
                import eu.exeris.sdk.annotation.DomainEvent.Trigger;
                import eu.exeris.sdk.annotation.ExerisDomain;
                import eu.exeris.sdk.annotation.Field;

                import java.util.UUID;

                @ExerisDomain(module = "buoys", path = "/buoys", realTimeApi = true)
                @DomainEvent(name = "BuoyMoved", trigger = Trigger.UPDATE, topic = "buoys.moved",
                        includeFields = {"label"})
                public class Buoy {

                    private UUID id;

                    @Field(label = "Label", required = true)
                    private String label;

                    public UUID getId() { return id; }
                    public void setId(UUID id) { this.id = id; }
                    public String getLabel() { return label; }
                    public void setLabel(String label) { this.label = label; }
                }
                """);
        return sources;
    }

    /** {@code POST /seed} creates the table and writes two buoys through the emitted repository. */
    private static Map<String, String> harnessSources() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("eu/exeris/e2e/buoys/BuoyApplication.java",
                """
                package eu.exeris.e2e.buoys;

                import eu.exeris.kernel.spi.persistence.TransactionalExecutor;

                public class BuoyApplication extends Application {

                    @Override
                    protected RuntimeComponents components(TransactionalExecutor transactionalExecutor) {
                        return new BuoyComponents(transactionalExecutor);
                    }
                }
                """);
        sources.put("eu/exeris/e2e/buoys/BuoyComponents.java",
                """
                package eu.exeris.e2e.buoys;

                import eu.exeris.e2e.buoys.domain.Buoy;
                import eu.exeris.kernel.core.http.routing.HttpRouter;
                import eu.exeris.kernel.spi.http.HttpMethod;
                import eu.exeris.kernel.spi.http.HttpStatus;
                import eu.exeris.kernel.spi.persistence.TransactionalExecutor;

                import java.util.UUID;

                public class BuoyComponents extends RuntimeComponents {

                    private final TransactionalExecutor executor;

                    public BuoyComponents(TransactionalExecutor transactionalExecutor) {
                        super(transactionalExecutor);
                        this.executor = transactionalExecutor;
                    }

                    @Override
                    public void configureRoutes(HttpRouter.Builder routes) {
                        routes.route(HttpMethod.GET, "/probe", exchange -> exchange.respond(HttpStatus.OK));
                        routes.route(HttpMethod.POST, "/seed", exchange -> {
                            executor.executeManaged(conn -> conn.executeUpdate(
                                    "CREATE TABLE buoys (id UUID PRIMARY KEY, label VARCHAR(255))"));
                            seed(UUID.fromString("%s"), "alpha");
                            seed(UUID.fromString("%s"), "bravo");
                            exchange.respond(HttpStatus.NO_CONTENT);
                        });
                    }

                    private void seed(UUID id, String label) {
                        Buoy buoy = new Buoy();
                        buoy.setId(id);
                        buoy.setLabel(label);
                        buoyRepository().save(buoy);
                    }
                }
                """.formatted(ALPHA, BRAVO));
        return sources;
    }
}
