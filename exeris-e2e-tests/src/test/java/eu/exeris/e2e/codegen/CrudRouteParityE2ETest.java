package eu.exeris.e2e.codegen;

import eu.exeris.kernel.core.http.client.KernelWebClient;
import eu.exeris.sdk.sourcemodel.ast.ActionMetadata;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import eu.exeris.tooling.codegen.java.kernel.KernelApplicationGenerator;
import eu.exeris.tooling.codegen.java.kernel.KernelClientGenerator;
import eu.exeris.tooling.codegen.java.openapi.OpenApiPathsBuilder;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One entity's CRUD surface, read out of every Java artefact that states it, and held against the
 * route contract in {@code contract/crud-routes.json}.
 *
 * <p><b>Why.</b> The kernel router matches methods exactly, so a generated client call on a verb
 * the generated router does not serve never reaches a generated server — and a test that asserts
 * only what its own emitter wrote cannot see that. This test compares one artefact's verbs with
 * another's, for the three Java-side artefacts (router, OpenAPI, client), and
 * {@code crud-route-parity.spec.ts} in {@code exeris-codegen-ts} does the same for the TypeScript
 * service against the same contract file — the only way to link two builds that never run
 * together.
 *
 * <p><b>The one exemption, and what retires it.</b> The Java client sends {@code PATCH}:
 * {@code KernelWebClient} has no {@code put} verb, so there is no call it can make
 * that the router serves. {@link #javaClientCallsOnlyServedRoutes()} asserts that fact as well as
 * the exemption, so the day the facade gains {@code put(...)} this test fails and says to switch.
 */
@Tag("e2e")
@Tag("codegen")
@DisplayName("CRUD route parity: router == OpenAPI == generated clients")
class CrudRouteParityE2ETest {

    private static final String DOMAIN_PACKAGE = "eu.exeris.e2e.parity.domain";
    private static final String ENTITY = "Order";
    private static final String BASE = "/orders";

    /** {@code routerBuilder.route(HttpMethod.PUT, "/orders/{id}", orderHandler::handleUpdate)} —
     *  tolerant of the receiver and handler expression, so a rename there does not blind it. */
    private static final Pattern ROUTER_ROUTE = Pattern.compile(
            "\\.route\\(\\s*HttpMethod\\.(\\w+),\\s*\"([^\"]+)\",\\s*[\\w.()]+::(\\w+)\\)");

    /** One emitted client method: its name, then its body up to the member-level close brace
     *  (KernelScaffold renders with a four-space indent). */
    private static final Pattern CLIENT_METHOD = Pattern.compile(
            "\\n    public [\\w<>\\[\\], ]+ (\\w+)\\([^)]*\\) \\{(.*?)\\n    }", Pattern.DOTALL);

    /** The kernel facade call inside a client method, and the path expression it passes. */
    private static final Pattern CLIENT_CALL = Pattern.compile("client\\.(\\w+)\\(([^,]+),");

    private static Contract contract;
    private static DomainMetadata metadata;

    record Route(String operation, String method, String path) {
        Endpoint endpoint() {
            return new Endpoint(method, path);
        }
    }

    record Contract(String description, List<Route> routes) {
        Map<String, Set<Endpoint>> byOperation() {
            return group(routes);
        }
    }

    record Endpoint(String method, String path) implements Comparable<Endpoint> {
        @Override
        public int compareTo(Endpoint other) {
            return (method + " " + path).compareTo(other.method + " " + other.path);
        }
    }

    @BeforeAll
    static void load() throws IOException {
        try (InputStream in = CrudRouteParityE2ETest.class.getResourceAsStream("/contract/crud-routes.json")) {
            assertThat(in).as("contract/crud-routes.json on the test classpath").isNotNull();
            contract = JsonMapper.builder().build().readValue(in, Contract.class);
        }
        metadata = DomainMetadata.builder(ENTITY, DOMAIN_PACKAGE)
                .path(BASE)
                .softDelete(true)
                .actions(List.of(
                        ActionMetadata.builder("cancel").methodName("cancel").build(),
                        ActionMetadata.builder("markUrgent").methodName("flagUrgent").build()))
                .build();
    }

    @Test
    @DisplayName("the generated router registers exactly the contract's routes")
    void routerServesTheContract() {
        String application = new KernelApplicationGenerator()
                .generateAll(List.of(metadata), DOMAIN_PACKAGE.replace(".domain", ""))
                .stream()
                .filter(file -> "java".equals(file.extension()))
                .map(GeneratedFile::content)
                .collect(Collectors.joining("\n"));

        Matcher m = ROUTER_ROUTE.matcher(application);
        List<Route> served = new java.util.ArrayList<>();
        while (m.find()) {
            served.add(new Route(routerOperation(m.group(3)), m.group(1), template(m.group(2))));
        }

        assertThat(served).as("route(...) registrations found in the emitted Application").isNotEmpty();
        assertThat(group(served)).isEqualTo(contract.byOperation());
    }

    @Test
    @DisplayName("the OpenAPI document publishes exactly the contract's routes")
    void openApiPublishesTheContract() {
        List<Route> published = new java.util.ArrayList<>();
        OpenApiPathsBuilder.buildPaths(metadata).forEach((path, item) -> {
            for (Map.Entry<PathItem.HttpMethod, Operation> op : item.readOperationsMap().entrySet()) {
                published.add(new Route(openApiOperation(op.getValue().getOperationId()),
                        op.getKey().name(), template(path)));
            }
        });

        assertThat(group(published)).isEqualTo(contract.byOperation());
    }

    @Test
    @DisplayName("the generated Java client calls only routes the router serves (update exempt until KernelWebClient has put)")
    void javaClientCallsOnlyServedRoutes() {
        String client = new KernelClientGenerator().generate(metadata).content();
        Map<String, Set<Endpoint>> expected = contract.byOperation();

        Matcher method = CLIENT_METHOD.matcher(client);
        int calls = 0;
        while (method.find()) {
            Matcher call = CLIENT_CALL.matcher(method.group(2));
            if (!call.find()) {
                continue;
            }
            calls++;
            String operation = clientOperation(method.group(1));
            Endpoint sent = new Endpoint(call.group(1).toUpperCase(java.util.Locale.ROOT),
                    clientTemplate(call.group(2).trim(), client));

            if ("update".equals(operation)) {
                boolean facadeHasPut = Arrays.stream(KernelWebClient.class.getMethods())
                        .anyMatch(candidate -> candidate.getName().equals("put"));
                assertThat(facadeHasPut)
                        .as("KernelWebClient now has put(...). Switch KernelClientGenerator.buildUpdate "
                                + "to client.put, drop its not-served Javadoc, and delete this exemption")
                        .isFalse();
                assertThat(sent).as("the known PATCH/PUT gap, and nothing else")
                        .isEqualTo(new Endpoint("PATCH", "{base}/{id}"));
                continue;
            }
            assertThat(expected.get(operation))
                    .as("client method %s sends %s", method.group(1), sent)
                    .contains(sent);
        }
        assertThat(calls).as("client calls found in the emitted client").isEqualTo(6);
    }

    // ---------------------------------------------------------------------------------------

    private static Map<String, Set<Endpoint>> group(List<Route> routes) {
        Map<String, Set<Endpoint>> grouped = new TreeMap<>();
        for (Route route : routes) {
            grouped.computeIfAbsent(route.operation(), k -> new TreeSet<>()).add(route.endpoint());
        }
        return grouped;
    }

    /** {@code /orders/{id}/actions/mark-urgent} → {@code {base}/{id}/actions/{action}}. */
    private static String template(String path) {
        assertThat(path).startsWith(BASE);
        return "{base}" + path.substring(BASE.length()).replaceFirst("/actions/[a-z0-9-]+$", "/actions/{action}");
    }

    private static String routerOperation(String handlerMethod) {
        return switch (handlerMethod) {
            case "handleGetAll" -> "list";
            case "handleGetById" -> "get";
            case "handleCreate" -> "create";
            case "handleUpdate" -> "update";
            case "handleDelete" -> "delete";
            default -> "action";
        };
    }

    private static String openApiOperation(String operationId) {
        if (operationId.equals("list" + ENTITY)) return "list";
        if (operationId.equals("get" + ENTITY + "ById")) return "get";
        if (operationId.equals("create" + ENTITY)) return "create";
        if (operationId.equals("update" + ENTITY)) return "update";
        if (operationId.equals("delete" + ENTITY)) return "delete";
        return "action";
    }

    private static String clientOperation(String clientMethod) {
        return switch (clientMethod) {
            case "findAll" -> "list";
            case "findById" -> "get";
            case "create" -> "create";
            case "update" -> "update";
            case "delete" -> "delete";
            default -> throw new AssertionError("unmapped client method " + clientMethod);
        };
    }

    /** The client's path expressions: {@code BASE_PATH}, {@code BASE_PATH + "/" + id}, and the
     *  paged {@code path} local, which must itself be {@code BASE_PATH} plus a query string. */
    private static String clientTemplate(String expression, String client) {
        return switch (expression) {
            case "BASE_PATH" -> "{base}";
            case "BASE_PATH + \"/\" + id" -> "{base}/{id}";
            case "path" -> {
                assertThat(client).contains("String path = BASE_PATH + \"?page=\"");
                yield "{base}";
            }
            default -> throw new AssertionError("unrecognised client path expression " + expression);
        };
    }
}
