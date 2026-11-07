package eu.exeris.tooling.codegen.java.openapi;

import eu.exeris.sdk.sourcemodel.ast.ActionMetadata;
import eu.exeris.sdk.sourcemodel.ast.ActionParamMetadata;
import eu.exeris.sdk.sourcemodel.ast.DomainEventMetadata;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.FieldMetadata;
import eu.exeris.sdk.sourcemodel.ast.ParamSource;
import eu.exeris.tooling.codegen.core.access.RouteRequirements;
import eu.exeris.tooling.codegen.core.access.RouteRequirements.Requirement;
import eu.exeris.tooling.codegen.java.support.BlobFields;
import eu.exeris.tooling.codegen.java.support.ForeignKeys;
import eu.exeris.tooling.codegen.java.support.KernelStreamScaffold;
import eu.exeris.tooling.codegen.java.support.ListQuerySupport;
import eu.exeris.tooling.codegen.java.support.NameCasing;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
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

    /** The media type of a stream route's response. */
    private static final String EVENT_STREAM = "text/event-stream";
    /** The status of a request the route served. */
    private static final String OK = "200";
    /** The OpenAPI type of a string schema. */
    private static final String STRING_TYPE = "string";
    /** The problem-object member naming the relationship of a missing reference (ADR-076 Amendment 1). */
    private static final String PROBLEM_FIELD = "field";
    /** The media type of an object stored without a declared one. */
    private static final String OCTET_STREAM = "application/octet-stream";
    /** The status of a route that answers no body. */
    private static final String NO_CONTENT = "204";

    private OpenApiPathsBuilder() {}

    /**
     * Builds the path items one entity exposes, rooted at its effective REST path, taking the entity
     * as a build of its own: only a reference to itself is a foreign key.
     *
     * @param metadata the entity to describe
     * @return the entity's paths and their operations
     */
    public static Paths buildPaths(DomainMetadata metadata) {
        return buildPaths(metadata, List.of(metadata));
    }

    /**
     * Builds the path items one entity of a build exposes, rooted at its effective REST path. The
     * build decides which foreign keys the entity's writes and delete can violate: a write that
     * references an entity of the build declares {@code 422}, and the delete of an entity another
     * references declares {@code 409} (ADR-076 Amendment 1). Every operation carries the security its
     * route demands, as {@link OpenApiSecurity} declares it: an action route its action's
     * requirement, every other route the entity's.
     *
     * @param metadata the entity to describe
     * @param build    every entity of the build, {@code metadata} included
     * @return the entity's paths and their operations
     */
    public static Paths buildPaths(DomainMetadata metadata, List<DomainMetadata> build) {
        Paths paths = new Paths();
        boolean missingReference = ForeignKeys.refusesMissingReference(metadata, build);
        boolean referencedDelete = ForeignKeys.refusesReferencedDelete(metadata, build);
        String basePath = metadata.effectivePath();
        String entityName = metadata.entityName();

        PathItem collectionPath = new PathItem();
        collectionPath.setGet(buildListOperation(metadata));
        collectionPath.setPost(buildCreateOperation(entityName, missingReference));
        paths.addPathItem(basePath, collectionPath);

        PathItem itemPath = new PathItem();
        itemPath.setGet(buildGetOperation(entityName));
        itemPath.setPut(buildUpdateOperation(entityName, metadata.versioned(), missingReference));
        itemPath.setDelete(buildDeleteOperation(entityName, referencedDelete));
        paths.addPathItem(basePath + "/{id}", itemPath);

        for (FieldMetadata field : BlobFields.of(metadata)) {
            paths.addPathItem(basePath + BlobFields.routeSuffix(field), buildBlobPath(entityName, field));
        }

        if (metadata.realTimeApi()) {
            PathItem livePath = new PathItem();
            livePath.setGet(buildLiveViewOperation(metadata));
            paths.addPathItem(basePath + "/stream", livePath);

            PathItem spectatePath = new PathItem();
            spectatePath.setGet(buildSpectateOperation(metadata));
            paths.addPathItem(basePath + "/{id}/stream", spectatePath);
        }

        Requirement entityRequirement = RouteRequirements.forEntity(metadata);
        paths.values().forEach(item -> item.readOperations()
                .forEach(operation -> OpenApiSecurity.apply(operation, entityRequirement)));

        if (metadata.hasActions()) {
            for (ActionMetadata action : metadata.actions()) {
                String actionPath = basePath + "/{id}/actions/" + NameCasing.kebab(action.name());
                Operation operation = action.streaming()
                        ? buildActionStreamOperation(metadata, action, missingReference)
                        : buildActionOperation(entityName, action, metadata.versioned(), missingReference);
                OpenApiSecurity.apply(operation, RouteRequirements.forAction(metadata, action));
                PathItem actionPathItem = new PathItem();
                actionPathItem.setPost(operation);
                paths.addPathItem(actionPath, actionPathItem);
            }
        }
        return paths;
    }

    /**
     * The list operation: the query parameters {@code <Entity>ListQuery.parse} reads — {@code page},
     * {@code search} over the entity's searchable properties, {@code size}, {@code sort} over the
     * entity's sortable properties, and one equality filter per filter property — and the
     * {@code <Entity>Page} envelope. {@code search} and {@code sort} are each declared only when
     * something is searchable or sortable, since the route refuses them otherwise. {@code 400} is what the handler
     * answers for every parameter the query refuses.
     */
    private static Operation buildListOperation(DomainMetadata metadata) {
        String entity = metadata.entityName();
        Operation op = new Operation();
        op.setOperationId("list" + entity);
        op.setSummary("List " + entity + ", one page at a time");
        op.setTags(List.of(entity));

        op.addParametersItem(queryParam(ListQuerySupport.PAGE, "Zero-based page index",
                OpenApiSchemas.typed(new Schema<Integer>(), "integer").format("int32")
                        .minimum(BigDecimal.ZERO)._default(0)));
        List<ListQuerySupport.Property> searchable = ListQuerySupport.searchable(metadata);
        if (!searchable.isEmpty()) {
            op.addParametersItem(queryParam(ListQuerySupport.SEARCH,
                    "Case-insensitive substring match against any of: "
                            + String.join(", ", searchable.stream().map(ListQuerySupport.Property::name).toList())
                            + ". A blank value is no search.",
                    OpenApiSchemas.typed(new Schema<String>(), STRING_TYPE)
                            .maxLength(ListQuerySupport.MAX_SEARCH_LENGTH)));
        }
        op.addParametersItem(queryParam(ListQuerySupport.SIZE, "Page size",
                OpenApiSchemas.typed(new Schema<Integer>(), "integer").format("int32")
                        .minimum(BigDecimal.ONE).maximum(BigDecimal.valueOf(ListQuerySupport.MAX_SIZE))
                        ._default(ListQuerySupport.DEFAULT_SIZE)));
        List<ListQuerySupport.Property> sortable = ListQuerySupport.sortable(metadata);
        if (!sortable.isEmpty()) {
            List<String> values = new ArrayList<>();
            for (ListQuerySupport.Property property : sortable) {
                values.add(property.name() + ",asc");
                values.add(property.name() + ",desc");
            }
            Schema<String> sortSchema = OpenApiSchemas.typed(new Schema<String>(), STRING_TYPE);
            sortSchema.setEnum(values);
            op.addParametersItem(queryParam(ListQuerySupport.SORT,
                    "<property>,<asc|desc>; unsorted, rows come in id order", sortSchema));
        }
        for (ListQuerySupport.Property filter : ListQuerySupport.filters(metadata)) {
            Schema<Object> schema = new Schema<>();
            OpenApiSchemas.typed(schema, TypeMapper.toOpenApiType(filter.javaType()));
            String format = TypeMapper.toOpenApiFormat(filter.javaType());
            if (format != null) {
                schema.setFormat(format);
            }
            op.addParametersItem(queryParam(filter.name(), "Only rows whose " + filter.name()
                    + " equals this value", schema));
        }

        ApiResponses responses = Responses.of(OK, "One page of " + entity).badRequest().serverError();
        responses.get(OK).setContent(new Content().addMediaType("application/json",
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
        op.setResponses(Responses.of(OK, entity + " details").badRequest().notFound().serverError());
        return op;
    }

    private static Operation buildCreateOperation(String entity, boolean missingReference) {
        Operation op = new Operation();
        op.setOperationId("create" + entity);
        op.setSummary("Create new " + entity);
        op.setTags(List.of(entity));
        op.setRequestBody(RequestBodyFactory.buildCreateRequestBody(entity));
        op.setResponses(Responses.of("201", "Created " + entity).badRequest()
                .unprocessable(missingReference).serverError());
        return op;
    }

    private static Operation buildUpdateOperation(String entity, boolean versioned, boolean missingReference) {
        Operation op = new Operation();
        op.setOperationId("update" + entity);
        op.setSummary("Update " + entity);
        op.setTags(List.of(entity));
        op.addParametersItem(buildIdParam());
        op.setRequestBody(RequestBodyFactory.buildUpdateRequestBody(entity));
        Responses responses = Responses.of(OK, "Updated " + entity).badRequest();
        op.setResponses((versioned ? responses.conflict() : responses.notFound())
                .unprocessable(missingReference).serverError());
        return op;
    }

    private static Operation buildDeleteOperation(String entity, boolean referencedDelete) {
        Operation op = new Operation();
        op.setOperationId("delete" + entity);
        op.setSummary("Delete " + entity);
        op.setTags(List.of(entity));
        op.addParametersItem(buildIdParam());
        Responses responses = Responses.of("204", entity + " deleted").badRequest().notFound();
        op.setResponses((referencedDelete ? responses.stillReferenced() : responses).serverError());
        return op;
    }

    private static Operation buildActionOperation(String entity, ActionMetadata action, boolean versioned,
                                                  boolean missingReference) {
        Operation op = new Operation();
        op.setOperationId(action.name() + entity);
        op.setSummary(action.description() != null ? action.description() : "Execute " + action.name());
        op.setTags(List.of(entity + " Actions"));
        op.addParametersItem(buildIdParam());
        if (action.hasParams()) {
            for (ActionParamMetadata param : action.params()) {
                if (param.effectiveBindingSource() == ParamSource.QUERY) {
                    op.addParametersItem(actionQueryParam(param));
                }
            }
            if (OpenApiComponentsBuilder.hasBodyParams(action)) {
                op.setRequestBody(RequestBodyFactory.buildActionRequestBody(entity, action));
            }
        }
        Responses responses = Responses.of(OK, "Action result").badRequest().notFound();
        op.setResponses((versioned ? responses.conflict() : responses)
                .unprocessable(missingReference).serverError());
        return op;
    }

    private static Parameter actionQueryParam(ActionParamMetadata param) {
        Parameter p = new Parameter();
        p.setName(param.effectiveQueryKey());
        p.setIn("query");
        p.setRequired(param.required());
        if (param.description() != null) {
            p.setDescription(param.description());
        }
        Schema<?> schema = OpenApiSchemas.typed(new Schema<>(), TypeMapper.toOpenApiType(param.type()));
        String format = TypeMapper.toOpenApiFormat(param.type());
        if (format != null) {
            schema.setFormat(format);
        }
        p.setSchema(schema);
        return p;
    }

    /**
     * A streaming action, {@code POST {base}/{id}/actions/{kebab}} (ADR-044 Amendment 2, decisions
     * 1 and 2): the request runs the action and opens an SSE stream of the events it triggers. The
     * path has no respond-once form, and the engine writes the {@code 200} head before the handler
     * runs, so {@code 200} is the only status the route answers; every refusal is one
     * {@code stream-error} frame carrying the status the respond-once action route answers for the
     * same failure (ADR-079: declare what the handler can answer). The request body is the
     * action's, as on the respond-once route. A write naming a missing row through a relationship
     * is one of those frames, with status {@code 422}, only when a foreign key of the build can
     * refuse it (ADR-076 Amendment 1); the operation itself gains no {@code 422}.
     */
    private static Operation buildActionStreamOperation(DomainMetadata metadata, ActionMetadata action,
                                                        boolean missingReference) {
        String entity = metadata.entityName();
        List<String> events = new ArrayList<>();
        for (DomainEventMetadata event : metadata.events()) {
            if (event.trigger() == DomainEventMetadata.Trigger.ACTION
                    && action.name().equals(event.actionName())) {
                events.add(KernelStreamScaffold.bindingOf(event, entity).wireName());
            }
        }
        Operation op = new Operation();
        op.setOperationId(action.name() + entity);
        op.setSummary(action.description() != null ? action.description() : "Execute " + action.name());
        op.setTags(List.of(entity + " Actions"));
        op.addParametersItem(buildIdParam());
        if (action.hasParams()) {
            for (ActionParamMetadata param : action.params()) {
                if (param.effectiveBindingSource() == ParamSource.QUERY) {
                    op.addParametersItem(actionQueryParam(param));
                }
            }
            if (OpenApiComponentsBuilder.hasBodyParams(action)) {
                op.setRequestBody(RequestBodyFactory.buildActionRequestBody(entity, action));
            }
        }
        String frames = events.isEmpty()
                ? "The action triggers no @DomainEvent, so the stream closes once the action has run, "
                        + "with no event frame. "
                : "The stream carries the events the action publishes for this " + entity + ", each one "
                        + "frame named by its @DomainEvent name (" + String.join(", ", events) + ") whose "
                        + "data is the event payload, and closes once each has arrived or a deadline has "
                        + "passed. ";
        String refusals = "A malformed id, "
                + (OpenApiComponentsBuilder.hasBodyParams(action) ? "a request body that does not decode, " : "")
                + "an absent or invisible row, "
                + (metadata.versioned() ? "a version conflict, " : "")
                + (missingReference ? "a relationship naming a row that does not exist, " : "")
                + "or a server failure is one " + KernelStreamScaffold.STREAM_ERROR_FRAME
                + " frame, whose data is a problem object (RFC 9457) with status "
                + frameStatuses(metadata.versioned(), missingReference)
                + ", after which the stream closes.";
        op.setDescription("A server-sent event stream: the request runs the action. " + frames + refusals);
        op.setResponses(eventStream("The event stream of the " + action.name() + " action"));
        return op;
    }

    /**
     * The live view, {@code GET {base}/stream} (ADR-044 obligation 1): an SSE stream of every event
     * of the entity. Like every stream route it answers only {@code 200}; it reads no id and no row,
     * so it has no refusal to send as a frame, and a failure closes the stream.
     */
    private static Operation buildLiveViewOperation(DomainMetadata metadata) {
        String entity = metadata.entityName();
        List<String> events = eventNames(metadata);
        Operation op = new Operation();
        op.setOperationId("stream" + entity);
        op.setSummary("Stream the events of every " + entity);
        op.setDescription(events.isEmpty()
                ? "A server-sent event stream. " + entity + " declares no @DomainEvent, so the stream "
                        + "carries only " + KernelStreamScaffold.KEEP_ALIVE_FRAME + " frames and closes "
                        + "after a fixed number of them; a client reconnects."
                : "A server-sent event stream that stays open until the client disconnects. "
                        + eventFrames("any " + entity, events) + " A failure closes the stream.");
        op.setTags(List.of(entity));
        op.setResponses(eventStream("The event stream of every " + entity));
        return op;
    }

    /**
     * The spectate route, {@code GET {base}/{id}/stream} (ADR-044 Amendment 2, decision 7): an SSE
     * stream of one row's events. The engine writes the {@code 200} head before the handler runs,
     * so {@code 200} is the only status the route answers; a malformed id, an absent row and a
     * failure arrive in the stream as one {@code stream-error} frame carrying the status the by-id
     * {@code GET} answers (ADR-079: declare what the handler can answer).
     */
    private static Operation buildSpectateOperation(DomainMetadata metadata) {
        String entity = metadata.entityName();
        List<String> events = eventNames(metadata);
        Operation op = new Operation();
        op.setOperationId("spectate" + entity);
        op.setSummary("Stream the events of one " + entity);
        op.setDescription("A server-sent event stream that stays open until the client disconnects. "
                + (events.isEmpty()
                        ? entity + " declares no @DomainEvent, so the stream carries only "
                                + KernelStreamScaffold.KEEP_ALIVE_FRAME + " frames."
                        : eventFrames("this " + entity, events))
                + " A malformed id, an absent or invisible row, or a server failure is one "
                + KernelStreamScaffold.STREAM_ERROR_FRAME + " frame, whose data is a problem object "
                + "(RFC 9457) with status 400, 404 or 500, after which the stream closes.");
        op.setTags(List.of(entity));
        op.addParametersItem(buildIdParam());
        op.setResponses(eventStream("The event stream of the " + entity));
        return op;
    }

    /** The SSE wire name of each of the entity's events, in declaration order. */
    private static List<String> eventNames(DomainMetadata metadata) {
        List<String> names = new ArrayList<>();
        for (DomainEventMetadata event : metadata.events()) {
            names.add(KernelStreamScaffold.bindingOf(event, metadata.entityName()).wireName());
        }
        return names;
    }

    /** The statuses a streaming action's {@code stream-error} frame can carry, as prose. */
    private static String frameStatuses(boolean versioned, boolean missingReference) {
        List<String> statuses = new ArrayList<>(List.of("400", "404"));
        if (versioned) {
            statuses.add("409");
        }
        if (missingReference) {
            statuses.add("422");
        }
        return String.join(", ", statuses) + " or 500";
    }

    /** The sentence naming an open-ended stream's event frames and its keep-alive. */
    private static String eventFrames(String subject, List<String> events) {
        return "Each event published for " + subject + " is one frame named by its @DomainEvent name ("
                + String.join(", ", events) + "), whose data is the event payload; a "
                + KernelStreamScaffold.KEEP_ALIVE_FRAME + " frame is sent while the stream is quiet.";
    }

    /**
     * The only response of a stream route's handler: {@code 200} with a {@code text/event-stream}
     * body. The engine writes that head before the handler runs, so the handler can answer nothing
     * else; what the edge answers before the stream opens is {@link OpenApiSecurity}'s to declare.
     */
    private static ApiResponses eventStream(String description) {
        ApiResponses responses = new ApiResponses();
        responses.addApiResponse(OK, new ApiResponse()
                .description(description)
                .content(new Content().addMediaType(EVENT_STREAM,
                        new MediaType().schema(OpenApiSchemas.typed(new Schema<String>(), STRING_TYPE)))));
        return responses;
    }

    /**
     * The routes of one {@code @Blob} field (ADR-108): {@code PUT} stores the raw body under its
     * {@code Content-Type}, {@code GET} answers the stored bytes, {@code HEAD} their content type and
     * length with no body, and {@code DELETE} removes them. {@code 404} names an absent row, and on
     * {@code GET} and {@code HEAD} an absent object too; {@code 415} is declared only for a field
     * that restricts its content types.
     */
    private static PathItem buildBlobPath(String entity, FieldMetadata field) {
        String suffix = entity + NameCasing.pascal(field.name());
        List<String> mediaTypes = field.blob().hasContentTypes() ? field.blob().contentTypes() : List.of(OCTET_STREAM);

        Content upload = new Content();
        Content download = new Content();
        for (String mediaType : mediaTypes) {
            upload.addMediaType(mediaType, new MediaType().schema(binarySchema()));
            download.addMediaType(mediaType, new MediaType().schema(binarySchema()));
        }
        Operation put = blobOperation(entity, "upload" + suffix, "Store the " + field.name() + " of a " + entity);
        put.setRequestBody(new RequestBody().required(true).content(upload)
                .description("The bytes to store; the request's Content-Type is stored with them"));
        Responses putResponses = Responses.of(NO_CONTENT, "Stored").badRequest().notFound();
        put.setResponses((field.blob().hasContentTypes() ? putResponses.unsupportedMediaType() : putResponses)
                .serverError());

        Operation get = blobOperation(entity, "download" + suffix, "Read the " + field.name() + " of a " + entity);
        get.setResponses(Responses.of(OK, "The stored bytes, with their stored content type")
                .badRequest().notFound().serverError());
        get.getResponses().get(OK).setContent(download);

        Operation head = blobOperation(entity, "stat" + suffix,
                "Read the content type and length of the " + field.name() + " of a " + entity);
        // OpenAPI ignores a declared Content-Type response header, so the description names it.
        head.setResponses(Responses.of(OK, "No body; Content-Type is the stored content type")
                .badRequest().notFound().serverError());
        head.getResponses().get(OK)
                .addHeaderObject("Content-Length", new Header().description("The stored length in bytes")
                        .schema(OpenApiSchemas.typed(new Schema<Long>(), "integer").format("int64")));

        Operation delete = blobOperation(entity, "delete" + suffix, "Delete the " + field.name() + " of a " + entity);
        delete.setResponses(Responses.of(NO_CONTENT, "Deleted, or nothing was stored")
                .badRequest().notFound().serverError());

        PathItem item = new PathItem();
        item.setPut(put);
        item.setGet(get);
        item.setHead(head);
        item.setDelete(delete);
        return item;
    }

    private static Operation blobOperation(String entity, String operationId, String summary) {
        Operation op = new Operation();
        op.setOperationId(operationId);
        op.setSummary(summary);
        op.setTags(List.of(entity));
        op.addParametersItem(buildIdParam());
        return op;
    }

    private static Schema<?> binarySchema() {
        return OpenApiSchemas.typed(new Schema<String>(), STRING_TYPE).format("binary");
    }

    private static Parameter buildIdParam() {
        Parameter param = new Parameter();
        param.setName("id");
        param.setIn("path");
        param.setRequired(true);
        param.setDescription("Entity ID (UUID)");
        param.setSchema(OpenApiSchemas.typed(new Schema<String>(), STRING_TYPE).format("uuid"));
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
     * ADR-076 fixes the status for an absent row, and ADR-079 the per-operation sets. A foreign key
     * adds {@code 422} to a write that references an entity of the build and {@code 409} to the
     * delete of an entity another references (ADR-076 Amendment 1).
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

        Responses unsupportedMediaType() {
            return add("415", "The Content-Type is not one the field accepts");
        }

        Responses stillReferenced() {
            return add("409", "Still referenced by other rows — remove or repoint them first");
        }

        /**
         * {@code 422} when {@code reachable}: the write can name a missing row through a relationship.
         * The body is an RFC 9457 problem object, sent as {@code application/json} — the media type
         * the kernel's typed-response encoder writes — whose {@code field} member names the
         * relationship.
         */
        Responses unprocessable(boolean reachable) {
            if (!reachable) {
                return this;
            }
            Schema<Object> problem = OpenApiSchemas.typed(new Schema<>(), "object");
            problem.addProperty("type", OpenApiSchemas.typed(new Schema<String>(), STRING_TYPE));
            problem.addProperty("title", OpenApiSchemas.typed(new Schema<String>(), STRING_TYPE));
            problem.addProperty("status", OpenApiSchemas.typed(new Schema<Integer>(), "integer").format("int32"));
            problem.addProperty("detail", OpenApiSchemas.typed(new Schema<String>(), STRING_TYPE));
            problem.addProperty(PROBLEM_FIELD, OpenApiSchemas.typed(new Schema<String>(), STRING_TYPE)
                    .description("The relationship that names the missing row"));
            problem.setRequired(List.of("type", "title", "status", "detail", PROBLEM_FIELD));
            add("422", "A relationship names a row that does not exist. The body is a problem object "
                    + "(RFC 9457) sent as application/json; its field member names the relationship");
            this.declared.get("422").setContent(new Content().addMediaType("application/json",
                    new MediaType().schema(problem)));
            return this;
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

