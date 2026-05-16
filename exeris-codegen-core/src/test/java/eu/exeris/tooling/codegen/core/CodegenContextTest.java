package eu.exeris.tooling.codegen.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CodegenContext")
class CodegenContextTest {

    @TempDir Path outputDir;

    @Test
    @DisplayName("Canonical constructor stores components and requires non-null outputDir")
    void canonicalConstructor() {
        CodegenContext ctx = new CodegenContext(outputDir, true, "prefix", false);

        assertThat(ctx.outputDir()).isEqualTo(outputDir);
        assertThat(ctx.generateTests()).isTrue();
        assertThat(ctx.packagePrefix()).isEqualTo("prefix");
        assertThat(ctx.generateComments()).isFalse();
    }

    @Test
    @DisplayName("Canonical constructor rejects null outputDir with NullPointerException")
    void canonicalConstructorRejectsNullOutputDir() {
        assertThatThrownBy(() -> new CodegenContext(null, false, null, true))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("outputDir");
    }

    @Test
    @DisplayName("forKernel(outputDir) factory produces the default context")
    void forKernelFactoryDefaults() {
        CodegenContext ctx = CodegenContext.forKernel(outputDir);

        assertThat(ctx.outputDir()).isEqualTo(outputDir);
        assertThat(ctx.generateTests()).isFalse();
        assertThat(ctx.packagePrefix()).isNull();
        assertThat(ctx.generateComments()).isTrue();
    }

    @Test
    @DisplayName("effectivePackage prepends the prefix when set")
    void effectivePackageWithPrefix() {
        CodegenContext ctx = new CodegenContext(outputDir, false, "com.acme", true);

        assertThat(ctx.effectivePackage("foo.bar")).isEqualTo("com.acme.foo.bar");
    }

    @Test
    @DisplayName("effectivePackage returns the base package when prefix is null")
    void effectivePackageNullPrefix() {
        CodegenContext ctx = new CodegenContext(outputDir, false, null, true);

        assertThat(ctx.effectivePackage("foo.bar")).isEqualTo("foo.bar");
    }

    @Test
    @DisplayName("effectivePackage treats a blank prefix as no prefix")
    void effectivePackageBlankPrefix() {
        CodegenContext ctx = new CodegenContext(outputDir, false, "   ", true);

        assertThat(ctx.effectivePackage("foo.bar")).isEqualTo("foo.bar");
    }

    @Test
    @DisplayName("Builder chains all setters and produces an equivalent record")
    void builderProducesRecord() {
        CodegenContext built = CodegenContext.builder()
                .outputDir(outputDir)
                .generateTests(true)
                .packagePrefix("p")
                .generateComments(false)
                .build();

        assertThat(built).isEqualTo(new CodegenContext(outputDir, true, "p", false));
    }

    @Test
    @DisplayName("Builder defaults: generateTests=false, packagePrefix=null, generateComments=true")
    void builderDefaults() {
        CodegenContext built = CodegenContext.builder().outputDir(outputDir).build();

        assertThat(built.generateTests()).isFalse();
        assertThat(built.packagePrefix()).isNull();
        assertThat(built.generateComments()).isTrue();
    }
}
