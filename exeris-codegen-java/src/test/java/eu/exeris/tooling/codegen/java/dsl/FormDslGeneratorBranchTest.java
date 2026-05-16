package eu.exeris.tooling.codegen.java.dsl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.FieldMetadata;
import eu.exeris.sdk.sourcemodel.ast.RelationshipMetadata;
import eu.exeris.sdk.sourcemodel.ast.UIMetadata;
import eu.exeris.sdk.sourcemodel.ast.UIMetadata.ComponentType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Branch-coverage boost for {@link FormDslGenerator}.
 *
 * <p>{@link FormDslGeneratorTest} (the prior test class) covers the
 * happy-path forms: basic shape, grid layout, groups, placeholders,
 * validation summary, edit/create mode toggles. This class targets the
 * type-mapping and component-dispatch branches it does not exercise —
 * the kind of code paths that go silently dead when a regression flips
 * one of them to the wrong constant.
 */
@DisplayName("FormDslGenerator — branch coverage")
class FormDslGeneratorBranchTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Lightweight UIFieldMetadata builder (record's canonical ctor is long). */
    private static UIMetadata.UIFieldMetadata override(String fieldName, ComponentType type) {
        return new UIMetadata.UIFieldMetadata(
                fieldName, type, 0, 0, true, true, true,
                null, null, null, null, null, null, null, null);
    }

    private static UIMetadata.UIFieldMetadata override(String fieldName, ComponentType type, int gridSpan) {
        return new UIMetadata.UIFieldMetadata(
                fieldName, type, gridSpan, 0, true, true, true,
                null, null, null, null, null, null, null, null);
    }

    private static UIMetadata.UIFieldMetadata override(String fieldName, String placeholder, String helpText, String cssClass) {
        return new UIMetadata.UIFieldMetadata(
                fieldName, ComponentType.AUTO, 0, 0, true, true, true,
                placeholder, helpText, null, null, cssClass, null, null, null);
    }

    @Test
    @DisplayName("Constructor rejects null metadata with NullPointerException")
    void constructorRejectsNullMetadata() {
        assertThatThrownBy(() -> new FormDslGenerator(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("mapToComponent: boolean / LocalDate / DateTime / Instant / LocalTime / numeric → matching component")
    void mapToComponentTypeBranches() throws IOException {
        // FieldMetadata.Builder rejects null type, so the
        // "type == null → text-input" branch in mapToComponent is dead
        // code through the public API and is not exercised here.
        DomainMetadata meta = DomainMetadata.builder("Order", "com.example.domain")
                .fields(List.of(
                        FieldMetadata.builder("active", "boolean").build(),
                        FieldMetadata.builder("placedOn", "LocalDate").build(),
                        FieldMetadata.builder("approvedAt", "LocalDateTime").build(),
                        FieldMetadata.builder("offset", "OffsetDateTime").build(),
                        FieldMetadata.builder("eventAt", "Instant").build(),
                        FieldMetadata.builder("openTime", "LocalTime").build(),
                        FieldMetadata.builder("count", "int").build(),
                        FieldMetadata.builder("price", "BigDecimal").build(),
                        FieldMetadata.builder("ratio", "Double").build()))
                .build();

        Map<String, String> components = collectComponents(meta);

        assertThat(components).contains(
                Map.entry("active", "checkbox"),
                Map.entry("placedOn", "date-picker"),
                Map.entry("approvedAt", "datetime-picker"),
                Map.entry("offset", "datetime-picker"),
                Map.entry("eventAt", "datetime-picker"),
                Map.entry("openTime", "time-picker"),
                Map.entry("count", "number-input"),
                Map.entry("price", "number-input"),
                Map.entry("ratio", "number-input"));
    }

    @Test
    @DisplayName("mapToComponent: maxLength > 500 → rich-text-editor, > 255 → textarea")
    void mapToComponentMaxLengthBranches() throws IOException {
        DomainMetadata meta = DomainMetadata.builder("Order", "com.example.domain")
                .fields(List.of(
                        FieldMetadata.builder("notes", "String").maxLength(1000).build(),
                        FieldMetadata.builder("summary", "String").maxLength(400).build(),
                        FieldMetadata.builder("title", "String").maxLength(100).build()))
                .build();

        Map<String, String> components = collectComponents(meta);

        assertThat(components).contains(
                Map.entry("notes", "rich-text-editor"),
                Map.entry("summary", "textarea"),
                Map.entry("title", "text-input"));
    }

    @Test
    @DisplayName("mapToComponent: format-driven mapping (email / phone / url / password / currency / color / code / unknown)")
    void mapToComponentFormatBranches() throws IOException {
        DomainMetadata meta = DomainMetadata.builder("Order", "com.example.domain")
                .fields(List.of(
                        FieldMetadata.builder("email", "String").format("email").build(),
                        FieldMetadata.builder("phone", "String").format("phone").build(),
                        FieldMetadata.builder("phoneAlt", "String").format("tel").build(),
                        FieldMetadata.builder("homepage", "String").format("url").build(),
                        FieldMetadata.builder("uri", "String").format("uri").build(),
                        FieldMetadata.builder("password", "String").format("password").build(),
                        FieldMetadata.builder("amount", "String").format("currency").build(),
                        FieldMetadata.builder("amount2", "String").format("money").build(),
                        FieldMetadata.builder("hex", "String").format("color").build(),
                        FieldMetadata.builder("snippet", "String").format("code").build(),
                        FieldMetadata.builder("other", "String").format("custom-fmt").build()))
                .build();

        Map<String, String> components = collectComponents(meta);

        assertThat(components).contains(
                Map.entry("email", "email-input"),
                Map.entry("phone", "phone-input"),
                Map.entry("phoneAlt", "phone-input"),
                Map.entry("homepage", "url-input"),
                Map.entry("uri", "url-input"),
                Map.entry("password", "password-input"),
                Map.entry("amount", "currency-input"),
                Map.entry("amount2", "currency-input"),
                Map.entry("hex", "color-picker"),
                Map.entry("snippet", "code-editor"),
                Map.entry("other", "text-input"));
    }

    @Test
    @DisplayName("mapToComponent: explicit ComponentType override on the UI field beats type-driven inference")
    void componentTypeOverrideBeatsTypeInference() throws IOException {
        DomainMetadata meta = DomainMetadata.builder("Order", "com.example.domain")
                .fields(List.of(FieldMetadata.builder("notes", "String").build()))
                .uiMetadata(UIMetadata.builder()
                        .fieldOverrides(List.of(override("notes", ComponentType.RICH_TEXT_EDITOR)))
                        .build())
                .build();

        Map<String, String> components = collectComponents(meta);

        assertThat(components).containsEntry("notes", "rich-text-editor");
    }

    @Test
    @DisplayName("componentTypeToString: every ComponentType maps to a non-empty kebab-case string")
    void componentTypeToStringFullEnumDispatch() throws IOException {
        // Build one field per ComponentType value (except AUTO, which is
        // the signal for "infer", and produces a different code path).
        List<ComponentType> testable = java.util.Arrays.stream(ComponentType.values())
                .filter(t -> t != ComponentType.AUTO)
                .toList();

        List<FieldMetadata> fields = new java.util.ArrayList<>();
        List<UIMetadata.UIFieldMetadata> overrides = new java.util.ArrayList<>();
        for (ComponentType t : testable) {
            String fieldName = "f_" + t.name().toLowerCase();
            fields.add(FieldMetadata.builder(fieldName, "String").build());
            overrides.add(override(fieldName, t));
        }

        DomainMetadata meta = DomainMetadata.builder("Order", "com.example.domain")
                .fields(fields)
                .uiMetadata(UIMetadata.builder().fieldOverrides(overrides).build())
                .build();

        Map<String, String> components = collectComponents(meta);
        // No assertion on exact strings (those vary per case in the switch)
        // — just that every value emits a non-blank, kebab-case-looking
        // string, so the dispatch is exercised.
        assertThat(components.values()).allSatisfy(s ->
                assertThat(s).isNotBlank().doesNotContainPattern("\\s"));
    }

    @Test
    @DisplayName("Grid span: large text (maxLength > 500) → full 12, boolean → 3, default → 6")
    void gridSpanBranches() throws IOException {
        DomainMetadata meta = DomainMetadata.builder("Order", "com.example.domain")
                .fields(List.of(
                        FieldMetadata.builder("notes", "String").maxLength(1000).build(),
                        FieldMetadata.builder("active", "boolean").build(),
                        FieldMetadata.builder("orderNumber", "String").build()))
                .build();

        JsonNode rows = MAPPER.readTree(new FormDslGenerator(meta).generateCreateForm())
                .get("rows");
        Map<String, Integer> spans = collectGridSpans(rows);

        assertThat(spans).contains(
                Map.entry("notes", 12),
                Map.entry("active", 3),
                Map.entry("orderNumber", 6));
    }

    @Test
    @DisplayName("Grid span: UIFieldMetadata.gridSpan override (positive) wins over type-driven default")
    void gridSpanOverride() throws IOException {
        DomainMetadata meta = DomainMetadata.builder("Order", "com.example.domain")
                .fields(List.of(FieldMetadata.builder("active", "boolean").build()))
                .uiMetadata(UIMetadata.builder()
                        .fieldOverrides(List.of(override("active", ComponentType.AUTO, 8)))
                        .build())
                .build();

        JsonNode rows = MAPPER.readTree(new FormDslGenerator(meta).generateCreateForm())
                .get("rows");

        assertThat(collectGridSpans(rows)).containsEntry("active", 8);
    }

    @Test
    @DisplayName("isExcludedField: readOnly field appears in EDIT form but not CREATE")
    void readOnlyExclusionDependsOnMode() throws IOException {
        DomainMetadata meta = DomainMetadata.builder("Order", "com.example.domain")
                .fields(List.of(
                        FieldMetadata.builder("orderNumber", "String").build(),
                        FieldMetadata.builder("auditTrail", "String").readOnly(true).build()))
                .build();
        FormDslGenerator gen = new FormDslGenerator(meta);

        Map<String, String> createComponents = collectComponents(
                MAPPER.readTree(gen.generateCreateForm()));
        Map<String, String> editComponents = collectComponents(
                MAPPER.readTree(gen.generateEditForm()));

        assertThat(createComponents).doesNotContainKey("auditTrail");
        assertThat(editComponents).containsKey("auditTrail");
    }

    @Test
    @DisplayName("Validation block: required / minLength / maxLength / min / max / pattern all surface when set")
    void validationBlockBranches() throws IOException {
        DomainMetadata meta = DomainMetadata.builder("Order", "com.example.domain")
                .fields(List.of(FieldMetadata.builder("orderNumber", "String")
                        .required(true)
                        .minLength(3).maxLength(20)
                        .min(1L).max(100L)
                        .pattern("^ORD-\\d+$")
                        .build()))
                .build();

        JsonNode rows = MAPPER.readTree(new FormDslGenerator(meta).generateCreateForm())
                .get("rows");
        JsonNode validation = firstFieldByName(rows, "orderNumber").get("validation");

        assertThat(validation.get("required").asBoolean()).isTrue();
        assertThat(validation.get("minLength").asInt()).isEqualTo(3);
        assertThat(validation.get("maxLength").asInt()).isEqualTo(20);
        assertThat(validation.get("min").asLong()).isEqualTo(1L);
        assertThat(validation.get("max").asLong()).isEqualTo(100L);
        assertThat(validation.get("pattern").asText()).isEqualTo("^ORD-\\d+$");
    }

    @Test
    @DisplayName("Relationship field: emits autocomplete config sourced from RelationshipMetadata (default displayField=\"name\")")
    void relationshipFieldEmitsAutocomplete() throws IOException {
        DomainMetadata meta = DomainMetadata.builder("Order", "com.example.domain")
                .fields(List.of(FieldMetadata.builder("ownerId", "UUID").build()))
                .relationships(List.of(
                        RelationshipMetadata.builder("ownerId", "User").build()))
                .build();

        JsonNode field = firstFieldByName(
                MAPPER.readTree(new FormDslGenerator(meta).generateCreateForm()).get("rows"),
                "ownerId");
        JsonNode autocomplete = field.get("autocomplete");

        assertThat(autocomplete.get("targetEntity").asText()).isEqualTo("User");
        assertThat(autocomplete.get("displayField").asText()).isEqualTo("name");
        assertThat(autocomplete.get("valueField").asText()).isEqualTo("id");
        assertThat(autocomplete.get("searchEndpoint").asText()).isEqualTo("/api/user/search");
    }

    @Test
    @DisplayName("Enum field emits a select config rooted at \"enum:<EnumType>\"")
    void enumFieldEmitsSelect() throws IOException {
        DomainMetadata meta = DomainMetadata.builder("Order", "com.example.domain")
                .fields(List.of(FieldMetadata.builder("status", "OrderStatus")
                        .enumType("com.example.OrderStatus")
                        .build()))
                .build();

        JsonNode field = firstFieldByName(
                MAPPER.readTree(new FormDslGenerator(meta).generateCreateForm()).get("rows"),
                "status");

        assertThat(field.get("component").asText()).isEqualTo("select");
        assertThat(field.get("select").get("source").asText())
                .isEqualTo("enum:com.example.OrderStatus");
        assertThat(field.get("select").get("clearable").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("UI overrides: placeholder, helpText, cssClass propagate to the emitted field")
    void uiOverridesPropagate() throws IOException {
        DomainMetadata meta = DomainMetadata.builder("Order", "com.example.domain")
                .fields(List.of(FieldMetadata.builder("orderNumber", "String").build()))
                .uiMetadata(UIMetadata.builder()
                        .fieldOverrides(List.of(
                                override("orderNumber", "Enter ID", "Unique business ID", "highlight")))
                        .build())
                .build();

        JsonNode field = firstFieldByName(
                MAPPER.readTree(new FormDslGenerator(meta).generateCreateForm()).get("rows"),
                "orderNumber");

        assertThat(field.get("placeholder").asText()).isEqualTo("Enter ID");
        assertThat(field.get("helpText").asText()).isEqualTo("Unique business ID");
        assertThat(field.get("cssClass").asText()).isEqualTo("highlight");
    }

    @Test
    @DisplayName("helpText falls back to field.description() when UI override does not supply it")
    void helpTextFallsBackToFieldDescription() throws IOException {
        DomainMetadata meta = DomainMetadata.builder("Order", "com.example.domain")
                .fields(List.of(
                        FieldMetadata.builder("orderNumber", "String")
                                .description("Business order number").build()))
                .build();

        JsonNode field = firstFieldByName(
                MAPPER.readTree(new FormDslGenerator(meta).generateCreateForm()).get("rows"),
                "orderNumber");

        assertThat(field.get("helpText").asText()).isEqualTo("Business order number");
    }

    @Test
    @DisplayName("readOnly field in EDIT form carries the readOnly:true marker on the emitted entry")
    void readOnlyMarkerEmittedInEditMode() throws IOException {
        DomainMetadata meta = DomainMetadata.builder("Order", "com.example.domain")
                .fields(List.of(
                        FieldMetadata.builder("auditTrail", "String").readOnly(true).build()))
                .build();

        JsonNode field = firstFieldByName(
                MAPPER.readTree(new FormDslGenerator(meta).generateEditForm()).get("rows"),
                "auditTrail");

        assertThat(field.get("readOnly").asBoolean()).isTrue();
    }

    // ---------- helpers ----------

    /** Collect emitted component name keyed by field.name(), across all rows. */
    private static Map<String, String> collectComponents(DomainMetadata meta) throws IOException {
        JsonNode form = MAPPER.readTree(new FormDslGenerator(meta).generateCreateForm());
        return collectComponents(form);
    }

    private static Map<String, String> collectComponents(JsonNode form) {
        Map<String, String> components = new java.util.LinkedHashMap<>();
        JsonNode rows = form.get("rows");
        if (rows == null) return components;
        rows.forEach(row -> row.get("fields").forEach(field ->
                components.put(field.get("name").asText(), field.get("component").asText())));
        return components;
    }

    private static Map<String, Integer> collectGridSpans(JsonNode rows) {
        Map<String, Integer> spans = new java.util.LinkedHashMap<>();
        rows.forEach(row -> row.get("fields").forEach(field ->
                spans.put(field.get("name").asText(), field.get("gridSpan").asInt())));
        return spans;
    }

    private static JsonNode firstFieldByName(JsonNode rows, String name) {
        return StreamSupport.stream(rows.spliterator(), false)
                .flatMap(row -> StreamSupport.stream(row.get("fields").spliterator(), false))
                .filter(f -> name.equals(f.get("name").asText()))
                .findFirst()
                .orElseThrow();
    }
}
