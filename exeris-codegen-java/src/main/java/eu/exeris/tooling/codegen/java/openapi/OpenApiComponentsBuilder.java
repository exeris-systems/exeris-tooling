package eu.exeris.tooling.codegen.java.openapi;

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
 * @author Exeris Team
 * @since 0.1.0
 */
public final class OpenApiComponentsBuilder {

    private OpenApiComponentsBuilder() {}

    public static Components buildComponents(DomainMetadata metadata) {
        Components components = new Components();
        Map<String, Schema> schemas = new LinkedHashMap<>();
        schemas.put(metadata.entityName(), buildEntitySchema(metadata));
        schemas.put(metadata.entityName() + "CreateDto", buildCreateDtoSchema(metadata));
        schemas.put(metadata.entityName() + "UpdateDto", buildUpdateDtoSchema(metadata));
        schemas.put(pageSchemaName(metadata.entityName()), buildPageSchema(metadata));
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
        schema.setType("object");
        schema.setDescription(metadata.description() != null ? metadata.description() : metadata.entityName() + " entity");
        Map<String, Schema> properties = new LinkedHashMap<>();
        properties.put("id", new Schema<String>().type("string").format("uuid").description("Unique identifier"));
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
        properties.put("createdAt", new Schema<String>().type("string").format("date-time").description("Creation timestamp"));
        properties.put("updatedAt", new Schema<String>().type("string").format("date-time").description("Last update timestamp"));
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
        schema.setType("object");
        schema.setDescription("One page of " + metadata.entityName());
        Map<String, Schema> properties = new LinkedHashMap<>();
        Schema<Object> content = new Schema<>();
        content.setType("array");
        content.setItems(new Schema<>().$ref("#/components/schemas/" + metadata.entityName()));
        content.setDescription("The rows of this page, in the query's order");
        properties.put("content", content);
        properties.put("totalElements", new Schema<Long>().type("integer").format("int64")
                .description("The number of rows the query matched"));
        properties.put("totalPages", new Schema<Integer>().type("integer").format("int32")
                .description("The number of pages of size those rows fill"));
        properties.put("size", new Schema<Integer>().type("integer").format("int32")
                .description("The page size"));
        properties.put("number", new Schema<Integer>().type("integer").format("int32")
                .description("The zero-based page index"));
        properties.put("first", new Schema<Boolean>().type("boolean")
                .description("Whether this is the first page"));
        properties.put("last", new Schema<Boolean>().type("boolean")
                .description("Whether no page follows this one"));
        schema.setProperties(properties);
        schema.setRequired(new java.util.ArrayList<>(ListQuerySupport.ENVELOPE));
        return schema;
    }

    private static Schema<?> buildCreateDtoSchema(DomainMetadata metadata) {
        Schema<Object> schema = new Schema<>();
        schema.setType("object");
        schema.setDescription("DTO for creating " + metadata.entityName());
        Map<String, Schema> properties = new LinkedHashMap<>();
        java.util.List<String> required = new java.util.ArrayList<>();
        Set<String> serverOwned = serverOwnedFields(metadata);
        if (metadata.hasFields()) {
            for (FieldMetadata field : metadata.fields()) {
                if (!field.readOnly() && !"id".equals(field.name()) && !serverOwned.contains(field.name())) {
                    properties.put(field.name(), buildFieldSchema(field));
                    if (field.required()) required.add(field.name());
                }
            }
        }
        schema.setProperties(properties);
        if (!required.isEmpty()) schema.setRequired(required);
        return schema;
    }

    private static Schema<?> buildUpdateDtoSchema(DomainMetadata metadata) {
        Schema<Object> schema = new Schema<>();
        schema.setType("object");
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

    private static Schema<?> buildFieldSchema(FieldMetadata field) {
        Schema<Object> schema = new Schema<>();
        schema.setType(TypeMapper.toOpenApiType(field.type()));
        String format = TypeMapper.toOpenApiFormat(field.type());
        if (format != null) schema.setFormat(format);
        // @Field.dataType=url is a front-presentation hint with a standard OpenAPI
        // format counterpart ("uri"); apply it as a cheap, additive parity hint
        // (Wave 1A, Java∪TS union). Other dataType values are FE-only facets.
        // This is deliberately last so the explicit author hint wins over any
        // type-derived format above — a field is only `dataType=url` when the author
        // declared it, and "uri" is the intended contract for that field.
        if ("url".equals(field.dataType())) schema.setFormat("uri");
        if (field.description() != null) schema.setDescription(field.description());
        if (field.minLength() != null) schema.setMinLength(field.minLength());
        if (field.maxLength() != null) schema.setMaxLength(field.maxLength());
        if (field.min() != null) schema.setMinimum(java.math.BigDecimal.valueOf(field.min()));
        if (field.max() != null) schema.setMaximum(java.math.BigDecimal.valueOf(field.max()));
        if (field.pattern() != null) schema.setPattern(field.pattern());
        return schema;
    }
}

