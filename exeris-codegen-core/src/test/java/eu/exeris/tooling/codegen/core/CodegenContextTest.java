package eu.exeris.tooling.codegen.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CodegenContext")
class CodegenContextTest {

    private static final Path OUTPUT = Path.of("/tmp/generated");

    @Test
    @DisplayName("Canonical constructor stores components and requires non-null outputDir")
    void canonicalConstructor() {
        CodegenContext ctx = new CodegenContext(OUTPUT, true, "prefix", false);

        assertThat(ctx.outputDir()).isEqualTo(OUTPUT);
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
        CodegenContext ctx = CodegenContext.forKernel(OUTPUT);

        assertThat(ctx.outputDir()).isEqualTo(OUTPUT);
        assertThat(ctx.generateTests()).isFalse();
        assertThat(ctx.packagePrefix()).isNull();
        assertThat(ctx.generateComments()).isTrue();
    }

    @Test
    @DisplayName("effectivePackage prepends the prefix when set")
    void effectivePackageWithPrefix() {
        CodegenContext ctx = new CodegenContext(OUTPUT, false, "com.acme", true);

        assertThat(ctx.effectivePackage("foo.bar")).isEqualTo("com.acme.foo.bar");
    }

    @Test
    @DisplayName("effectivePackage returns the base package when prefix is null")
    void effectivePackageNullPrefix() {
        CodegenContext ctx = new CodegenContext(OUTPUT, false, null, true);

        assertThat(ctx.effectivePackage("foo.bar")).isEqualTo("foo.bar");
    }

    @Test
    @DisplayName("effectivePackage treats a blank prefix as no prefix")
    void effectivePackageBlankPrefix() {
        CodegenContext ctx = new CodegenContext(OUTPUT, false, "   ", true);

        assertThat(ctx.effectivePackage("foo.bar")).isEqualTo("foo.bar");
    }

    @Test
    @DisplayName("Builder chains all setters and produces an equivalent record")
    void builderProducesRecord() {
        CodegenContext built = CodegenContext.builder()
                .outputDir(OUTPUT)
                .generateTests(true)
                .packagePrefix("p")
                .generateComments(false)
                .build();

        assertThat(built).isEqualTo(new CodegenContext(OUTPUT, true, "p", false));
    }

    @Test
    @DisplayName("Builder defaults: generateTests=false, packagePrefix=null, generateComments=true")
    void builderDefaults() {
        CodegenContext built = CodegenContext.builder().outputDir(OUTPUT).build();

        assertThat(built.generateTests()).isFalse();
        assertThat(built.packagePrefix()).isNull();
        assertThat(built.generateComments()).isTrue();
    }
}
