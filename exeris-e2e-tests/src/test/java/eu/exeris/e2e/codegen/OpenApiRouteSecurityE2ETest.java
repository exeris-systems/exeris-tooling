package eu.exeris.e2e.codegen;

import eu.exeris.e2e.codegen.RoutePolicyContractE2ETest.Row;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
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

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The security the emitted OpenAPI documents declare per operation, held against the contract in
 * {@code contract/route-policy.json} that the generated route policy answers (ADR-079 Amendment 2).
 *
 * <p>The documents are the ones {@code KernelOpenApiGenerator} writes for {@link RoutePolicyFixture},
 * one per entity, parsed back from their YAML. An operation carries {@code security} exactly when its
 * route's contract row demands an identity, with the row's scopes as one requirement object each, in
 * the row's order; every other operation carries none. A {@code HEAD} operation answers from the
 * {@code GET} row of its path, as the policy does.
 */
@Tag("e2e")
@Tag("codegen")
@DisplayName("OpenAPI route security: emitted security == route-policy contract")
class OpenApiRouteSecurityE2ETest {

    private static final String AUTHENTICATED = "authenticated";
    private static final String ANY_SCOPE = "anyScope";
    private static final String SCHEME = "bearerAuth";
    private static final String NONE = "none";

    private static final Map<String, OpenAPI> DOCUMENTS = new LinkedHashMap<>();

    @BeforeAll
    static void generate() {
        List<DomainMetadata> domains = RoutePolicyFixture.domains();
        KernelOpenApiGenerator generator = new KernelOpenApiGenerator();
        for (DomainMetadata domain : domains) {
            GeneratedFile file = generator.generateMultiple(domain, domains).get(0);
            SwaggerParseResult parsed = new OpenAPIV3Parser().readContents(file.content());
            assertThat(parsed.getMessages()).as("parse messages of %s", domain.entityName()).isEmpty();
            DOCUMENTS.put(domain.entityName(), parsed.getOpenAPI());
        }
    }

    @Test
    @DisplayName("the operations carrying security are exactly the contract rows that demand identity, scopes included")
    void securityFollowsTheContract() throws IOException {
        Map<String, String> expected = new LinkedHashMap<>();
        for (Row row : RoutePolicyContractE2ETest.read("/contract/route-policy.json").routes()) {
            String path = RoutePolicyFixture.instantiate(row.path(), row.entity(), row.action(), row.field());
            expected.put(row.entity() + " " + row.method() + " " + path, expectedSecurity(row));
        }

        Map<String, String> declared = new LinkedHashMap<>();
        DOCUMENTS.forEach((entity, document) -> document.getPaths().forEach((path, item) ->
                item.readOperationsMap().forEach((method, operation) ->
                        declared.put(entity + " " + method + " " + path, describe(operation)))));

        for (Map.Entry<String, String> row : expected.entrySet()) {
            if (!NONE.equals(row.getValue())) {
                assertThat(declared).as("an emitted operation for contract row %s", row.getKey())
                        .containsKey(row.getKey());
            }
        }
        declared.forEach((operation, security) -> assertThat(security)
                .as("security of %s", operation)
                .isEqualTo(expected.getOrDefault(contractKey(operation), NONE)));
        assertThat(declared.values()).as("the fixture exercises both requirement kinds")
                .anyMatch(AUTHENTICATED::equals)
                .anyMatch(security -> security.startsWith(ANY_SCOPE));
    }

    @Test
    @DisplayName("an operation with security declares 401, and 403 when it requires scopes; any other neither")
    void edgeStatusesFollowTheSecurity() {
        DOCUMENTS.values().forEach(document -> document.getPaths().values().stream()
                .flatMap(item -> item.readOperations().stream())
                .forEach(operation -> {
                    String security = describe(operation);
                    assertThat(operation.getResponses().containsKey("401"))
                            .as("401 on %s", operation.getOperationId()).isEqualTo(!NONE.equals(security));
                    assertThat(operation.getResponses().containsKey("403"))
                            .as("403 on %s", operation.getOperationId()).isEqualTo(security.startsWith(ANY_SCOPE));
                }));
    }

    @Test
    @DisplayName("the bearer scheme is in exactly the documents with a secured operation; no root security")
    void schemeOnlyWhereSomeOperationDemandsIdentity() {
        DOCUMENTS.forEach((entity, document) -> {
            boolean secured = document.getPaths().values().stream()
                    .flatMap(item -> item.readOperations().stream())
                    .anyMatch(operation -> operation.getSecurity() != null);
            assertThat(document.getSecurity()).as("root security of %s", entity).isNull();
            if (secured) {
                assertThat(document.getComponents().getSecuritySchemes()).as("schemes of %s", entity)
                        .containsOnlyKeys(SCHEME);
                assertThat(document.getComponents().getSecuritySchemes().get(SCHEME).getScheme())
                        .isEqualTo("bearer");
            } else {
                assertThat(document.getComponents().getSecuritySchemes()).as("schemes of %s", entity).isNull();
            }
        });
        assertThat(DOCUMENTS.get("Tag").getComponents().getSecuritySchemes()).isNull();
        assertThat(DOCUMENTS.get("Order").getComponents().getSecuritySchemes()).isNotNull();
    }

    /** The key of the contract row an emitted operation answers from: {@code HEAD} reads {@code GET}. */
    private static String contractKey(String operation) {
        String[] parts = operation.split(" ", 3);
        String method = PathItem.HttpMethod.HEAD.name().equals(parts[1]) ? "GET" : parts[1];
        return parts[0] + " " + method + " " + parts[2];
    }

    private static String expectedSecurity(Row row) {
        return switch (row.requirement().kind()) {
            case AUTHENTICATED -> AUTHENTICATED;
            case ANY_SCOPE -> ANY_SCOPE + row.requirement().scopes();
            default -> NONE;
        };
    }

    /** An operation's security as the contract writes a requirement, scopes in the document's order. */
    private static String describe(Operation operation) {
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
            assertThat(security).as("authenticated security of %s", operation.getOperationId()).hasSize(1);
            return AUTHENTICATED;
        }
        assertThat(security).as("one requirement object per scope on %s", operation.getOperationId())
                .hasSameSizeAs(scopes);
        return ANY_SCOPE + scopes;
    }
}
