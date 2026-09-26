package eu.exeris.e2e.boot;

import eu.exeris.e2e.codegen.compile.ProcessorCompiler;
import eu.exeris.kernel.community.testkit.http.KernelBootstrapHttpEngineFixture;
import eu.exeris.kernel.core.http.routing.HttpRouter;
import eu.exeris.kernel.spi.http.HttpHandler;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.tooling.codegen.java.CodegenPipeline;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T23 slice B1 — the emitted application's stream routes resolve on a <b>real</b> kernel boot.
 *
 * <p>T23 was closed once (0.6.0) on the strength of assertions about emitted <em>text</em>, and
 * reopened when the dog-food found that the object the kernel actually holds — the forwarding
 * lambda {@code Application.run()} bound — is never an {@code HttpRouter}, so the stream dispatcher
 * never resolved a single generated stream route. The guard that was missing, in ROADMAP's own
 * words, is "a real boot of the emitted {@code Application} with a stream route". This is it:
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
 * <em>before</em> the handler runs. Asserting only the head is how the dog-food's own test passed
 * over a stream handler that would have thrown on its first line — it read
 * {@code KernelProviders.eventEngine()} on the stream thread, where the kernel binds no engine. So
 * the boot test here publishes an event and reads the frame it produces.
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

    private static URLClassLoader appLoader;

    @BeforeAll
    static void generateCompileAndLoad() throws IOException {
        Path entityClasses = workspace.resolve("target/classes");
        Path generated = workspace.resolve("src/main/generated/java");
        Path harness = workspace.resolve("src/test/java");
        Path appClasses = workspace.resolve("target/app-classes");

        ProcessorCompiler.compile(workspace.resolve("src/main/java"), entityClasses, null, domainSources());
        CodegenPipeline.createDefault().run(entityClasses.resolve("exeris-metadata"), generated, BASE_PACKAGE);

        List<String> files = new ArrayList<>(javaSourcesUnder(generated));
        for (Map.Entry<String, String> source : harnessSources().entrySet()) {
            Path file = harness.resolve(source.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue());
            files.add(file.toString());
        }
        compile(files, appClasses, entityClasses);

        appLoader = new URLClassLoader(
                new URL[]{appClasses.toUri().toURL(), entityClasses.toUri().toURL()},
                GeneratedAppBootE2ETest.class.getClassLoader());
    }

    @AfterAll
    static void closeLoader() throws IOException {
        if (appLoader != null) {
            appLoader.close();
        }
    }

    @Test
    @DisplayName("edgeRouter(...) on a real kernel: an HttpRouter that resolves the stream, 503 and "
            + "close-on-open while composing, then respond-once traffic reaches the handler slot")
    void edgeRouterHoldsItsContractOnARealKernel() throws Exception {
        AtomicReference<HttpHandler> handlerSlot = new AtomicReference<>();
        AtomicReference<Object> componentsSlot = new AtomicReference<>();
        HttpHandler edge = edgeRouter(handlerSlot, componentsSlot);

        // The property T23 turns on: the kernel's stream dispatcher resolves a stream only through
        // `handler instanceof HttpRouter`. The forwarding lambda this replaces never was one.
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
    @DisplayName("the emitted Application boots, composes inside the boot callback, and a stream "
            + "opened through its edge router receives a frame published from a request thread")
    void emittedApplicationServesAStreamFrame() throws Exception {
        try (BootedApplication app = BootedApplication.start(appLoader, BASE_PACKAGE + ".LiveApplication")) {
            int port = app.port();

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
                        .contains("event: BeaconPinged");
                System.out.println("[T23 B1] SSE frame received over the generated edge router: " + frame);
            }

            // The live view learns that its peer left only when it next emits — the stream SPI has
            // no liveness signal — and the kernel's shutdown drain waits for a busy stream up to 60s.
            // A few more events let the handler find out and unwind before the application stops.
            for (int i = 0; i < 3; i++) {
                RawHttp.request(port, "POST", "/ping");
                Thread.sleep(100);
            }
        }
    }

    // ------------------------------------------------------------------ harness

    private static HttpHandler edgeRouter(AtomicReference<HttpHandler> handlerSlot,
                                          AtomicReference<Object> componentsSlot) throws Exception {
        Class<?> lifecycle = appLoader.loadClass(BASE_PACKAGE + ".RuntimeLifecycle");
        return (HttpHandler) lifecycle.getMethod("edgeRouter", AtomicReference.class, AtomicReference.class)
                .invoke(null, handlerSlot, componentsSlot);
    }

    /**
     * Compiles the emitted tree and the harness against the test classpath plus the entity the
     * processor compiled. The ADR-058 contract classpath is {@code GeneratedTestsE2ETest}'s job;
     * this test needs the kernel's runtime providers on the classpath anyway, to boot.
     */
    private static void compile(List<String> files, Path outputDir, Path entityClasses) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("a JDK (not a JRE) is required").isNotNull();
        Files.createDirectories(outputDir);
        List<String> args = new ArrayList<>(List.of(
                "-d", outputDir.toString(),
                "-classpath", System.getProperty("java.class.path") + File.pathSeparator + entityClasses,
                "--release", "25",
                "-nowarn"));
        args.addAll(files);
        ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
        int rc = compiler.run(null, null, diagnostics, args.toArray(String[]::new));
        assertThat(rc).as("emitted tree + harness must compile:%n%s", diagnostics).isZero();
    }

    private static List<String> javaSourcesUnder(Path root) throws IOException {
        try (Stream<Path> tree = Files.walk(root)) {
            return tree.filter(p -> p.toString().endsWith(".java")).map(Path::toString).sorted().toList();
        }
    }

    /**
     * One live entity: {@code realTimeApi} (so a stream route is emitted) with one
     * {@code @DomainEvent} carrying a payload (so the stream handler is the EV1 producer, whose
     * engine was the latent defect, and the publisher encodes a payload).
     */
    private static Map<String, String> domainSources() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("eu/exeris/e2e/live/domain/Beacon.java",
                """
                package eu.exeris.e2e.live.domain;

                import eu.exeris.sdk.annotation.DomainEvent;
                import eu.exeris.sdk.annotation.ExerisDomain;
                import eu.exeris.sdk.annotation.Field;

                import java.util.UUID;

                @ExerisDomain(module = "live", path = "/beacons", realTimeApi = true)
                // MANUAL: published by consumer code (the /ping route below), not by a CRUD handler,
                // so the fixture needs no database table to produce an event.
                @DomainEvent(name = "BeaconPinged", topic = "live.beacons", trigger = DomainEvent.Trigger.MANUAL,
                        includeFields = {"label"})
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

                    // No graph, no crypto: the fixture needs http and the event bus. events pulls
                    // persistence and memory by dependency closure.
                    @Override
                    protected String subsystems() {
                        return "http,events";
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
                import eu.exeris.kernel.core.http.routing.HttpRouter;
                import eu.exeris.kernel.spi.http.HttpMethod;
                import eu.exeris.kernel.spi.http.HttpStatus;
                import eu.exeris.kernel.spi.persistence.TransactionalExecutor;

                import java.util.UUID;

                public class LiveComponents extends RuntimeComponents {

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
                }
                """);
        return sources;
    }
}
