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
}
