package eu.exeris.tooling.codegen.java.openapi;

import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.tooling.codegen.core.access.RouteRequirements;
import eu.exeris.tooling.codegen.core.access.RouteRequirements.Requirement;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The security an operation declares: the requirement the generated route policy binds for the
 * operation's route, read from {@link RouteRequirements}, the source that policy reads (ADR-079
 * Amendment 2).
 *
 * <p>An operation whose route demands an identity names the {@value #SCHEME} scheme and declares
 * the statuses the kernel's edge enforcement answers before the handler runs: {@code 401} for a
 * missing or invalid bearer token, and, where the route demands scopes, {@code 403} for a token
 * holding none of them. Neither carries a body, because the kernel writes both without one. An
 * {@code anyScope} requirement is one requirement object per scope, in declaration order, since
 * OpenAPI reads alternatives across objects and conjunction within one. A route that demands
 * nothing, or declares nothing, gains nothing.
 */
final class OpenApiSecurity {

    /** The name of the bearer scheme in {@code components.securitySchemes}. */
    static final String SCHEME = "bearerAuth";

    private OpenApiSecurity() {}

    /**
     * Whether some route of {@code metadata} demands an identity, which is what puts the scheme in
     * the entity's components.
     */
    static boolean demandsIdentity(DomainMetadata metadata) {
        return RouteRequirements.demandIdentity(List.of(metadata));
    }

    /** The bearer scheme every requirement names: HTTP {@code bearer}. */
    static Map<String, SecurityScheme> schemes() {
        return Map.of(SCHEME, new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer"));
    }

    /**
     * Declares {@code requirement} on {@code operation}: its security requirements, and {@code 401}
     * and {@code 403} merged into the response set in status order. Nothing changes when the
     * requirement is absent or demands no identity.
     */
    static void apply(Operation operation, Requirement requirement) {
        if (requirement == null || !requirement.demandsIdentity()) {
            return;
        }
        Map<String, ApiResponse> statuses = new TreeMap<>(operation.getResponses());
        statuses.put("401", new ApiResponse().description("No valid bearer token"));
        List<SecurityRequirement> security = new ArrayList<>();
        if (requirement.kind() == RouteRequirements.Kind.ANY_SCOPE) {
            for (String scope : requirement.scopes()) {
                security.add(new SecurityRequirement().addList(SCHEME, scope));
            }
            statuses.put("403", new ApiResponse().description("The token holds none of the required scopes"));
        } else {
            security.add(new SecurityRequirement().addList(SCHEME));
        }
        ApiResponses responses = new ApiResponses();
        statuses.forEach(responses::addApiResponse);
        operation.setSecurity(security);
        operation.setResponses(responses);
    }
}
