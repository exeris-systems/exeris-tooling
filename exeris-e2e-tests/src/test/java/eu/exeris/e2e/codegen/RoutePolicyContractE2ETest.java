package eu.exeris.e2e.codegen;

import eu.exeris.e2e.codegen.RoutePolicyFixture.RegisteredRoute;
import eu.exeris.kernel.spi.http.HttpRoutePolicy;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The route requirements the generated {@code GeneratedRoutePolicy} declares, held against the
 * contract in {@code contract/route-policy.json}.
 *
 * <p>The generated TypeScript reads the same file; it is the one place the two builds meet. The
 * policy is generated from {@link RoutePolicyFixture}, compiled against the kernel SPI and asked,
 * for every route {@code RuntimeLifecycle} registers, what that route requires: a contract row's
 * route answers the row's requirement and every other generated route an abstention. The example
 * fixture is parsed and checked for shape, never held against emitted code.
 */
@Tag("e2e")
@Tag("codegen")
@DisplayName("Route policy contract: emitted GeneratedRoutePolicy == contract")
class RoutePolicyContractE2ETest {

    private static final String PERMIT_ALL = "permitAll";
    private static final String AUTHENTICATED = "authenticated";
    private static final String ANY_SCOPE = "anyScope";
    private static final String ENTITY = "entity";
    private static final String ACTION = "action";
    private static final String ABSTAIN = "abstain";
    private static final String ID = "42";
    private static final Set<String> KINDS = Set.of(PERMIT_ALL, AUTHENTICATED, ANY_SCOPE);
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "DELETE", "PATCH");
    private static final Set<String> SOURCES = Set.of(ENTITY, ACTION);

    record Requirement(String kind, List<String> scopes) {}

    record Row(String entity, String method, String path, String action, String field, Requirement requirement,
               String source) {}

    record Contract(String description, List<Row> routes) {}

    private static HttpRoutePolicy policy;
    private static List<RegisteredRoute> registered;

    @BeforeAll
    static void generate(@TempDir Path workDir) throws Exception {
        var files = RoutePolicyFixture.generate();
        registered = RoutePolicyFixture.registeredRoutes(RoutePolicyFixture.content(files, "RuntimeLifecycle"));
        policy = RoutePolicyFixture.compilePolicy(
                RoutePolicyFixture.content(files, RoutePolicyFixture.POLICY_TYPE), workDir);
    }

    @Test
    @DisplayName("every contract row is a generated route, and the policy answers each route as the contract says")
    void emittedPolicyMatchesTheContract() throws IOException {
        Contract contract = read("/contract/route-policy.json");
        assertWellFormed(contract);
        assertThat(contract.routes()).isNotEmpty();

        Map<String, String> expected = new LinkedHashMap<>();
        for (Row row : contract.routes()) {
            String template = RoutePolicyFixture.instantiate(row.path(), row.entity(), row.action(), row.field());
            assertThat(registered)
                    .as("contract row %s is a route RuntimeLifecycle registers", row)
                    .anyMatch(route -> route.method().equals(row.method()) && route.template().equals(template));
            assertThat(expected.put(row.method() + " " + template, describe(row.requirement())))
                    .as("one row per route: %s", row)
                    .isNull();
        }

        for (RegisteredRoute route : registered) {
            String key = route.method() + " " + route.template();
            String path = route.template().replace("{id}", ID);
            assertThat(RoutePolicyFixture.describe(policy.requirementFor(RoutePolicyFixture.method(route.method()), path)))
                    .as("requirement for %s", key)
                    .isEqualTo(expected.getOrDefault(key, ABSTAIN));
        }
    }

    private static String describe(Requirement requirement) {
        if (!ANY_SCOPE.equals(requirement.kind())) {
            return requirement.kind();
        }
        return ANY_SCOPE + requirement.scopes().stream().sorted().toList();
    }

    @Test
    @DisplayName("the example fixture parses and every row is well-formed")
    void exampleFixtureIsWellFormed() throws IOException {
        Contract example = read("/contract/route-policy.example.json");

        assertThat(example.routes()).isNotEmpty();
        assertWellFormed(example);
        assertThat(example.routes()).extracting(r -> r.requirement().kind()).containsAll(KINDS);
        assertThat(example.routes()).extracting(Row::source).containsAll(SOURCES);
        assertThat(example.routes()).extracting(Row::method).doesNotContain("HEAD");
    }

    private static Contract read(String resource) throws IOException {
        try (InputStream in = RoutePolicyContractE2ETest.class.getResourceAsStream(resource)) {
            assertThat(in).as(resource + " on the test classpath").isNotNull();
            return JsonMapper.builder().build().readValue(in, Contract.class);
        }
    }

    private static void assertWellFormed(Contract contract) {
        assertThat(contract.description()).isNotBlank();
        assertThat(contract.routes()).isNotNull();
        for (Row row : contract.routes()) {
            assertThat(row.entity()).as("entity of %s", row).isNotBlank();
            assertThat(METHODS).as("method of %s", row).contains(row.method());
            assertThat(row.path()).as("path of %s", row).startsWith("{base}");
            assertThat(SOURCES).as("source of %s", row).contains(row.source());
            assertThat(KINDS).as("kind of %s", row).contains(row.requirement().kind());
            boolean actionPath = row.path().endsWith("/actions/{action}");
            assertThat(row.action() != null).as("action key iff action path: %s", row).isEqualTo(actionPath);
            boolean blobPath = row.path().equals("{base}/{id}/blobs/{field}");
            assertThat(row.field() != null).as("field key iff blob path: %s", row).isEqualTo(blobPath);
            boolean scoped = ANY_SCOPE.equals(row.requirement().kind());
            assertThat(row.requirement().scopes() != null && !row.requirement().scopes().isEmpty())
                    .as("scopes iff anyScope: %s", row).isEqualTo(scoped);
            if (ACTION.equals(row.source())) {
                assertThat(actionPath).as("an action-sourced row is an action route: %s", row).isTrue();
            }
        }
    }
}
