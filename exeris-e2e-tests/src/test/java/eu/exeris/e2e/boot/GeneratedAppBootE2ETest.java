package eu.exeris.e2e.boot;

import eu.exeris.e2e.codegen.compile.GeneratedTree;
import eu.exeris.kernel.community.testkit.http.KernelBootstrapHttpEngineFixture;
import eu.exeris.kernel.core.http.routing.HttpRouter;
import eu.exeris.kernel.spi.http.HttpHandler;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpStatus;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The emitted application's stream routes resolve on a <b>real</b> kernel boot.
 *
 * <p>Assertions about emitted <em>text</em> cannot show this. What decides it is the object the
 * kernel actually holds as its server handler: the stream dispatcher resolves a stream only when
 * that object is an {@code HttpRouter}. So the emitted {@code Application} is booted, with a stream
 * route:
 *
 * <pre>
 *   @ExerisDomain(realTimeApi) + @DomainEvent source
 *        → javac + ExerisDomainProcessor      (real metadata)
 *        → CodegenPipeline.run                (Application, RuntimeComponents, RuntimeLifecycle, …)
 *        → javac over the emitted tree + a consumer-style Application subclass
 *        → KernelBootstrap                    (http + events, persistence on in-memory H2)
 *        → a real socket                      (respond-once requests, an SSE stream, a frame)
 * </pre>
 *
 * <p><b>A frame, not a head.</b> The SSE response head is written by the kernel's stream engine
 * <em>before</em> the handler runs, so a test that asserts only the head passes over a stream
 * handler that throws on its first line — one that reads {@code KernelProviders.eventEngine()} on
 * the stream thread, where the kernel binds no engine. So the boot test here publishes an event and
 * reads the frame it produces.
 *
 * <p>Two tests, because two properties need two harnesses. The fixture test drives
 * {@code RuntimeLifecycle.edgeRouter(...)} with slots the test controls, which is the only way to
 * observe the composing window (503, close-on-open) deterministically. The boot test runs the
 * emitted {@code Application.run()} end to end, which is the only way to prove the edge router is
 * what the kernel is handed and that composition inside the boot callback feeds it.
 */
@Tag("e2e")
@Tag("boot")
@DisplayName("T23 — the emitted Application serves its stream routes on a real kernel boot")
class GeneratedAppBootE2ETest {

    private static final String BASE_PACKAGE = "eu.exeris.e2e.live";
    private static final Duration FRAME_TIMEOUT = Duration.ofSeconds(20);

    @TempDir
    static Path workspace;

    private static GeneratedTree app;
    private static ClassLoader appLoader;

    @BeforeAll
    static void generateCompileAndLoad() throws IOException {
        app = GeneratedTree.build(workspace, BASE_PACKAGE, domainSources(), harnessSources());
        appLoader = app.loader();
    }

    @AfterAll
    static void closeLoader() throws IOException {
        if (app != null) {
            app.close();
        }
    }

    @Test
    @DisplayName("edgeRouter(...) on a real kernel: an HttpRouter that resolves the stream, 503 and "
            + "close-on-open while composing, then respond-once traffic reaches the handler slot")
    void edgeRouterHoldsItsContractOnARealKernel() throws Exception {
        AtomicReference<HttpHandler> handlerSlot = new AtomicReference<>();
        AtomicReference<Object> componentsSlot = new AtomicReference<>();
        HttpHandler edge = edgeRouter(handlerSlot, componentsSlot);

        // The property this turns on: the kernel's stream dispatcher resolves a stream only through
        // `handler instanceof HttpRouter`.
        assertThat(edge).isInstanceOf(HttpRouter.class);
        HttpRouter router = (HttpRouter) edge;
        assertThat(router.resolveStream(HttpMethod.GET, "/beacons/stream")).isNotNull();
        assertThat(router.resolveStream(HttpMethod.GET, "/beacons")).isNull();

        try (KernelBootstrapHttpEngineFixture kernel = new KernelBootstrapHttpEngineFixture()) {
            kernel.start(edge);
            int port = kernel.boundPort();

            // Composing — both slots empty. A respond-once request is refused, not dropped...
            assertThat(RawHttp.request(port, "GET", "/beacons")).startsWith("HTTP/1.1 503");

            // ...and a stream route resolves AS a stream, then closes: the head is written before
            // any handler runs, so a status cannot refuse it.
            try (SseConnection sse = SseConnection.open(port, "/beacons/stream")) {
                assertThat(sse.head()).startsWith("HTTP/1.1 200").containsIgnoringCase("text/event-stream");
                assertThat(sse.awaitServerClose(FRAME_TIMEOUT))
                        .as("closed by the server, with no frame, while nothing is composed")
                        .isEmpty();
            }

            // The handler slot filled: respond-once traffic reaches it through notFound, whatever
            // its path — including one the stream route's prefix shares.
            handlerSlot.set(exchange -> exchange.respond(HttpStatus.NO_CONTENT));
            assertThat(RawHttp.request(port, "GET", "/beacons")).startsWith("HTTP/1.1 204");
            assertThat(RawHttp.request(port, "POST", "/beacons/stream-not")).startsWith("HTTP/1.1 204");
        }
    }

    @Test
    @DisplayName("the emitted Application boots, composes inside the boot callback, starts its saga "
            + "and subscriber, and a stream opened through its edge router receives a frame, with "
            + "its payload, published from a request thread")
    void emittedApplicationServesAStreamFrame() throws Exception {
        List<String> probe = probe();
        probe.clear();
        try (BootedApplication app = BootedApplication.start(appLoader, BASE_PACKAGE + ".LiveApplication")) {
            int port = app.port();

            // By the time the application answers anything, the saga plan is
            // registered and the subscriber is receiving — in that order.
            assertThat(probe).as("activation, before the handler slot was set")
                    .containsExactly("saga:initialize:BeaconSaga", "subscriber:subscribe");

            // Respond-once traffic reaches the COMPOSED router — a hand-registered route, and a
            // generated handler whose path-id guard answers before any repository is touched.
            assertThat(RawHttp.request(port, "GET", "/probe")).startsWith("HTTP/1.1 200");
            assertThat(RawHttp.request(port, "GET", "/beacons/not-a-uuid")).startsWith("HTTP/1.1 400");

            try (SseConnection sse = SseConnection.open(port, "/beacons/stream")) {
                assertThat(sse.head()).startsWith("HTTP/1.1 200").containsIgnoringCase("text/event-stream");

                // The handler subscribes inside handle(), after the head is on the wire, so one
                // publish could race the subscription. Publish until a frame arrives.
                List<String> frame = sse.awaitFrame(
                        () -> RawHttp.request(port, "POST", "/ping"), FRAME_TIMEOUT);

                assertThat(frame).as("the SSE frame the published event produced")
                        .contains("event: BeaconPinged")
                        // The publisher captured the codec registry at composition, so a publish
                        // from the request thread — where the kernel binds none — encodes. Resolved
                        // per publish, this line would be `data: ` (empty).
                        .contains("data: {\"label\":\"alpha\"}");
                System.out.println("[T23 B1 / T48 C1] SSE frame received over the generated edge router: "
                        + frame);
            }

            // The generated subscriber, subscribed at boot, received the same event and payload.
            awaitProbe(probe, "subscriber:received:{\"label\":\"alpha\"}");

            // The live view learns that its peer left only when it next emits — the stream SPI has
            // no liveness signal — and the kernel's shutdown drain waits for a busy stream up to 60s.
            // A few more events let the handler find out and unwind before the application stops.
            for (int i = 0; i < 3; i++) {
                RawHttp.request(port, "POST", "/ping");
                Thread.sleep(100);
            }
        }

        // ...and was released when the application stopped.
        assertThat(probe).last().isEqualTo("subscriber:unsubscribe");
    }

    // ------------------------------------------------------------------ harness

    @SuppressWarnings("unchecked")
    private static List<String> probe() throws Exception {
        return (List<String>) appLoader.loadClass(BASE_PACKAGE + ".LiveComponents").getField("PROBE").get(null);
    }

    private static void awaitProbe(List<String> probe, String expected) throws InterruptedException {
        long deadline = System.nanoTime() + FRAME_TIMEOUT.toNanos();
        while (!probe.contains(expected)) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("never recorded " + expected + "; recorded " + probe);
            }
            Thread.sleep(20);
        }
    }

    private static HttpHandler edgeRouter(AtomicReference<HttpHandler> handlerSlot,
                                          AtomicReference<Object> componentsSlot) throws Exception {
        Class<?> lifecycle = appLoader.loadClass(BASE_PACKAGE + ".RuntimeLifecycle");
        return (HttpHandler) lifecycle.getMethod("edgeRouter", AtomicReference.class, AtomicReference.class)
                .invoke(null, handlerSlot, componentsSlot);
    }

    /**
     * One live entity: {@code realTimeApi} (so a stream route is emitted) with one
     * {@code @DomainEvent} carrying a payload (so the stream handler is the EV1 producer, which
     * takes its engine by constructor, and the publisher encodes a payload).
     */
    private static Map<String, String> domainSources() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("eu/exeris/e2e/live/domain/Beacon.java",
                """
                package eu.exeris.e2e.live.domain;

                import eu.exeris.sdk.annotation.DomainEvent;
                import eu.exeris.sdk.annotation.ExerisDomain;
                import eu.exeris.sdk.annotation.Field;
                import eu.exeris.sdk.annotation.Saga;
                import eu.exeris.sdk.annotation.SagaStep;

                import java.util.UUID;

                @ExerisDomain(module = "live", path = "/beacons", realTimeApi = true)
                // MANUAL: published by consumer code (the /ping route below), not by a CRUD handler,
                // so the fixture needs no database table to produce an event.
                @DomainEvent(name = "BeaconPinged", topic = "live.beacons", trigger = DomainEvent.Trigger.MANUAL,
                        includeFields = {"label"})
                // A saga, so the boot must compile and register its plan.
                @Saga(name = "BeaconSaga", timeout = "PT5M", maxRetries = 2)
                public class Beacon {

                    private UUID id;

                    @Field(label = "Label", required = true)
                    private String label;

                    public UUID getId() {
                        return id;
                    }

                    public void setId(UUID id) {
                        this.id = id;
                    }

                    public String getLabel() {
                        return label;
                    }

                    public void setLabel(String label) {
                        this.label = label;
                    }

                    @SagaStep(order = 0, name = "relay", service = "relay", command = "Relay")
                    public void relay() {
                    }
                }
                """);
        return sources;
    }

    /**
     * What a consumer writes around the emitted tree: an {@code Application} subclass with its own
     * subsystem list and its own {@code RuntimeComponents} (ADR-070 obligations 4 and 7). The
     * emitted {@code Application.run()} is not overridden — it is the thing under test.
     */
    private static Map<String, String> harnessSources() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("eu/exeris/e2e/live/LiveApplication.java",
                """
                package eu.exeris.e2e.live;

                import eu.exeris.kernel.spi.persistence.TransactionalExecutor;

                public class LiveApplication extends Application {

                    // No graph, no crypto: the fixture needs http, the event bus and the flow engine.
                    // events and flow pull persistence and memory by dependency closure.
                    @Override
                    protected String subsystems() {
                        return "http,events,flow";
                    }

                    @Override
                    protected RuntimeComponents components(TransactionalExecutor transactionalExecutor) {
                        return new LiveComponents(transactionalExecutor);
                    }
                }
                """);
        sources.put("eu/exeris/e2e/live/LiveComponents.java",
                """
                package eu.exeris.e2e.live;

                import eu.exeris.e2e.live.domain.Beacon;
                import eu.exeris.e2e.live.event.BeaconEventSubscriber;
                import eu.exeris.e2e.live.saga.BeaconSagaFlow;
                import eu.exeris.kernel.core.http.routing.HttpRouter;
                import eu.exeris.kernel.spi.context.KernelProviders;
                import eu.exeris.kernel.spi.events.EventDescriptor;
                import eu.exeris.kernel.spi.events.EventPayload;
                import eu.exeris.kernel.spi.flow.model.FlowExecutionPlan;
                import eu.exeris.kernel.spi.http.HttpMethod;
                import eu.exeris.kernel.spi.http.HttpStatus;
                import eu.exeris.kernel.spi.persistence.TransactionalExecutor;

                import java.lang.foreign.ValueLayout;
                import java.nio.charset.StandardCharsets;
                import java.util.List;
                import java.util.UUID;
                import java.util.concurrent.CopyOnWriteArrayList;

                public class LiveComponents extends RuntimeComponents {

                    /** What the application did, in order — read by the test through reflection. */
                    public static final List<String> PROBE = new CopyOnWriteArrayList<>();

                    private static final UUID BEACON_ID = UUID.fromString("00000000-0000-0000-0000-0000000b0a1c");

                    public LiveComponents(TransactionalExecutor transactionalExecutor) {
                        super(transactionalExecutor);
                    }

                    @Override
                    public void configureRoutes(HttpRouter.Builder routes) {
                        routes.route(HttpMethod.GET, "/probe", exchange -> exchange.respond(HttpStatus.OK));
                        // Publishes from a request thread — where a generated handler publishes
                        // (ADR-075) — through the publisher the generated factory composed.
                        routes.route(HttpMethod.POST, "/ping", exchange -> {
                            Beacon beacon = new Beacon();
                            beacon.setId(BEACON_ID);
                            beacon.setLabel("alpha");
                            beaconEventPublisher().publishBeaconPingedEvent(BEACON_ID, beacon);
                            exchange.respond(HttpStatus.ACCEPTED);
                        });
                    }

                    // Behaviour installed the way the seam intends — by overriding a factory with a
                    // subclass of the generated type. Construction still uses the boot-bound engines.
                    @Override
                    protected BeaconSagaFlow createBeaconSagaFlow() {
                        return new BeaconSagaFlow(KernelProviders.flowEngine()) {
                            @Override
                            public synchronized FlowExecutionPlan initialize() {
                                FlowExecutionPlan plan = super.initialize();
                                PROBE.add("saga:initialize:" + plan.definitionName());
                                return plan;
                            }
                        };
                    }

                    @Override
                    protected BeaconEventSubscriber createBeaconEventSubscriber() {
                        return new BeaconEventSubscriber(KernelProviders.eventEngine()) {
                            @Override
                            public void subscribe() {
                                super.subscribe();
                                PROBE.add("subscriber:subscribe");
                            }

                            @Override
                            public void unsubscribe() {
                                super.unsubscribe();
                                PROBE.add("subscriber:unsubscribe");
                            }

                            @Override
                            protected void handleBeaconPingedEvent(EventDescriptor descriptor, EventPayload payload) {
                                try (payload) {
                                    PROBE.add("subscriber:received:" + new String(
                                            payload.segment().toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8));
                                }
                            }
                        };
                    }
                }
                """);
        return sources;
    }
}
