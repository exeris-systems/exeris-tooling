package eu.exeris.tooling.codegen.java.dsl;

import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.FieldMetadata;
import eu.exeris.sdk.sourcemodel.ast.RelationshipMetadata;
import eu.exeris.sdk.sourcemodel.ast.UIMetadata;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Generates form DSL JSON for Atom v3 UI with Grid Layout support.
 * Supports:
 * - Grid-based layouts with configurable columns
 * - Field groups (collapsible sections)
 * - Advanced component types (autocomplete, rich text, etc.)
 * - Conditional field visibility
 * - Relationship handling with autocomplete
 *
 * @author Exeris Team
 * @since 0.1.0
 */
public final class FormDslGenerator {

    private static final int DEFAULT_GRID_COLUMNS = 12;
    private static final int DEFAULT_FIELD_SPAN = 6;

    private final DomainMetadata metadata;
    private final ObjectMapper mapper;

    public FormDslGenerator(DomainMetadata metadata) {
        this.metadata = Objects.requireNonNull(metadata);
        this.mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    public String generateCreateForm() throws IOException {
        return mapper.writeValueAsString(buildForm("create"));
    }

    public String generateEditForm() throws IOException {
        return mapper.writeValueAsString(buildForm("edit"));
    }

    public void writeCreateFormTo(Path outputPath) throws IOException {
        Files.createDirectories(outputPath);
        Files.writeString(outputPath.resolve(metadata.entityName().toLowerCase() + ".create-form.json"), generateCreateForm());
    }

    public void writeEditFormTo(Path outputPath) throws IOException {
        Files.createDirectories(outputPath);
        Files.writeString(outputPath.resolve(metadata.entityName().toLowerCase() + ".edit-form.json"), generateEditForm());
    }

    private Map<String, Object> buildForm(String mode) {
        Map<String, Object> form = new LinkedHashMap<>();
        form.put("$type", "form");
        form.put("$version", "2.0");
        form.put("entity", metadata.entityName());
        form.put("mode", mode);
        form.put("title", ("create".equals(mode) ? "Create " : "Edit ") + metadata.entityName());

        // Grid layout configuration
        UIMetadata uiMeta = metadata.uiMetadata();
        int gridColumns = (uiMeta != null) ? uiMeta.columns() : DEFAULT_GRID_COLUMNS;
        form.put("layout", buildLayoutConfig(gridColumns));

        // Build groups and fields
        if (uiMeta != null && uiMeta.groups() != null && !uiMeta.groups().isEmpty()) {
            form.put("groups", buildGroups(uiMeta, mode));
        } else {
            // Fallback: flat field list with grid layout
            form.put("rows", buildGridRows(mode));
        }

        form.put("actions", buildFormActions(mode));

        // Add validation summary
        if (metadata.hasFields()) {
            form.put("validationSummary", buildValidationSummary());
        }

        return form;
    }

    private Map<String, Object> buildLayoutConfig(int columns) {
        return Map.of(
                "type", "grid",
                "columns", columns,
                "gap", "16px",
                "responsive", Map.of(
                        "sm", 1,
                        "md", 2,
                        "lg", columns > 2 ? columns / 2 : columns,
                        "xl", columns
                )
        );
    }

    private List<Map<String, Object>> buildGroups(UIMetadata uiMeta, String mode) {
        return uiMeta.groups().stream()
                .sorted(Comparator.comparingInt(UIMetadata.UIGroupMetadata::order))
                .map(group -> buildGroup(group, mode))
                .collect(Collectors.toList());
    }

    private Map<String, Object> buildGroup(UIMetadata.UIGroupMetadata group, String mode) {
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("name", group.name());
        g.put("label", group.label());
        if (group.description() != null && !group.description().isBlank()) {
            g.put("description", group.description());
        }
        g.put("collapsible", group.collapsible());
        g.put("collapsed", group.collapsed());
        if (group.icon() != null && !group.icon().isBlank()) {
            g.put("icon", group.icon());
        }
        g.put("gridSpan", group.gridSpan());

        // Build fields for this group
        List<Map<String, Object>> groupFields = new ArrayList<>();
        if (group.fields() != null && metadata.hasFields()) {
            for (String fieldName : group.fields()) {
                metadata.findField(fieldName).ifPresent(field -> {
                    if (!isExcludedField(field, mode)) {
                        groupFields.add(buildFormField(field, getFieldUIOverride(fieldName)));
                    }
                });
            }
        }
        g.put("fields", groupFields);

        return g;
    }

    private List<Map<String, Object>> buildGridRows(String mode) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (!metadata.hasFields()) return rows;

        List<FieldMetadata> visibleFields = metadata.fields().stream()
                .filter(f -> !isExcludedField(f, mode))
                .sorted(Comparator.comparingInt(this::getFieldOrder))
                .toList();

        // Group fields into rows based on grid span
        Map<String, Object> currentRow = new LinkedHashMap<>();
        List<Map<String, Object>> rowFields = new ArrayList<>();
        int currentSpan = 0;

        for (FieldMetadata field : visibleFields) {
            int fieldSpan = getFieldGridSpan(field);
            if (currentSpan + fieldSpan > DEFAULT_GRID_COLUMNS && !rowFields.isEmpty()) {
                currentRow.put("fields", new ArrayList<>(rowFields));
                rows.add(currentRow);
                currentRow = new LinkedHashMap<>();
                rowFields = new ArrayList<>();
                currentSpan = 0;
            }
            rowFields.add(buildFormField(field, getFieldUIOverride(field.name())));
            currentSpan += fieldSpan;
        }

        if (!rowFields.isEmpty()) {
            currentRow.put("fields", rowFields);
            rows.add(currentRow);
        }

        return rows;
    }

    private boolean isExcludedField(FieldMetadata field, String mode) {
        if ("id".equals(field.name())) return true;
        if (field.hidden()) return true;
        if (field.readOnly() && "create".equals(mode)) return true;
        return false;
    }

    private int getFieldOrder(FieldMetadata field) {
        UIMetadata.UIFieldMetadata override = getFieldUIOverride(field.name());
        return (override != null) ? override.displayOrder() : 999;
    }

    private int getFieldGridSpan(FieldMetadata field) {
        UIMetadata.UIFieldMetadata override = getFieldUIOverride(field.name());
        if (override != null && override.gridSpan() > 0) {
            return override.gridSpan();
        }
        // Large text fields get full width
        if (field.maxLength() != null && field.maxLength() > 500) return 12;
        // Booleans are smaller
        if (field.isBoolean()) return 3;
        return DEFAULT_FIELD_SPAN;
    }

    private UIMetadata.UIFieldMetadata getFieldUIOverride(String fieldName) {
        UIMetadata uiMeta = metadata.uiMetadata();
        if (uiMeta == null || uiMeta.fieldOverrides() == null) return null;
        return uiMeta.fieldOverrides().stream()
                .filter(f -> fieldName.equals(f.fieldName()))
                .findFirst()
                .orElse(null);
    }

    private Map<String, Object> buildFormField(FieldMetadata field, UIMetadata.UIFieldMetadata uiOverride) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("name", field.name());
        f.put("label", field.displayName() != null ? field.displayName() : humanize(field.name()));
        f.put("component", mapToComponent(field, uiOverride));
        f.put("gridSpan", getFieldGridSpan(field));
        f.put("required", field.required());

        // Placeholder and help text
        String placeholder = (uiOverride != null && uiOverride.placeholder() != null)
                ? uiOverride.placeholder()
                : null;
        if (placeholder != null) f.put("placeholder", placeholder);

        String helpText = (uiOverride != null && uiOverride.helpText() != null)
                ? uiOverride.helpText()
                : field.description();
        if (helpText != null) f.put("helpText", helpText);

        // CSS class
        if (uiOverride != null && uiOverride.cssClass() != null) {
            f.put("cssClass", uiOverride.cssClass());
        }

        // Read-only
        if (field.readOnly()) {
            f.put("readOnly", true);
        }

        // Autocomplete config for relationships
        if (uiOverride != null && uiOverride.autocomplete() != null) {
            f.put("autocomplete", buildAutocompleteConfig(uiOverride.autocomplete()));
        } else if (isRelationshipField(field.name())) {
            f.put("autocomplete", buildRelationshipAutocomplete(field.name()));
        }

        // Select config
        if (uiOverride != null && uiOverride.select() != null) {
            f.put("select", buildSelectConfig(uiOverride.select()));
        } else if (field.isEnum()) {
            f.put("select", Map.of("source", "enum:" + field.enumType(), "clearable", true));
        }

        // Validation
        Map<String, Object> validation = buildValidation(field);
        if (!validation.isEmpty()) {
            f.put("validation", validation);
        }

        return f;
    }

    private String mapToComponent(FieldMetadata field, UIMetadata.UIFieldMetadata uiOverride) {
        // Explicit override takes priority
        if (uiOverride != null && uiOverride.componentType() != null
                && uiOverride.componentType() != UIMetadata.ComponentType.AUTO) {
            // CUSTOM (SDK B4 escape hatch) names an app-supplied control via
            // customComponent(); emit that name, falling back to "custom".
            if (uiOverride.componentType() == UIMetadata.ComponentType.CUSTOM) {
                String custom = uiOverride.customComponent();
                return (custom != null && !custom.isBlank()) ? custom : "custom";
            }
            return componentTypeToString(uiOverride.componentType());
        }

        String type = field.type();
        if (type == null) return "text-input";

        // Enum handling
        if (field.isEnum()) return "select";

        // Relationship handling
        if (isRelationshipField(field.name())) return "autocomplete";

        // Type-based mapping
        if (type.contains("boolean") || type.contains("Boolean")) return "checkbox";
        if (type.contains("LocalDate") && !type.contains("DateTime")) return "date-picker";
        if (type.contains("DateTime") || type.contains("OffsetDateTime")) return "datetime-picker";
        if (type.contains("Instant")) return "datetime-picker";
        if (type.contains("LocalTime")) return "time-picker";
        if (type.contains("int") || type.contains("Integer") || type.contains("long") ||
                type.contains("Long") || type.contains("BigDecimal") || type.contains("Double")) {
            return "number-input";
        }

        // Length-based mapping
        if (field.maxLength() != null) {
            if (field.maxLength() > 500) return "rich-text-editor";
            if (field.maxLength() > 255) return "textarea";
        }

        // Format-based mapping
        if (field.format() != null) {
            return switch (field.format().toLowerCase()) {
                case "email" -> "email-input";
                case "phone", "tel" -> "phone-input";
                case "url", "uri" -> "url-input";
                case "password" -> "password-input";
                case "currency", "money" -> "currency-input";
                case "color" -> "color-picker";
                case "code" -> "code-editor";
                default -> "text-input";
            };
        }

        return "text-input";
    }

    private String componentTypeToString(UIMetadata.ComponentType type) {
        return switch (type) {
            case TEXT_INPUT -> "text-input";
            case TEXT_AREA -> "textarea";
            case NUMBER_INPUT -> "number-input";
            case DATE_PICKER -> "date-picker";
            case DATETIME_PICKER -> "datetime-picker";
            case TIME_PICKER -> "time-picker";
            case CHECKBOX -> "checkbox";
            case TOGGLE -> "toggle";
            case SELECT -> "select";
            case MULTI_SELECT -> "multi-select";
            case RADIO_GROUP -> "radio-group";
            case AUTOCOMPLETE -> "autocomplete";
            case FILE_UPLOAD -> "file-upload";
            case IMAGE_UPLOAD -> "image-upload";
            case RICH_TEXT_EDITOR -> "rich-text-editor";
            case CODE_EDITOR -> "code-editor";
            case COLOR_PICKER -> "color-picker";
            case SLIDER -> "slider";
            case RATING -> "rating";
            case CHIPS -> "chips";
            case PASSWORD -> "password-input";
            case EMAIL -> "email-input";
            case PHONE -> "phone-input";
            case URL -> "url-input";
            case CURRENCY -> "currency-input";
            case HIDDEN -> "hidden";
            case CUSTOM -> "custom";
            default -> "text-input";
        };
    }

    private boolean isRelationshipField(String fieldName) {
        if (!metadata.hasRelationships()) return false;
        return metadata.relationships().stream()
                .anyMatch(r -> fieldName.equals(r.fieldName()));
    }

    private Map<String, Object> buildRelationshipAutocomplete(String fieldName) {
        return metadata.relationships().stream()
                .filter(r -> fieldName.equals(r.fieldName()))
                .findFirst()
                .map(r -> {
                    Map<String, Object> config = new LinkedHashMap<>();
                    config.put("targetEntity", r.targetEntity());
                    config.put("displayField", r.displayField() != null ? r.displayField() : "name");
                    config.put("valueField", "id");
                    config.put("searchEndpoint", "/api/" + r.targetEntity().toLowerCase() + "/search");
                    config.put("minChars", 2);
                    config.put("maxResults", 20);
                    return config;
                })
                .orElse(Map.of());
    }

    private Map<String, Object> buildAutocompleteConfig(UIMetadata.AutocompleteConfig config) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("targetEntity", config.targetEntity());
        c.put("displayField", config.displayField());
        c.put("valueField", config.valueField());
        if (config.searchEndpoint() != null) {
            c.put("searchEndpoint", config.searchEndpoint());
        }
        c.put("minChars", config.minChars());
        c.put("maxResults", config.maxResults());
        c.put("allowCreate", config.allowCreate());
        if (config.createAction() != null) {
            c.put("createAction", config.createAction());
        }
        return c;
    }

    private Map<String, Object> buildSelectConfig(UIMetadata.SelectConfig config) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("source", config.optionsSource());
        if (config.optionsEndpoint() != null) {
            c.put("endpoint", config.optionsEndpoint());
        }
        if (config.staticOptions() != null && !config.staticOptions().isEmpty()) {
            c.put("options", config.staticOptions().stream()
                    .map(o -> Map.of("value", o.value(), "label", o.label()))
                    .toList());
        }
        c.put("multiple", config.multiple());
        c.put("clearable", config.clearable());
        c.put("searchable", config.searchable());
        if (config.groupBy() != null) {
            c.put("groupBy", config.groupBy());
        }
        return c;
    }

    private Map<String, Object> buildValidation(FieldMetadata field) {
        Map<String, Object> validation = new LinkedHashMap<>();
        if (field.required()) validation.put("required", true);
        if (field.minLength() != null) validation.put("minLength", field.minLength());
        if (field.maxLength() != null) validation.put("maxLength", field.maxLength());
        if (field.min() != null) validation.put("min", field.min());
        if (field.max() != null) validation.put("max", field.max());
        if (field.pattern() != null) validation.put("pattern", field.pattern());
        return validation;
    }

    private Map<String, Object> buildValidationSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("showOnSubmit", true);
        summary.put("scrollToFirst", true);
        summary.put("highlightFields", true);
        return summary;
    }

    private List<Map<String, Object>> buildFormActions(String mode) {
        List<Map<String, Object>> actions = new ArrayList<>();
        actions.add(Map.of(
                "name", "cancel",
                "label", "Cancel",
                "type", "secondary",
                "action", "cancel",
                "icon", "close"
        ));
        actions.add(Map.of(
                "name", "submit",
                "label", "create".equals(mode) ? "Create" : "Save",
                "type", "primary",
                "action", "submit",
                "icon", "create".equals(mode) ? "add" : "save"
        ));
        return actions;
    }

    private String humanize(String camelCase) {
        if (camelCase == null || camelCase.isEmpty()) return camelCase;
        StringBuilder result = new StringBuilder();
        result.append(Character.toUpperCase(camelCase.charAt(0)));
        for (int i = 1; i < camelCase.length(); i++) {
            char c = camelCase.charAt(i);
            if (Character.isUpperCase(c)) {
                result.append(' ');
            }
            result.append(c);
        }
        return result.toString();
    }
}
