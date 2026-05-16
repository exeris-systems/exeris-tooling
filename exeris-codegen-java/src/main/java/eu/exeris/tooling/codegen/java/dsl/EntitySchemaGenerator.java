package eu.exeris.tooling.codegen.java.dsl;

import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.FieldMetadata;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Generates entity schema JSON for Atom v3 UI.
 * @author Exeris Team
 * @since 0.1.0
 */
public final class EntitySchemaGenerator {

    private final DomainMetadata metadata;
    private final ObjectMapper mapper;

    public EntitySchemaGenerator(DomainMetadata metadata) {
        this.metadata = Objects.requireNonNull(metadata, "metadata cannot be null");
        this.mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    public String generate() throws IOException {
        return mapper.writeValueAsString(buildSchema());
    }

    public void writeTo(Path outputPath) throws IOException {
        Files.createDirectories(outputPath);
        String fileName = metadata.entityName().toLowerCase(Locale.ROOT) + ".schema.json";
        Files.writeString(outputPath.resolve(fileName), generate());
    }

    private Map<String, Object> buildSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("$id", "https://exeris.eu/schemas/" + metadata.entityName().toLowerCase());
        schema.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        schema.put("title", metadata.entityName());
        schema.put("type", "object");
        if (metadata.description() != null) schema.put("description", metadata.description());

        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        properties.put("id", Map.of("type", "string", "format", "uuid"));

        if (metadata.hasFields()) {
            for (FieldMetadata field : metadata.fields()) {
                properties.put(field.name(), buildFieldSchema(field));
                if (field.required()) required.add(field.name());
            }
        }

        schema.put("properties", properties);
        if (!required.isEmpty()) schema.put("required", required);
        return schema;
    }

    private Map<String, Object> buildFieldSchema(FieldMetadata field) {
        Map<String, Object> fs = new LinkedHashMap<>();
        fs.put("type", mapToJsonSchemaType(field.type()));
        String format = mapToJsonSchemaFormat(field.type());
        if (format != null) fs.put("format", format);
        if (field.displayName() != null) fs.put("title", field.displayName());
        if (field.description() != null) fs.put("description", field.description());
        if (field.minLength() != null) fs.put("minLength", field.minLength());
        if (field.maxLength() != null) fs.put("maxLength", field.maxLength());
        if (field.min() != null) fs.put("minimum", field.min());
        if (field.max() != null) fs.put("maximum", field.max());
        if (field.pattern() != null) fs.put("pattern", field.pattern());
        return fs;
    }

    private String mapToJsonSchemaType(String javaType) {
        if (javaType == null) return "string";
        if (javaType.contains("int") || javaType.contains("Integer") || javaType.contains("long") || javaType.contains("Long")) return "integer";
        if (javaType.contains("double") || javaType.contains("float") || javaType.contains("BigDecimal")) return "number";
        if (javaType.contains("boolean") || javaType.contains("Boolean")) return "boolean";
        return "string";
    }

    private String mapToJsonSchemaFormat(String javaType) {
        if (javaType == null) return null;
        if (javaType.contains("UUID")) return "uuid";
        if (javaType.contains("LocalDate") && !javaType.contains("DateTime")) return "date";
        if (javaType.contains("DateTime") || javaType.contains("Instant")) return "date-time";
        return null;
    }
}
