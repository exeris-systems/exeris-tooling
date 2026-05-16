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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("DomainMetadataGenerator")
class DomainMetadataGeneratorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir Path tempDir;

    @Test
    @DisplayName("Constructor rejects null metadata")
    void constructorRejectsNullMetadata() {
        assertThatThrownBy(() -> new DomainMetadataGenerator(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("metadata cannot be null");
    }

    @Test
    @DisplayName("generate() emits minimal JSON for an empty domain (no fields, no actions)")
    void generateMinimalDomain() throws IOException {
        DomainMetadata meta = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders")
                .build();

        JsonNode json = MAPPER.readTree(new DomainMetadataGenerator(meta).generate());

        assertThat(json.get("$schema").asText())
                .isEqualTo("https://exeris.eu/schemas/domain-metadata/v1.json");
        assertThat(json.get("schemaVersion").asText()).isEqualTo("1.0.0");
        assertThat(json.get("generatedAt").asText()).isNotEmpty();
        assertThat(json.get("generatedFrom").asText()).isEqualTo("com.example.domain.Order");
        assertThat(json.get("entity").get("name").asText()).isEqualTo("Order");
        assertThat(json.get("entity").get("package").asText()).isEqualTo("com.example.domain");
        assertThat(json.get("entity").get("audited").asBoolean()).isFalse();
        assertThat(json.get("entity").get("softDelete").asBoolean()).isFalse();
        assertThat(json.get("entity").get("multiTenant").asBoolean()).isFalse();
        assertThat(json.get("api").get("basePath").asText()).isEqualTo("/orders");
        assertThat(json.get("api").get("version").asText()).isEqualTo("v1");
        // Fields + actions omitted when empty.
        assertThat(json.has("fields")).isFalse();
        assertThat(json.has("actions")).isFalse();
    }

    @Test
    @DisplayName("Entity flags (audited / softDelete / tenantScoped) propagate to JSON")
    void entityFlagsPropagate() throws IOException {
        DomainMetadata meta = DomainMetadata.builder("Order", "com.example.domain")
                .audited(true).softDelete(true).tenantScoped(true)
                .build();

        JsonNode json = MAPPER.readTree(new DomainMetadataGenerator(meta).generate());

        assertThat(json.get("entity").get("audited").asBoolean()).isTrue();
        assertThat(json.get("entity").get("softDelete").asBoolean()).isTrue();
        assertThat(json.get("entity").get("multiTenant").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("Field info: type mapped via DslTypeMapper, javaType preserved, displayName included when set")
    void fieldInfoMapsTypeAndDisplayName() throws IOException {
        DomainMetadata meta = DomainMetadata.builder("Order", "com.example.domain")
                .fields(List.of(
                        FieldMetadata.builder("orderNumber", "String")
                                .displayName("Order Number")
                                .required(true)
                                .searchable(true)
                                .sortable(true)
                                .build(),
                        FieldMetadata.builder("amount", "BigDecimal").build()))
                .build();

        JsonNode json = MAPPER.readTree(new DomainMetadataGenerator(meta).generate());
        JsonNode fields = json.get("fields");

        assertThat(fields.isArray()).isTrue();
        assertThat(fields).hasSize(2);

        JsonNode orderNumber = fields.get(0);
        assertThat(orderNumber.get("name").asText()).isEqualTo("orderNumber");
        assertThat(orderNumber.get("type").asText()).isEqualTo("string");
        assertThat(orderNumber.get("javaType").asText()).isEqualTo("String");
        assertThat(orderNumber.get("required").asBoolean()).isTrue();
        assertThat(orderNumber.get("searchable").asBoolean()).isTrue();
        assertThat(orderNumber.get("sortable").asBoolean()).isTrue();
        assertThat(orderNumber.get("label").asText()).isEqualTo("Order Number");

        // Second field has no displayName → label omitted.
        JsonNode amount = fields.get(1);
        assertThat(amount.get("type").asText()).isEqualTo("number");
        assertThat(amount.has("label")).isFalse();
    }

    @Test
    @DisplayName("Action info: method + flags propagate; permissions emitted only when present")
    void actionInfoCovers() throws IOException {
        DomainMetadata meta = DomainMetadata.builder("Order", "com.example.domain")
                .actions(List.of(
                        ActionMetadata.builder("approve")
                                .httpMethod("POST")
                                .async(true)
                                .dangerous(false)
                                .permissions(List.of("ORDER_APPROVE"))
                                .build(),
                        ActionMetadata.builder("cancel")
                                .httpMethod("DELETE")
                                .dangerous(true)
                                .build()))
                .build();

        JsonNode json = MAPPER.readTree(new DomainMetadataGenerator(meta).generate());
        JsonNode actions = json.get("actions");

        assertThat(actions).hasSize(2);

        JsonNode approve = actions.get(0);
        assertThat(approve.get("name").asText()).isEqualTo("approve");
        assertThat(approve.get("method").asText()).isEqualTo("POST");
        assertThat(approve.get("async").asBoolean()).isTrue();
        assertThat(approve.get("dangerous").asBoolean()).isFalse();
        assertThat(approve.get("permissions").get(0).asText()).isEqualTo("ORDER_APPROVE");

        JsonNode cancel = actions.get(1);
        assertThat(cancel.get("dangerous").asBoolean()).isTrue();
        // No permissions on the action → key omitted.
        assertThat(cancel.has("permissions")).isFalse();
    }

    @Test
    @DisplayName("writeTo creates the output directory and writes <entity>.meta.json")
    void writeToCreatesFile() throws IOException {
        Path nested = tempDir.resolve("does/not/exist/yet");
        DomainMetadata meta = DomainMetadata.builder("Order", "com.example.domain").build();

        new DomainMetadataGenerator(meta).writeTo(nested);

        Path expected = nested.resolve("order.meta.json");
        assertThat(Files.exists(expected)).isTrue();
        // Content is valid JSON with the expected schema URL.
        JsonNode written = MAPPER.readTree(Files.readString(expected));
        assertThat(written.get("$schema").asText())
                .isEqualTo("https://exeris.eu/schemas/domain-metadata/v1.json");
    }
}
