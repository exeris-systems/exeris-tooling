package eu.exeris.tooling.codegen.java.openapi;

import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.License;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("OpenApiGenerator")
class OpenApiGeneratorTest {

    @TempDir Path tempDir;

    private OpenApiGenerator generator;

    @BeforeEach
    void setup() {
        generator = new OpenApiGenerator();
        generator.setOutputDirectory(tempDir);
    }

    @Test
    @DisplayName("generateYaml emits a non-empty OpenAPI 3.1 YAML document")
    void generateYaml() throws IOException {
        DomainMetadata meta = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders").build();

        String yaml = generator.generateYaml(meta);

        assertThat(yaml)
                .contains("openapi: 3.1.0")
                .contains("Order API")
                .contains("/orders");
    }

    @Test
    @DisplayName("generate(metadata) writes <entity>-api.yaml under the output directory")
    void generateWritesFile() throws IOException {
        DomainMetadata meta = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders").build();

        OpenAPI openAPI = generator.generate(meta);

        assertThat(openAPI.getOpenapi()).isEqualTo("3.1.0");
        Path outFile = tempDir.resolve("order-api.yaml");
        assertThat(Files.exists(outFile)).isTrue();
        assertThat(Files.readString(outFile)).contains("openapi: 3.1.0");
    }

    @Test
    @DisplayName("validateMetadata: null metadata → IllegalArgumentException")
    void rejectsNullMetadata() {
        assertThatThrownBy(() -> generator.generateYaml(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be null");
    }

    @Test
    @DisplayName("validateMetadata: empty entity name → IllegalArgumentException")
    void rejectsEmptyEntityName() {
        DomainMetadata bad = DomainMetadata.builder("", "com.example.domain").build();

        assertThatThrownBy(() -> generator.generateYaml(bad))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Entity name");
    }

    @Test
    @DisplayName("Description: domain description used when set; falls back to \"REST API for <Entity> management\" when blank")
    void infoDescriptionBranches() throws IOException {
        DomainMetadata withDesc = DomainMetadata.builder("Order", "com.example.domain")
                .description("Customer orders").build();
        DomainMetadata withoutDesc = DomainMetadata.builder("Order", "com.example.domain")
                .build();

        assertThat(generator.generate(withDesc).getInfo().getDescription())
                .isEqualTo("Customer orders");
        assertThat(generator.generate(withoutDesc).getInfo().getDescription())
                .isEqualTo("REST API for Order management");
    }

    @Test
    @DisplayName("Contact and License setters propagate into the emitted Info block")
    void contactAndLicensePropagate() throws IOException {
        Contact contact = new Contact().name("Exeris").email("oss@exeris.eu");
        License license = new License().name("Apache-2.0");
        generator.setContact(contact);
        generator.setLicense(license);
        generator.setApiTitle("My API");
        generator.setBaseUrl("https://api.exeris.eu");

        OpenAPI openAPI = generator.generate(
                DomainMetadata.builder("Order", "com.example.domain").build());

        assertThat(openAPI.getInfo().getContact()).isSameAs(contact);
        assertThat(openAPI.getInfo().getLicense()).isSameAs(license);
        assertThat(openAPI.getInfo().getTitle()).isEqualTo("My API - Order API");
        assertThat(openAPI.getServers().get(0).getUrl()).isEqualTo("https://api.exeris.eu");
    }

    @Test
    @DisplayName("generateAggregated builds a multi-entity spec under <module>-api.yaml")
    void generateAggregated() throws IOException {
        DomainMetadata order = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders").build();
        DomainMetadata product = DomainMetadata.builder("Product", "com.example.domain")
                .path("/products").build();

        OpenAPI openAPI = generator.generateAggregated(List.of(order, product), "catalog");

        assertThat(openAPI.getOpenapi()).isEqualTo("3.1.0");
        assertThat(openAPI.getInfo().getTitle()).isEqualTo("Exeris API - Catalog Module");
        assertThat(openAPI.getPaths()).containsKey("/orders");
        assertThat(openAPI.getPaths()).containsKey("/products");
        // Tag list aggregates both entities.
        assertThat(openAPI.getTags()).extracting("name")
                .contains("Order", "Product");
        assertThat(Files.exists(tempDir.resolve("catalog-api.yaml"))).isTrue();
    }

    @Test
    @DisplayName("generateAggregated rejects null / empty metadata list")
    void generateAggregatedRejectsEmpty() {
        assertThatThrownBy(() -> generator.generateAggregated(null, "catalog"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> generator.generateAggregated(List.of(), "catalog"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("getOutputDirectory returns the path set via the setter")
    void outputDirectoryGetter() {
        assertThat(generator.getOutputDirectory()).isEqualTo(tempDir);
    }
}
