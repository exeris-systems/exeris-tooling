package eu.exeris.e2e.boot;

import eu.exeris.e2e.codegen.compile.GeneratedTree;
import eu.exeris.kernel.community.testkit.http.KernelBootstrapHttpEngineFixture;
import eu.exeris.kernel.core.http.routing.HttpRouter;
import eu.exeris.kernel.spi.http.HttpHandler;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpStreamExchange;
import eu.exeris.kernel.spi.http.HttpStreamHandler;
import eu.exeris.kernel.spi.http.StreamEvent;
import eu.exeris.kernel.spi.http.StreamRouteResolver;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The emitted application's stream routes resolve on a <b>real</b> kernel boot.
 *
 * <p>Assertions about emitted <em>text</em> cannot show this. What decides it is the object the
 * kernel actually holds as its server handler: the stream dispatcher resolves a stream only through
 * that object's {@code StreamRouteResolver}, so every wrapper between the kernel and the router has
 * to carry stream resolution through. So the emitted {@code Application} is booted, with a stream
 * route, a {@code decorate} wrapper and a stream route registered in {@code configureRoutes}:
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
 * <p>Two harnesses, because two properties need them. The fixture test drives
 * {@code RuntimeLifecycle.edgeHandler(...)} with a slot the test controls, which is the only way to
 * observe the composing window (503 for a stream open too) deterministically. The boot tests run
 * the emitted {@code Application.run()} end to end, which is the only way to prove the edge handler
 * is what the kernel is handed and that composition inside the boot callback feeds it: with a
 * {@code decorate} wrapper that resolves streams, and with the compositions it refuses. A second
 * application, with no generated stream route, shows that the refusal follows what the built router
 * serves rather than what was generated.
 */
@Tag("e2e")
@Tag("boot")
@DisplayName("T23 — the emitted Application serves its stream routes on a real kernel boot")
class GeneratedAppBootE2ETest {

    private static final String BASE_PACKAGE = "eu.exeris.e2e.live";
    private static final String QUIET_PACKAGE = "eu.exeris.e2e.quiet";
    private static final Duration FRAME_TIMEOUT = Duration.ofSeconds(20);

    @TempDir
    static Path workspace;

    private static GeneratedTree app;
    private static ClassLoader appLoader;
    private static GeneratedTree quietApp;
    private static ClassLoader quietLoader;

    @BeforeAll
    static void generateCompileAndLoad() throws IOException {
        app = GeneratedTree.build(workspace.resolve("live"), BASE_PACKAGE, domainSources(), harnessSources());
        appLoader = app.loader();
        quietApp = GeneratedTree.build(workspace.resolve("quiet"), QUIET_PACKAGE,
                quietDomainSources(), quietHarnessSources());
        quietLoader = quietApp.loader();
    }

    @AfterAll
    static void closeLoader() throws IOException {
        if (app != null) {
            app.close();
        }
        if (quietApp != null) {
            quietApp.close();
        }
    }

    @Test
    @DisplayName("edgeHandler(...) on a real kernel: 503 for every request, a stream open included, "
            + "while composing; then streams resolve through the slot's StreamRouteResolver")
    void edgeHandlerHoldsItsContractOnARealKernel() throws Exception {
        AtomicReference<HttpHandler> handlerSlot = new AtomicReference<>();
        HttpHandler edge = edgeHandler(handlerSlot);

        // The property this turns on: the kernel's stream dispatcher resolves a stream only
        // through `handler instanceof StreamRouteResolver`.
        assertThat(edge).isInstanceOf(StreamRouteResolver.class).isNotInstanceOf(HttpRouter.class);
        assertThat(((StreamRouteResolver) edge).resolveStream(HttpMethod.GET, "/beacons/stream"))
                .as("nothing resolves while the slot is empty").isNull();

        try (KernelBootstrapHttpEngineFixture kernel = new KernelBootstrapHttpEngineFixture()) {
            kernel.start(edge);
            int port = kernel.boundPort();

            // Composing — the slot is empty. A respond-once request is refused, not dropped, and
            // so is a stream open: no stream resolves, so it is answered like any request.
            assertThat(RawHttp.request(port, "GET", "/beacons")).startsWith("HTTP/1.1 503");
            try (SseConnection sse = SseConnection.open(port, "/beacons/stream")) {
                assertThat(sse.head()).startsWith("HTTP/1.1 503");
            }

            // A router in the slot: its stream routes resolve through the edge, with their path
            // parameters, and everything else reaches its respond-once table.
            handlerSlot.set(HttpRouter.builder()
                    .streamRoute(HttpMethod.GET, "/echo/{name}/stream",
                            exchange -> exchange.emit(StreamEvent.of("echo", exchange.pathParams().get("name"))))
                    .notFound(exchange -> exchange.respond(HttpStatus.NO_CONTENT))
                    .build());
            try (SseConnection sse = SseConnection.open(port, "/echo/alpha/stream")) {
                assertThat(sse.head()).startsWith("HTTP/1.1 200").containsIgnoringCase("text/event-stream");
                assertThat(sse.awaitFrame(() -> { }, FRAME_TIMEOUT))
                        .containsExactly("event: echo", "data: alpha");
            }
            assertThat(RawHttp.request(port, "GET", "/beacons")).startsWith("HTTP/1.1 204");

            // A handler in the slot that is not a resolver erases the stream table behind it —
            // the kernel's contract, and the reason run() refuses one when the router serves streams.
            HttpRouter router = HttpRouter.builder()
                    .streamRoute(HttpMethod.GET, "/echo/{name}/stream", exchange -> { })
                    .notFound(exchange -> exchange.respond(HttpStatus.NO_CONTENT))
                    .build();
            handlerSlot.set(router::handle);
            assertThat(RawHttp.request(port, "GET", "/echo/alpha/stream")).startsWith("HTTP/1.1 204");
        }

        // The emitted requireDecoratedStreamRoute(...) probes a generated template route by its
        // own template string, so a template has to match itself: "{id}" is a non-empty segment.
        HttpStreamHandlerProbe generated = new HttpStreamHandlerProbe();
        assertThat(HttpRouter.builder()
                .streamRoute(HttpMethod.POST, "/beacons/{id}/actions/track", generated)
                .build()
                .resolveStream(HttpMethod.POST, "/beacons/{id}/actions/track"))
                .isNotNull()
                .extracting(match -> match.handler()).isSameAs(generated);
    }

    /** A stream handler with an identity of its own, so a lookup can be checked for it. */
    private static final class HttpStreamHandlerProbe implements HttpStreamHandler {
        @Override
        public void handle(HttpStreamExchange exchange) {
            // never opened
        }
    }

    @Test
    @DisplayName("the emitted Application boots, composes inside the boot callback, starts its saga "
            + "and subscriber; through a decorate wrapper that resolves streams, a generated stream "
            + "receives a frame published from a request thread, and a configureRoutes stream runs "
            + "inside the wrapper's binding")
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
            // generated handler whose path-id guard answers before any repository is touched —
            // through the decorate wrapper, which binds TAG for it.
            assertThat(RawHttp.request(port, "GET", "/probe")).startsWith("HTTP/1.1 200");
            assertThat(RawHttp.request(port, "GET", "/beacons/not-a-uuid")).startsWith("HTTP/1.1 400");
            assertThat(RawHttp.request(port, "GET", "/tag")).startsWith("HTTP/1.1 200");

            // A stream registered in configureRoutes resolves, and the wrapper's binding and the
            // captured path parameter both reach it.
            try (SseConnection sse = SseConnection.open(port, "/tagged/alpha/stream")) {
                assertThat(sse.head()).startsWith("HTTP/1.1 200").containsIgnoringCase("text/event-stream");
                assertThat(sse.awaitFrame(() -> { }, FRAME_TIMEOUT))
                        .containsExactly("event: tagged", "data: tenant-7:alpha");
            }

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
                System.out.println("[K9 / T48 C1] SSE frame received through the decorate wrapper: "
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

    @Test
    @DisplayName("the emitted subsystems() names what the domain uses — a saga and a domain event, "
            + "no graph — and the harness's override replaces it")
    void subsystemsAreDerivedAndTheOverrideReplacesThem() throws Exception {
        assertThat(subsystems(BASE_PACKAGE + ".Application"))
                .isEqualTo("http,persistence,flow,events,crypto");
        assertThat(subsystems(BASE_PACKAGE + ".LiveApplication")).isEqualTo("http,events,flow");
    }

    @Test
    @DisplayName("a decorate wrapper that does not resolve streams is refused at boot in an application "
            + "with stream routes, naming the wrapper's class and the cure")
    void wrapperThatResolvesNoStreamsIsRefused() {
        assertThatThrownBy(() -> BootedApplication.start(appLoader, BASE_PACKAGE + ".PlainWrapApplication"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("exited during boot")
                .rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RuntimeComponents.decorate returned eu.exeris.e2e.live.PlainWrapper,")
                .hasMessageContaining("does not implement StreamRouteResolver")
                .hasMessageContaining("Implement StreamRouteResolver on the wrapper and delegate resolveStream "
                        + "to the router");
    }

    @Test
    @DisplayName("a decorate wrapper that is a StreamRouteResolver but resolves no stream for a "
            + "generated route is refused at boot, naming its class and the route")
    void resolverThatHidesAGeneratedStreamIsRefused() {
        assertThatThrownBy(() -> BootedApplication.start(appLoader, BASE_PACKAGE + ".NullResolverApplication"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("exited during boot")
                .rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RuntimeComponents.decorate returned eu.exeris.e2e.live.NullResolver, "
                        + "which resolves no stream for GET /beacons/stream");
    }

    @Test
    @DisplayName("a configureRoutes stream route at a generated stream path is refused at boot by the "
            + "kernel's router builder, naming the method and path")
    void streamRouteAtAGeneratedPathIsRefused() {
        // Generated stream routes are registered before configureRoutes, so the kernel builder
        // sees the hand-written one second and refuses it as it is registered.
        assertThatThrownBy(() -> BootedApplication.start(appLoader, BASE_PACKAGE + ".DisplacingApplication"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("exited during boot")
                .rootCause()
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a stream route is already registered for GET /beacons/stream");
    }

    @Test
    @DisplayName("an application with no generated stream route refuses a decorate wrapper that is not "
            + "a StreamRouteResolver once configureRoutes registers a stream, naming the wrapper's class")
    void wrapperIsRefusedWhenOnlyConfigureRoutesRegistersAStream() {
        // The guard asks the built router whether it serves any stream, so a stream the
        // consumer registered is protected the same way a generated one is.
        assertThatThrownBy(() -> BootedApplication.start(quietLoader, QUIET_PACKAGE + ".StreamingPlainWrapApplication"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("exited during boot")
                .rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RuntimeComponents.decorate returned eu.exeris.e2e.quiet.QuietWrapper,")
                .hasMessageContaining("does not implement StreamRouteResolver")
                .hasMessageContaining("Implement StreamRouteResolver on the wrapper and delegate resolveStream "
                        + "to the router");
    }

    @Test
    @DisplayName("an application whose router serves no stream route boots behind a decorate wrapper "
            + "that is not a StreamRouteResolver, and the wrapper serves its requests")
    void wrapperIsAcceptedWhenTheRouterServesNoStream() throws Exception {
        try (BootedApplication booted = BootedApplication.start(quietLoader, QUIET_PACKAGE + ".QuietApplication")) {
            int port = booted.port();
            assertThat(RawHttp.request(port, "GET", "/tag")).as("the wrapper's binding reaches the route")
                    .startsWith("HTTP/1.1 200");
            assertThat(RawHttp.request(port, "GET", "/lamps/not-a-uuid")).startsWith("HTTP/1.1 400");
        }
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

    private static String subsystems(String applicationClass) throws Exception {
        Class<?> type = appLoader.loadClass(applicationClass);
        Method subsystems = appLoader.loadClass(BASE_PACKAGE + ".Application")
                .getDeclaredMethod("subsystems");
        subsystems.setAccessible(true);
        return (String) subsystems.invoke(type.getDeclaredConstructor().newInstance());
    }

    private static HttpHandler edgeHandler(AtomicReference<HttpHandler> handlerSlot) throws Exception {
        Class<?> lifecycle = appLoader.loadClass(BASE_PACKAGE + ".RuntimeLifecycle");
        return (HttpHandler) lifecycle.getMethod("edgeHandler", AtomicReference.class)
                .invoke(null, handlerSlot);
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
     * subsystem list and its own {@code RuntimeComponents} (ADR-070 obligations 4 and 7), a
     * {@code decorate} wrapper that binds {@code TAG} and resolves streams by delegating, and a
     * stream route of its own. {@code PlainWrapApplication} swaps in a wrapper that is not a
     * resolver, {@code NullResolverApplication} a resolver that resolves nothing, and
     * {@code DisplacingApplication} a stream at a generated path; all three must be refused. The
     * emitted {@code Application.run()} is not overridden — it is the thing under test.
     */
    private static Map<String, String> harnessSources() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("eu/exeris/e2e/live/LiveApplication.java",
                """
                package eu.exeris.e2e.live;

                import eu.exeris.kernel.spi.persistence.TransactionalExecutor;

                public class LiveApplication extends Application {

                    // The generated default is http,persistence,flow,events,crypto. This drops crypto,
                    // which nothing here uses, and names no persistence: events and flow pull
                    // persistence and memory by dependency closure.
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
                import eu.exeris.kernel.spi.http.HttpHandler;
                import eu.exeris.kernel.spi.http.HttpMethod;
                import eu.exeris.kernel.spi.http.HttpStatus;
                import eu.exeris.kernel.spi.http.StreamEvent;
                import eu.exeris.kernel.spi.persistence.TransactionalExecutor;

                import java.lang.foreign.ValueLayout;
                import java.nio.charset.StandardCharsets;
                import java.util.List;
                import java.util.UUID;
                import java.util.concurrent.CopyOnWriteArrayList;

                public class LiveComponents extends RuntimeComponents {

                    /** What the application did, in order — read by the test through reflection. */
                    public static final List<String> PROBE = new CopyOnWriteArrayList<>();

                    /** Bound by the decorate wrapper; a route and a stream report whether it was. */
                    public static final ScopedValue<String> TAG = ScopedValue.newInstance();

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
                        routes.route(HttpMethod.GET, "/tag",
                                exchange -> exchange.respond(TAG.isBound() ? HttpStatus.OK : HttpStatus.CONFLICT));
                        // A stream of the consumer's own, beside the generated ones.
                        routes.streamRoute(HttpMethod.GET, "/tagged/{name}/stream",
                                exchange -> exchange.emit(StreamEvent.of("tagged",
                                        (TAG.isBound() ? TAG.get() : "unbound") + ":"
                                                + exchange.pathParams().get("name"))));
                    }

                    @Override
                    public HttpHandler decorate(HttpRouter router) {
                        return new TagBinding(router, "tenant-7");
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
        sources.put("eu/exeris/e2e/live/TagBinding.java",
                """
                package eu.exeris.e2e.live;

                import eu.exeris.kernel.core.http.routing.HttpRouter;
                import eu.exeris.kernel.spi.http.HttpExchange;
                import eu.exeris.kernel.spi.http.HttpHandler;
                import eu.exeris.kernel.spi.http.HttpMethod;
                import eu.exeris.kernel.spi.http.HttpStreamHandler;
                import eu.exeris.kernel.spi.http.StreamMatch;
                import eu.exeris.kernel.spi.http.StreamRouteResolver;

                /** The wrapper shape the decorate Javadoc shows: it binds around requests and streams. */
                public record TagBinding(HttpRouter router, String tag) implements HttpHandler, StreamRouteResolver {

                    @Override
                    public void handle(HttpExchange exchange) {
                        ScopedValue.where(LiveComponents.TAG, tag).run(() -> router.handle(exchange));
                    }

                    @Override
                    public StreamMatch resolveStream(HttpMethod method, String path) {
                        StreamMatch match = router.resolveStream(method, path);
                        if (match == null) {
                            return null;
                        }
                        HttpStreamHandler route = match.handler();
                        return new StreamMatch(exchange -> ScopedValue.where(LiveComponents.TAG, tag)
                                .run(() -> route.handle(exchange)), match.params());
                    }
                }
                """);
        sources.put("eu/exeris/e2e/live/PlainWrapApplication.java",
                """
                package eu.exeris.e2e.live;

                import eu.exeris.kernel.core.http.routing.HttpRouter;
                import eu.exeris.kernel.spi.http.HttpHandler;
                import eu.exeris.kernel.spi.persistence.TransactionalExecutor;

                /** The same application behind a decorate wrapper that is not a StreamRouteResolver. */
                public class PlainWrapApplication extends LiveApplication {

                    @Override
                    protected RuntimeComponents components(TransactionalExecutor transactionalExecutor) {
                        return new LiveComponents(transactionalExecutor) {
                            @Override
                            public HttpHandler decorate(HttpRouter router) {
                                return new PlainWrapper(router);
                            }
                        };
                    }
                }
                """);
        sources.put("eu/exeris/e2e/live/PlainWrapper.java",
                """
                package eu.exeris.e2e.live;

                import eu.exeris.kernel.core.http.routing.HttpRouter;
                import eu.exeris.kernel.spi.http.HttpExchange;
                import eu.exeris.kernel.spi.http.HttpHandler;

                /** Binds TAG around requests, and resolves no streams. */
                public record PlainWrapper(HttpRouter router) implements HttpHandler {

                    @Override
                    public void handle(HttpExchange exchange) {
                        ScopedValue.where(LiveComponents.TAG, "tenant-7").run(() -> router.handle(exchange));
                    }
                }
                """);
        sources.put("eu/exeris/e2e/live/DisplacingApplication.java",
                """
                package eu.exeris.e2e.live;

                import eu.exeris.kernel.core.http.routing.HttpRouter;
                import eu.exeris.kernel.spi.http.HttpMethod;
                import eu.exeris.kernel.spi.persistence.TransactionalExecutor;

                /** Registers a stream of its own at the path the generated live view serves. */
                public class DisplacingApplication extends LiveApplication {

                    @Override
                    protected RuntimeComponents components(TransactionalExecutor transactionalExecutor) {
                        return new LiveComponents(transactionalExecutor) {
                            @Override
                            public void configureRoutes(HttpRouter.Builder routes) {
                                super.configureRoutes(routes);
                                routes.streamRoute(HttpMethod.GET, "/beacons/stream", exchange -> { });
                            }
                        };
                    }
                }
                """);
        sources.put("eu/exeris/e2e/live/NullResolver.java",
                """
                package eu.exeris.e2e.live;

                import eu.exeris.kernel.core.http.routing.HttpRouter;
                import eu.exeris.kernel.spi.http.HttpExchange;
                import eu.exeris.kernel.spi.http.HttpHandler;
                import eu.exeris.kernel.spi.http.HttpMethod;
                import eu.exeris.kernel.spi.http.StreamMatch;
                import eu.exeris.kernel.spi.http.StreamRouteResolver;

                /** A resolver by type that resolves nothing. */
                public record NullResolver(HttpRouter router) implements HttpHandler, StreamRouteResolver {

                    @Override
                    public void handle(HttpExchange exchange) {
                        router.handle(exchange);
                    }

                    @Override
                    public StreamMatch resolveStream(HttpMethod method, String path) {
                        return null;
                    }
                }
                """);
        sources.put("eu/exeris/e2e/live/NullResolverApplication.java",
                """
                package eu.exeris.e2e.live;

                import eu.exeris.kernel.core.http.routing.HttpRouter;
                import eu.exeris.kernel.spi.http.HttpHandler;
                import eu.exeris.kernel.spi.persistence.TransactionalExecutor;

                /** The same application behind a resolver that hides every stream. */
                public class NullResolverApplication extends LiveApplication {

                    @Override
                    protected RuntimeComponents components(TransactionalExecutor transactionalExecutor) {
                        return new LiveComponents(transactionalExecutor) {
                            @Override
                            public HttpHandler decorate(HttpRouter router) {
                                return new NullResolver(router);
                            }
                        };
                    }
                }
                """);
        return sources;
    }

    /** One entity with no stream route: no {@code realTimeApi}, no streaming action. */
    private static Map<String, String> quietDomainSources() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("eu/exeris/e2e/quiet/domain/Lamp.java",
                """
                package eu.exeris.e2e.quiet.domain;

                import eu.exeris.sdk.annotation.ExerisDomain;
                import eu.exeris.sdk.annotation.Field;

                import java.util.UUID;

                @ExerisDomain(module = "quiet", path = "/lamps")
                public class Lamp {

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
                }
                """);
        return sources;
    }

    /**
     * Around the stream-less tree: {@code QuietApplication} decorates with a wrapper that is not a
     * resolver and registers no stream, which must boot; {@code StreamingPlainWrapApplication} adds
     * one stream in {@code configureRoutes} behind the same wrapper, which must be refused.
     */
    private static Map<String, String> quietHarnessSources() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("eu/exeris/e2e/quiet/QuietApplication.java",
                """
                package eu.exeris.e2e.quiet;

                import eu.exeris.kernel.spi.persistence.TransactionalExecutor;

                public class QuietApplication extends Application {

                    @Override
                    protected String subsystems() {
                        return "http,events,flow";
                    }

                    @Override
                    protected RuntimeComponents components(TransactionalExecutor transactionalExecutor) {
                        return new QuietComponents(transactionalExecutor);
                    }
                }
                """);
        sources.put("eu/exeris/e2e/quiet/QuietComponents.java",
                """
                package eu.exeris.e2e.quiet;

                import eu.exeris.kernel.core.http.routing.HttpRouter;
                import eu.exeris.kernel.spi.http.HttpHandler;
                import eu.exeris.kernel.spi.http.HttpMethod;
                import eu.exeris.kernel.spi.http.HttpStatus;
                import eu.exeris.kernel.spi.persistence.TransactionalExecutor;

                public class QuietComponents extends RuntimeComponents {

                    /** Bound by the decorate wrapper; a route reports whether it was. */
                    public static final ScopedValue<String> TAG = ScopedValue.newInstance();

                    public QuietComponents(TransactionalExecutor transactionalExecutor) {
                        super(transactionalExecutor);
                    }

                    @Override
                    public void configureRoutes(HttpRouter.Builder routes) {
                        routes.route(HttpMethod.GET, "/probe", exchange -> exchange.respond(HttpStatus.OK));
                        routes.route(HttpMethod.GET, "/tag",
                                exchange -> exchange.respond(TAG.isBound() ? HttpStatus.OK : HttpStatus.CONFLICT));
                    }

                    @Override
                    public HttpHandler decorate(HttpRouter router) {
                        return new QuietWrapper(router);
                    }
                }
                """);
        sources.put("eu/exeris/e2e/quiet/QuietWrapper.java",
                """
                package eu.exeris.e2e.quiet;

                import eu.exeris.kernel.core.http.routing.HttpRouter;
                import eu.exeris.kernel.spi.http.HttpExchange;
                import eu.exeris.kernel.spi.http.HttpHandler;

                /** Binds TAG around requests, and resolves no streams. */
                public record QuietWrapper(HttpRouter router) implements HttpHandler {

                    @Override
                    public void handle(HttpExchange exchange) {
                        ScopedValue.where(QuietComponents.TAG, "tenant-7").run(() -> router.handle(exchange));
                    }
                }
                """);
        sources.put("eu/exeris/e2e/quiet/StreamingPlainWrapApplication.java",
                """
                package eu.exeris.e2e.quiet;

                import eu.exeris.kernel.core.http.routing.HttpRouter;
                import eu.exeris.kernel.spi.http.HttpMethod;
                import eu.exeris.kernel.spi.http.StreamEvent;
                import eu.exeris.kernel.spi.persistence.TransactionalExecutor;

                /** The same wrapper, with one stream of the consumer's own and none generated. */
                public class StreamingPlainWrapApplication extends QuietApplication {

                    @Override
                    protected RuntimeComponents components(TransactionalExecutor transactionalExecutor) {
                        return new QuietComponents(transactionalExecutor) {
                            @Override
                            public void configureRoutes(HttpRouter.Builder routes) {
                                super.configureRoutes(routes);
                                routes.streamRoute(HttpMethod.GET, "/lamps/live",
                                        exchange -> exchange.emit(StreamEvent.of("lamp", "on")));
                            }
                        };
                    }
                }
                """);
        return sources;
    }
}
