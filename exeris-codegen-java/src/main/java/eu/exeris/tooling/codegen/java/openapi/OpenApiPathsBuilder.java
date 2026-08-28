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
        itemPath.setPut(buildUpdateOperation(entityName, metadata.versioned()));
        itemPath.setDelete(buildDeleteOperation(entityName));
        paths.addPathItem(basePath + "/{id}", itemPath);

        if (metadata.hasActions()) {
            for (ActionMetadata action : metadata.actions()) {
                String actionPath = basePath + "/{id}/actions/" + NameCasing.kebab(action.name());
                PathItem actionPathItem = new PathItem();
                actionPathItem.setPost(buildActionOperation(entityName, action, metadata.versioned()));
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
        op.setResponses(Responses.of("200", "List of " + entity).serverError());
        return op;
    }

    private static Operation buildGetOperation(String entity) {
        Operation op = new Operation();
        op.setOperationId("get" + entity + "ById");
        op.setSummary("Get " + entity + " by ID");
        op.setTags(List.of(entity));
        op.addParametersItem(buildIdParam());
        op.setResponses(Responses.of("200", entity + " details").badRequest().notFound().serverError());
        return op;
    }

    private static Operation buildCreateOperation(String entity) {
        Operation op = new Operation();
        op.setOperationId("create" + entity);
        op.setSummary("Create new " + entity);
        op.setTags(List.of(entity));
        op.setRequestBody(RequestBodyFactory.buildCreateRequestBody(entity));
        op.setResponses(Responses.of("201", "Created " + entity).badRequest().serverError());
        return op;
    }

    private static Operation buildUpdateOperation(String entity, boolean versioned) {
        Operation op = new Operation();
        op.setOperationId("update" + entity);
        op.setSummary("Update " + entity);
        op.setTags(List.of(entity));
        op.addParametersItem(buildIdParam());
        op.setRequestBody(RequestBodyFactory.buildUpdateRequestBody(entity));
        Responses responses = Responses.of("200", "Updated " + entity).badRequest();
        op.setResponses(versioned
                ? responses.conflict().serverError()
                : responses.notFound().serverError());
        return op;
    }

    private static Operation buildDeleteOperation(String entity) {
        Operation op = new Operation();
        op.setOperationId("delete" + entity);
        op.setSummary("Delete " + entity);
        op.setTags(List.of(entity));
        op.addParametersItem(buildIdParam());
        op.setResponses(Responses.of("204", entity + " deleted").badRequest().notFound().serverError());
        return op;
    }

    private static Operation buildActionOperation(String entity, ActionMetadata action, boolean versioned) {
        Operation op = new Operation();
        op.setOperationId(action.name() + entity);
        op.setSummary(action.description() != null ? action.description() : "Execute " + action.name());
        op.setTags(List.of(entity + " Actions"));
        op.addParametersItem(buildIdParam());
        if (action.hasParams()) {
            op.setRequestBody(RequestBodyFactory.buildActionRequestBody(entity, action));
        }
        Responses responses = Responses.of("200", "Action result").badRequest().notFound();
        op.setResponses(versioned ? responses.conflict().serverError() : responses.serverError());
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

    /**
     * The statuses one operation declares, named at each call site from what the emitted handler
     * for that route can answer.
     *
     * <p>Every emitted route ends in a {@code catch (RuntimeException)} that answers {@code 500},
     * and a tenant-partitioned entity answers it from the tenant guard as well, so
     * {@link #serverError()} closes every set. The rest is per-route: {@code 400} needs an id to
     * parse or a body to decode, {@code 404} needs an id to miss, and {@code 409} is raised only by
     * the write path of a versioned entity — where it replaces {@code 404} rather than joining it,
     * because that update matches on {@code id} and version together and reports the pair.
     *
     * <p>Two corrections are recorded in this shape. Until ADR-076 the spec declared {@code 404}
     * and not {@code 500} while the handler answered {@code 500} and not {@code 404} for an absent
     * row. Until ADR-079 one set served every operation: the collection {@code GET} and the create
     * {@code POST} declared a {@code 404} they have no id to produce, the collection {@code GET}
     * declared a {@code 400} it has nothing to reject, and every operation declared a {@code 401}
     * no emitted route can reach.
     */
    private static final class Responses {

        private final ApiResponses responses = new ApiResponses();

        private Responses() {}

        static Responses of(String code, String description) {
            return new Responses().add(code, description);
        }

        Responses badRequest() {
            return add("400", "Bad request");
        }

        Responses notFound() {
            return add("404", "Not found");
        }

        Responses conflict() {
            return add("409", "Version conflict — re-read and retry");
        }

        /** Terminal, because every emitted handler can answer it. */
        ApiResponses serverError() {
            return add("500", "Internal server error").responses;
        }

        private Responses add(String code, String description) {
            responses.addApiResponse(code, new ApiResponse().description(description));
            return this;
        }
    }

}

