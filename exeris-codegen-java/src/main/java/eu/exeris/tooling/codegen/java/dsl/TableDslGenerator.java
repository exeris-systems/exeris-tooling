package eu.exeris.tooling.codegen.java.dsl;

import eu.exeris.sdk.sourcemodel.ast.ActionMetadata;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.FieldMetadata;
import eu.exeris.tooling.codegen.java.support.NameCasing;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Generates table DSL JSON for Atom v3 UI.
 * @author Exeris Team
 * @since 0.1.0
 */
public final class TableDslGenerator {

    private final DomainMetadata metadata;
    private final ObjectMapper mapper;

    public TableDslGenerator(DomainMetadata metadata) {
        this.metadata = Objects.requireNonNull(metadata, "metadata cannot be null");
        this.mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    public String generate() throws IOException {
        return mapper.writeValueAsString(buildTable());
    }

    public void writeTo(Path outputPath) throws IOException {
        Files.createDirectories(outputPath);
        Files.writeString(outputPath.resolve(metadata.entityName().toLowerCase() + ".table.json"), generate());
    }

    private Map<String, Object> buildTable() {
        Map<String, Object> table = new LinkedHashMap<>();
        table.put("$type", "table");
        table.put("entity", metadata.entityName());
        table.put("title", metadata.pluralName() != null ? metadata.pluralName() : metadata.entityName() + "s");
        table.put("apiPath", metadata.effectivePath());
        table.put("columns", buildColumns());
        table.put("actions", List.of(
            Map.of("name", "create", "label", "Create " + metadata.entityName(), "icon", "plus", "type", "primary", "action", "navigate", "target", metadata.effectivePath() + "/new"),
            Map.of("name", "refresh", "label", "Refresh", "icon", "refresh", "type", "secondary", "action", "refresh")
        ));
        table.put("rowActions", buildRowActions());
        table.put("filters", buildFilters());
        table.put("pagination", Map.of("enabled", true, "pageSize", 20, "pageSizeOptions", List.of(10, 20, 50, 100)));
        table.put("sorting", Map.of("enabled", true, "defaultSort", "createdAt", "defaultOrder", "desc"));
        return table;
    }

    private List<Map<String, Object>> buildColumns() {
        List<Map<String, Object>> columns = new ArrayList<>();
        if (metadata.hasFields()) {
            for (FieldMetadata field : metadata.fields()) {
                if (!field.hidden() && !"id".equals(field.name())) {
                    Map<String, Object> col = new LinkedHashMap<>();
                    col.put("name", field.name());
                    col.put("label", field.displayName() != null ? field.displayName() : field.name());
                    col.put("type", DslTypeMapper.mapType(field.type()));
                    col.put("sortable", field.sortable());
                    col.put("filterable", field.filterable());
                    columns.add(col);
                }
            }
        }
        columns.add(Map.of("name", "createdAt", "label", "Created", "type", "datetime", "sortable", true));
        return columns;
    }

    private List<Map<String, Object>> buildRowActions() {
        List<Map<String, Object>> actions = new ArrayList<>();
        actions.add(Map.of("name", "view", "label", "View", "icon", "eye", "action", "navigate", "target", metadata.effectivePath() + "/{id}"));
        actions.add(Map.of("name", "edit", "label", "Edit", "icon", "pencil", "action", "navigate", "target", metadata.effectivePath() + "/{id}/edit"));
        actions.add(Map.of("name", "delete", "label", "Delete", "icon", "trash", "action", "delete", "confirm", true, "confirmMessage", "Are you sure you want to delete this " + metadata.entityName().toLowerCase() + "?"));
        if (metadata.hasActions()) {
            for (ActionMetadata action : metadata.actions()) {
                Map<String, Object> a = new LinkedHashMap<>();
                a.put("name", action.name());
                a.put("label", action.name());
                a.put("action", "api");
                a.put("method", action.httpMethod());
                a.put("endpoint", metadata.effectivePath() + "/{id}/actions/" + NameCasing.kebab(action.name()));
                if (action.dangerous()) a.put("dangerous", true);
                if (action.requiresConfirmation()) a.put("confirm", true);
                actions.add(a);
            }
        }
        return actions;
    }

    private List<Map<String, Object>> buildFilters() {
        List<Map<String, Object>> filters = new ArrayList<>();
        if (metadata.hasFields()) {
            for (FieldMetadata field : metadata.fields()) {
                if (field.filterable()) {
                    filters.add(Map.of("name", field.name(), "label", field.displayName() != null ? field.displayName() : field.name(), "type", mapToFilterType(field)));
                }
            }
        }
        return filters;
    }

    private String mapToFilterType(FieldMetadata field) {
        String type = field.type();
        if (type == null) return "text";
        if (type.contains("boolean") || type.contains("Boolean")) return "boolean";
        if (type.contains("LocalDate") && !type.contains("DateTime")) return "date-range";
        if (type.contains("DateTime") || type.contains("Instant")) return "datetime-range";
        // Numeric range — covers every primitive + boxed numeric Java type
        // we map elsewhere as "number" in DslTypeMapper. double / float
        // were previously absent and silently fell through to "text",
        // breaking the range-filter contract.
        if (type.contains("int") || type.contains("Integer")
                || type.contains("long") || type.contains("Long")
                || type.contains("double") || type.contains("Double")
                || type.contains("float") || type.contains("Float")
                || type.contains("BigDecimal")) return "number-range";
        if (field.isEnum()) return "select";
        return "text";
    }

}
