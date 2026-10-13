package eu.exeris.tooling.initializr.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TemplateRendererTest {

    @Test
    @DisplayName("replaces variables with their values, and does not scan a value again")
    void variables() {
        assertThat(TemplateRenderer.render("Hello, {{ name }}! {{x}}", Map.of("name", "{{x}}", "x", 1)))
                .isEqualTo("Hello, {{x}}! 1");
    }

    @Test
    @DisplayName("keeps a section's body when its key is true, and an inverted section's when it is false")
    void sections() {
        String template = "a{{#on}}-on{{/on}}{{^on}}-off{{/on}}b";
        assertThat(TemplateRenderer.render(template, Map.of("on", true))).isEqualTo("a-onb");
        assertThat(TemplateRenderer.render(template, Map.of("on", false))).isEqualTo("a-offb");
    }

    @Test
    @DisplayName("nests sections with different keys, and renders variables inside a kept body")
    void nestedSections() {
        String template = "{{#a}}[{{#b}}{{v}}{{/b}}]{{/a}}";
        assertThat(TemplateRenderer.render(template, Map.of("a", true, "b", true, "v", "x"))).isEqualTo("[x]");
        assertThat(TemplateRenderer.render(template, Map.of("a", true, "b", false, "v", "x"))).isEqualTo("[]");
    }

    @Test
    @DisplayName("does not read the variables of a dropped section")
    void droppedSectionIsNotRead() {
        assertThat(TemplateRenderer.render("{{#a}}{{missing}}{{/a}}ok", Map.of("a", false))).isEqualTo("ok");
    }

    @Test
    @DisplayName("refuses a variable the context does not hold")
    void unknownVariable() {
        assertThatThrownBy(() -> TemplateRenderer.render("{{nope}}", Map.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("nope");
    }

    @Test
    @DisplayName("refuses a section key that is absent or not a boolean")
    void nonBooleanSection() {
        assertThatThrownBy(() -> TemplateRenderer.render("{{#k}}x{{/k}}", Map.of("k", "yes")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("'k'");
        assertThatThrownBy(() -> TemplateRenderer.render("{{^k}}x{{/k}}", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("refuses an unclosed tag, an unclosed section and a stray closing tag")
    void malformed() {
        assertThatThrownBy(() -> TemplateRenderer.render("a {{b", Map.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unclosed tag");
        assertThatThrownBy(() -> TemplateRenderer.render("{{#s}}body", Map.of("s", true)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("{{/s}}");
        assertThatThrownBy(() -> TemplateRenderer.render("x{{/s}}", Map.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("without its section");
    }

    @Test
    @DisplayName("reads a template from the classpath and refuses a name it does not have")
    void readResource() {
        assertThat(TemplateRenderer.readResource("/eu/exeris/tooling/initializr/templates/gitignore.tmpl"))
                .contains("target/");
        assertThatThrownBy(() -> TemplateRenderer.readResource("/no/such/template.tmpl"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
