package eu.exeris.tooling.processor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.google.testing.compile.Compiler.javac;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Processor parity with SDK 0.13: @GraphProperty and @UIGroup")
class ProcessorParityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String fixtureResource(String name) {
        try (InputStream is = ProcessorParityTest.class.getResourceAsStream("/processor-parity/" + name)) {
            if (is == null) {
                throw new IllegalArgumentException("Fixture resource not found: " + name);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private JsonNode compileAndReadMetadata(String name) throws IOException {
        String source = fixtureResource(name + ".java");
        // Inject primary key required by processor build-time validation:
        String compilableSource = source.replace("{", "{\n    private java.util.UUID id;\n");
        Compilation compilation = javac()
                .withProcessors(new ExerisDomainProcessor())
                .compile(JavaFileObjects.forSourceString("parity." + name, compilableSource));

        assertThat(compilation).succeeded();
        JavaFileObject metadataFile = compilation.generatedFile(
                StandardLocation.CLASS_OUTPUT, "exeris-metadata/" + name + ".json")
                .orElseThrow(() -> new AssertionError("exeris-metadata/" + name + ".json not generated"));

        try (InputStream is = metadataFile.openInputStream()) {
            return MAPPER.readTree(is);
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"GraphProperties", "GraphNoProperties"})
    @DisplayName("Processor emits identical graph properties JSON to SDK parity fixtures")
    void processorMatchesSdkGraphPropertiesFixtures(String name) throws IOException {
        JsonNode expected = MAPPER.readTree(fixtureResource(name + ".expected.json"));
        JsonNode actual = compileAndReadMetadata(name);

        assertThat(actual.path("graphMetadata").path("properties"))
                .isEqualTo(expected.path("graphMetadata").path("properties"));
    }

    @Test
    @DisplayName("Processor emits identical uiMetadata JSON to SDK UiGroups parity fixture")
    void processorMatchesSdkUiGroupsFixture() throws IOException {
        JsonNode expected = MAPPER.readTree(fixtureResource("UiGroups.expected.json"));
        JsonNode actual = compileAndReadMetadata("UiGroups");

        assertThat(actual.path("uiMetadata"))
                .isEqualTo(expected.path("uiMetadata"));
    }

    @Test
    @DisplayName("@GraphProperty without @Graph is not extracted and graphMetadata is null")
    void graphPropertyWithoutGraphIsNotExtracted() throws IOException {
        Compilation compilation = javac()
                .withProcessors(new ExerisDomainProcessor())
                .compile(JavaFileObjects.forSourceString("com.example.Item", """
                        package com.example;

                        import eu.exeris.sdk.annotation.ExerisDomain;
                        import eu.exeris.sdk.annotation.GraphProperty;

                        @ExerisDomain(module = "catalog", path = "/items")
                        public class Item {
                            private java.util.UUID id;
                            @GraphProperty(indexed = true)
                            private String code;
                        }
                        """));

        assertThat(compilation).succeeded();
        JavaFileObject metadataFile = compilation.generatedFile(
                StandardLocation.CLASS_OUTPUT, "exeris-metadata/Item.json").orElseThrow();

        try (InputStream is = metadataFile.openInputStream()) {
            JsonNode actual = MAPPER.readTree(is);
            assertThat(actual.has("graphMetadata")).isFalse();
        }
    }

    @Test
    @DisplayName("@UIGroup folding rules: first-declaration wins per attribute, first-declaration group order, defaults applied")
    void uiGroupFoldingRules() throws IOException {
        Compilation compilation = javac()
                .withProcessors(new ExerisDomainProcessor())
                .compile(JavaFileObjects.forSourceString("com.example.Customer", """
                        package com.example;

                        import eu.exeris.sdk.annotation.ExerisDomain;
                        import eu.exeris.sdk.annotation.UI;
                        import eu.exeris.sdk.annotation.UIGroup;

                        @ExerisDomain(module = "crm", path = "/customers")
                        @UI(listView = false)
                        public class Customer {
                            private java.util.UUID id;

                            @UIGroup(name = "grpB", label = "Group B First", order = 10, collapsible = true, collapsed = true, icon = "b_icon")
                            private String b1;

                            @UIGroup(name = "grpA", description = "Group A Description", order = 5)
                            private String a1;

                            @UIGroup(name = "grpB", label = "Ignored B", order = 99, collapsible = false, collapsed = false, icon = "ignored")
                            private String b2;

                            @UIGroup(name = "grpA", label = "Group A Label", icon = "a_icon")
                            private String a2;

                            @UIGroup(name = "grpC")
                            private String c1;
                        }
                        """));

        assertThat(compilation).succeeded();
        JavaFileObject metadataFile = compilation.generatedFile(
                StandardLocation.CLASS_OUTPUT, "exeris-metadata/Customer.json").orElseThrow();

        try (InputStream is = metadataFile.openInputStream()) {
            JsonNode actual = MAPPER.readTree(is);
            JsonNode ui = actual.path("uiMetadata");

            assertThat(ui.path("listView").asBoolean()).isFalse();
            assertThat(ui.path("detailView").asBoolean()).isTrue();

            JsonNode groups = ui.path("groups");
            assertThat(groups).hasSize(3);

            // Group order is first-declaration: grpB, grpA, grpC
            JsonNode grpB = groups.get(0);
            assertThat(grpB.path("name").asText()).isEqualTo("grpB");
            assertThat(grpB.path("label").asText()).isEqualTo("Group B First");
            assertThat(grpB.path("order").asInt()).isEqualTo(10);
            assertThat(grpB.path("collapsible").asBoolean()).isTrue();
            assertThat(grpB.path("collapsed").asBoolean()).isTrue();
            assertThat(grpB.path("icon").asText()).isEqualTo("b_icon");
            assertThat(grpB.path("gridSpan").asInt()).isEqualTo(12);
            assertThat(grpB.path("fields")).extracting(JsonNode::asText).containsExactly("b1", "b2");

            JsonNode grpA = groups.get(1);
            assertThat(grpA.path("name").asText()).isEqualTo("grpA");
            assertThat(grpA.path("label").asText()).isEqualTo("Group A Label");
            assertThat(grpA.path("description").asText()).isEqualTo("Group A Description");
            assertThat(grpA.path("order").asInt()).isEqualTo(5);
            assertThat(grpA.path("collapsible").asBoolean()).isTrue();
            assertThat(grpA.path("collapsed").asBoolean()).isFalse();
            assertThat(grpA.path("icon").asText()).isEqualTo("a_icon");
            assertThat(grpA.path("gridSpan").asInt()).isEqualTo(12);
            assertThat(grpA.path("fields")).extracting(JsonNode::asText).containsExactly("a1", "a2");

            JsonNode grpC = groups.get(2);
            assertThat(grpC.path("name").asText()).isEqualTo("grpC");
            assertThat(grpC.path("order").asInt()).isEqualTo(999);
            assertThat(grpC.path("collapsible").asBoolean()).isTrue();
            assertThat(grpC.path("collapsed").asBoolean()).isFalse();
            assertThat(grpC.path("gridSpan").asInt()).isEqualTo(12);
            assertThat(grpC.path("fields")).extracting(JsonNode::asText).containsExactly("c1");
        }
    }
}
