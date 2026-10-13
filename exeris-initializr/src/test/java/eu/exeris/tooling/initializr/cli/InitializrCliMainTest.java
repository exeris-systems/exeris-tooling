package eu.exeris.tooling.initializr.cli;

import eu.exeris.tooling.initializr.engine.ScaffoldVersions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class InitializrCliMainTest {

    @TempDir
    Path tmp;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private int run(String... args) {
        return InitializrCliMain.run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    private String out() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String err() {
        return err.toString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("writes the project, says where, and exits 0")
    void writesProject() {
        Path target = tmp.resolve("shop");
        assertThat(run("--topology=e2e", "--artifact-id=shop", "--output-dir=" + target)).isEqualTo(0);
        assertThat(target.resolve("backend/pom.xml")).content()
                .contains("<version>" + ScaffoldVersions.bundled().tooling() + "</version>");
        assertThat(target.resolve("frontend/package.json")).exists();
        assertThat(out()).contains("Wrote the e2e project shop (7 files) to " + target.toAbsolutePath());
        assertThat(err()).isEmpty();
    }

    @Test
    @DisplayName("writes a ZIP archive with --zip")
    void writesArchive() {
        Path archive = tmp.resolve("shop.zip");
        assertThat(run("--artifact-id=shop", "--zip=" + archive)).isEqualTo(0);
        assertThat(archive).isRegularFile();
    }

    @Test
    @DisplayName("refuses a non-empty directory with EXT-INIT-5002 and exit 1, and writes into it with --force")
    void refusesNonEmptyDirectory() throws IOException {
        Files.writeString(tmp.resolve("keep.txt"), "mine");
        assertThat(run("--output-dir=" + tmp)).isEqualTo(1);
        assertThat(err()).startsWith("[Exeris] EXT-INIT-5002: ").contains("is not empty", "--force");
        assertThat(tmp.resolve("pom.xml")).doesNotExist();

        assertThat(run("--output-dir=" + tmp, "--force")).isEqualTo(0);
        assertThat(tmp.resolve("pom.xml")).exists();
        assertThat(tmp.resolve("keep.txt")).hasContent("mine");
    }

    @Test
    @DisplayName("reports a write that fails with EXT-INIT-5003 and exit 1")
    void writeFails() throws IOException {
        Path blocker = Files.writeString(tmp.resolve("blocker"), "a file");
        assertThat(run("--zip=" + blocker.resolve("demo.zip"))).isEqualTo(1);
        assertThat(err()).startsWith("[Exeris] EXT-INIT-5003: Writing ").contains("Exception");
    }

    @Test
    @DisplayName("refuses an invalid argument with EXT-INIT-5001, the usage, and exit 2")
    void refusesArguments() {
        assertThat(run("--capabilities=audit-trail")).isEqualTo(2);
        assertThat(err()).startsWith("[Exeris] EXT-INIT-5001: Unknown switch: --capabilities")
                .contains("Usage: java -jar exeris-initializr-<version>.jar");
        assertThat(out()).isEmpty();
    }

    @Test
    @DisplayName("prints the version it was built as, and the usage, with exit 0")
    void versionAndHelp() {
        assertThat(run("--version")).isEqualTo(0);
        assertThat(out()).isEqualTo("exeris-initializr " + ScaffoldVersions.bundled().tooling()
                + System.lineSeparator());
        out.reset();
        assertThat(run("--help")).isEqualTo(0);
        assertThat(out()).contains("--topology=<t>", "Exit status: 0 success, 1 write refused or failed, 2 invalid");
        assertThat(err()).isEmpty();
    }
}
