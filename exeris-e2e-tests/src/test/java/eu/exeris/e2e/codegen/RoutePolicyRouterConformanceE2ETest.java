package eu.exeris.e2e.codegen;

import eu.exeris.e2e.codegen.RoutePolicyFixture.RegisteredRoute;
import eu.exeris.kernel.core.http.routing.HttpRouter;
import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpRoutePolicy;
import eu.exeris.kernel.spi.http.HttpStreamHandler;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.http.RouteRequirement;
import eu.exeris.kernel.spi.http.StreamMatch;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The generated route policy answers, for any request, the requirement of the route the kernel
 * router dispatches that request to (ADR-105 obligation 4).
 *
 * <p>The kernel's own {@link HttpRouter} is built from the routes the generated
 * {@code RuntimeLifecycle} registers, each handler recording its route. For every path in the
 * corpus the router's choice is resolved as the kernel resolves it: a stream route through
 * {@link HttpRouter#resolveStream}, otherwise a respond-once route through
 * {@link HttpRouter#handle}, the policy then asked with {@link HttpRouter#routeMethod}. The
 * expected answer is the policy answer for the route reached, taken from the policy itself on
 * that route's own template, so the test holds the policy's matching to the router's and leaves
 * the requirement of each route to {@code RoutePolicyContractE2ETest}. Where the router reaches no
 * generated route the policy must abstain.
 *
 * <p>The corpus is every route at an id, plus a trailing slash, a doubled and an empty segment, a
 * query component, {@code HEAD} for every {@code GET}, ids that are another route's literal
 * segment ({@code stream}, {@code catalog}, {@code blobs}), and paths no route serves.
 */
@Tag("e2e")
@Tag("codegen")
@DisplayName("Route policy conformance: GeneratedRoutePolicy matches as the kernel HttpRouter routes")
class RoutePolicyRouterConformanceE2ETest {

    private static final String ABSTAIN = "abstain";

    private static HttpRoutePolicy policy;
    private static List<RegisteredRoute> registered;
    private static HttpRouter router;
    private static final Map<Object, RegisteredRoute> HANDLERS = new IdentityHashMap<>();
    private static final AtomicReference<RegisteredRoute> REACHED = new AtomicReference<>();

    @BeforeAll
    static void build(@TempDir Path workDir) throws Exception {
        var files = RoutePolicyFixture.generate();
        registered = RoutePolicyFixture.registeredRoutes(RoutePolicyFixture.content(files, "RuntimeLifecycle"));
        assertThat(registered).as("routes RuntimeLifecycle registers").isNotEmpty();
        policy = RoutePolicyFixture.compilePolicy(
                RoutePolicyFixture.content(files, RoutePolicyFixture.POLICY_TYPE), workDir);

        HttpRouter.Builder builder = HttpRouter.builder();
        for (RegisteredRoute route : registered) {
            HttpMethod method = RoutePolicyFixture.method(route.method());
            if (route.stream()) {
                HttpStreamHandler handler = exchange -> REACHED.set(route);
                HANDLERS.put(handler, route);
                builder.streamRoute(method, route.template(), handler);
            } else {
                builder.route(method, route.template(), exchange -> REACHED.set(route));
            }
        }
        router = builder.build();
    }

    @Test
    @DisplayName("the policy answers the requirement of the route the router reaches, and abstains where it reaches none")
    void policyFollowsTheRouter() {
        List<String> mismatches = new ArrayList<>();
        for (Probe probe : corpus()) {
            String expected = expected(probe);
            String actual = RoutePolicyFixture.describe(
                    policy.requirementFor(policyMethod(probe), probe.path()));
            if (!expected.equals(actual)) {
                mismatches.add(probe + ": router reaches " + expected + ", policy answers " + actual);
            }
        }
        assertThat(mismatches).isEmpty();
    }

    @Test
    @DisplayName("the corpus reaches every generated route and at least one path no route serves")
    void corpusCoversTheTable() {
        Set<RegisteredRoute> reached = new LinkedHashSet<>();
        boolean unserved = false;
        for (Probe probe : corpus()) {
            RegisteredRoute route = reach(probe);
            if (route == null) {
                unserved = true;
            } else {
                reached.add(route);
            }
        }
        assertThat(reached).containsExactlyInAnyOrderElementsOf(registered);
        assertThat(unserved).isTrue();
    }

    private record Probe(HttpMethod method, String path) {
        @Override
        public String toString() {
            return method + " " + path;
        }
    }

    private static List<Probe> corpus() {
        Set<Probe> probes = new LinkedHashSet<>();
        for (RegisteredRoute route : registered) {
            HttpMethod method = RoutePolicyFixture.method(route.method());
            for (String id : List.of("42", "stream", "catalog", "blobs", "")) {
                String path = route.template().replace("{id}", id);
                for (String variant : List.of(path, path + "/", path.replaceFirst("/", "//"), path + "?q=1&x=/a")) {
                    probes.add(new Probe(method, variant));
                    if (method == HttpMethod.GET) {
                        probes.add(new Probe(HttpMethod.HEAD, variant));
                    }
                }
            }
        }
        for (String path : List.of("/", "/nothing", "/orders/42/actions/unknown", "/orders/42/stream/extra",
                "/tags/42/actions/merge/x", "/orders/42/blobs/unknown", "/tags/42/blobs/receipt")) {
            for (HttpMethod method : List.of(HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH)) {
                probes.add(new Probe(method, path));
            }
        }
        return List.copyOf(probes);
    }

    /** The policy's own answer on the template of the route the router reaches, or an abstention. */
    private static String expected(Probe probe) {
        RegisteredRoute route = reach(probe);
        if (route == null) {
            return ABSTAIN;
        }
        String own = route.template().replace("{id}", "42");
        RouteRequirement requirement = policy.requirementFor(RoutePolicyFixture.method(route.method()), own);
        return RoutePolicyFixture.describe(requirement);
    }

    /** The method the kernel asks the policy with: the router's route method for a respond-once request. */
    private static HttpMethod policyMethod(Probe probe) {
        return isStream(probe) ? probe.method() : router.routeMethod(probe.method(), probe.path());
    }

    private static boolean isStream(Probe probe) {
        return router.resolveStream(probe.method(), probe.path()) != null;
    }

    /** The generated route the router dispatches {@code probe} to, or {@code null}. */
    private static RegisteredRoute reach(Probe probe) {
        StreamMatch stream = router.resolveStream(probe.method(), probe.path());
        if (stream != null) {
            return HANDLERS.get(stream.handler());
        }
        REACHED.set(null);
        router.handle(exchange(probe));
        return REACHED.get();
    }

    private static HttpExchange exchange(Probe probe) {
        HttpRequest request = HttpRequest.noBody(probe.method(), probe.path(), HttpVersion.HTTP_1_1, List.of());
        return new HttpExchange() {
            @Override
            public HttpRequest request() {
                return request;
            }

            @Override
            public void respond(HttpResponse response) {
                // A 404 from the router: no generated route was reached.
            }
        };
    }
}
