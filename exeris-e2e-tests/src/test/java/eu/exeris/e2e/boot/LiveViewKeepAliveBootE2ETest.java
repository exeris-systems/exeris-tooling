package eu.exeris.e2e.boot;

import eu.exeris.e2e.codegen.compile.GeneratedTree;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The entity-level live view keeps a quiet stream alive and notices a disconnect on a <b>real</b>
 * kernel boot (ADR-044 Amendment 2, obligation 4).
 *
 * <p>The entity publishes no event during the test, so the only frames the live view can write are
 * its {@code keep-alive} frames. A client that disconnects is noticed by the failed write of the
 * next one, which ends the stream and releases its subscriptions and thread; kernel shutdown then
 * completes without waiting out the kernel's 60-second drain.
 */
@Tag("e2e")
@Tag("boot")
@DisplayName("ADR-044 Amendment 2 — GET {base}/stream writes keep-alive frames and ends on a disconnect")
class LiveViewKeepAliveBootE2ETest {

    private static final String BASE_PACKAGE = "eu.exeris.e2e.gauges";
    /** The generated keep-alive interval is 15 seconds; two intervals plus slack. */
    private static final Duration TWO_INTERVALS = Duration.ofSeconds(40);
    /** A disconnect is noticed by the second keep-alive after it; the kernel's drain deadline is 60. */
    private static final Duration PROMPT_SHUTDOWN = Duration.ofSeconds(45);

    @TempDir
    static Path workspace;

    @Test
    @DisplayName("a quiet live view sends a keep-alive frame, and after the client disconnects "
            + "kernel shutdown does not wait out the drain")
    void quietLiveViewKeepsAliveAndEndsOnDisconnect() throws Exception {
        GeneratedTree app = GeneratedTree.build(workspace, BASE_PACKAGE, domainSources(), harnessSources());
        BootedApplication booted = BootedApplication.start(app.loader(), BASE_PACKAGE + ".GaugeApplication");
        try {
            try (SseConnection live = SseConnection.open(booted.port(), "/gauges/stream")) {
                assertThat(live.head()).startsWith("HTTP/1.1 200").containsIgnoringCase("text/event-stream");
                List<String> frame = live.awaitFrame(() -> { }, TWO_INTERVALS);
                assertThat(frame).first().isEqualTo("event: keep-alive");
            }
            long stopped = System.nanoTime();
            booted.close();
            assertThat(Duration.ofNanos(System.nanoTime() - stopped))
                    .as("kernel shutdown after the live-view client disconnected")
                    .isLessThan(PROMPT_SHUTDOWN);
        } finally {
            app.close();
        }
    }

    private static Map<String, String> domainSources() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("eu/exeris/e2e/gauges/domain/Gauge.java",
                """
                package eu.exeris.e2e.gauges.domain;

                import eu.exeris.sdk.annotation.DomainEvent;
                import eu.exeris.sdk.annotation.DomainEvent.Trigger;
                import eu.exeris.sdk.annotation.ExerisDomain;
                import eu.exeris.sdk.annotation.Field;

                import java.util.UUID;

                @ExerisDomain(module = "gauges", path = "/gauges", realTimeApi = true)
                @DomainEvent(name = "GaugeRead", trigger = Trigger.UPDATE, topic = "gauges.read",
                        includeFields = {"label"})
                public class Gauge {

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

    private static Map<String, String> harnessSources() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("eu/exeris/e2e/gauges/GaugeApplication.java",
                """
                package eu.exeris.e2e.gauges;

                import eu.exeris.kernel.spi.persistence.TransactionalExecutor;

                public class GaugeApplication extends Application {

                    @Override
                    protected RuntimeComponents components(TransactionalExecutor transactionalExecutor) {
                        return new GaugeComponents(transactionalExecutor);
                    }
                }
                """);
        sources.put("eu/exeris/e2e/gauges/GaugeComponents.java",
                """
                package eu.exeris.e2e.gauges;

                import eu.exeris.kernel.core.http.routing.HttpRouter;
                import eu.exeris.kernel.spi.http.HttpMethod;
                import eu.exeris.kernel.spi.http.HttpStatus;
                import eu.exeris.kernel.spi.persistence.TransactionalExecutor;

                public class GaugeComponents extends RuntimeComponents {

                    public GaugeComponents(TransactionalExecutor transactionalExecutor) {
                        super(transactionalExecutor);
                    }

                    @Override
                    public void configureRoutes(HttpRouter.Builder routes) {
                        routes.route(HttpMethod.GET, "/probe", exchange -> exchange.respond(HttpStatus.OK));
                    }
                }
                """);
        return sources;
    }
}
