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
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The route requirements the generated {@code GeneratedRoutePolicy} declares, held against the
 * contract in {@code contract/route-policy.json}.
 *
 * <p>The generated TypeScript guards read the same file; it is the one place the two builds meet.
 * The policy declares no row until the generator extracts access, so the contract's {@code routes}
 * is empty and the emitted policy must declare no requirement. The example fixture is parsed and
 * checked for shape, never held against emitted code.
 */
@Tag("e2e")
@Tag("codegen")
@DisplayName("Route policy contract: emitted GeneratedRoutePolicy == contract")
class RoutePolicyContractE2ETest {

    private static final String DOMAIN_PACKAGE = "eu.exeris.e2e.parity.domain";
    private static final String POLICY_TYPE = "GeneratedRoutePolicy";
    private static final String PERMIT_ALL = "permitAll";
    private static final String AUTHENTICATED = "authenticated";
    private static final String ANY_SCOPE = "anyScope";
    private static final String ENTITY = "entity";
    private static final String ACTION = "action";
    private static final Set<String> KINDS = Set.of(PERMIT_ALL, AUTHENTICATED, ANY_SCOPE);
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "DELETE", "PATCH");
    private static final Set<String> SOURCES = Set.of(ENTITY, ACTION);

    /** A requirement built in the emitted policy; {@code abstain()} is the absence of a row. */
    private static final Pattern DECLARED = Pattern.compile(
            "RouteRequirement\\s*\\.\\s*(permitAll|authenticated|requiringAnyScope)\\(");

    record Requirement(String kind, List<String> scopes) {}

    record Row(String entity, String method, String path, String action, Requirement requirement, String source) {}

    record Contract(String description, List<Row> routes) {}

    private static String policy;

    @BeforeAll
    static void generate() {
        DomainMetadata metadata = DomainMetadata.builder("Order", DOMAIN_PACKAGE)
                .path("/orders")
                .realTimeApi(true)
                .actions(List.of(
                        ActionMetadata.builder("cancel").methodName("cancel").build(),
                        ActionMetadata.builder("trackShipment").methodName("trackShipment")
                                .streaming(true).build()))
                .build();
        policy = new KernelApplicationGenerator()
                .generateAll(List.of(metadata), DOMAIN_PACKAGE.replace(".domain", ""))
                .stream()
                .filter(file -> POLICY_TYPE.equals(file.className()))
                .map(GeneratedFile::content)
                .findFirst()
                .orElseThrow(() -> new AssertionError(POLICY_TYPE + " is not emitted"));
    }

    @Test
    @DisplayName("the emitted policy declares exactly the contract's rows")
    void emittedPolicyMatchesTheContract() throws IOException {
        Contract contract = read("/contract/route-policy.json");
        assertWellFormed(contract);

        Set<String> emitted = new TreeSet<>();
        Matcher m = DECLARED.matcher(policy);
        while (m.find()) {
            emitted.add("requiringAnyScope".equals(m.group(1)) ? ANY_SCOPE : m.group(1));
        }
        Set<String> expected = new TreeSet<>();
        contract.routes().forEach(r -> expected.add(r.requirement().kind()));

        assertThat(emitted).as("requirement kinds the emitted policy builds").isEqualTo(expected);
        if (contract.routes().isEmpty()) {
            assertThat(policy).as("a policy with no row only abstains").contains("ABSTAIN");
        }
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
            boolean scoped = ANY_SCOPE.equals(row.requirement().kind());
            assertThat(row.requirement().scopes() != null && !row.requirement().scopes().isEmpty())
                    .as("scopes iff anyScope: %s", row).isEqualTo(scoped);
            if (ACTION.equals(row.source())) {
                assertThat(actionPath).as("an action-sourced row is an action route: %s", row).isTrue();
            }
        }
    }
}
