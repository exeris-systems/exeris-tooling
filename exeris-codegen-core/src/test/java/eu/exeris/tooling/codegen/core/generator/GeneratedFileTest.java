package eu.exeris.tooling.codegen.core.generator;

import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator.ArtifactType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("GeneratedFile")
class GeneratedFileTest {

    @Test
    @DisplayName("Canonical 5-arg constructor stores all components verbatim")
    void canonicalConstructor() {
        GeneratedFile file = new GeneratedFile("com.example.foo", "Bar", "class Bar {}",
                ArtifactType.SERVICE, "java");

        assertThat(file.packageName()).isEqualTo("com.example.foo");
        assertThat(file.className()).isEqualTo("Bar");
        assertThat(file.content()).isEqualTo("class Bar {}");
        assertThat(file.artifactType()).isEqualTo(ArtifactType.SERVICE);
        assertThat(file.extension()).isEqualTo("java");
    }

    @Test
    @DisplayName("4-arg compact constructor defaults extension to \"java\"")
    void compactConstructorDefaultsExtension() {
        GeneratedFile file = new GeneratedFile("com.example", "Foo", "src",
                ArtifactType.CONTROLLER);

        assertThat(file.extension()).isEqualTo("java");
    }

    @Test
    @DisplayName("fullyQualifiedName joins package and class for non-empty package")
    void fullyQualifiedNameForNonEmptyPackage() {
        GeneratedFile file = new GeneratedFile("com.example.foo", "Bar", "src",
                ArtifactType.SERVICE);

        assertThat(file.fullyQualifiedName()).isEqualTo("com.example.foo.Bar");
    }

    @Test
    @DisplayName("fullyQualifiedName returns just the class name for empty package")
    void fullyQualifiedNameForEmptyPackage() {
        GeneratedFile file = new GeneratedFile("", "Bar", "src", ArtifactType.SERVICE);

        assertThat(file.fullyQualifiedName()).isEqualTo("Bar");
    }

    @Test
    @DisplayName("relativePath joins package-as-slashes + class + extension")
    void relativePathNonEmptyPackage() {
        GeneratedFile file = new GeneratedFile("com.example.foo", "Bar", "src",
                ArtifactType.SERVICE, "sql");

        assertThat(file.relativePath()).isEqualTo("com/example/foo/Bar.sql");
    }

    @Test
    @DisplayName("relativePath drops directory when package is empty")
    void relativePathEmptyPackage() {
        GeneratedFile file = new GeneratedFile("", "Bar", "src", ArtifactType.SERVICE, "yaml");

        assertThat(file.relativePath()).isEqualTo("Bar.yaml");
    }

    @Test
    @DisplayName("relativePath falls back to \"java\" when extension is null")
    void relativePathNullExtensionFallsBackToJava() {
        GeneratedFile file = new GeneratedFile("com.example", "Foo", "src",
                ArtifactType.SERVICE, null);

        assertThat(file.relativePath()).isEqualTo("com/example/Foo.java");
    }

    @Test
    @DisplayName("Builder chains setters and produces the right record")
    void builderProducesRecord() {
        GeneratedFile file = GeneratedFile.builder()
                .packageName("com.example")
                .className("Foo")
                .content("body")
                .artifactType(ArtifactType.REPOSITORY)
                .extension("ts")
                .build();

        assertThat(file).isEqualTo(
                new GeneratedFile("com.example", "Foo", "body",
                        ArtifactType.REPOSITORY, "ts"));
    }

    @Test
    @DisplayName("Builder defaults: empty package + class + content, \"java\" extension")
    void builderDefaults() {
        GeneratedFile file = GeneratedFile.builder()
                .artifactType(ArtifactType.OPENAPI_SPEC)
                .build();

        assertThat(file.packageName()).isEmpty();
        assertThat(file.className()).isEmpty();
        assertThat(file.content()).isEmpty();
        assertThat(file.extension()).isEqualTo("java");
        assertThat(file.artifactType()).isEqualTo(ArtifactType.OPENAPI_SPEC);
    }
}
