package eu.exeris.tooling.codegen.java.openapi;

import eu.exeris.sdk.sourcemodel.ast.ActionMetadata;
import eu.exeris.sdk.sourcemodel.ast.ActionParamMetadata;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.FieldMetadata;
import eu.exeris.tooling.codegen.java.support.DataScopeSupport;
import eu.exeris.tooling.codegen.java.support.ListQuerySupport;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.media.Schema;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Builds OpenAPI components (schemas) from domain metadata.
 * @since 0.1
 */
public final class OpenApiComponentsBuilder {

    private OpenApiComponentsBuilder() {}

    /**
     * Builds the schemas one entity contributes: the entity itself, its create and update DTOs,
     * its list-page envelope, and the request body of each action that takes parameters.
     *
     * @param metadata the entity to describe
     * @return the components, with schemas in that order and the actions in declaration order
     */
    public static Components buildComponents(DomainMetadata metadata) {
        Components components = new Components();
        Map<String, Schema> schemas = new LinkedHashMap<>();
        schemas.put(metadata.entityName(), buildEntitySchema(metadata));
        schemas.put(metadata.entityName() + "CreateDto", buildCreateDtoSchema(metadata));
        schemas.put(metadata.entityName() + "UpdateDto", buildUpdateDtoSchema(metadata));
        schemas.put(pageSchemaName(metadata.entityName()), buildPageSchema(metadata));
        if (metadata.hasActions()) {
            for (ActionMetadata action : metadata.actions()) {
                if (action.hasParams()) {
                    schemas.put(RequestBodyFactory.actionRequestSchemaName(metadata.entityName(), action),
                            buildActionRequestSchema(action));
                }
            }
        }
        components.setSchemas(schemas);
        return components;
    }

    /**
     * The fields the server owns on write: a tenant-partitioned entity's owner and a
     * UNIVERSE entity's {@code @SharedScope} key. The generated repository stamps both from the
     * bound storage context, refuses a value that contradicts it, and never updates the owner; so
     * the entity schema marks them {@code readOnly} and the create/update DTOs omit them — the same
     * split the TypeScript emitter makes (its {@code systemFieldNames}), so the published contract
     * and the generated client agree on what a request may carry.
     */
    private static Set<String> serverOwnedFields(DomainMetadata metadata) {
        Set<String> owned = new LinkedHashSet<>();
        DataScopeSupport.ownerFieldName(metadata).ifPresent(owned::add);
        DataScopeSupport.sharedScopeField(metadata).ifPresent(field -> owned.add(field.name()));
        return owned;
    }

    private static Schema<?> buildEntitySchema(DomainMetadata metadata) {
        Schema<Object> schema = new Schema<>();
        OpenApiSchemas.typed(schema, "object");
        schema.setDescription(metadata.description() != null
                ? metadata.description()
                : metadata.entityName() + " entity");
        Map<String, Schema> properties = new LinkedHashMap<>();
        properties.put("id", OpenApiSchemas.typed(new Schema<String>(), "string").format("uuid")
                .description("Unique identifier"));
        Set<String> serverOwned = serverOwnedFields(metadata);
        if (metadata.hasFields()) {
            for (FieldMetadata field : metadata.fields()) {
                Schema<?> fieldSchema = buildFieldSchema(field);
                if (serverOwned.contains(field.name())) {
                    fieldSchema.setReadOnly(true);
                }
                properties.put(field.name(), fieldSchema);
            }
        }
        properties.put("createdAt", OpenApiSchemas.typed(new Schema<String>(), "string")
                .format("date-time")
                .description("Creation timestamp"));
        properties.put("updatedAt", OpenApiSchemas.typed(new Schema<String>(), "string")
                .format("date-time")
                .description("Last update timestamp"));
        schema.setProperties(properties);
        return schema;
    }

    /** The name of the list route's envelope schema — {@code <Entity>Page}, as the Java record is named. */
    static String pageSchemaName(String entityName) {
        return entityName + "Page";
    }

    /**
     * The list route's envelope, member for member as {@link ListQuerySupport#ENVELOPE} names it and
     * the generated {@code <Entity>Page} record declares it. Every member is always present.
     */
    private static Schema<?> buildPageSchema(DomainMetadata metadata) {
        Schema<Object> schema = new Schema<>();
        OpenApiSchemas.typed(schema, "object");
        schema.setDescription("One page of " + metadata.entityName());
        Map<String, Schema> properties = new LinkedHashMap<>();
        Schema<Object> content = new Schema<>();
        OpenApiSchemas.typed(content, "array");
        content.setItems(new Schema<>().$ref("#/components/schemas/" + metadata.entityName()));
        content.setDescription("The rows of this page, in the query's order");
        properties.put("content", content);
        properties.put("totalElements", OpenApiSchemas.typed(new Schema<Long>(), "integer").format("int64")
                .description("The number of rows the query matched"));
        properties.put("totalPages", OpenApiSchemas.typed(new Schema<Integer>(), "integer").format("int32")
                .description("The number of pages of size those rows fill"));
        properties.put("size", OpenApiSchemas.typed(new Schema<Integer>(), "integer").format("int32")
                .description("The page size"));
        properties.put("number", OpenApiSchemas.typed(new Schema<Integer>(), "integer").format("int32")
                .description("The zero-based page index"));
        properties.put("first", OpenApiSchemas.typed(new Schema<Boolean>(), "boolean")
                .description("Whether this is the first page"));
        properties.put("last", OpenApiSchemas.typed(new Schema<Boolean>(), "boolean")
                .description("Whether no page follows this one"));
        schema.setProperties(properties);
        schema.setRequired(new java.util.ArrayList<>(ListQuerySupport.ENVELOPE));
        return schema;
    }

    private static Schema<?> buildCreateDtoSchema(DomainMetadata metadata) {
        Schema<Object> schema = new Schema<>();
        OpenApiSchemas.typed(schema, "object");
        schema.setDescription("DTO for creating " + metadata.entityName());
        Map<String, Schema> properties = new LinkedHashMap<>();
        java.util.List<String> required = new java.util.ArrayList<>();
        Set<String> serverOwned = serverOwnedFields(metadata);
        if (metadata.hasFields()) {
            for (FieldMetadata field : metadata.fields()) {
                if (!field.readOnly() && !"id".equals(field.name()) && !serverOwned.contains(field.name())) {
                    properties.put(field.name(), buildFieldSchema(field));
                    if (field.required()) {
                        required.add(field.name());
                    }
                }
            }
        }
        schema.setProperties(properties);
        if (!required.isEmpty()) {
            schema.setRequired(required);
        }
        return schema;
    }

    private static Schema<?> buildUpdateDtoSchema(DomainMetadata metadata) {
        Schema<Object> schema = new Schema<>();
        OpenApiSchemas.typed(schema, "object");
        schema.setDescription("DTO for updating " + metadata.entityName());
        Map<String, Schema> properties = new LinkedHashMap<>();
        Set<String> serverOwned = serverOwnedFields(metadata);
        if (metadata.hasFields()) {
            for (FieldMetadata field : metadata.fields()) {
                if (!field.readOnly() && !"id".equals(field.name()) && !serverOwned.contains(field.name())) {
                    properties.put(field.name(), buildFieldSchema(field));
                }
            }
        }
        schema.setProperties(properties);
        return schema;
    }

    /**
     * The body of one action, member for member as the emitted {@code <Action>Request} record
     * declares it: one property per {@code @ActionParam}, in declaration order, with its type and
     * format. No member is {@code required}: no emitted handler checks that a parameter is present,
     * and {@code @ActionParam.required} and {@code .description} are registered as inert in the
     * processor's strict audit.
     */
    private static Schema<?> buildActionRequestSchema(ActionMetadata action) {
        Schema<Object> schema = new Schema<>();
        OpenApiSchemas.typed(schema, "object");
        schema.setDescription("Request for the " + action.name() + " action");
        Map<String, Schema> properties = new LinkedHashMap<>();
        for (ActionParamMetadata param : action.params()) {
            Schema<Object> paramSchema =
                    OpenApiSchemas.typed(new Schema<>(), TypeMapper.toOpenApiType(param.type()));
            String format = TypeMapper.toOpenApiFormat(param.type());
            if (format != null) {
                paramSchema.setFormat(format);
            }
            properties.put(param.name(), paramSchema);
        }
        schema.setProperties(properties);
        return schema;
    }

    private static Schema<?> buildFieldSchema(FieldMetadata field) {
        Schema<Object> schema = new Schema<>();
        OpenApiSchemas.typed(schema, TypeMapper.toOpenApiType(field.type()));
        String format = TypeMapper.toOpenApiFormat(field.type());
        if (format != null) {
            schema.setFormat(format);
        }
        // @Field.dataType=url is a front-presentation hint with a standard OpenAPI
        // format counterpart ("uri"); apply it as a cheap, additive parity hint
        // (Wave 1A, Java∪TS union). Other dataType values are FE-only facets.
        // This is deliberately last so the explicit author hint wins over any
        // type-derived format above — a field is only `dataType=url` when the author
        // declared it, and "uri" is the intended contract for that field.
        if ("url".equals(field.dataType())) {
            schema.setFormat("uri");
        }
        if (field.description() != null) {
            schema.setDescription(field.description());
        }
        if (field.minLength() != null) {
            schema.setMinLength(field.minLength());
        }
        if (field.maxLength() != null) {
            schema.setMaxLength(field.maxLength());
        }
        if (field.min() != null) {
            schema.setMinimum(java.math.BigDecimal.valueOf(field.min()));
        }
        if (field.max() != null) {
            schema.setMaximum(java.math.BigDecimal.valueOf(field.max()));
        }
        if (field.pattern() != null) {
            schema.setPattern(field.pattern());
        }
        return schema;
    }
}

