package eu.exeris.tooling.codegen.java.dsl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.FieldMetadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("EntitySchemaGenerator")
class EntitySchemaGeneratorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir Path tempDir;

    @Test
    @DisplayName("Constructor rejects null metadata")
    void constructorRejectsNullMetadata() {
        assertThatThrownBy(() -> new EntitySchemaGenerator(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("metadata cannot be null");
    }

    @Test
    @DisplayName("Minimal schema includes $id, $schema, title, type, and the always-present id property")
    void minimalSchemaSkeleton() throws IOException {
        DomainMetadata meta = DomainMetadata.builder("Order", "com.example.domain").build();

        JsonNode schema = MAPPER.readTree(new EntitySchemaGenerator(meta).generate());

        assertThat(schema.get("$id").asText()).isEqualTo("https://exeris.eu/schemas/order");
        assertThat(schema.get("$schema").asText())
                .isEqualTo("https://json-schema.org/draft/2020-12/schema");
        assertThat(schema.get("title").asText()).isEqualTo("Order");
        assertThat(schema.get("type").asText()).isEqualTo("object");
        // DomainMetadata.Builder defaults description to "" (not null) so
        // the schema includes the key with an empty value — assert that
        // explicitly rather than expecting omission.
        assertThat(schema.get("description").asText()).isEmpty();
        // id property always emitted, as uuid string.
        JsonNode id = schema.get("properties").get("id");
        assertThat(id.get("type").asText()).isEqualTo("string");
        assertThat(id.get("format").asText()).isEqualTo("uuid");
        // No required[] when no required fields.
        assertThat(schema.has("required")).isFalse();
    }

    @Test
    @DisplayName("description is included when domain metadata provides it")
    void descriptionIncluded() throws IOException {
        DomainMetadata meta = DomainMetadata.builder("Order", "com.example.domain")
                .description("Customer order entity")
                .build();

        JsonNode schema = MAPPER.readTree(new EntitySchemaGenerator(meta).generate());

        assertThat(schema.get("description").asText()).isEqualTo("Customer order entity");
    }

    @Test
    @DisplayName("Field schema: int / long → integer, double / float / BigDecimal → number, boolean → boolean, default → string")
    void fieldSchemaTypeMapping() throws IOException {
        DomainMetadata meta = DomainMetadata.builder("Order", "com.example.domain")
                .fields(List.of(
                        FieldMetadata.builder("count", "int").build(),
                        FieldMetadata.builder("longId", "long").build(),
                        FieldMetadata.builder("amount", "BigDecimal").build(),
                        FieldMetadata.builder("active", "boolean").build(),
                        FieldMetadata.builder("name", "String").build()))
                .build();

        JsonNode properties = MAPPER.readTree(new EntitySchemaGenerator(meta).generate())
                .get("properties");

        assertThat(properties.get("count").get("type").asText()).isEqualTo("integer");
        assertThat(properties.get("longId").get("type").asText()).isEqualTo("integer");
        assertThat(properties.get("amount").get("type").asText()).isEqualTo("number");
        assertThat(properties.get("active").get("type").asText()).isEqualTo("boolean");
        assertThat(properties.get("name").get("type").asText()).isEqualTo("string");
    }

    @Test
    @DisplayName("Field schema format: UUID, LocalDate, Instant/DateTime → JSON-Schema formats")
    void fieldSchemaFormatMapping() throws IOException {
        DomainMetadata meta = DomainMetadata.builder("Order", "com.example.domain")
                .fields(List.of(
                        FieldMetadata.builder("ref", "UUID").build(),
                        FieldMetadata.builder("date", "LocalDate").build(),
                        FieldMetadata.builder("at", "Instant").build(),
                        FieldMetadata.builder("dt", "LocalDateTime").build(),
                        FieldMetadata.builder("plain", "String").build()))
                .build();

        JsonNode properties = MAPPER.readTree(new EntitySchemaGenerator(meta).generate())
                .get("properties");

        assertThat(properties.get("ref").get("format").asText()).isEqualTo("uuid");
        assertThat(properties.get("date").get("format").asText()).isEqualTo("date");
        assertThat(properties.get("at").get("format").asText()).isEqualTo("date-time");
        assertThat(properties.get("dt").get("format").asText()).isEqualTo("date-time");
        // No format mapping for plain String.
        assertThat(properties.get("plain").has("format")).isFalse();
    }

    @Test
    @DisplayName("Field schema: optional metadata (title, description, length, range, pattern) is included only when present")
    void fieldSchemaOptionalMetadata() throws IOException {
        DomainMetadata meta = DomainMetadata.builder("Order", "com.example.domain")
                .fields(List.of(
                        FieldMetadata.builder("orderNumber", "String")
                                .displayName("Order #")
                                .description("Unique business identifier")
                                .minLength(3)
                                .maxLength(20)
                                .pattern("^ORD-\\d+$")
                                .build(),
                        FieldMetadata.builder("quantity", "int")
                                .min(1L)
                                .max(1000L)
                                .build()))
                .build();

        JsonNode properties = MAPPER.readTree(new EntitySchemaGenerator(meta).generate())
                .get("properties");

        JsonNode orderNumber = properties.get("orderNumber");
        assertThat(orderNumber.get("title").asText()).isEqualTo("Order #");
        assertThat(orderNumber.get("description").asText()).isEqualTo("Unique business identifier");
        assertThat(orderNumber.get("minLength").asInt()).isEqualTo(3);
        assertThat(orderNumber.get("maxLength").asInt()).isEqualTo(20);
        assertThat(orderNumber.get("pattern").asText()).isEqualTo("^ORD-\\d+$");

        JsonNode quantity = properties.get("quantity");
        assertThat(quantity.get("minimum").asLong()).isEqualTo(1L);
        assertThat(quantity.get("maximum").asLong()).isEqualTo(1000L);
    }

    @Test
    @DisplayName("required[] lists every field flagged required")
    void requiredArrayCollectsRequiredFields() throws IOException {
        DomainMetadata meta = DomainMetadata.builder("Order", "com.example.domain")
                .fields(List.of(
                        FieldMetadata.builder("a", "String").required(true).build(),
                        FieldMetadata.builder("b", "String").build(),
                        FieldMetadata.builder("c", "String").required(true).build()))
                .build();

        JsonNode schema = MAPPER.readTree(new EntitySchemaGenerator(meta).generate());

        JsonNode required = schema.get("required");
        assertThat(required.isArray()).isTrue();
        assertThat(required).hasSize(2);
        assertThat(required.get(0).asText()).isEqualTo("a");
        assertThat(required.get(1).asText()).isEqualTo("c");
    }

    @Test
    @DisplayName("writeTo creates the directory and writes <entity>.schema.json")
    void writeToCreatesFile() throws IOException {
        Path nested = tempDir.resolve("nested/dir");
        DomainMetadata meta = DomainMetadata.builder("Order", "com.example.domain").build();

        new EntitySchemaGenerator(meta).writeTo(nested);

        Path expected = nested.resolve("order.schema.json");
        assertThat(Files.exists(expected)).isTrue();
        assertThat(MAPPER.readTree(Files.readString(expected)).get("title").asText())
                .isEqualTo("Order");
    }
}
