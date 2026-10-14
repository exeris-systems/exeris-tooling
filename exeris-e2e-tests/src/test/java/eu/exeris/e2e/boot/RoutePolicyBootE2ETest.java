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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The route policy the emitted {@code Application} binds, on a <b>real</b> kernel boot.
 *
 * <p>Emitted text cannot show what a bound policy answers: the answer is the kernel's, taken per
 * request. So the emitted application is booted twice, once as generated
 * and once with a subclass that overrides {@code applicationPolicy()}:
 *
 * <pre>
 *   @ExerisDomain source
 *        → javac + ExerisDomainProcessor      (real metadata)
 *        → CodegenPipeline.run                (Application, GeneratedRoutePolicy, …)
 *        → javac over the emitted tree + two consumer-style Application subclasses
 *        → KernelBootstrap                    (http + persistence on in-memory H2; no security)
 *        → a real socket
 * </pre>
 *
 * <p>With no override, a handler runs with no principal, an unknown path answers 404, and no route
 * is refused. Each overriding application declares a requirement through one of the three hooks
 * ({@code applicationPolicy()}, {@code unmatchedRoutes()}, {@code routePolicy()}), and the routes it
 * names are refused with the status of that requirement, whatever the route's origin: a hand-written
 * one, a generated CRUD one, or a generated stream one. A refused stream route answers its refusal
 * as the response head and never opens the stream.
 */
@Tag("e2e")
@Tag("boot")
@DisplayName("ADR-105 — the emitted Application binds a route policy that declines everywhere")
class RoutePolicyBootE2ETest {

    private static final String BASE_PACKAGE = "eu.exeris.e2e.policy";
    private static final String NOTE_ID = "00000000-0000-4000-8000-00000000000a";

    @TempDir
    static Path workspace;

    private static GeneratedTree app;

    @BeforeAll
    static void generateCompileAndLoad() throws IOException {
        app = GeneratedTree.build(workspace, BASE_PACKAGE, domainSources(), harnessSources());
    }

    @AfterAll
    static void closeLoader() throws IOException {
        if (app != null) {
            app.close();
        }
    }

    @Test
    @DisplayName("as generated, every route answers as it did without a policy: a handler runs, "
            + "carrying no principal, and an unknown path is a 404 rather than a refusal")
    void generatedApplicationAnswersEveryRoute() throws Exception {
        try (BootedApplication booted = BootedApplication.start(app.loader(), BASE_PACKAGE + ".PlainApplication")) {
            int port = booted.port();

            assertThat(RawHttp.request(port, "GET", "/probe")).startsWith("HTTP/1.1 200");
            assertThat(RawHttp.request(port, "GET", "/reports")).startsWith("HTTP/1.1 200");
            assertThat(RawHttp.request(port, "GET", "/principal")).startsWith("HTTP/1.1 204");
            // A generated handler: its path-id guard answers before any repository is touched.
            assertThat(RawHttp.request(port, "GET", "/notes/not-a-uuid")).startsWith("HTTP/1.1 400");
            assertThat(RawHttp.request(port, "GET", "/nowhere")).startsWith("HTTP/1.1 404");
        }
    }

    @Test
    @DisplayName("an applicationPolicy() override declares a requirement and the route it names is "
            + "refused; a route it abstains on, and an unmatched one, still answer")
    void applicationPolicyCanDenyARoute() throws Exception {
        try (BootedApplication booted = BootedApplication.start(app.loader(), BASE_PACKAGE + ".GuardedApplication")) {
            int port = booted.port();

            assertThat(RawHttp.request(port, "GET", "/reports")).startsWith("HTTP/1.1 401");
            assertThat(RawHttp.request(port, "GET", "/probe")).startsWith("HTTP/1.1 200");
            assertThat(RawHttp.request(port, "GET", "/notes/not-a-uuid")).startsWith("HTTP/1.1 400");
            assertThat(RawHttp.request(port, "GET", "/nowhere")).startsWith("HTTP/1.1 404");
        }
    }

    @Test
    @DisplayName("an applicationPolicy() requirement refuses the generated CRUD routes it names, by "
            + "path, and the routes it abstains on still answer")
    void applicationPolicyRefusesGeneratedCrudRoutes() throws Exception {
        try (BootedApplication booted = BootedApplication.start(app.loader(), BASE_PACKAGE + ".CrudGuardedApplication")) {
            int port = booted.port();

            assertThat(RawHttp.request(port, "GET", "/notes")).startsWith("HTTP/1.1 401");
            assertThat(RawHttp.request(port, "POST", "/notes", "{\"title\":\"t\"}")).startsWith("HTTP/1.1 401");
            assertThat(RawHttp.request(port, "GET", "/notes/" + NOTE_ID)).startsWith("HTTP/1.1 401");
            assertThat(RawHttp.request(port, "PUT", "/notes/" + NOTE_ID, "{\"title\":\"t\"}"))
                    .startsWith("HTTP/1.1 401");
            assertThat(RawHttp.request(port, "DELETE", "/notes/" + NOTE_ID)).startsWith("HTTP/1.1 401");
            // The requirement names no other path: a hand-written route and the generated routes of
            // another entity still reach their handlers.
            assertThat(RawHttp.request(port, "GET", "/probe")).startsWith("HTTP/1.1 200");
            assertThat(RawHttp.request(port, "GET", "/feeds/not-a-uuid")).startsWith("HTTP/1.1 400");
        }
    }

    @Test
    @DisplayName("an applicationPolicy() requirement refuses a generated stream route before the "
            + "200 head: the response is the refusal, never an event stream")
    void applicationPolicyRefusesGeneratedStreamRoutes() throws Exception {
        try (BootedApplication booted = BootedApplication.start(app.loader(), BASE_PACKAGE + ".StreamGuardedApplication")) {
            int port = booted.port();

            String liveView = RawHttp.request(port, "GET", "/feeds/stream");
            assertThat(liveView).startsWith("HTTP/1.1 401").doesNotContainIgnoringCase("text/event-stream");
            String spectate = RawHttp.request(port, "GET", "/feeds/" + NOTE_ID + "/stream");
            assertThat(spectate).startsWith("HTTP/1.1 401").doesNotContainIgnoringCase("text/event-stream");
            // The by-id read of the same entity is a different route, and is not named.
            assertThat(RawHttp.request(port, "GET", "/feeds/not-a-uuid")).startsWith("HTTP/1.1 400");
        }
    }

    @Test
    @DisplayName("an unmatchedRoutes() requirement refuses every route no policy describes, generated "
            + "or not, while a route the application permits explicitly stays reachable")
    void unmatchedRoutesDeniesByDefault() throws Exception {
        try (BootedApplication booted = BootedApplication.start(app.loader(), BASE_PACKAGE + ".ClosedApplication")) {
            int port = booted.port();

            assertThat(RawHttp.request(port, "GET", "/probe")).startsWith("HTTP/1.1 200");
            assertThat(RawHttp.request(port, "GET", "/reports")).startsWith("HTTP/1.1 401");
            assertThat(RawHttp.request(port, "GET", "/notes/" + NOTE_ID)).startsWith("HTTP/1.1 401");
            assertThat(RawHttp.request(port, "GET", "/nowhere")).startsWith("HTTP/1.1 401");
        }
    }

    @Test
    @DisplayName("a routePolicy() override has the whole decision: the generated policy, "
            + "applicationPolicy() and unmatchedRoutes() are not consulted")
    void routePolicyOverrideReplacesTheFold() throws Exception {
        try (BootedApplication booted = BootedApplication.start(app.loader(), BASE_PACKAGE + ".WholePolicyApplication")) {
            int port = booted.port();

            assertThat(RawHttp.request(port, "GET", "/notes/" + NOTE_ID)).startsWith("HTTP/1.1 401");
            assertThat(RawHttp.request(port, "GET", "/probe")).startsWith("HTTP/1.1 200");
            // applicationPolicy() of this application denies /reports and unmatchedRoutes() denies
            // everything else; the override answers permitAll for both.
            assertThat(RawHttp.request(port, "GET", "/reports")).startsWith("HTTP/1.1 200");
            assertThat(RawHttp.request(port, "GET", "/nowhere")).startsWith("HTTP/1.1 404");
        }
    }

    @Test
    @DisplayName("an exact-path requirement refuses the route whatever the query string and whatever "
            + "the method, HEAD included")
    void exactPathRequirementIgnoresQueryAndMethod() throws Exception {
        try (BootedApplication booted = BootedApplication.start(app.loader(), BASE_PACKAGE + ".ExactPathApplication")) {
            int port = booted.port();

            assertThat(RawHttp.request(port, "GET", "/reports")).startsWith("HTTP/1.1 401");
            assertThat(RawHttp.request(port, "GET", "/reports?x=1")).startsWith("HTTP/1.1 401");
            assertThat(RawHttp.request(port, "HEAD", "/reports")).startsWith("HTTP/1.1 401");
            assertThat(RawHttp.request(port, "GET", "/probe")).startsWith("HTTP/1.1 200");
            assertThat(RawHttp.request(port, "GET", "/probe?x=1")).startsWith("HTTP/1.1 200");
        }
    }

    private static Map<String, String> domainSources() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("eu/exeris/e2e/policy/domain/Note.java",
                """
                package eu.exeris.e2e.policy.domain;

                import eu.exeris.sdk.annotation.ExerisDomain;
                import eu.exeris.sdk.annotation.Field;

                import java.util.UUID;

                @ExerisDomain(module = "notes", path = "/notes")
                public class Note {

                    private UUID id;

                    @Field(label = "Title")
                    private String title;

                    public UUID getId() { return id; }
                    public void setId(UUID id) { this.id = id; }
                    public String getTitle() { return title; }
                    public void setTitle(String title) { this.title = title; }
                }
                """);
        sources.put("eu/exeris/e2e/policy/domain/Feed.java",
                """
                package eu.exeris.e2e.policy.domain;

                import eu.exeris.sdk.annotation.ExerisDomain;
                import eu.exeris.sdk.annotation.Field;

                import java.util.UUID;

                @ExerisDomain(module = "feeds", path = "/feeds", realTimeApi = true)
                public class Feed {

                    private UUID id;

                    @Field(label = "Label")
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
        sources.put("eu/exeris/e2e/policy/PlainApplication.java",
                """
                package eu.exeris.e2e.policy;

                import eu.exeris.kernel.spi.persistence.TransactionalExecutor;

                public class PlainApplication extends Application {

                    @Override
                    protected RuntimeComponents components(TransactionalExecutor transactionalExecutor) {
                        return new PolicyComponents(transactionalExecutor);
                    }
                }
                """);
        sources.put("eu/exeris/e2e/policy/GuardedApplication.java",
                """
                package eu.exeris.e2e.policy;

                import eu.exeris.kernel.spi.http.HttpMethod;
                import eu.exeris.kernel.spi.http.HttpRoutePolicy;
                import eu.exeris.kernel.spi.http.RouteRequirement;
                import eu.exeris.kernel.spi.persistence.TransactionalExecutor;

                public class GuardedApplication extends Application {

                    @Override
                    protected RuntimeComponents components(TransactionalExecutor transactionalExecutor) {
                        return new PolicyComponents(transactionalExecutor);
                    }

                    @Override
                    protected HttpRoutePolicy applicationPolicy() {
                        return (method, path) -> method == HttpMethod.GET && path.equals("/reports")
                                ? RouteRequirement.authenticated()
                                : RouteRequirement.abstain();
                    }
                }
                """);
        sources.put("eu/exeris/e2e/policy/CrudGuardedApplication.java",
                """
                package eu.exeris.e2e.policy;

                import eu.exeris.kernel.spi.http.HttpRoutePolicy;
                import eu.exeris.kernel.spi.http.RouteRequirement;
                import eu.exeris.kernel.spi.persistence.TransactionalExecutor;

                public class CrudGuardedApplication extends Application {

                    @Override
                    protected RuntimeComponents components(TransactionalExecutor transactionalExecutor) {
                        return new PolicyComponents(transactionalExecutor);
                    }

                    @Override
                    protected HttpRoutePolicy applicationPolicy() {
                        return (method, path) -> path.equals("/notes") || path.startsWith("/notes/")
                                ? RouteRequirement.authenticated()
                                : RouteRequirement.abstain();
                    }
                }
                """);
        sources.put("eu/exeris/e2e/policy/StreamGuardedApplication.java",
                """
                package eu.exeris.e2e.policy;

                import eu.exeris.kernel.spi.http.HttpRoutePolicy;
                import eu.exeris.kernel.spi.http.RouteRequirement;
                import eu.exeris.kernel.spi.persistence.TransactionalExecutor;

                public class StreamGuardedApplication extends Application {

                    @Override
                    protected RuntimeComponents components(TransactionalExecutor transactionalExecutor) {
                        return new PolicyComponents(transactionalExecutor);
                    }

                    @Override
                    protected HttpRoutePolicy applicationPolicy() {
                        return (method, path) -> path.startsWith("/feeds/") && path.endsWith("stream")
                                ? RouteRequirement.authenticated()
                                : RouteRequirement.abstain();
                    }
                }
                """);
        sources.put("eu/exeris/e2e/policy/ClosedApplication.java",
                """
                package eu.exeris.e2e.policy;

                import eu.exeris.kernel.spi.http.HttpRoutePolicy;
                import eu.exeris.kernel.spi.http.RouteRequirement;
                import eu.exeris.kernel.spi.persistence.TransactionalExecutor;

                public class ClosedApplication extends Application {

                    @Override
                    protected RuntimeComponents components(TransactionalExecutor transactionalExecutor) {
                        return new PolicyComponents(transactionalExecutor);
                    }

                    @Override
                    protected HttpRoutePolicy applicationPolicy() {
                        return (method, path) -> path.equals("/probe")
                                ? RouteRequirement.permitAll()
                                : RouteRequirement.abstain();
                    }

                    @Override
                    protected RouteRequirement unmatchedRoutes() {
                        return RouteRequirement.authenticated();
                    }
                }
                """);
        sources.put("eu/exeris/e2e/policy/WholePolicyApplication.java",
                """
                package eu.exeris.e2e.policy;

                import eu.exeris.kernel.spi.http.HttpRoutePolicy;
                import eu.exeris.kernel.spi.http.RouteRequirement;
                import eu.exeris.kernel.spi.persistence.TransactionalExecutor;

                public class WholePolicyApplication extends Application {

                    @Override
                    protected RuntimeComponents components(TransactionalExecutor transactionalExecutor) {
                        return new PolicyComponents(transactionalExecutor);
                    }

                    @Override
                    protected HttpRoutePolicy routePolicy() {
                        return (method, path) -> path.startsWith("/notes/")
                                ? RouteRequirement.authenticated()
                                : RouteRequirement.permitAll();
                    }

                    @Override
                    protected HttpRoutePolicy applicationPolicy() {
                        return (method, path) -> path.equals("/reports")
                                ? RouteRequirement.authenticated()
                                : RouteRequirement.abstain();
                    }

                    @Override
                    protected RouteRequirement unmatchedRoutes() {
                        return RouteRequirement.authenticated();
                    }
                }
                """);
        sources.put("eu/exeris/e2e/policy/ExactPathApplication.java",
                """
                package eu.exeris.e2e.policy;

                import eu.exeris.kernel.spi.http.HttpRoutePolicy;
                import eu.exeris.kernel.spi.http.RouteRequirement;
                import eu.exeris.kernel.spi.persistence.TransactionalExecutor;

                public class ExactPathApplication extends Application {

                    @Override
                    protected RuntimeComponents components(TransactionalExecutor transactionalExecutor) {
                        return new PolicyComponents(transactionalExecutor);
                    }

                    @Override
                    protected HttpRoutePolicy applicationPolicy() {
                        return (method, path) -> path.equals("/reports")
                                ? RouteRequirement.authenticated()
                                : RouteRequirement.abstain();
                    }
                }
                """);
        sources.put("eu/exeris/e2e/policy/PolicyComponents.java",
                """
                package eu.exeris.e2e.policy;

                import eu.exeris.kernel.core.http.routing.HttpRouter;
                import eu.exeris.kernel.spi.context.KernelProviders;
                import eu.exeris.kernel.spi.http.HttpMethod;
                import eu.exeris.kernel.spi.http.HttpStatus;
                import eu.exeris.kernel.spi.persistence.TransactionalExecutor;

                public class PolicyComponents extends RuntimeComponents {

                    public PolicyComponents(TransactionalExecutor transactionalExecutor) {
                        super(transactionalExecutor);
                    }

                    @Override
                    public void configureRoutes(HttpRouter.Builder routes) {
                        routes.route(HttpMethod.GET, "/probe", exchange -> exchange.respond(HttpStatus.OK));
                        routes.route(HttpMethod.GET, "/reports", exchange -> exchange.respond(HttpStatus.OK));
                        routes.route(HttpMethod.GET, "/principal", exchange -> exchange.respond(
                                KernelProviders.PRINCIPAL_CONTEXT.isBound()
                                        ? HttpStatus.OK : HttpStatus.NO_CONTENT));
                    }
                }
                """);
        return sources;
    }
}
