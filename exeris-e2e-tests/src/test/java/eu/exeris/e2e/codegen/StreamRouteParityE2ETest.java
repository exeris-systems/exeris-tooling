package eu.exeris.e2e.codegen;

import eu.exeris.sdk.sourcemodel.ast.ActionMetadata;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import eu.exeris.tooling.codegen.java.kernel.KernelApplicationGenerator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One entity's SSE stream routes, read out of the generated application and held against the
 * route contract in {@code contract/stream-routes.json}.
 *
 * <p>The generated TypeScript stream clients open the contract's {@code routes};
 * {@code stream-route-parity.spec.ts} in {@code exeris-codegen-ts} holds them against the same
 * file, which is the only place the two builds meet. The application serves {@code routes} and
 * {@code withoutClient} together: a route under {@code withoutClient} has no generated client yet.
 * A streaming action is served as a stream only, so this test also proves its path has no
 * respond-once registration — the generated TypeScript service calls none.
 */
@Tag("e2e")
@Tag("codegen")
@DisplayName("Stream route parity: generated streamRoute(...) registrations == contract")
class StreamRouteParityE2ETest {

    private static final String DOMAIN_PACKAGE = "eu.exeris.e2e.parity.domain";
    private static final String BASE = "/orders";

    /** {@code edge.streamRoute(HttpMethod.GET, "/orders/stream", lazyStream(...))}. */
    private static final Pattern STREAM_ROUTE = Pattern.compile(
            "\\.streamRoute\\(\\s*HttpMethod\\.(\\w+),\\s*\"([^\"]+)\"");

    /** A respond-once registration: {@code .route(HttpMethod.POST, "/orders/{id}/actions/x", ...)}. */
    private static final Pattern ROUTE = Pattern.compile("\\.route\\(\\s*HttpMethod\\.(\\w+),\\s*\"([^\"]+)\"");

    record Route(String operation, String method, String path) {}

    record Contract(String description, List<Route> routes, List<Route> withoutClient) {}

    private static Contract contract;
    private static String application;

    @BeforeAll
    static void load() throws IOException {
        try (InputStream in = StreamRouteParityE2ETest.class.getResourceAsStream("/contract/stream-routes.json")) {
            assertThat(in).as("contract/stream-routes.json on the test classpath").isNotNull();
            contract = JsonMapper.builder().build().readValue(in, Contract.class);
        }
        DomainMetadata metadata = DomainMetadata.builder("Order", DOMAIN_PACKAGE)
                .path(BASE)
                .realTimeApi(true)
                .actions(List.of(
                        ActionMetadata.builder("cancel").methodName("cancel").build(),
                        ActionMetadata.builder("trackShipment").methodName("trackShipment")
                                .streaming(true).build()))
                .build();
        application = new KernelApplicationGenerator()
                .generateAll(List.of(metadata), DOMAIN_PACKAGE.replace(".domain", ""))
                .stream()
                .filter(file -> "java".equals(file.extension()))
                .map(GeneratedFile::content)
                .collect(Collectors.joining("\n"));
    }

    @Test
    @DisplayName("the generated application registers exactly the contract's stream routes")
    void applicationServesTheContract() {
        Matcher m = STREAM_ROUTE.matcher(application);
        List<String> served = new ArrayList<>();
        while (m.find()) {
            served.add(m.group(1) + " " + template(m.group(2)));
        }
        List<String> expected = new ArrayList<>();
        contract.routes().forEach(r -> expected.add(r.method() + " " + r.path()));
        contract.withoutClient().forEach(r -> expected.add(r.method() + " " + r.path()));

        assertThat(served).as("streamRoute(...) registrations found in the emitted application")
                .containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    @DisplayName("the spectate route is served, and no generated client opens it yet (wave S4)")
    void spectateRouteHasNoClientYet() {
        assertThat(contract.withoutClient()).extracting(Route::operation).containsExactly("spectate");
        assertThat(contract.routes()).extracting(Route::operation).doesNotContain("spectate");
        assertThat(application).contains("routerBuilder.streamRoute(HttpMethod.GET, \"/orders/{id}/stream\", "
                + "orderSpectateStreamHandler);");
    }

    @Test
    @DisplayName("a streaming action has no respond-once route")
    void streamingActionIsStreamOnly() {
        Matcher m = ROUTE.matcher(application);
        List<String> respondOnce = new ArrayList<>();
        while (m.find()) {
            respondOnce.add(m.group(1) + " " + m.group(2));
        }
        assertThat(respondOnce).contains("POST /orders/{id}/actions/cancel")
                .doesNotContain("POST /orders/{id}/actions/track-shipment");
    }

    /** {@code /orders/{id}/actions/track-shipment} → {@code {base}/{id}/actions/{action}}. */
    private static String template(String path) {
        assertThat(path).startsWith(BASE);
        return "{base}" + path.substring(BASE.length()).replaceFirst("/actions/[a-z0-9-]+$", "/actions/{action}");
    }
}
