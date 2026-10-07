package eu.exeris.tooling.codegen.java.openapi;

import eu.exeris.sdk.sourcemodel.ast.ActionMetadata;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.tooling.codegen.java.support.ListQuerySupport;
import eu.exeris.tooling.codegen.java.support.NameCasing;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds OpenAPI paths from domain metadata.
 * @since 0.1
 */
public final class OpenApiPathsBuilder {

    private OpenApiPathsBuilder() {}

    /**
     * Builds the path items one entity exposes, rooted at its effective REST path.
     *
     * @param metadata the entity to describe
     * @return the entity's paths and their operations
     */
    public static Paths buildPaths(DomainMetadata metadata) {
        Paths paths = new Paths();
        String basePath = metadata.effectivePath();
        String entityName = metadata.entityName();

        PathItem collectionPath = new PathItem();
        collectionPath.setGet(buildListOperation(metadata));
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

    /**
     * The list operation: the query parameters {@code <Entity>ListQuery.parse} reads — {@code page},
     * {@code size}, {@code sort} over the entity's sortable properties, and one equality filter per
     * filter property — and the {@code <Entity>Page} envelope. {@code sort} is declared only when
     * something is sortable, since the route refuses it otherwise. {@code 400} is what the handler
     * answers for every parameter the query refuses.
     */
    private static Operation buildListOperation(DomainMetadata metadata) {
        String entity = metadata.entityName();
        Operation op = new Operation();
        op.setOperationId("list" + entity);
        op.setSummary("List " + entity + ", one page at a time");
        op.setTags(List.of(entity));

        op.addParametersItem(queryParam(ListQuerySupport.PAGE, "Zero-based page index",
                new Schema<Integer>().type("integer").format("int32")
                        .minimum(BigDecimal.ZERO)._default(0)));
        op.addParametersItem(queryParam(ListQuerySupport.SIZE, "Page size",
                new Schema<Integer>().type("integer").format("int32")
                        .minimum(BigDecimal.ONE).maximum(BigDecimal.valueOf(ListQuerySupport.MAX_SIZE))
                        ._default(ListQuerySupport.DEFAULT_SIZE)));
        List<ListQuerySupport.Property> sortable = ListQuerySupport.sortable(metadata);
        if (!sortable.isEmpty()) {
            List<String> values = new ArrayList<>();
            for (ListQuerySupport.Property property : sortable) {
                values.add(property.name() + ",asc");
                values.add(property.name() + ",desc");
            }
            Schema<String> sortSchema = new Schema<String>().type("string");
            sortSchema.setEnum(values);
            op.addParametersItem(queryParam(ListQuerySupport.SORT,
                    "<property>,<asc|desc>; unsorted, rows come in id order", sortSchema));
        }
        for (ListQuerySupport.Property filter : ListQuerySupport.filters(metadata)) {
            Schema<Object> schema = new Schema<>();
            schema.setType(TypeMapper.toOpenApiType(filter.javaType()));
            String format = TypeMapper.toOpenApiFormat(filter.javaType());
            if (format != null) {
                schema.setFormat(format);
            }
            op.addParametersItem(queryParam(filter.name(), "Only rows whose " + filter.name()
                    + " equals this value", schema));
        }

        ApiResponses responses = Responses.of("200", "One page of " + entity).badRequest().serverError();
        responses.get("200").setContent(new Content().addMediaType("application/json",
                new MediaType().schema(new Schema<>().$ref(
                        "#/components/schemas/" + OpenApiComponentsBuilder.pageSchemaName(entity)))));
        op.setResponses(responses);
        return op;
    }

    private static Parameter queryParam(String name, String description, Schema<?> schema) {
        Parameter param = new Parameter();
        param.setName(name);
        param.setIn("query");
        param.setRequired(false);
        param.setDescription(description);
        param.setSchema(schema);
        return param;
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
     * ADR-076 fixes the status for an absent row, and ADR-079 the per-operation sets.
     */
    private static final class Responses {

        private final ApiResponses declared = new ApiResponses();

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
            return add("500", "Internal server error").declared;
        }

        private Responses add(String code, String description) {
            declared.addApiResponse(code, new ApiResponse().description(description));
            return this;
        }
    }

}

