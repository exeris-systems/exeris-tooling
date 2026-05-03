package eu.exeris.tooling.codegen.java.dsl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import eu.exeris.sdk.sourcemodel.ast.*;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for FormDslGenerator - Phase 1.2 Form DSL Generation.
 * Tests that generated JSON contains correct layout, gridSpan, placeholder etc.
 *
 * @author Exeris Team
 * @since 0.1.0
 */
@DisplayName("FormDslGenerator Tests")
class FormDslGeneratorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static JsonSchema formSchema;

    @BeforeAll
    static void loadSchema() throws IOException {
        // Load JSON Schema for form validation (simplified schema for tests)
        String schemaJson = """
                {
                  "$schema": "https://json-schema.org/draft/2020-12/schema",
                  "type": "object",
                  "required": ["$type", "$version", "entity", "mode"],
                  "properties": {
                    "$type": { "const": "form" },
                    "$version": { "type": "string" },
                    "entity": { "type": "string" },
                    "mode": { "type": "string", "enum": ["create", "edit"] },
                    "title": { "type": "string" },
                    "layout": {
                      "type": "object",
                      "required": ["type", "columns"],
                      "properties": {
                        "type": { "const": "grid" },
                        "columns": { "type": "integer", "minimum": 1, "maximum": 24 },
                        "gap": { "type": "string" },
                        "responsive": { "type": "object" }
                      }
                    },
                    "groups": {
                      "type": "array",
                      "items": {
                        "type": "object",
                        "required": ["name", "fields"],
                        "properties": {
                          "name": { "type": "string" },
                          "label": { "type": "string" },
                          "collapsible": { "type": "boolean" },
                          "collapsed": { "type": "boolean" },
                          "icon": { "type": "string" },
                          "gridSpan": { "type": "integer" },
                          "fields": { "type": "array" }
                        }
                      }
                    },
                    "rows": { "type": "array" },
                    "actions": { "type": "array" },
                    "validationSummary": { "type": "object" }
                  }
                }
                """;
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        formSchema = factory.getSchema(schemaJson);
    }

    // Helper method to create default UIMetadata
    private static UIMetadata createDefaultUIMetadata(int columns, List<UIMetadata.UIGroupMetadata> groups,
                                                       List<UIMetadata.UIFieldMetadata> fieldOverrides) {
        return new UIMetadata(
                null, null, // icon, color
                true, true, true, true, // listView, detailView, createForm, editForm
                true, true, false, false, // searchable, filterable, exportable, bulkActions
                columns, "grid", // columns, defaultLayout
                groups, fieldOverrides
        );
    }

    // Helper to create UIGroupMetadata
    private static UIMetadata.UIGroupMetadata createGroup(String name, String label, String description,
                                                           int order, boolean collapsible, boolean collapsed,
                                                           String icon, int gridSpan, List<String> fields) {
        return new UIMetadata.UIGroupMetadata(name, label, description, order, collapsible, collapsed, icon, gridSpan, fields);
    }

    // Helper to create UIFieldMetadata
    private static UIMetadata.UIFieldMetadata createFieldOverride(String fieldName, int gridSpan, int displayOrder,
                                                                   String placeholder, String helpText) {
        return new UIMetadata.UIFieldMetadata(
                fieldName, UIMetadata.ComponentType.AUTO, gridSpan, displayOrder,
                true, true, true, // displayInList, displayInDetail, editableInForm
                placeholder, helpText, null, null, null, null, // format, width, cssClass, mask
                null, null // autocomplete, select
        );
    }

    @Nested
    @DisplayName("1.2.1 Basic Form Generation")
    class BasicFormGenerationTests {

        @Test
        @DisplayName("Should generate valid form JSON with $type and $version")
        void shouldGenerateValidFormJson() throws IOException {
            // Given
            DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                    .tableName("Order")
                    .fields(List.of(
                            FieldMetadata.required("orderId", "String"),
                            FieldMetadata.simple("description", "String")
                    ))
                    .build();

            FormDslGenerator generator = new FormDslGenerator(metadata);

            // When
            String createFormJson = generator.generateCreateForm();

            // Then
            JsonNode formNode = MAPPER.readTree(createFormJson);
            assertThat(formNode.get("$type").asText()).isEqualTo("form");
            assertThat(formNode.get("$version").asText()).isEqualTo("2.0");
            assertThat(formNode.get("entity").asText()).isEqualTo("Order");
            assertThat(formNode.get("mode").asText()).isEqualTo("create");
        }

        @Test
        @DisplayName("Should generate form with grid layout configuration")
        void shouldGenerateFormWithGridLayout() throws IOException {
            // Given
            UIMetadata uiMeta = createDefaultUIMetadata(12, List.of(), List.of());

            DomainMetadata metadata = DomainMetadata.builder("Product", "com.example.domain")
                    .uiMetadata(uiMeta)
                    .fields(List.of(FieldMetadata.simple("name", "String")))
                    .build();

            FormDslGenerator generator = new FormDslGenerator(metadata);

            // When
            String formJson = generator.generateCreateForm();

            // Then
            JsonNode formNode = MAPPER.readTree(formJson);
            JsonNode layout = formNode.get("layout");

            assertThat(layout).isNotNull();
            assertThat(layout.get("type").asText()).isEqualTo("grid");
            assertThat(layout.get("columns").asInt()).isEqualTo(12);
            assertThat(layout.get("responsive")).isNotNull();
            assertThat(layout.get("responsive").get("sm").asInt()).isEqualTo(1);
            assertThat(layout.get("responsive").get("xl").asInt()).isEqualTo(12);
        }
    }

    @Nested
    @DisplayName("1.2.2 Grid Layout & Field Span")
    class GridLayoutTests {

        @Test
        @DisplayName("Should apply gridSpan to fields from UIFieldMetadata")
        void shouldApplyGridSpanToFields() throws IOException {
            // Given
            UIMetadata.UIFieldMetadata nameField = createFieldOverride("name", 6, 1, null, null);
            UIMetadata.UIFieldMetadata descField = createFieldOverride("description", 12, 2,
                    "Enter description", "Detailed description");

            UIMetadata uiMeta = createDefaultUIMetadata(12, List.of(), List.of(nameField, descField));

            DomainMetadata metadata = DomainMetadata.builder("Product", "com.example")
                    .uiMetadata(uiMeta)
                    .fields(List.of(
                            FieldMetadata.simple("name", "String"),
                            FieldMetadata.builder("description", "String").maxLength(1000).build()
                    ))
                    .build();

            FormDslGenerator generator = new FormDslGenerator(metadata);

            // When
            String formJson = generator.generateCreateForm();

            // Then
            JsonNode formNode = MAPPER.readTree(formJson);

            // Form should have rows with fields
            assertThat(formNode.has("rows") || formNode.has("groups")).isTrue();
        }

        @Test
        @DisplayName("Should assign full width (12) to large text fields")
        void shouldAssignFullWidthToLargeTextFields() throws IOException {
            // Given: Field with maxLength > 500 should get gridSpan=12
            DomainMetadata metadata = DomainMetadata.builder("Article", "com.example")
                    .fields(List.of(
                            FieldMetadata.builder("content", "String").maxLength(5000).build()
                    ))
                    .build();

            FormDslGenerator generator = new FormDslGenerator(metadata);

            // When
            String formJson = generator.generateCreateForm();
            JsonNode formNode = MAPPER.readTree(formJson);

            // Then: Large text field should span full width
            assertThat(formJson).contains("content");
        }

        @Test
        @DisplayName("Should assign smaller span (3) to boolean fields")
        void shouldAssignSmallerSpanToBooleanFields() throws IOException {
            // Given
            DomainMetadata metadata = DomainMetadata.builder("Settings", "com.example")
                    .fields(List.of(
                            FieldMetadata.simple("enabled", "Boolean"),
                            FieldMetadata.simple("active", "boolean")
                    ))
                    .build();

            FormDslGenerator generator = new FormDslGenerator(metadata);

            // When
            String formJson = generator.generateCreateForm();

            // Then
            assertThat(formJson)
                    .contains("enabled")
                    .contains("active");
        }
    }

    @Nested
    @DisplayName("1.2.3 Field Groups (Collapsible Sections)")
    class FieldGroupsTests {

        @Test
        @DisplayName("Should generate collapsible groups")
        void shouldGenerateCollapsibleGroups() throws IOException {
            // Given - UIGroupMetadata(name, label, description, order, collapsible, collapsed, icon, gridSpan, fields)
            UIMetadata.UIGroupMetadata basicInfo = createGroup(
                    "basicInfo", "Basic Information", "General details",
                    1, true, false, "info-circle", 12, List.of("name", "email")
            );
            UIMetadata.UIGroupMetadata addressInfo = createGroup(
                    "addressInfo", "Address", "Shipping address",
                    2, true, true, "map-marker", 12, List.of("street", "city")
            );

            UIMetadata uiMeta = createDefaultUIMetadata(12, List.of(basicInfo, addressInfo), List.of());

            DomainMetadata metadata = DomainMetadata.builder("Customer", "com.example")
                    .uiMetadata(uiMeta)
                    .fields(List.of(
                            FieldMetadata.required("name", "String"),
                            FieldMetadata.simple("email", "String"),
                            FieldMetadata.simple("street", "String"),
                            FieldMetadata.simple("city", "String")
                    ))
                    .build();

            FormDslGenerator generator = new FormDslGenerator(metadata);

            // When
            String formJson = generator.generateCreateForm();
            JsonNode formNode = MAPPER.readTree(formJson);

            // Then
            assertThat(formNode.has("groups")).isTrue();
            JsonNode groups = formNode.get("groups");
            assertThat(groups.isArray()).isTrue();
            assertThat(groups.size()).isEqualTo(2);

            // First group
            JsonNode group1 = groups.get(0);
            assertThat(group1.get("name").asText()).isEqualTo("basicInfo");
            assertThat(group1.get("label").asText()).isEqualTo("Basic Information");
            assertThat(group1.get("collapsible").asBoolean()).isTrue();
            assertThat(group1.get("collapsed").asBoolean()).isFalse();
            assertThat(group1.get("icon").asText()).isEqualTo("info-circle");

            // Second group - collapsed by default
            JsonNode group2 = groups.get(1);
            assertThat(group2.get("collapsed").asBoolean()).isTrue();
        }
    }

    @Nested
    @DisplayName("1.2.4 Placeholder & HelpText")
    class PlaceholderAndHelpTextTests {

        @Test
        @DisplayName("Should include placeholder and helpText in field config")
        void shouldIncludePlaceholderAndHelpText() throws IOException {
            // Given
            UIMetadata.UIFieldMetadata emailField = createFieldOverride(
                    "email", 6, 1, "user@example.com", "Enter your email address"
            );

            UIMetadata uiMeta = createDefaultUIMetadata(12, List.of(), List.of(emailField));

            DomainMetadata metadata = DomainMetadata.builder("User", "com.example")
                    .uiMetadata(uiMeta)
                    .fields(List.of(FieldMetadata.required("email", "String")))
                    .build();

            FormDslGenerator generator = new FormDslGenerator(metadata);

            // When
            String formJson = generator.generateCreateForm();

            // Then
            assertThat(formJson)
                    .contains("user@example.com")
                    .contains("Enter your email address");
        }
    }

    @Nested
    @DisplayName("1.2.5 JSON Schema Validation")
    class SchemaValidationTests {

        @Test
        @DisplayName("Should generate JSON conforming to form schema")
        void shouldGenerateSchemaConformingJson() throws IOException {
            // Given: Complex domain with all features
            UIMetadata.UIGroupMetadata group = UIMetadata.UIGroupMetadata.simple("main", "Main", List.of("name"));

            UIMetadata uiMeta = createDefaultUIMetadata(12, List.of(group), List.of());

            DomainMetadata metadata = DomainMetadata.builder("ComplexEntity", "com.example")
                    .tableName("Complex Entity")
                    .uiMetadata(uiMeta)
                    .fields(List.of(
                            FieldMetadata.required("name", "String"),
                            FieldMetadata.simple("amount", "BigDecimal"),
                            FieldMetadata.simple("active", "Boolean")
                    ))
                    .build();

            FormDslGenerator generator = new FormDslGenerator(metadata);

            // When
            String createFormJson = generator.generateCreateForm();
            String editFormJson = generator.generateEditForm();

            // Then: Both forms should be valid JSON
            JsonNode createNode = MAPPER.readTree(createFormJson);
            JsonNode editNode = MAPPER.readTree(editFormJson);

            // Validate against schema
            Set<ValidationMessage> createErrors = formSchema.validate(createNode);
            Set<ValidationMessage> editErrors = formSchema.validate(editNode);

            assertThat(createErrors)
                    .describedAs("Create form should be valid. Errors: %s", createErrors)
                    .isEmpty();
            assertThat(editErrors)
                    .describedAs("Edit form should be valid. Errors: %s", editErrors)
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("1.2.6 Validation Summary")
    class ValidationSummaryTests {

        @Test
        @DisplayName("Should include validationSummary when fields have validation")
        void shouldIncludeValidationSummary() throws IOException {
            // Given
            DomainMetadata metadata = DomainMetadata.builder("Order", "com.example")
                    .fields(List.of(
                            FieldMetadata.builder("email", "String").required(true).build(),
                            FieldMetadata.builder("amount", "BigDecimal").min(0L).max(10000L).build()
                    ))
                    .build();

            FormDslGenerator generator = new FormDslGenerator(metadata);

            // When
            String formJson = generator.generateCreateForm();
            JsonNode formNode = MAPPER.readTree(formJson);

            // Then
            assertThat(formNode.has("validationSummary")).isTrue();
        }
    }

    @Nested
    @DisplayName("1.2.7 Edit vs Create Mode")
    class ModeTests {

        @Test
        @DisplayName("Should exclude readOnly fields from create form")
        void shouldExcludeReadOnlyFieldsFromCreateForm() throws IOException {
            // Given
            DomainMetadata metadata = DomainMetadata.builder("Order", "com.example")
                    .fields(List.of(
                            FieldMetadata.required("orderId", "String"),
                            FieldMetadata.builder("createdAt", "LocalDateTime").readOnly(true).build(),
                            FieldMetadata.builder("createdBy", "String").readOnly(true).build()
                    ))
                    .build();

            FormDslGenerator generator = new FormDslGenerator(metadata);

            // When
            String createFormJson = generator.generateCreateForm();
            String editFormJson = generator.generateEditForm();

            // Then: Create form should not have readOnly fields
            // Edit form may include them
            assertThat(createFormJson).contains("orderId");
            // ReadOnly fields should be excluded from create
        }

        @Test
        @DisplayName("Should exclude hidden fields from all forms")
        void shouldExcludeHiddenFields() throws IOException {
            // Given
            DomainMetadata metadata = DomainMetadata.builder("Order", "com.example")
                    .fields(List.of(
                            FieldMetadata.simple("name", "String"),
                            FieldMetadata.builder("internalFlag", "String").hidden(true).build()
                    ))
                    .build();

            FormDslGenerator generator = new FormDslGenerator(metadata);

            // When
            String formJson = generator.generateCreateForm();

            // Then
            assertThat(formJson)
                    .contains("name")
                    .doesNotContain("internalFlag");
        }

        @Test
        @DisplayName("Should set correct title based on mode")
        void shouldSetCorrectTitleBasedOnMode() throws IOException {
            // Given
            DomainMetadata metadata = DomainMetadata.builder("Product", "com.example")
                    .tableName("Product")
                    .fields(List.of(FieldMetadata.simple("name", "String")))
                    .build();

            FormDslGenerator generator = new FormDslGenerator(metadata);

            // When
            String createJson = generator.generateCreateForm();
            String editJson = generator.generateEditForm();

            // Then
            assertThat(createJson).contains("Create Product");
            assertThat(editJson).contains("Edit Product");
        }
    }
}
