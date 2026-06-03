package eu.exeris.tooling.codegen.maven;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("DetachMojo — thin shell over DetachService")
class DetachMojoTest {

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static DetachMojo mojo(Path tmp) {
        DetachMojo mojo = new DetachMojo();
        mojo.generatedDir = tmp.resolve("src/main/generated/java").toFile();
        mojo.targetDir = tmp.resolve("src/main/java").toFile();
        mojo.gitignore = tmp.resolve(".gitignore").toFile();
        mojo.ignoreEntry = "src/main/generated/";
        return mojo;
    }

    @Test
    @DisplayName("promotes generated sources and cleans .gitignore")
    void promotesAndCleansGitignore(@TempDir Path tmp) throws Exception {
        DetachMojo mojo = mojo(tmp);
        write(mojo.generatedDir.toPath().resolve("com/shop/Order.java"), "class Order {}");
        Files.write(mojo.gitignore.toPath(), java.util.List.of("src/main/generated/", "target/"));

        mojo.execute();

        assertThat(mojo.targetDir.toPath().resolve("com/shop/Order.java")).exists();
        assertThat(Files.readAllLines(mojo.gitignore.toPath())).containsExactly("target/");
    }

    @Test
    @DisplayName("nothing to detach is a clean no-op")
    void noOpWhenNothingGenerated(@TempDir Path tmp) throws Exception {
        DetachMojo mojo = mojo(tmp);

        mojo.execute(); // must not throw
    }

    @Test
    @DisplayName("failOnConflict=true raises MojoFailureException when an owned file collides")
    void failsOnConflictWhenConfigured(@TempDir Path tmp) throws Exception {
        DetachMojo mojo = mojo(tmp);
        mojo.failOnConflict = true;
        write(mojo.generatedDir.toPath().resolve("com/shop/Order.java"), "GENERATED");
        write(mojo.targetDir.toPath().resolve("com/shop/Order.java"), "OWNED");

        assertThatThrownBy(mojo::execute)
                .isInstanceOf(MojoFailureException.class)
                .hasMessageContaining("conflict");
    }

    @Test
    @DisplayName("failOnConflict=false logs conflicts but does not fail the build")
    void tolerantOnConflictByDefault(@TempDir Path tmp) throws Exception {
        DetachMojo mojo = mojo(tmp);
        write(mojo.generatedDir.toPath().resolve("com/shop/Order.java"), "GENERATED");
        write(mojo.targetDir.toPath().resolve("com/shop/Order.java"), "OWNED");

        mojo.execute(); // must not throw

        assertThat(mojo.targetDir.toPath().resolve("com/shop/Order.java")).content().isEqualTo("OWNED");
    }

    @Test
    @DisplayName("wraps a service IOException as MojoExecutionException")
    void wrapsIoFailure(@TempDir Path tmp) {
        DetachMojo mojo = mojo(tmp);
        mojo.service = new DetachService() {
            @Override
            public DetachResult detach(Path g, Path t, Path gi, String entry) throws IOException {
                throw new IOException("read-only fs");
            }
        };

        assertThatThrownBy(mojo::execute)
                .isInstanceOf(MojoExecutionException.class)
                .hasMessageContaining("Detach failed")
                .hasRootCauseMessage("read-only fs");
    }
}
