package eu.exeris.e2e.codegen;

import eu.exeris.e2e.codegen.compile.EmittedJavac;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The lint gate must fail on a warning, name its category, and scope an accepted warning to the
 * one file it was accepted for; a gate that cannot fail would pass every emitted tree.
 */
@Tag("e2e")
@Tag("codegen")
@Tag("compile")
@DisplayName("Emitted-code lint gate")
class EmittedLintGateTest {

    @TempDir
    Path workspace;

    @Test
    @DisplayName("a lint warning fails the compile and is rendered with its diagnostic code")
    void warningFails() throws IOException {
        EmittedJavac.Result result = compile("Raw.java", "class Raw { java.util.List l = new java.util.ArrayList(); }");

        assertThat(result.success()).isTrue();
        assertThat(result.clean()).isFalse();
        assertThat(result.render()).contains("compiler.warn.raw.class.use");
    }

    @Test
    @DisplayName("an accepted warning is accepted only in the file it was accepted for")
    void acceptedWarningIsFileScoped() throws IOException {
        String body = "class %s implements AutoCloseable { public void close() { }"
                + " void run() { try (%s c = new %s()) { System.out.println(); } } }";
        EmittedJavac.Result accepted = compile("Application.java", body.formatted("Application", "Application", "Application"));
        EmittedJavac.Result other = compile("Other.java", body.formatted("Other", "Other", "Other"));

        assertThat(accepted.clean()).isTrue();
        assertThat(other.clean()).isFalse();
        assertThat(other.render()).contains("compiler.warn.try.resource.not.referenced");
    }

    private EmittedJavac.Result compile(String fileName, String source) throws IOException {
        Path dir = Files.createTempDirectory(workspace, "lint");
        Path file = dir.resolve(fileName);
        Files.writeString(file, source);
        return EmittedJavac.compile(List.of(file.toString()), dir.resolve("out"),
                System.getProperty("java.class.path") + File.pathSeparator + dir);
    }
}
