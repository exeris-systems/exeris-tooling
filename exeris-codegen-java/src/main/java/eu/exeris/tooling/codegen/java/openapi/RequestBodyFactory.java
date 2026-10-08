package eu.exeris.tooling.codegen.java.openapi;

import eu.exeris.sdk.sourcemodel.ast.ActionMetadata;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.RequestBody;

/**
 * Factory for OpenAPI request body definitions.
 * @since 0.1
 */
public final class RequestBodyFactory {

    private static final String APPLICATION_JSON = "application/json";

    private RequestBodyFactory() {}

    /**
     * Builds the required JSON request body of an entity's create operation.
     *
     * @param entityName the entity's simple name
     * @return a body referencing the {@code <Entity>CreateDto} schema
     */
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

    /**
     * Builds the required JSON request body of an entity's update operation.
     *
     * @param entityName the entity's simple name
     * @return a body referencing the {@code <Entity>UpdateDto} schema
     */
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

    /**
     * Builds the required JSON request body of one entity action.
     *
     * @param entityName the entity's simple name
     * @param action     the action the body is for
     * @return a body referencing the {@code <Entity><Action>Request} schema
     */
    public static RequestBody buildActionRequestBody(String entityName, ActionMetadata action) {
        RequestBody requestBody = new RequestBody();
        requestBody.setDescription("Request for " + action.name() + " action");
        requestBody.setRequired(true);
        Content content = new Content();
        MediaType mediaType = new MediaType();
        mediaType.setSchema(new Schema<>().$ref("#/components/schemas/"
                + actionRequestSchemaName(entityName, action)));
        content.addMediaType(APPLICATION_JSON, mediaType);
        requestBody.setContent(content);
        return requestBody;
    }

    /**
     * The name of an action's request-body schema, {@code <Entity><Action>Request}. The body
     * references it and {@link OpenApiComponentsBuilder} defines it under the same name.
     *
     * @param entityName the entity's simple name
     * @param action     the action the body is for
     * @return the schema name
     */
    static String actionRequestSchemaName(String entityName, ActionMetadata action) {
        return entityName + capitalize(action.name()) + "Request";
    }

    private static String capitalize(String str) {
        if (str == null || str.isEmpty()) {
            return str;
        }
        return Character.toUpperCase(str.charAt(0)) + str.substring(1);
    }
}

