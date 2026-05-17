package eu.exeris.tooling.codegen.java.dsl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.FieldMetadata;
import eu.exeris.sdk.sourcemodel.ast.RelationshipMetadata;
import eu.exeris.sdk.sourcemodel.ast.UIMetadata;
import eu.exeris.sdk.sourcemodel.ast.UIMetadata.AutocompleteConfig;
import eu.exeris.sdk.sourcemodel.ast.UIMetadata.ComponentType;
import eu.exeris.sdk.sourcemodel.ast.UIMetadata.SelectConfig;
import eu.exeris.sdk.sourcemodel.ast.UIMetadata.SelectOption;
import eu.exeris.sdk.sourcemodel.ast.UIMetadata.UIFieldMetadata;
import eu.exeris.sdk.sourcemodel.ast.UIMetadata.UIGroupMetadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Closes the remaining coverage gaps in {@link FormDslGenerator} that
 * {@link FormDslGeneratorTest} and {@link FormDslGeneratorBranchTest}
 * leave behind. Targets the surfaces that were measured at 0% coverage
 * in the per-method JaCoCo report (buildSelectConfig,
 * buildAutocompleteConfig, the writeXxxFormTo file-I/O wrappers) plus
 * the remaining branch holes in buildGroup, buildGridRows row-overflow,
 * buildLayoutConfig small-column case, humanize null/empty inputs,
 * isExcludedField hidden path, buildRelationshipAutocomplete
 * displayField override, getFieldUIOverride null-fieldOverrides path,
 * and buildForm validationSummary suppression.
 */
@DisplayName("FormDslGenerator — remaining coverage gaps")
class FormDslGeneratorGapsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ---------- buildAutocompleteConfig (12 lines / 4 branches were 0%) ----------

    @Test
    @DisplayName("buildAutocompleteConfig: searchEndpoint + createAction set → both emitted")
    void autocompleteConfigSearchEndpointAndCreateActionEmitted() throws IOException {
        AutocompleteConfig ac = new AutocompleteConfig(
                "Customer", "fullName", "id", "/api/customer/search",
                3, 50, true, "openCustomerForm");
        JsonNode field = fieldFromOverride("partnerId", uiOverrideWithAutocomplete("partnerId", ac));

        JsonNode emitted = field.get("autocomplete");
        assertThat(emitted.get("targetEntity").asText()).isEqualTo("Customer");
        assertThat(emitted.get("displayField").asText()).isEqualTo("fullName");
        assertThat(emitted.get("valueField").asText()).isEqualTo("id");
        assertThat(emitted.get("searchEndpoint").asText()).isEqualTo("/api/customer/search");
        assertThat(emitted.get("minChars").asInt()).isEqualTo(3);
        assertThat(emitted.get("maxResults").asInt()).isEqualTo(50);
        assertThat(emitted.get("allowCreate").asBoolean()).isTrue();
        assertThat(emitted.get("createAction").asText()).isEqualTo("openCustomerForm");
    }

    @Test
    @DisplayName("buildAutocompleteConfig: searchEndpoint null → endpoint key NOT emitted")
    void autocompleteConfigSearchEndpointOmittedWhenNull() throws IOException {
        AutocompleteConfig ac = new AutocompleteConfig(
                "Customer", "fullName", "id", null,
                2, 20, false, null);
        JsonNode field = fieldFromOverride("partnerId", uiOverrideWithAutocomplete("partnerId", ac));

        JsonNode emitted = field.get("autocomplete");
        assertThat(emitted.has("searchEndpoint")).isFalse();
        assertThat(emitted.has("createAction")).isFalse();
        assertThat(emitted.get("allowCreate").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("buildAutocompleteConfig: UI override beats the relationship-derived autocomplete")
    void autocompleteOverrideBeatsRelationshipDerived() throws IOException {
        // The field is BOTH a relationship and has an explicit override — the
        // override path must win (the relationship-derived autocomplete is
        // not consulted when uiOverride.autocomplete() is non-null).
        AutocompleteConfig ac = AutocompleteConfig.forEntity("OverrideTarget", "label");
        DomainMetadata meta = DomainMetadata.builder("Order", "com.example.domain")
                .fields(List.of(FieldMetadata.builder("ownerId", "UUID").build()))
                .relationships(List.of(RelationshipMetadata.builder("ownerId", "User").build()))
                .uiMetadata(UIMetadata.builder()
                        .fieldOverrides(List.of(uiOverrideWithAutocomplete("ownerId", ac)))
                        .build())
                .build();

        JsonNode field = firstFieldByName(
                MAPPER.readTree(new FormDslGenerator(meta).generateCreateForm()).get("rows"),
                "ownerId");

        assertThat(field.get("autocomplete").get("targetEntity").asText()).isEqualTo("OverrideTarget");
    }

    // ---------- buildSelectConfig (14 lines / 8 branches were 0%) ----------

    @Test
    @DisplayName("buildSelectConfig: endpoint + staticOptions + groupBy set → all keys emitted")
    void selectConfigFullySpecified() throws IOException {
        SelectConfig sc = new SelectConfig(
                "api",
                "/api/priorities",
                List.of(SelectOption.simple("LOW", "Low"), SelectOption.simple("HIGH", "High")),
                true, false, true, "category");
        JsonNode field = fieldFromOverride("priority", uiOverrideWithSelect("priority", sc));

        JsonNode select = field.get("select");
        assertThat(select.get("source").asText()).isEqualTo("api");
        assertThat(select.get("endpoint").asText()).isEqualTo("/api/priorities");
        assertThat(select.get("multiple").asBoolean()).isTrue();
        assertThat(select.get("clearable").asBoolean()).isFalse();
        assertThat(select.get("searchable").asBoolean()).isTrue();
        assertThat(select.get("groupBy").asText()).isEqualTo("category");
        JsonNode options = select.get("options");
        assertThat(options.isArray()).isTrue();
        assertThat(options).hasSize(2);
        assertThat(options.get(0).get("value").asText()).isEqualTo("LOW");
        assertThat(options.get(0).get("label").asText()).isEqualTo("Low");
    }

    @Test
    @DisplayName("buildSelectConfig: endpoint null + staticOptions null + groupBy null → optional keys omitted")
    void selectConfigMinimalSpec() throws IOException {
        SelectConfig sc = new SelectConfig(
                "enum:Status", null, null, false, true, false, null);
        JsonNode field = fieldFromOverride("status", uiOverrideWithSelect("status", sc));

        JsonNode select = field.get("select");
        assertThat(select.get("source").asText()).isEqualTo("enum:Status");
        assertThat(select.has("endpoint")).isFalse();
        assertThat(select.has("options")).isFalse();
        assertThat(select.has("groupBy")).isFalse();
        assertThat(select.get("multiple").asBoolean()).isFalse();
        assertThat(select.get("clearable").asBoolean()).isTrue();
        assertThat(select.get("searchable").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("buildSelectConfig: empty staticOptions list → options key still omitted")
    void selectConfigEmptyStaticOptionsOmitsKey() throws IOException {
        SelectConfig sc = new SelectConfig(
                "static", null, List.of(), false, true, false, null);
        JsonNode field = fieldFromOverride("status", uiOverrideWithSelect("status", sc));

        assertThat(field.get("select").has("options")).isFalse();
    }

    @Test
    @DisplayName("buildSelectConfig: UI override beats the enum-derived select")
    void selectOverrideBeatsEnumDerived() throws IOException {
        // The field is an enum AND has an explicit select override — the
        // override must win over the `field.isEnum()` fallback emission.
        SelectConfig sc = new SelectConfig("custom", null, null, false, false, false, null);
        DomainMetadata meta = DomainMetadata.builder("Order", "com.example.domain")
                .fields(List.of(FieldMetadata.builder("status", "OrderStatus")
                        .enumType("com.example.OrderStatus")
                        .build()))
                .uiMetadata(UIMetadata.builder()
                        .fieldOverrides(List.of(uiOverrideWithSelect("status", sc)))
                        .build())
                .build();

        JsonNode field = firstFieldByName(
                MAPPER.readTree(new FormDslGenerator(meta).generateCreateForm()).get("rows"),
                "status");

        assertThat(field.get("select").get("source").asText()).isEqualTo("custom");
    }

    // ---------- writeCreateFormTo / writeEditFormTo (6 lines were 0%) ----------

    @Test
    @DisplayName("writeCreateFormTo: writes <entityname>.create-form.json with the same content as generateCreateForm")
    void writeCreateFormToWritesNamedFileMatchingGeneratedContent(@TempDir Path tmp) throws IOException {
        DomainMetadata meta = simpleDomainWithOneField();
        FormDslGenerator gen = new FormDslGenerator(meta);

        gen.writeCreateFormTo(tmp);

        Path expected = tmp.resolve("order.create-form.json");
        assertThat(expected).exists();
        assertThat(Files.readString(expected)).isEqualTo(gen.generateCreateForm());
    }

    @Test
    @DisplayName("writeEditFormTo: writes <entityname>.edit-form.json with the same content as generateEditForm")
    void writeEditFormToWritesNamedFileMatchingGeneratedContent(@TempDir Path tmp) throws IOException {
        DomainMetadata meta = simpleDomainWithOneField();
        FormDslGenerator gen = new FormDslGenerator(meta);

        gen.writeEditFormTo(tmp);

        Path expected = tmp.resolve("order.edit-form.json");
        assertThat(expected).exists();
        assertThat(Files.readString(expected)).isEqualTo(gen.generateEditForm());
    }

    @Test
    @DisplayName("writeCreateFormTo: target directory is created if it does not yet exist")
    void writeCreateFormToCreatesMissingTargetDir(@TempDir Path tmp) throws IOException {
        DomainMetadata meta = simpleDomainWithOneField();
        Path missing = tmp.resolve("not/yet/created");

        new FormDslGenerator(meta).writeCreateFormTo(missing);

        assertThat(missing).isDirectory();
        assertThat(missing.resolve("order.create-form.json")).exists();
    }

    // ---------- buildGroup branches (description / icon / fields skip) ----------

    @Test
    @DisplayName("buildGroup: description blank → description key NOT emitted; icon blank → icon NOT emitted")
    void buildGroupBlankDescriptionAndIconOmitted() throws IOException {
        DomainMetadata meta = DomainMetadata.builder("Order", "com.example.domain")
                .fields(List.of(FieldMetadata.builder("orderNumber", "String").build()))
                .uiMetadata(UIMetadata.builder()
                        .groups(List.of(new UIGroupMetadata(
                                "main", "Main", "   ", 1, false, false, "  ", 12,
                                List.of("orderNumber"))))
                        .build())
                .build();

        JsonNode group = firstGroup(meta);

        assertThat(group.has("description")).isFalse();
        assertThat(group.has("icon")).isFalse();
        assertThat(group.get("label").asText()).isEqualTo("Main");
    }

    @Test
    @DisplayName("buildGroup: description null → description key NOT emitted; icon null → icon NOT emitted")
    void buildGroupNullDescriptionAndIconOmitted() throws IOException {
        DomainMetadata meta = DomainMetadata.builder("Order", "com.example.domain")
                .fields(List.of(FieldMetadata.builder("orderNumber", "String").build()))
                .uiMetadata(UIMetadata.builder()
                        .groups(List.of(new UIGroupMetadata(
                                "main", "Main", null, 1, false, false, null, 12,
                                List.of("orderNumber"))))
                        .build())
                .build();

        JsonNode group = firstGroup(meta);

        assertThat(group.has("description")).isFalse();
        assertThat(group.has("icon")).isFalse();
    }

    @Test
    @DisplayName("buildGroup: description + icon both populated → both emitted on the group object")
    void buildGroupDescriptionAndIconPropagate() throws IOException {
        DomainMetadata meta = DomainMetadata.builder("Order", "com.example.domain")
                .fields(List.of(FieldMetadata.builder("orderNumber", "String").build()))
                .uiMetadata(UIMetadata.builder()
                        .groups(List.of(new UIGroupMetadata(
                                "main", "Main", "Main section", 1, true, true, "settings", 6,
                                List.of("orderNumber"))))
                        .build())
                .build();

        JsonNode group = firstGroup(meta);

        assertThat(group.get("description").asText()).isEqualTo("Main section");
        assertThat(group.get("icon").asText()).isEqualTo("settings");
        assertThat(group.get("collapsible").asBoolean()).isTrue();
        assertThat(group.get("collapsed").asBoolean()).isTrue();
        assertThat(group.get("gridSpan").asInt()).isEqualTo(6);
    }

    @Test
    @DisplayName("buildGroup: field names that don't resolve via findField are silently dropped")
    void buildGroupSkipsUnknownFieldNames() throws IOException {
        DomainMetadata meta = DomainMetadata.builder("Order", "com.example.domain")
                .fields(List.of(FieldMetadata.builder("orderNumber", "String").build()))
                .uiMetadata(UIMetadata.builder()
                        .groups(List.of(UIGroupMetadata.simple("main", "Main",
                                List.of("orderNumber", "doesNotExist", "alsoMissing"))))
                        .build())
                .build();

        JsonNode group = firstGroup(meta);
        JsonNode fields = group.get("fields");

        assertThat(fields).hasSize(1);
        assertThat(fields.get(0).get("name").asText()).isEqualTo("orderNumber");
    }

    @Test
    @DisplayName("buildGroup: excluded fields (hidden / readOnly in create / id) are filtered out of the group")
    void buildGroupFiltersOutExcludedFields() throws IOException {
        DomainMetadata meta = DomainMetadata.builder("Order", "com.example.domain")
                .fields(List.of(
                        FieldMetadata.builder("orderNumber", "String").build(),
                        FieldMetadata.builder("id", "UUID").build(),
                        FieldMetadata.builder("hiddenField", "String").hidden(true).build(),
                        FieldMetadata.builder("auditTrail", "String").readOnly(true).build()))
                .uiMetadata(UIMetadata.builder()
                        .groups(List.of(UIGroupMetadata.simple("main", "Main",
                                List.of("orderNumber", "id", "hiddenField", "auditTrail"))))
                        .build())
                .build();

        JsonNode group = firstGroup(meta);
        JsonNode fields = group.get("fields");
        List<String> emittedNames = StreamSupport.stream(fields.spliterator(), false)
                .map(f -> f.get("name").asText())
                .toList();

        // Only orderNumber survives in CREATE mode (id always excluded, hidden
        // always excluded, readOnly excluded in CREATE).
        assertThat(emittedNames).containsExactly("orderNumber");
    }

    @Test
    @DisplayName("buildGroup: groups with null field list emit an empty fields array")
    void buildGroupWithNullFieldsList() throws IOException {
        DomainMetadata meta = DomainMetadata.builder("Order", "com.example.domain")
                .fields(List.of(FieldMetadata.builder("orderNumber", "String").build()))
                .uiMetadata(UIMetadata.builder()
                        .groups(List.of(new UIGroupMetadata(
                                "empty", "Empty", null, 1, false, false, null, 12,
                                null)))
                        .build())
                .build();

        JsonNode group = firstGroup(meta);

        assertThat(group.get("fields").isArray()).isTrue();
        assertThat(group.get("fields")).isEmpty();
    }

    // ---------- buildGridRows row overflow ----------

    @Test
    @DisplayName("buildGridRows: fields whose combined gridSpan exceeds 12 columns split into multiple rows")
    void buildGridRowsSplitsOnOverflow() throws IOException {
        // Three default-span fields = 18 columns total → must split into 2 rows
        // (first row holds the first two fields = 12 columns; the third spills).
        DomainMetadata meta = DomainMetadata.builder("Order", "com.example.domain")
                .fields(List.of(
                        FieldMetadata.builder("a", "String").build(),
                        FieldMetadata.builder("b", "String").build(),
                        FieldMetadata.builder("c", "String").build()))
                .build();

        JsonNode rows = MAPPER.readTree(new FormDslGenerator(meta).generateCreateForm())
                .get("rows");

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("fields")).hasSize(2);
        assertThat(rows.get(1).get("fields")).hasSize(1);
        assertThat(rows.get(1).get("fields").get(0).get("name").asText()).isEqualTo("c");
    }

    // ---------- buildLayoutConfig small-columns case ----------

    @Test
    @DisplayName("buildLayoutConfig: columns ≤ 2 → responsive.lg keeps the column count instead of halving")
    void buildLayoutConfigSmallColumnCount() throws IOException {
        DomainMetadata meta = DomainMetadata.builder("Order", "com.example.domain")
                .fields(List.of(FieldMetadata.builder("orderNumber", "String").build()))
                .uiMetadata(UIMetadata.builder().columns(2).build())
                .build();

        JsonNode form = MAPPER.readTree(new FormDslGenerator(meta).generateCreateForm());
        JsonNode responsive = form.get("layout").get("responsive");

        // columns = 2 hits the `: columns` branch (not `columns / 2 = 1`).
        assertThat(responsive.get("lg").asInt()).isEqualTo(2);
        assertThat(responsive.get("xl").asInt()).isEqualTo(2);
    }

    // ---------- humanize loop-body branches ----------

    @Test
    @DisplayName("humanize: loop body — uppercase chars get a leading space; all-lowercase input has none")
    void humanizeLoopBodyBranches() throws IOException {
        // humanize's null/empty short-circuits at lines 442-443 are
        // STRUCTURALLY UNREACHABLE through this code path: the only call
        // site is buildFormField passing field.name(), and FieldMetadata's
        // builder rejects null/empty names. What is exercisable — and
        // matters for catching a regression in the per-character loop
        // body — is the isUpperCase branch:
        //   * all-lowercase input never enters the if branch (label is
        //     first-char-upper + rest verbatim, no inserted spaces).
        //   * camelCase input enters the branch on every uppercase char
        //     (label has a space before each uppercase letter).
        DomainMetadata meta = DomainMetadata.builder("Order", "com.example.domain")
                .fields(List.of(
                        FieldMetadata.builder("active", "boolean").build(),       // all lowercase
                        FieldMetadata.builder("orderNumber", "String").build())) // camelCase
                .build();

        JsonNode rows = MAPPER.readTree(new FormDslGenerator(meta).generateCreateForm())
                .get("rows");
        Map<String, String> labels = new java.util.LinkedHashMap<>();
        rows.forEach(row -> row.get("fields").forEach(field ->
                labels.put(field.get("name").asText(), field.get("label").asText())));

        assertThat(labels.get("active")).isEqualTo("Active");
        assertThat(labels.get("orderNumber")).isEqualTo("Order Number");
    }

    // ---------- isExcludedField hidden() arm ----------

    @Test
    @DisplayName("isExcludedField: hidden field is excluded from BOTH create and edit forms")
    void hiddenFieldExcludedFromBothModes() throws IOException {
        DomainMetadata meta = DomainMetadata.builder("Order", "com.example.domain")
                .fields(List.of(
                        FieldMetadata.builder("orderNumber", "String").build(),
                        FieldMetadata.builder("secret", "String").hidden(true).build()))
                .build();
        FormDslGenerator gen = new FormDslGenerator(meta);

        for (String form : List.of(gen.generateCreateForm(), gen.generateEditForm())) {
            JsonNode rows = MAPPER.readTree(form).get("rows");
            List<String> names = StreamSupport.stream(rows.spliterator(), false)
                    .flatMap(row -> StreamSupport.stream(row.get("fields").spliterator(), false))
                    .map(f -> f.get("name").asText())
                    .toList();
            assertThat(names).contains("orderNumber").doesNotContain("secret");
        }
    }

    // ---------- buildRelationshipAutocomplete displayField branch ----------

    @Test
    @DisplayName("buildRelationshipAutocomplete: RelationshipMetadata.displayField set → used verbatim (no \"name\" fallback)")
    void relationshipDisplayFieldOverridesNameFallback() throws IOException {
        DomainMetadata meta = DomainMetadata.builder("Order", "com.example.domain")
                .fields(List.of(FieldMetadata.builder("ownerId", "UUID").build()))
                .relationships(List.of(
                        RelationshipMetadata.builder("ownerId", "User")
                                .displayField("emailAddress")
                                .build()))
                .build();

        JsonNode field = firstFieldByName(
                MAPPER.readTree(new FormDslGenerator(meta).generateCreateForm()).get("rows"),
                "ownerId");

        assertThat(field.get("autocomplete").get("displayField").asText()).isEqualTo("emailAddress");
    }

    // ---------- getFieldUIOverride: uiMeta exists but fieldOverrides null ----------

    @Test
    @DisplayName("getFieldUIOverride: uiMetadata present but with no fieldOverrides → falls through to field defaults")
    void uiMetadataWithoutFieldOverridesUsesDefaults() throws IOException {
        DomainMetadata meta = DomainMetadata.builder("Order", "com.example.domain")
                .fields(List.of(FieldMetadata.builder("orderNumber", "String").build()))
                // UIMetadata built but fieldOverrides explicitly set to null.
                // Canonical ctor mirrors UIMetadata.defaults() — only the last
                // arg (fieldOverrides) differs, set to null to exercise the
                // getFieldUIOverride null-guard branch.
                .uiMetadata(new UIMetadata(
                        null, null, true, true, true, true, true, true, false, false,
                        12, "grid", List.of(), null /* fieldOverrides */))
                .build();

        JsonNode field = firstFieldByName(
                MAPPER.readTree(new FormDslGenerator(meta).generateCreateForm()).get("rows"),
                "orderNumber");

        // No override → label is humanized fieldName, no placeholder, default span.
        assertThat(field.get("label").asText()).isEqualTo("Order Number");
        assertThat(field.has("placeholder")).isFalse();
        assertThat(field.get("gridSpan").asInt()).isEqualTo(6);
    }

    // ---------- buildForm: hasFields() == false → no validationSummary ----------

    @Test
    @DisplayName("buildForm: domain with no fields → no validationSummary key, and rows is the empty fallback")
    void emptyDomainSuppressesValidationSummary() throws IOException {
        DomainMetadata meta = DomainMetadata.builder("Empty", "com.example.domain").build();

        JsonNode form = MAPPER.readTree(new FormDslGenerator(meta).generateCreateForm());

        assertThat(form.has("validationSummary")).isFalse();
        assertThat(form.has("rows")).isTrue();
        assertThat(form.get("rows")).isEmpty();
    }

    // ---------- mapToComponent compound-`||` alternative arms ----------

    @Test
    @DisplayName("mapToComponent: each alternative arm of every compound `||` short-circuit is taken")
    void mapToComponentEveryDisjunctionArmCovered() throws IOException {
        // FormDslGeneratorBranchTest hits the FIRST true arm of each `||`
        // chain. JaCoCo also wants the OTHER arms exercised as the
        // "becomes-true-after-earlier-false" path. These types fill in the
        // gaps:
        //   - "Boolean" hits boolean||Boolean's second arm (existing test
        //     uses lowercase "boolean").
        //   - "Integer" / "long" / "Long" hit the numeric chain's mid arms
        //     (existing test uses "int" / "BigDecimal" / "Double").
        // Note: OffsetDateTime contains the "DateTime" substring, so it
        // hits the FIRST arm of DateTime||OffsetDateTime — the second arm
        // is structurally unreachable through Java type names emitted by
        // the SDK processor and is not covered here.
        DomainMetadata meta = DomainMetadata.builder("Order", "com.example.domain")
                .fields(List.of(
                        FieldMetadata.builder("flag", "Boolean").build(),
                        FieldMetadata.builder("counter", "Integer").build(),
                        FieldMetadata.builder("rowId", "long").build(),
                        FieldMetadata.builder("sequence", "Long").build()))
                .build();

        JsonNode rows = MAPPER.readTree(new FormDslGenerator(meta).generateCreateForm())
                .get("rows");
        Map<String, String> components = new java.util.LinkedHashMap<>();
        rows.forEach(row -> row.get("fields").forEach(field ->
                components.put(field.get("name").asText(), field.get("component").asText())));

        assertThat(components).contains(
                Map.entry("flag", "checkbox"),
                Map.entry("counter", "number-input"),
                Map.entry("rowId", "number-input"),
                Map.entry("sequence", "number-input"));
    }

    // ---------- helpers ----------

    private static DomainMetadata simpleDomainWithOneField() {
        return DomainMetadata.builder("Order", "com.example.domain")
                .fields(List.of(FieldMetadata.builder("orderNumber", "String").build()))
                .build();
    }

    private static UIFieldMetadata uiOverrideWithAutocomplete(String fieldName, AutocompleteConfig ac) {
        return new UIFieldMetadata(
                fieldName, ComponentType.AUTOCOMPLETE, 0, 0, true, true, true,
                null, null, null, null, null, null, ac, null);
    }

    private static UIFieldMetadata uiOverrideWithSelect(String fieldName, SelectConfig sc) {
        return new UIFieldMetadata(
                fieldName, ComponentType.SELECT, 0, 0, true, true, true,
                null, null, null, null, null, null, null, sc);
    }

    /** Build a Domain with a single field that carries the supplied UI override, and emit the form. */
    private static JsonNode fieldFromOverride(String fieldName, UIFieldMetadata override) throws IOException {
        DomainMetadata meta = DomainMetadata.builder("Order", "com.example.domain")
                .fields(List.of(FieldMetadata.builder(fieldName, "String").build()))
                .uiMetadata(UIMetadata.builder().fieldOverrides(List.of(override)).build())
                .build();
        JsonNode rows = MAPPER.readTree(new FormDslGenerator(meta).generateCreateForm()).get("rows");
        return firstFieldByName(rows, fieldName);
    }

    private static JsonNode firstGroup(DomainMetadata meta) throws IOException {
        JsonNode form = MAPPER.readTree(new FormDslGenerator(meta).generateCreateForm());
        JsonNode groups = form.get("groups");
        assertThat(groups).as("buildForm must emit a 'groups' array when uiMetadata.groups() is non-empty").isNotNull();
        assertThat(groups).isNotEmpty();
        return groups.get(0);
    }

    private static JsonNode firstFieldByName(JsonNode rows, String name) {
        return StreamSupport.stream(rows.spliterator(), false)
                .flatMap(row -> StreamSupport.stream(row.get("fields").spliterator(), false))
                .filter(f -> name.equals(f.get("name").asText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Field not found: " + name));
    }
}
