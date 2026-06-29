package eu.exeris.tooling.codegen.java.openapi;

import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.FieldMetadata;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.media.Schema;

import java.util.LinkedHashMap;
import java.util.Map;

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
        components.setSecuritySchemes(OpenApiSecurityBuilder.buildSecuritySchemes());
        components.setSchemas(schemas);
        return components;
    }

    private static Schema<?> buildEntitySchema(DomainMetadata metadata) {
        Schema<Object> schema = new Schema<>();
        schema.setType("object");
        schema.setDescription(metadata.description() != null ? metadata.description() : metadata.entityName() + " entity");
        Map<String, Schema> properties = new LinkedHashMap<>();
        properties.put("id", new Schema<String>().type("string").format("uuid").description("Unique identifier"));
        if (metadata.hasFields()) {
            for (FieldMetadata field : metadata.fields()) {
                properties.put(field.name(), buildFieldSchema(field));
            }
        }
        properties.put("createdAt", new Schema<String>().type("string").format("date-time").description("Creation timestamp"));
        properties.put("updatedAt", new Schema<String>().type("string").format("date-time").description("Last update timestamp"));
        schema.setProperties(properties);
        return schema;
    }

    private static Schema<?> buildCreateDtoSchema(DomainMetadata metadata) {
        Schema<Object> schema = new Schema<>();
        schema.setType("object");
        schema.setDescription("DTO for creating " + metadata.entityName());
        Map<String, Schema> properties = new LinkedHashMap<>();
        java.util.List<String> required = new java.util.ArrayList<>();
        if (metadata.hasFields()) {
            for (FieldMetadata field : metadata.fields()) {
                if (!field.readOnly() && !"id".equals(field.name())) {
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
        if (metadata.hasFields()) {
            for (FieldMetadata field : metadata.fields()) {
                if (!field.readOnly() && !"id".equals(field.name())) {
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

