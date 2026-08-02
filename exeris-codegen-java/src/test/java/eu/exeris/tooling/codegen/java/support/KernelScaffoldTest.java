package eu.exeris.tooling.codegen.java.support;

import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.TypeSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Modifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

@DisplayName("KernelScaffold")
class KernelScaffoldTest {

    @Nested
    @DisplayName("publicClass")
    class PublicClassTests {

        @Test
        @DisplayName("sets PUBLIC modifier")
        void setsPublicModifier() {
            TypeSpec spec = KernelScaffold.publicClass("Foo").build();
            assertThat(spec.modifiers()).contains(Modifier.PUBLIC);
        }

        @Test
        @DisplayName("preserves the class name")
        void preservesName() {
            TypeSpec spec = KernelScaffold.publicClass("OrderHandler").build();
            assertThat(spec.name()).isEqualTo("OrderHandler");
        }

        @Test
        @DisplayName("rejects null className")
        void rejectsNullName() {
            assertThatNullPointerException()
                    .isThrownBy(() -> KernelScaffold.publicClass(null))
                    .withMessageContaining("className");
        }
    }

    @Nested
    @DisplayName("render")
    class RenderTests {

        @Test
        @DisplayName("emits 4-space indent")
        void fourSpaceIndent() {
            TypeSpec type = KernelScaffold.publicClass("Foo")
                    .addMethod(MethodSpec.methodBuilder("bar")
                            .addModifiers(Modifier.PUBLIC)
                            .addStatement("int x = 1")
                            .build())
                    .build();
            String out = KernelScaffold.render("com.example", type);
            assertThat(out).contains("    public void bar()");
            assertThat(out).contains("        int x = 1;");
        }

        @Test
        @DisplayName("skips java.lang imports")
        void skipsJavaLangImports() {
            TypeSpec type = KernelScaffold.publicClass("Foo")
                    .addField(String.class, "name", Modifier.PRIVATE)
                    .build();
            String out = KernelScaffold.render("com.example", type);
            assertThat(out).doesNotContain("import java.lang.String");
            assertThat(out).contains("private String name;");
        }

        @Test
        @DisplayName("rejects null packageName")
        void rejectsNullPackage() {
            TypeSpec type = KernelScaffold.publicClass("Foo").build();
            assertThatNullPointerException()
                    .isThrownBy(() -> KernelScaffold.render(null, type))
                    .withMessageContaining("packageName");
        }

        @Test
        @DisplayName("rejects null type")
        void rejectsNullType() {
            assertThatNullPointerException()
                    .isThrownBy(() -> KernelScaffold.render("com.example", null))
                    .withMessageContaining("type");
        }
    }

    @Nested
    @DisplayName("loggerField")
    class LoggerFieldTests {

        @Test
        @DisplayName("emits a System.Logger named after the generated class, not a facade")
        void emitsSystemLogger() {
            TypeSpec type = KernelScaffold.publicClass("OrderRepository")
                    .addField(KernelScaffold.loggerField(
                            com.palantir.javapoet.ClassName.get("com.shop.repository", "OrderRepository")))
                    .build();

            assertThat(KernelScaffold.render("com.shop.repository", type))
                    .contains("private static final System.Logger LOG = "
                            + "System.getLogger(OrderRepository.class.getName());")
                    .doesNotContain("org.slf4j");
        }

        @Test
        @DisplayName("rejects null selfType")
        void rejectsNullSelfType() {
            assertThatNullPointerException()
                    .isThrownBy(() -> KernelScaffold.loggerField(null))
                    .withMessageContaining("selfType");
        }
    }

    @Nested
    @DisplayName("escapeQuotes")
    class EscapeQuotesTests {

        @Test
        @DisplayName("quotes around a literal survive instead of being eaten")
        void quotesAroundALiteralSurvive() {
            String raw = "[{0}] step 'reserveStock' at index {1}";

            // Unescaped, MessageFormat reads the pair as a quoted section and drops both quotes.
            assertThat(java.text.MessageFormat.format(raw, "OrderSaga", 2))
                    .isEqualTo("[OrderSaga] step reserveStock at index 2");

            assertThat(java.text.MessageFormat.format(KernelScaffold.escapeQuotes(raw), "OrderSaga", 2))
                    .isEqualTo("[OrderSaga] step 'reserveStock' at index 2");
        }

        @Test
        @DisplayName("a placeholder inside quotes is otherwise swallowed whole")
        void quotedPlaceholderWouldBeSwallowed() {
            // The sharp edge, and the reason the saga emitter bakes the step name into the literal
            // rather than passing it as an argument: a placeholder *inside* a quoted section is
            // emitted verbatim, so the argument is lost with no error anywhere.
            String raw = "[{0}] step '{1}' at index {2}";

            assertThat(java.text.MessageFormat.format(raw, "OrderSaga", "reserveStock", 2))
                    .isEqualTo("[OrderSaga] step {1} at index 2");

            assertThat(java.text.MessageFormat.format(
                    KernelScaffold.escapeQuotes(raw), "OrderSaga", "reserveStock", 2))
                    .isEqualTo("[OrderSaga] step 'reserveStock' at index 2");
        }

        @Test
        @DisplayName("leaves placeholders alone, so a whole pattern can be passed through")
        void leavesPlaceholdersAlone() {
            assertThat(KernelScaffold.escapeQuotes("a {0} b {1}")).isEqualTo("a {0} b {1}");
        }

        @Test
        @DisplayName("rejects null pattern")
        void rejectsNullPattern() {
            assertThatNullPointerException()
                    .isThrownBy(() -> KernelScaffold.escapeQuotes(null))
                    .withMessageContaining("pattern");
        }
    }
}
