package eu.exeris.tooling.initializr.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the jar this module ships as a separate process, on the JDK running the build, the way a
 * user runs it: {@code java -jar}, with nothing else on the classpath.
 */
class InitializrCliJarIT {

    private static final Path JAR = Path.of(System.getProperty("exeris.initializr.jar"));
    private static final String VERSION = System.getProperty("exeris.initializr.version");

    @TempDir
    Path tmp;

    private record Result(int status, String out, String err) {
    }

    private Result run(String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-jar", JAR.toString()));
        command.addAll(List.of(args));
        Path out = tmp.resolve("stdout.txt");
        Path err = tmp.resolve("stderr.txt");
        Process process = new ProcessBuilder(command).directory(tmp.toFile())
                .redirectOutput(out.toFile()).redirectError(err.toFile()).start();
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).as("the jar exits within a minute").isTrue();
        return new Result(process.exitValue(), Files.readString(out, StandardCharsets.UTF_8),
                Files.readString(err, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("the jar names its main class and version in the manifest")
    void manifest() throws IOException {
        try (JarFile jar = new JarFile(JAR.toFile())) {
            assertThat(jar.getManifest().getMainAttributes().getValue("Main-Class"))
                    .isEqualTo(InitializrCliMain.class.getName());
            assertThat(jar.getManifest().getMainAttributes().getValue("Implementation-Version")).isEqualTo(VERSION);
        }
    }

    @Test
    @DisplayName("--version prints the version the jar was built as")
    void version() throws IOException, InterruptedException {
        Result result = run("--version");
        assertThat(result.status()).isZero();
        assertThat(result.out().strip()).isEqualTo("exeris-initializr " + VERSION);
    }

    @Test
    @DisplayName("writes a backend whose parent is the version the jar was built as")
    void writesBackend() throws IOException, InterruptedException {
        Result result = run("--artifact-id=shop", "--output-dir=shop");
        assertThat(result.status()).as(result.err()).isZero();
        assertThat(tmp.resolve("shop/pom.xml")).content(StandardCharsets.UTF_8)
                .contains("""
                        <artifactId>exeris-app-parent</artifactId>
                                <version>%s</version>""".formatted(VERSION));
        assertThat(tmp.resolve("shop/src/main/java/com/example/shop/domain/Item.java")).exists();
    }

    @Test
    @DisplayName("exits 2 with EXT-INIT-5001 on an unknown switch, and 1 with EXT-INIT-5002 on a non-empty directory")
    void failures() throws IOException, InterruptedException {
        Result unknown = run("--capabilities=audit-trail");
        assertThat(unknown.status()).isEqualTo(2);
        assertThat(unknown.err()).contains("[Exeris] EXT-INIT-5001: Unknown switch: --capabilities");

        Result refused = run("--output-dir=.");
        assertThat(refused.status()).isEqualTo(1);
        assertThat(refused.err()).contains("[Exeris] EXT-INIT-5002: ");
    }
}
