package eu.exeris.tooling.codegen.java.dsl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.exeris.sdk.sourcemodel.ast.ActionMetadata;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.FieldMetadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TableDslGenerator")
class TableDslGeneratorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir Path tempDir;

    @Test
    @DisplayName("Table skeleton: $type / entity / title / apiPath / pagination / sorting")
    void tableSkeleton() throws IOException {
        DomainMetadata meta = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders")
                .build();

        JsonNode table = MAPPER.readTree(new TableDslGenerator(meta).generate());

        assertThat(table.get("$type").asText()).isEqualTo("table");
        assertThat(table.get("entity").asText()).isEqualTo("Order");
        assertThat(table.get("title").asText()).isEqualTo("Orders");
        assertThat(table.get("apiPath").asText()).isEqualTo("/orders");

        JsonNode pagination = table.get("pagination");
        assertThat(pagination.get("enabled").asBoolean()).isTrue();
        assertThat(pagination.get("pageSize").asInt()).isEqualTo(20);
        assertThat(pagination.get("pageSizeOptions")).hasSize(4);

        JsonNode sorting = table.get("sorting");
        assertThat(sorting.get("defaultSort").asText()).isEqualTo("createdAt");
        assertThat(sorting.get("defaultOrder").asText()).isEqualTo("desc");
    }

    @Test
    @DisplayName("Columns: id and hidden fields are filtered out; createdAt is always appended")
    void columnsFilterIdAndHidden() throws IOException {
        DomainMetadata meta = DomainMetadata.builder("Order", "com.example.domain")
                .fields(List.of(
                        FieldMetadata.builder("id", "UUID").build(),
                        FieldMetadata.builder("orderNumber", "String").build(),
                        FieldMetadata.builder("internal", "String").hidden(true).build()))
                .build();

        JsonNode columns = MAPPER.readTree(new TableDslGenerator(meta).generate())
                .get("columns");

        List<String> names = StreamSupport.stream(columns.spliterator(), false)
                .map(c -> c.get("name").asText())
                .toList();
        assertThat(names).containsExactly("orderNumber", "createdAt");
    }

    @Test
    @DisplayName("Column labels fall back to field name when displayName is unset; type via DslTypeMapper")
    void columnLabelAndTypeMapping() throws IOException {
        DomainMetadata meta = DomainMetadata.builder("Order", "com.example.domain")
                .fields(List.of(
                        FieldMetadata.builder("amount", "BigDecimal")
                                .displayName("Total Amount").sortable(true).filterable(true).build(),
                        FieldMetadata.builder("orderNumber", "String").build()))
                .build();

        JsonNode columns = MAPPER.readTree(new TableDslGenerator(meta).generate())
                .get("columns");

        JsonNode amount = columns.get(0);
        assertThat(amount.get("label").asText()).isEqualTo("Total Amount");
        assertThat(amount.get("type").asText()).isEqualTo("number");
        assertThat(amount.get("sortable").asBoolean()).isTrue();
        assertThat(amount.get("filterable").asBoolean()).isTrue();

        JsonNode orderNumber = columns.get(1);
        // displayName unset → label falls back to name.
        assertThat(orderNumber.get("label").asText()).isEqualTo("orderNumber");
        assertThat(orderNumber.get("type").asText()).isEqualTo("string");
    }

    @Test
    @DisplayName("Row actions: always-present view / edit / delete; domain actions appended with dangerous/confirm flags")
    void rowActions() throws IOException {
        DomainMetadata meta = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders")
                .actions(List.of(
                        ActionMetadata.builder("approve")
                                .httpMethod("POST")
                                .requiresConfirmation(true)
                                .build(),
                        ActionMetadata.builder("cancel")
                                .httpMethod("DELETE")
                                .dangerous(true)
                                .build()))
                .build();

        JsonNode actions = MAPPER.readTree(new TableDslGenerator(meta).generate())
                .get("rowActions");

        assertThat(actions).hasSize(5); // 3 baseline + 2 domain
        assertThat(actions.get(0).get("name").asText()).isEqualTo("view");
        assertThat(actions.get(1).get("name").asText()).isEqualTo("edit");
        assertThat(actions.get(2).get("name").asText()).isEqualTo("delete");
        assertThat(actions.get(2).get("confirmMessage").asText())
                .contains("Are you sure")
                .contains("order");

        JsonNode approve = actions.get(3);
        assertThat(approve.get("name").asText()).isEqualTo("approve");
        assertThat(approve.get("endpoint").asText()).isEqualTo("/orders/{id}/actions/approve");
        assertThat(approve.get("confirm").asBoolean()).isTrue();
        assertThat(approve.has("dangerous")).isFalse();

        JsonNode cancel = actions.get(4);
        assertThat(cancel.get("dangerous").asBoolean()).isTrue();
        assertThat(cancel.has("confirm")).isFalse();
    }

    @Test
    @DisplayName("Domain action endpoints kebab-case the camelCase action name")
    void rowActionEndpointsKebabCased() throws IOException {
        DomainMetadata meta = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders")
                .actions(List.of(
                        ActionMetadata.builder("forceRefund").httpMethod("POST").build()))
                .build();

        JsonNode actions = MAPPER.readTree(new TableDslGenerator(meta).generate())
                .get("rowActions");

        assertThat(actions.get(3).get("endpoint").asText())
                .isEqualTo("/orders/{id}/actions/force-refund");
    }

    @Test
    @DisplayName("Filters: only filterable fields included; filter type derived from Java type / enum flag")
    void filterTypeMapping() throws IOException {
        DomainMetadata meta = DomainMetadata.builder("Order", "com.example.domain")
                .fields(List.of(
                        // Not filterable → excluded.
                        FieldMetadata.builder("secret", "String").build(),
                        // Boolean → "boolean".
                        FieldMetadata.builder("active", "boolean").filterable(true).build(),
                        // LocalDate → "date-range".
                        FieldMetadata.builder("placedOn", "LocalDate").filterable(true).build(),
                        // Instant → "datetime-range".
                        FieldMetadata.builder("at", "Instant").filterable(true).build(),
                        // long → "number-range".
                        FieldMetadata.builder("count", "long").filterable(true).build(),
                        // Enum-flagged field → "select".
                        FieldMetadata.builder("status", "OrderStatus")
                                .filterable(true).enumType("com.example.OrderStatus").build(),
                        // Plain string → "text".
                        FieldMetadata.builder("description", "String").filterable(true).build()))
                .build();

        JsonNode filters = MAPPER.readTree(new TableDslGenerator(meta).generate())
                .get("filters");

        assertThat(filters).hasSize(6);
        assertThat(filters.get(0).get("type").asText()).isEqualTo("boolean");
        assertThat(filters.get(1).get("type").asText()).isEqualTo("date-range");
        assertThat(filters.get(2).get("type").asText()).isEqualTo("datetime-range");
        assertThat(filters.get(3).get("type").asText()).isEqualTo("number-range");
        assertThat(filters.get(4).get("type").asText()).isEqualTo("select");
        assertThat(filters.get(5).get("type").asText()).isEqualTo("text");
    }

    @Test
    @DisplayName("Filter label falls back to field name when displayName is unset")
    void filterLabelFallback() throws IOException {
        DomainMetadata meta = DomainMetadata.builder("Order", "com.example.domain")
                .fields(List.of(
                        FieldMetadata.builder("orderNumber", "String")
                                .filterable(true).displayName("Order #").build(),
                        FieldMetadata.builder("amount", "BigDecimal").filterable(true).build()))
                .build();

        JsonNode filters = MAPPER.readTree(new TableDslGenerator(meta).generate())
                .get("filters");

        assertThat(filters.get(0).get("label").asText()).isEqualTo("Order #");
        assertThat(filters.get(1).get("label").asText()).isEqualTo("amount");
    }

    @Test
    @DisplayName("writeTo creates the directory and writes <entity>.table.json")
    void writeToCreatesFile() throws IOException {
        Path nested = tempDir.resolve("out/tables");
        DomainMetadata meta = DomainMetadata.builder("Order", "com.example.domain").build();

        new TableDslGenerator(meta).writeTo(nested);

        Path expected = nested.resolve("order.table.json");
        assertThat(Files.exists(expected)).isTrue();
        assertThat(MAPPER.readTree(Files.readString(expected)).get("$type").asText())
                .isEqualTo("table");
    }
}
