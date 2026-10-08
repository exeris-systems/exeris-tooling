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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A streaming action runs, and the events it triggers stream back, on a <b>real</b> kernel boot
 * (ADR-044 Amendment 2).
 *
 * <pre>
 *   @ExerisDomain + @Action(streaming = true) + @DomainEvent(trigger = ACTION) source
 *        → javac + ExerisDomainProcessor      (real metadata)
 *        → CodegenPipeline.run                (per-action stream handler, publisher, router)
 *        → javac over the emitted tree + a consumer-style Application subclass
 *        → KernelBootstrap                    (http + persistence on in-memory H2 + events)
 *        → a real socket                      (POST /parcels/{id}/actions/track as SSE)
 * </pre>
 *
 * <p>The entity is {@code GLOBAL}: H2 implements no row-level security, and the tenant guard of a
 * tenant-partitioned entity refuses every stream until the application binds a route policy. The
 * stream-id filter that isolates a tenant-partitioned stream is the same code on a global one, and
 * it is what the foreign-row event below exercises: the harness's service publishes an event for
 * another row while the action runs, after the stream has subscribed. The harness's publisher
 * drops one event, so one stream waits out its deadline.
 */
@Tag("e2e")
@Tag("boot")
@DisplayName("ADR-044 Amendment 2 — a streaming action runs and streams its events on a real kernel boot")
class ActionStreamBootE2ETest {

    private static final String BASE_PACKAGE = "eu.exeris.e2e.parcels";
    private static final String ALPHA = "00000000-0000-4000-8000-00000000000a";
    private static final String BRAVO = "00000000-0000-4000-8000-00000000000b";
    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(20);

    @TempDir
    static Path workspace;

    private static GeneratedTree app;
    private static BootedApplication booted;

    @BeforeAll
    static void bootWithSeededRows() throws IOException {
        app = GeneratedTree.build(workspace, BASE_PACKAGE, domainSources(), harnessSources());
        booted = BootedApplication.start(app.loader(), BASE_PACKAGE + ".ParcelApplication");
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
    @DisplayName("the handler is the amended driver, not the keep-alive scaffold")
    void emittedHandlerRunsTheAction() throws IOException {
        String handler = Files.readString(app.generatedRoot()
                .resolve("eu/exeris/e2e/parcels/handler/ParcelTrackStreamHandler.java"));
        assertThat(handler)
                .contains("entity.track();")
                .contains("tokens.add(bus.subscribe(\"ParcelTrackedEvent\"")
                .doesNotContain("keep-alive");
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
    @DisplayName("the action runs and persists; its event arrives as a named frame; the stream closes; "
            + "the event published for another row while it ran is filtered out")
    void actionRunsAndItsEventStreamsBack() throws Exception {
        List<String> lines;
        try (SseConnection sse = SseConnection.open(booted.port(), "POST",
                "/parcels/" + ALPHA + "/actions/track", null)) {
            assertThat(sse.head()).startsWith("HTTP/1.1 200").containsIgnoringCase("text/event-stream");
            lines = sse.awaitServerClose(CLOSE_TIMEOUT);
        }

        assertThat(lines).as("the frames the stream carried before the server closed it")
                .containsExactly("event: ParcelTracked", "data: {\"label\":\"alpha tracked\"}", "");
        assertThat(lines).noneMatch(line -> line.contains("foreign"));
        assertThat(RawHttp.request(booted.port(), "GET", "/parcels/" + ALPHA))
                .startsWith("HTTP/1.1 200")
                .contains("\"label\":\"alpha tracked\"");
    }

    @Test
    @DisplayName("an action with a body decodes it, runs, and streams its event")
    void actionWithABodyDecodesIt() throws Exception {
        List<String> lines;
        try (SseConnection sse = SseConnection.open(booted.port(), "POST",
                "/parcels/" + BRAVO + "/actions/relabel", "{\"label\":\"charlie\"}")) {
            lines = sse.awaitServerClose(CLOSE_TIMEOUT);
        }

        assertThat(lines).containsExactly("event: ParcelRelabelled", "data: {\"label\":\"charlie\"}", "");
    }

    @Test
    @DisplayName("refusals after the 200 head are one stream-error frame with the respond-once status")
    void refusalsAreStreamErrorFrames() throws Exception {
        assertThat(frames("/parcels/not-a-uuid/actions/track", null))
                .containsExactly("event: stream-error",
                        "data: {\"type\":\"about:blank\",\"title\":\"Bad Request\",\"status\":400}", "");
        assertThat(frames("/parcels/00000000-0000-4000-8000-0000000000ff/actions/track", null))
                .containsExactly("event: stream-error",
                        "data: {\"type\":\"about:blank\",\"title\":\"Not Found\",\"status\":404}", "");
        assertThat(frames("/parcels/" + BRAVO + "/actions/relabel", "{\"label\":"))
                .containsExactly("event: stream-error",
                        "data: {\"type\":\"about:blank\",\"title\":\"Bad Request\",\"status\":400}", "");
    }

    @Test
    @DisplayName("a triggered event that never arrives closes the stream at its deadline, with no frame")
    void missingEventClosesAtTheDeadline() throws Exception {
        long started = System.nanoTime();
        List<String> lines;
        try (SseConnection sse = SseConnection.open(booted.port(), "POST",
                "/parcels/" + BRAVO + "/actions/hold", null)) {
            lines = sse.awaitServerClose(Duration.ofSeconds(60));
        }
        Duration open = Duration.ofNanos(System.nanoTime() - started);

        assertThat(lines).isEmpty();
        // STREAM_DEADLINE_MILLIS is 30 s; the lower bound shows the stream waited for the event.
        assertThat(open).isGreaterThanOrEqualTo(Duration.ofSeconds(29));
    }

    private static List<String> frames(String path, String body) throws Exception {
        try (SseConnection sse = SseConnection.open(booted.port(), "POST", path, body)) {
            assertThat(sse.head()).startsWith("HTTP/1.1 200");
            return sse.awaitServerClose(CLOSE_TIMEOUT);
        }
    }

    // ------------------------------------------------------------------ harness

    private static Map<String, String> domainSources() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("eu/exeris/e2e/parcels/domain/Parcel.java",
                """
                package eu.exeris.e2e.parcels.domain;

                import eu.exeris.sdk.annotation.Action;
                import eu.exeris.sdk.annotation.ActionParam;
                import eu.exeris.sdk.annotation.DomainEvent;
                import eu.exeris.sdk.annotation.DomainEvent.Trigger;
                import eu.exeris.sdk.annotation.ExerisDomain;
                import eu.exeris.sdk.annotation.Field;

                import java.util.UUID;

                @ExerisDomain(module = "parcels", path = "/parcels")
                @DomainEvent(name = "ParcelTracked", trigger = Trigger.ACTION, action = "track",
                        topic = "parcels.tracked", includeFields = {"label"})
                @DomainEvent(name = "ParcelRelabelled", trigger = Trigger.ACTION, action = "relabel",
                        topic = "parcels.relabelled", includeFields = {"label"})
                @DomainEvent(name = "ParcelHeld", trigger = Trigger.ACTION, action = "hold",
                        topic = "parcels.held")
                public class Parcel {

                    private UUID id;

                    @Field(label = "Label", required = true)
                    private String label;

                    public UUID getId() { return id; }
                    public void setId(UUID id) { this.id = id; }
                    public String getLabel() { return label; }
                    public void setLabel(String label) { this.label = label; }

                    @Action(name = "track", label = "Track", streaming = true)
                    public void track() { this.label = this.label + " tracked"; }

                    @Action(name = "hold", label = "Hold", streaming = true)
                    public void hold() { }

                    @Action(name = "relabel", label = "Relabel", streaming = true,
                            streamEventType = "Relabelled")
                    public void relabel(@ActionParam(label = "Label") String label) {
                        this.label = label;
                    }
                }
                """);
        return sources;
    }

    /**
     * {@code POST /seed} creates the table and writes two parcels through the emitted repository.
     * The service publishes a {@code ParcelTracked} for the bravo row whenever the alpha row is
     * updated, so the alpha stream's subscription sees an event of the same type under a foreign
     * stream id before its own.
     */
    private static Map<String, String> harnessSources() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("eu/exeris/e2e/parcels/ParcelApplication.java",
                """
                package eu.exeris.e2e.parcels;

                import eu.exeris.kernel.spi.persistence.TransactionalExecutor;

                public class ParcelApplication extends Application {

                    @Override
                    protected RuntimeComponents components(TransactionalExecutor transactionalExecutor) {
                        return new ParcelComponents(transactionalExecutor);
                    }
                }
                """);
        sources.put("eu/exeris/e2e/parcels/ParcelComponents.java",
                """
                package eu.exeris.e2e.parcels;

                import eu.exeris.e2e.parcels.domain.Parcel;
                import eu.exeris.e2e.parcels.event.ParcelEventPublisher;
                import eu.exeris.e2e.parcels.repository.ParcelRepository;
                import eu.exeris.e2e.parcels.service.ParcelService;
                import eu.exeris.kernel.core.http.routing.HttpRouter;
                import eu.exeris.kernel.spi.context.KernelProviders;
                import eu.exeris.kernel.spi.events.EventEngine;
                import eu.exeris.kernel.spi.http.HttpMethod;
                import eu.exeris.kernel.spi.http.HttpStatus;
                import eu.exeris.kernel.spi.persistence.TransactionalExecutor;

                import java.util.UUID;

                public class ParcelComponents extends RuntimeComponents {

                    static final UUID ALPHA = UUID.fromString("%s");
                    static final UUID BRAVO = UUID.fromString("%s");

                    private final TransactionalExecutor executor;

                    public ParcelComponents(TransactionalExecutor transactionalExecutor) {
                        super(transactionalExecutor);
                        this.executor = transactionalExecutor;
                    }

                    @Override
                    protected ParcelEventPublisher createParcelEventPublisher() {
                        return new SilentHoldPublisher(KernelProviders.eventEngine());
                    }

                    @Override
                    protected ParcelService createParcelService() {
                        return new ForeignPublishingService(parcelRepository(), this);
                    }

                    @Override
                    public void configureRoutes(HttpRouter.Builder routes) {
                        routes.route(HttpMethod.GET, "/probe", exchange -> exchange.respond(HttpStatus.OK));
                        routes.route(HttpMethod.POST, "/seed", exchange -> {
                            executor.executeManaged(conn -> conn.executeUpdate(
                                    "CREATE TABLE parcels (id UUID PRIMARY KEY, label VARCHAR(255))"));
                            seed(ALPHA, "alpha");
                            seed(BRAVO, "bravo");
                            exchange.respond(HttpStatus.NO_CONTENT);
                        });
                    }

                    private void seed(UUID id, String label) {
                        Parcel parcel = new Parcel();
                        parcel.setId(id);
                        parcel.setLabel(label);
                        parcelRepository().save(parcel);
                    }

                    /** Publishes every event but ParcelHeld, so the hold stream waits for its deadline. */
                    static final class SilentHoldPublisher extends ParcelEventPublisher {

                        SilentHoldPublisher(EventEngine eventEngine) {
                            super(eventEngine);
                        }

                        @Override
                        public void publishParcelHeldEvent(UUID streamId, Parcel parcel) {
                            // not published
                        }
                    }

                    static final class ForeignPublishingService extends ParcelService {

                        private final ParcelComponents components;

                        ForeignPublishingService(ParcelRepository repository, ParcelComponents components) {
                            super(repository);
                            this.components = components;
                        }

                        @Override
                        public Parcel update(UUID id, Parcel parcel) {
                            if (ALPHA.equals(id)) {
                                Parcel foreign = new Parcel();
                                foreign.setId(BRAVO);
                                foreign.setLabel("foreign");
                                components.parcelEventPublisher().publishParcelTrackedEvent(BRAVO, foreign);
                            }
                            return super.update(id, parcel);
                        }
                    }
                }
                """.formatted(ALPHA, BRAVO));
        return sources;
    }
}
