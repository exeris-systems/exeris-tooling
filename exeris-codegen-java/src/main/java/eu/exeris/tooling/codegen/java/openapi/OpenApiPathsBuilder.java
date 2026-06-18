package eu.exeris.tooling.codegen.java.openapi;

import eu.exeris.sdk.sourcemodel.ast.ActionMetadata;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.tooling.codegen.java.support.NameCasing;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;

import java.util.List;

/**
 * Builds OpenAPI paths from domain metadata.
 * @author Exeris Team
 * @since 0.1.0
 */
public final class OpenApiPathsBuilder {

    private OpenApiPathsBuilder() {}

    public static Paths buildPaths(DomainMetadata metadata) {
        Paths paths = new Paths();
        String basePath = metadata.effectivePath();
        String entityName = metadata.entityName();

        PathItem collectionPath = new PathItem();
        collectionPath.setGet(buildListOperation(entityName));
        collectionPath.setPost(buildCreateOperation(entityName));
        paths.addPathItem(basePath, collectionPath);

        PathItem itemPath = new PathItem();
        itemPath.setGet(buildGetOperation(entityName));
        itemPath.setPut(buildUpdateOperation(entityName));
        itemPath.setDelete(buildDeleteOperation(entityName));
        paths.addPathItem(basePath + "/{id}", itemPath);

        if (metadata.hasActions()) {
            for (ActionMetadata action : metadata.actions()) {
                String actionPath = basePath + "/{id}/actions/" + NameCasing.kebab(action.name());
                PathItem actionPathItem = new PathItem();
                actionPathItem.setPost(buildActionOperation(entityName, action));
                paths.addPathItem(actionPath, actionPathItem);
            }
        }
        return paths;
    }

    private static Operation buildListOperation(String entity) {
        Operation op = new Operation();
        op.setOperationId("list" + entity);
        op.setSummary("List all " + entity);
        op.setTags(List.of(entity));
        op.setResponses(buildResponses("200", "List of " + entity));
        return op;
    }

    private static Operation buildGetOperation(String entity) {
        Operation op = new Operation();
        op.setOperationId("get" + entity + "ById");
        op.setSummary("Get " + entity + " by ID");
        op.setTags(List.of(entity));
        op.addParametersItem(buildIdParam());
        op.setResponses(buildResponses("200", entity + " details"));
        return op;
    }

    private static Operation buildCreateOperation(String entity) {
        Operation op = new Operation();
        op.setOperationId("create" + entity);
        op.setSummary("Create new " + entity);
        op.setTags(List.of(entity));
        op.setRequestBody(RequestBodyFactory.buildCreateRequestBody(entity));
        op.setResponses(buildResponses("201", "Created " + entity));
        return op;
    }

    private static Operation buildUpdateOperation(String entity) {
        Operation op = new Operation();
        op.setOperationId("update" + entity);
        op.setSummary("Update " + entity);
        op.setTags(List.of(entity));
        op.addParametersItem(buildIdParam());
        op.setRequestBody(RequestBodyFactory.buildUpdateRequestBody(entity));
        op.setResponses(buildResponses("200", "Updated " + entity));
        return op;
    }

    private static Operation buildDeleteOperation(String entity) {
        Operation op = new Operation();
        op.setOperationId("delete" + entity);
        op.setSummary("Delete " + entity);
        op.setTags(List.of(entity));
        op.addParametersItem(buildIdParam());
        op.setResponses(buildResponses("204", entity + " deleted"));
        return op;
    }

    private static Operation buildActionOperation(String entity, ActionMetadata action) {
        Operation op = new Operation();
        op.setOperationId(action.name() + entity);
        op.setSummary(action.description() != null ? action.description() : "Execute " + action.name());
        op.setTags(List.of(entity + " Actions"));
        op.addParametersItem(buildIdParam());
        if (action.hasParams()) {
            op.setRequestBody(RequestBodyFactory.buildActionRequestBody(entity, action));
        }
        op.setResponses(buildResponses("200", "Action result"));
        return op;
    }

    private static Parameter buildIdParam() {
        Parameter param = new Parameter();
        param.setName("id");
        param.setIn("path");
        param.setRequired(true);
        param.setDescription("Entity ID (UUID)");
        param.setSchema(new io.swagger.v3.oas.models.media.Schema<String>().type("string").format("uuid"));
        return param;
    }

    private static ApiResponses buildResponses(String code, String description) {
        ApiResponses responses = new ApiResponses();
        responses.addApiResponse(code, new ApiResponse().description(description));
        responses.addApiResponse("400", new ApiResponse().description("Bad request"));
        responses.addApiResponse("401", new ApiResponse().description("Unauthorized"));
        responses.addApiResponse("404", new ApiResponse().description("Not found"));
        return responses;
    }

}

