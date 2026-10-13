package eu.exeris.tooling.initializr.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TemplateRendererTest {

    @Test
    @DisplayName("Renders simple variables")
    void rendersVariables() {
        String template = "Hello, {{name}}! Welcome to {{app}}.";
        Map<String, Object> ctx = Map.of("name", "Alice", "app", "Exeris");
        String result = TemplateRenderer.render(template, ctx);
        assertThat(result).isEqualTo("Hello, Alice! Welcome to Exeris.");
    }

    @Test
    @DisplayName("Renders conditional sections when condition is truthy")
    void rendersConditionalSectionsTruthy() {
        String template = "Base{{#enabled}} - feature active: {{feature}}{{/enabled}}.";
        Map<String, Object> ctx = Map.of("enabled", true, "feature", "Audit");
        String result = TemplateRenderer.render(template, ctx);
        assertThat(result).isEqualTo("Base - feature active: Audit.");
    }

    @Test
    @DisplayName("Omits conditional sections when condition is false or null")
    void omitsConditionalSectionsFalsy() {
        String template = "Base{{#enabled}} - feature active{{/enabled}}.";
        Map<String, Object> ctx = Map.of("enabled", false);
        String result = TemplateRenderer.render(template, ctx);
        assertThat(result).isEqualTo("Base.");

        Map<String, Object> ctxNull = Map.of();
        String resultNull = TemplateRenderer.render(template, ctxNull);
        assertThat(resultNull).isEqualTo("Base.");
    }

    @Test
    @DisplayName("Renders inverted sections when condition is falsy")
    void rendersInvertedSections() {
        String template = "Database: {{^isPostgres}}H2 in-memory{{/isPostgres}}{{#isPostgres}}PostgreSQL{{/isPostgres}}";
        assertThat(TemplateRenderer.render(template, Map.of("isPostgres", false)))
                .isEqualTo("Database: H2 in-memory");
        assertThat(TemplateRenderer.render(template, Map.of("isPostgres", true)))
                .isEqualTo("Database: PostgreSQL");
    }

    @Test
    @DisplayName("Handles collections and truthiness correctly")
    void handlesCollections() {
        String template = "{{#items}}Has items: count={{items}}{{/items}}{{^items}}Empty{{/items}}";
        assertThat(TemplateRenderer.render(template, Map.of("items", List.of("a", "b"))))
                .isEqualTo("Has items: count=[a, b]");
        assertThat(TemplateRenderer.render(template, Map.of("items", List.of())))
                .isEqualTo("Empty");
    }
}
