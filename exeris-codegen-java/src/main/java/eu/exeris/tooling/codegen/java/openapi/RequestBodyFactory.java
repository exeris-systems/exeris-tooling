package eu.exeris.tooling.codegen.java.openapi;

import eu.exeris.sdk.sourcemodel.ast.ActionMetadata;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.RequestBody;

/**
 * Factory for OpenAPI request body definitions.
 * @author Exeris Team
 * @since 0.1.0
 */
public final class RequestBodyFactory {

    private static final String APPLICATION_JSON = "application/json";

    private RequestBodyFactory() {}

    public static RequestBody buildCreateRequestBody(String entityName) {
        RequestBody requestBody = new RequestBody();
        requestBody.setDescription("Create " + entityName + " request");
        requestBody.setRequired(true);
        Content content = new Content();
        MediaType mediaType = new MediaType();
        mediaType.setSchema(new Schema<>().$ref("#/components/schemas/" + entityName + "CreateDto"));
        content.addMediaType(APPLICATION_JSON, mediaType);
        requestBody.setContent(content);
        return requestBody;
    }

    public static RequestBody buildUpdateRequestBody(String entityName) {
        RequestBody requestBody = new RequestBody();
        requestBody.setDescription("Update " + entityName + " request");
        requestBody.setRequired(true);
        Content content = new Content();
        MediaType mediaType = new MediaType();
        mediaType.setSchema(new Schema<>().$ref("#/components/schemas/" + entityName + "UpdateDto"));
        content.addMediaType(APPLICATION_JSON, mediaType);
        requestBody.setContent(content);
        return requestBody;
    }

    public static RequestBody buildActionRequestBody(String entityName, ActionMetadata action) {
        RequestBody requestBody = new RequestBody();
        requestBody.setDescription("Request for " + action.name() + " action");
        requestBody.setRequired(true);
        Content content = new Content();
        MediaType mediaType = new MediaType();
        String schemaName = entityName + capitalize(action.name()) + "Request";
        mediaType.setSchema(new Schema<>().$ref("#/components/schemas/" + schemaName));
        content.addMediaType(APPLICATION_JSON, mediaType);
        requestBody.setContent(content);
        return requestBody;
    }

    private static String capitalize(String str) {
        if (str == null || str.isEmpty()) return str;
        return Character.toUpperCase(str.charAt(0)) + str.substring(1);
    }
}

