package eu.exeris.tooling.codegen.java.dsl;

import eu.exeris.sdk.sourcemodel.ast.ActionMetadata;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.FieldMetadata;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

/**
 * Generates domain metadata JSON files for frontend generators.
 * @author Exeris Team
 * @since 0.1.0
 */
public final class DomainMetadataGenerator {

    private static final String SCHEMA_URL = "https://exeris.eu/schemas/domain-metadata/v1.json";
    private static final String SCHEMA_VERSION = "1.0.0";

    private final DomainMetadata metadata;
    private final ObjectMapper mapper;

    public DomainMetadataGenerator(DomainMetadata metadata) {
        this.metadata = Objects.requireNonNull(metadata, "metadata cannot be null");
        this.mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public String generate() throws IOException {
        return mapper.writeValueAsString(buildMetadataJson());
    }

    public void writeTo(Path outputPath) throws IOException {
        Files.createDirectories(outputPath);
        String fileName = metadata.entityName().toLowerCase(Locale.ROOT) + ".meta.json";
        Files.writeString(outputPath.resolve(fileName), generate());
    }

    private Map<String, Object> buildMetadataJson() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("$schema", SCHEMA_URL);
        json.put("schemaVersion", SCHEMA_VERSION);
        json.put("generatedAt", Instant.now().toString());
        json.put("generatedFrom", metadata.fullyQualifiedName());
        json.put("entity", buildEntityInfo());
        json.put("api", buildApiInfo());
        if (metadata.hasFields()) json.put("fields", buildFieldsInfo());
        if (metadata.hasActions()) json.put("actions", buildActionsInfo());
        return json;
    }

    private Map<String, Object> buildEntityInfo() {
        Map<String, Object> entity = new LinkedHashMap<>();
        entity.put("name", metadata.entityName());
        entity.put("package", metadata.packageName());
        entity.put("displayName", metadata.entityName());
        entity.put("audited", metadata.audited());
        entity.put("softDelete", metadata.softDelete());
        entity.put("multiTenant", metadata.tenantScoped());
        return entity;
    }

    private Map<String, Object> buildApiInfo() {
        Map<String, Object> api = new LinkedHashMap<>();
        api.put("basePath", metadata.effectivePath());
        api.put("version", "v1");
        return api;
    }

    private List<Map<String, Object>> buildFieldsInfo() {
        return metadata.fields().stream().map(this::buildFieldInfo).toList();
    }

    private Map<String, Object> buildFieldInfo(FieldMetadata field) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("name", field.name());
        f.put("type", DslTypeMapper.mapType(field.type()));
        f.put("javaType", field.type());
        f.put("required", field.required());
        f.put("searchable", field.searchable());
        f.put("sortable", field.sortable());
        if (field.displayName() != null) f.put("label", field.displayName());
        return f;
    }

    private List<Map<String, Object>> buildActionsInfo() {
        return metadata.actions().stream().map(this::buildActionInfo).toList();
    }

    private Map<String, Object> buildActionInfo(ActionMetadata action) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("name", action.name());
        a.put("label", action.name());
        a.put("method", action.httpMethod());
        a.put("async", action.async());
        a.put("dangerous", action.dangerous());
        if (action.hasPermissions()) a.put("permissions", action.permissions());
        return a;
    }
}
