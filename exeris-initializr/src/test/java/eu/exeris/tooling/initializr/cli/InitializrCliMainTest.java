package eu.exeris.tooling.initializr.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class InitializrCliMainTest {

    @Test
    @DisplayName("CLI prints help on --help flag")
    void printsHelp() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream orig = System.out;
        try {
            System.setOut(new PrintStream(out));
            InitializrCliMain.main(new String[]{"--help"});
        } finally {
            System.setOut(orig);
        }

        assertThat(out.toString()).contains("Exeris Initializr CLI");
        assertThat(out.toString()).contains("--name");
    }

    @Test
    @DisplayName("CLI generates project files into target directory and zip")
    void generatesProjectToDiskAndZip(@TempDir Path tempDir) {
        Path outputDir = tempDir.resolve("my-app");
        Path zipFile = tempDir.resolve("my-app.zip");

        InitializrCliMain.main(new String[]{
                "--name", "My App",
                "--slug", "my-app",
                "--type", "E2E",
                "--db", "H2",
                "--capabilities", "audit-trail,jwt-validation",
                "--output", outputDir.toString(),
                "--zip", zipFile.toString()
        });

        assertThat(Files.exists(outputDir)).isTrue();
        assertThat(Files.exists(outputDir.resolve("README.md"))).isTrue();
        assertThat(Files.exists(outputDir.resolve("backend/pom.xml"))).isTrue();
        assertThat(Files.exists(outputDir.resolve("frontend/package.json"))).isTrue();

        assertThat(Files.exists(zipFile)).isTrue();
        assertThat(zipFile.toFile().length()).isGreaterThan(0);
    }
}
