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
 * <p>Emitted text cannot show that binding a policy leaves the routes as they were: the answer is
 * the kernel's, taken per request. So the emitted application is booted twice, once as generated
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
 * <p>The generated application answers every route as it did before a policy was bound: a handler
 * runs, and sees no principal. The overriding application declares a requirement for one route, and
 * that route is refused, while every other route still answers.
 */
@Tag("e2e")
@Tag("boot")
@DisplayName("ADR-105 — the emitted Application binds a route policy that declines everywhere")
class RoutePolicyBootE2ETest {

    private static final String BASE_PACKAGE = "eu.exeris.e2e.policy";

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
