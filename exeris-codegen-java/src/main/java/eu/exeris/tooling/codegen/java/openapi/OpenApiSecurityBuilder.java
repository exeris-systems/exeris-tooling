package eu.exeris.tooling.codegen.java.openapi;

import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds OpenAPI security schemes and requirements.
 * @author Exeris Team
 * @since 0.1.0
 */
public final class OpenApiSecurityBuilder {

    private static final String BEARER_AUTH = "bearerAuth";

    private OpenApiSecurityBuilder() {}

    public static List<SecurityRequirement> buildSecurity(DomainMetadata metadata) {
        SecurityRequirement requirement = new SecurityRequirement();
        requirement.addList(BEARER_AUTH);
        return List.of(requirement);
    }

    public static Map<String, SecurityScheme> buildSecuritySchemes() {
        Map<String, SecurityScheme> schemes = new LinkedHashMap<>();
        SecurityScheme bearerScheme = new SecurityScheme();
        bearerScheme.setType(SecurityScheme.Type.HTTP);
        bearerScheme.setScheme("bearer");
        bearerScheme.setBearerFormat("JWT");
        bearerScheme.setDescription("JWT Bearer token authentication");
        schemes.put(BEARER_AUTH, bearerScheme);
        return schemes;
    }
}

