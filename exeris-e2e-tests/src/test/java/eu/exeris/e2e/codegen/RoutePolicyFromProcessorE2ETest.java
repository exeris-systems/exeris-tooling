package eu.exeris.e2e.codegen;

import eu.exeris.e2e.codegen.RoutePolicyContractE2ETest.Requirement;
import eu.exeris.e2e.codegen.RoutePolicyContractE2ETest.Row;
import eu.exeris.e2e.codegen.RoutePolicyFixture.RegisteredRoute;
import eu.exeris.e2e.codegen.compile.BlobFacet;
import eu.exeris.e2e.codegen.compile.ProcessorCompiler;
import eu.exeris.kernel.spi.http.HttpRoutePolicy;
import eu.exeris.sdk.sourcemodel.ast.ActionMetadata;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.FieldMetadata;
import eu.exeris.sdk.sourcemodel.ast.RouteAccess;
import eu.exeris.tooling.codegen.core.MetadataLoader;
import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import eu.exeris.tooling.codegen.java.CodegenPipeline;
import eu.exeris.tooling.codegen.java.kernel.KernelApplicationGenerator;
import eu.exeris.tooling.codegen.java.kernel.KernelOpenApiGenerator;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The route policy and the OpenAPI security of the {@link RoutePolicyFixture} declarations written
 * as annotated source and compiled through the real processor: {@code @RouteAccess} on a type and
 * on an action method, and {@code permissions} on {@code @ExerisDomain} and on {@code @Action}.
 *
 * <pre>
 *   @ExerisDomain / @Action / @RouteAccess source
 *        → javac + ExerisDomainProcessor        (real metadata)
 *        → KernelApplicationGenerator           (GeneratedRoutePolicy, RuntimeLifecycle)
 *        → KernelOpenApiGenerator               (security per operation)
 * </pre>
 *
 * <p>The extracted metadata is held against the values the source declares and against what
 * {@link RoutePolicyFixture} builds by hand, so the hand-built model the other route-policy tests
 * generate from is the one the processor writes for these declarations. The policy emitted from the
 * processor's metadata is held against {@code contract/route-policy.json}, route by route, and the
 * OpenAPI documents against the same rows (ADR-079 Amendment 2).
 *
 * <p>{@code Order} and {@code Catalog} are {@code GLOBAL} with {@code realTimeApi}, and the processor
 * refuses a {@code @Blob} on a {@code GLOBAL} entity, so their {@code BlobRef} fields carry no
 * {@code @Blob} in the source and the facet is written onto the metadata ({@link BlobFacet}), which
 * the generators take as given.
 */
@Tag("e2e")
@Tag("codegen")
@DisplayName("Route policy from processor metadata: @RouteAccess and permissions reach the policy and the OpenAPI security")
class RoutePolicyFromProcessorE2ETest {

    private static final String DOMAIN = RoutePolicyFixture.DOMAIN_PACKAGE.replace('.', '/') + "/";
    private static final String ID = "42";
    private static final String ABSTAIN = "abstain";
    private static final String SCHEME = "bearerAuth";
    private static final String NONE = "none";

    @TempDir
    static Path workspace;

    private static Map<String, DomainMetadata> extracted;
    private static List<DomainMetadata> domains;
    private static List<GeneratedFile> files;
    private static HttpRoutePolicy policy;

    @BeforeAll
    static void compileAndGenerate() throws Exception {
        Path classes = workspace.resolve("target/classes");
        ProcessorCompiler.compile(workspace.resolve("src/main/java"), classes, null, sources());
        Path metadata = classes.resolve(MetadataLoader.METADATA_DIR);
        BlobFacet.on("Order", "receipt", List.of("application/pdf")).apply(metadata);
        BlobFacet.on("Catalog", "image", List.of()).apply(metadata);

        extracted = CodegenPipeline.createDefault().loadMetadata(metadata).stream()
                .collect(Collectors.toMap(DomainMetadata::entityName, Function.identity()));
        domains = RoutePolicyFixture.domains().stream()
                .map(d -> extracted.get(d.entityName()))
                .toList();
        assertThat(domains).as("one processor metadata per fixture entity").doesNotContainNull();

        files = new KernelApplicationGenerator().generateAll(domains, RoutePolicyFixture.BASE_PACKAGE);
        policy = RoutePolicyFixture.compilePolicy(
                RoutePolicyFixture.content(files, RoutePolicyFixture.POLICY_TYPE), workspace.resolve("policy"));
    }

    @Test
    @DisplayName("@RouteAccess and permissions reach the metadata of the entity and of each action with their declared values")
    void accessReachesTheMetadata() {
        DomainMetadata order = extracted.get("Order");
        assertThat(order.routeAccess()).isNull();
        assertThat(order.permissions()).containsExactly("order:read", "order:write");
        assertThat(action(order, "cancel").permissions()).isEmpty();
        assertThat(action(order, "cancel").routeAccess()).isNull();
        assertThat(action(order, "approve").permissions()).containsExactly("order:approve");
        assertThat(action(order, "approve").routeAccess()).isNull();
        assertThat(action(order, "trackShipment").streaming()).isTrue();

        DomainMetadata invoice = extracted.get("Invoice");
        assertThat(invoice.routeAccess()).isEqualTo(RouteAccess.AUTHENTICATED);
        assertThat(invoice.permissions()).isEmpty();
        assertThat(action(invoice, "export").routeAccess()).isEqualTo(RouteAccess.PUBLIC);
        assertThat(action(invoice, "export").permissions()).isEmpty();

        assertThat(extracted.get("Catalog").routeAccess()).isEqualTo(RouteAccess.PUBLIC);
        assertThat(extracted.get("Catalog").effectivePath()).isEqualTo("/orders/catalog");

        DomainMetadata tag = extracted.get("Tag");
        assertThat(tag.routeAccess()).isNull();
        assertThat(tag.permissions()).isEmpty();
        assertThat(action(tag, "merge").routeAccess()).isNull();
        assertThat(action(tag, "merge").permissions()).isEmpty();
    }

    @Test
    @DisplayName("RoutePolicyFixture builds the metadata the processor writes, in every attribute the route policy reads")
    void handBuiltFixtureMatchesTheProcessor() {
        assertThat(domains.stream().map(RoutePolicyFromProcessorE2ETest::policyInputs).toList())
                .isEqualTo(RoutePolicyFixture.domains().stream()
                        .map(RoutePolicyFromProcessorE2ETest::policyInputs).toList());
    }

    @Test
    @DisplayName("the policy generated from the processor's metadata is the one generated from RoutePolicyFixture")
    void generatedPolicyEqualsTheFixturePolicy() {
        assertThat(RoutePolicyFixture.content(files, RoutePolicyFixture.POLICY_TYPE))
                .isEqualTo(RoutePolicyFixture.content(RoutePolicyFixture.generate(), RoutePolicyFixture.POLICY_TYPE));
    }

    @Test
    @DisplayName("the compiled policy answers every registered route as contract/route-policy.json says, abstaining on the rest")
    void compiledPolicyAnswersTheContract() throws IOException {
        List<RegisteredRoute> registered = RoutePolicyFixture.registeredRoutes(
                RoutePolicyFixture.content(files, "RuntimeLifecycle"));
        Map<String, String> expected = new LinkedHashMap<>();
        for (Row row : RoutePolicyContractE2ETest.read("/contract/route-policy.json").routes()) {
            String template = RoutePolicyFixture.instantiate(row.path(), row.entity(), row.action(), row.field());
            assertThat(registered).as("contract row %s is a route the processor-built application registers", row)
                    .anyMatch(route -> route.method().equals(row.method()) && route.template().equals(template));
            expected.put(row.method() + " " + template, describe(row.requirement()));
        }

        for (RegisteredRoute route : registered) {
            String key = route.method() + " " + route.template();
            assertThat(requirement(route.method(), route.template().replace("{id}", ID)))
                    .as("requirement for %s", key)
                    .isEqualTo(expected.getOrDefault(key, ABSTAIN));
        }
    }

    @Test
    @DisplayName("the nearest declaration wins, and permissions are any-of")
    void nearestDeclarationWins() {
        assertThat(requirement("POST", "/orders/42/actions/approve")).isEqualTo("anyScope[order:approve]");
        assertThat(requirement("POST", "/orders/42/actions/cancel")).isEqualTo("anyScope[order:read, order:write]");
        assertThat(requirement("GET", "/orders")).isEqualTo("anyScope[order:read, order:write]");
        assertThat(requirement("POST", "/invoices/42/actions/export")).isEqualTo("permitAll");
        assertThat(requirement("PUT", "/invoices/42")).isEqualTo("authenticated");
        assertThat(requirement("GET", "/orders/catalog/42")).isEqualTo("permitAll");
        assertThat(requirement("GET", "/tags")).isEqualTo(ABSTAIN);
        assertThat(requirement("POST", "/tags/42/actions/merge")).isEqualTo(ABSTAIN);
    }

    @Test
    @DisplayName("each OpenAPI operation declares the security of its contract row, scopes as one requirement object each")
    void openApiSecurityFollowsTheContract() throws IOException {
        Map<String, String> expected = new LinkedHashMap<>();
        for (Row row : RoutePolicyContractE2ETest.read("/contract/route-policy.json").routes()) {
            String path = RoutePolicyFixture.instantiate(row.path(), row.entity(), row.action(), row.field());
            expected.put(row.entity() + " " + row.method() + " " + path, switch (row.requirement().kind()) {
                case "authenticated" -> "authenticated";
                case "anyScope" -> "anyScope" + row.requirement().scopes();
                default -> NONE;
            });
        }

        Map<String, String> declared = new LinkedHashMap<>();
        KernelOpenApiGenerator generator = new KernelOpenApiGenerator();
        for (DomainMetadata domain : domains) {
            SwaggerParseResult parsed = new OpenAPIV3Parser().readContents(
                    generator.generateMultiple(domain, domains).get(0).content());
            assertThat(parsed.getMessages()).as("parse messages of %s", domain.entityName()).isEmpty();
            OpenAPI document = parsed.getOpenAPI();
            document.getPaths().forEach((path, item) -> item.readOperationsMap().forEach((method, operation) ->
                    declared.put(domain.entityName() + " " + method + " " + path, security(operation))));
        }

        expected.forEach((operation, security) -> {
            if (!NONE.equals(security)) {
                assertThat(declared).as("an emitted operation for contract row %s", operation).containsKey(operation);
            }
        });
        declared.forEach((operation, security) -> assertThat(security)
                .as("security of %s", operation)
                .isEqualTo(expected.getOrDefault(contractKey(operation), NONE)));
        assertThat(declared)
                .containsEntry("Order POST /orders/{id}/actions/approve", "anyScope[order:approve]")
                .containsEntry("Order POST /orders/{id}/actions/cancel", "anyScope[order:read, order:write]")
                .containsEntry("Invoice POST /invoices/{id}/actions/export", NONE)
                .containsEntry("Invoice GET /invoices/{id}", "authenticated")
                .containsEntry("Tag GET /tags", NONE);
    }

    private static ActionMetadata action(DomainMetadata domain, String name) {
        return domain.actions().stream()
                .filter(a -> a.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError(domain.entityName() + " has no action " + name));
    }

    private static String requirement(String method, String path) {
        return RoutePolicyFixture.describe(policy.requirementFor(RoutePolicyFixture.method(method), path));
    }

    private static String describe(Requirement requirement) {
        return "anyScope".equals(requirement.kind())
                ? "anyScope" + requirement.scopes().stream().sorted().toList()
                : requirement.kind();
    }

    /** The key of the contract row an emitted operation answers from: {@code HEAD} reads {@code GET}. */
    private static String contractKey(String operation) {
        String[] parts = operation.split(" ", 3);
        String method = PathItem.HttpMethod.HEAD.name().equals(parts[1]) ? "GET" : parts[1];
        return parts[0] + " " + method + " " + parts[2];
    }

    /** An operation's security as the contract writes a requirement, scopes in the document's order. */
    private static String security(Operation operation) {
        List<SecurityRequirement> security = operation.getSecurity();
        if (security == null || security.isEmpty()) {
            return NONE;
        }
        List<String> scopes = new ArrayList<>();
        for (SecurityRequirement requirement : security) {
            assertThat(requirement).as("requirement of %s", operation.getOperationId()).containsOnlyKeys(SCHEME);
            scopes.addAll(requirement.get(SCHEME));
        }
        if (scopes.isEmpty()) {
            return "authenticated";
        }
        assertThat(security).as("one requirement object per scope on %s", operation.getOperationId())
                .hasSameSizeAs(scopes);
        return "anyScope" + scopes;
    }

    /** What the route policy reads of an entity: its routes, its access, its actions' and blob fields'. */
    private static List<Object> policyInputs(DomainMetadata domain) {
        List<Object> inputs = new ArrayList<>(List.of(domain.entityName(), domain.packageName(),
                domain.effectivePath(), domain.realTimeApi(), String.valueOf(domain.routeAccess()),
                domain.permissions()));
        for (ActionMetadata action : domain.actions()) {
            inputs.add(List.of(action.name(), action.methodName(), action.streaming(),
                    String.valueOf(action.routeAccess()), action.permissions()));
        }
        for (FieldMetadata field : domain.fields()) {
            if (field.blob() != null) {
                inputs.add(List.of(field.name(), field.blob().contentTypes()));
            }
        }
        return inputs;
    }

    private static Map<String, String> sources() {
        return Map.of(
                DOMAIN + "Order.java", """
                        package eu.exeris.e2e.parity.domain;

                        import eu.exeris.kernel.spi.storage.blob.BlobRef;
                        import eu.exeris.sdk.annotation.Action;
                        import eu.exeris.sdk.annotation.ExerisDomain;

                        import java.util.UUID;

                        @ExerisDomain(module = "orders", path = "/orders", realTimeApi = true,
                                      permissions = {"order:read", "order:write"})
                        public class Order {
                            private UUID id;
                            private BlobRef receipt;
                            public UUID getId() { return id; }
                            public void setId(UUID id) { this.id = id; }
                            public BlobRef getReceipt() { return receipt; }
                            public void setReceipt(BlobRef receipt) { this.receipt = receipt; }

                            @Action(name = "cancel", label = "Cancel")
                            public void cancel() { }

                            @Action(name = "approve", label = "Approve", permissions = {"order:approve"})
                            public void approve() { }

                            @Action(name = "trackShipment", label = "Track shipment", streaming = true)
                            public void trackShipment() { }
                        }
                        """,
                DOMAIN + "Invoice.java", """
                        package eu.exeris.e2e.parity.domain;

                        import eu.exeris.sdk.annotation.Action;
                        import eu.exeris.sdk.annotation.ExerisDomain;
                        import eu.exeris.sdk.annotation.RouteAccess;

                        import java.util.UUID;

                        @ExerisDomain(module = "invoices", path = "/invoices", realTimeApi = true)
                        @RouteAccess(RouteAccess.Level.AUTHENTICATED)
                        public class Invoice {
                            private UUID id;
                            public UUID getId() { return id; }
                            public void setId(UUID id) { this.id = id; }

                            @Action(name = "export", label = "Export")
                            @RouteAccess(RouteAccess.Level.PUBLIC)
                            public void export() { }
                        }
                        """,
                DOMAIN + "Catalog.java", """
                        package eu.exeris.e2e.parity.domain;

                        import eu.exeris.kernel.spi.storage.blob.BlobRef;
                        import eu.exeris.sdk.annotation.ExerisDomain;
                        import eu.exeris.sdk.annotation.RouteAccess;

                        import java.util.UUID;

                        @ExerisDomain(module = "catalog", path = "/orders/catalog", realTimeApi = true)
                        @RouteAccess(RouteAccess.Level.PUBLIC)
                        public class Catalog {
                            private UUID id;
                            private BlobRef image;
                            public UUID getId() { return id; }
                            public void setId(UUID id) { this.id = id; }
                            public BlobRef getImage() { return image; }
                            public void setImage(BlobRef image) { this.image = image; }
                        }
                        """,
                DOMAIN + "Tag.java", """
                        package eu.exeris.e2e.parity.domain;

                        import eu.exeris.sdk.annotation.Action;
                        import eu.exeris.sdk.annotation.ExerisDomain;

                        import java.util.UUID;

                        @ExerisDomain(module = "tags", path = "/tags")
                        public class Tag {
                            private UUID id;
                            public UUID getId() { return id; }
                            public void setId(UUID id) { this.id = id; }

                            @Action(name = "merge", label = "Merge")
                            public void merge() { }
                        }
                        """);
    }
}
