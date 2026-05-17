package eu.exeris.tooling.codegen.java;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the body of {@link CodegenMain#main(String[])} via the unit-testable
 * {@link CodegenMain#runOrPrintError(String[], java.io.PrintStream)}. The
 * {@code main} method itself is a single statement that translates the
 * returned exit code into {@code System.exit} — the one structurally-unreachable
 * line from unit tests.
 */
class CodegenMainTest {

    @TempDir
    Path metadataDir;
    @TempDir
    Path outputDir;

    private final ObjectMapper mapper = CodegenPipeline.defaultMapper();

    private static String[] argsFor(Path metadataDir, Path outputDir, String basePackage) {
        if (basePackage == null) {
            return new String[]{
                    "--metadata-dir=" + metadataDir,
                    "--output-dir=" + outputDir
            };
        }
        return new String[]{
                "--metadata-dir=" + metadataDir,
                "--output-dir=" + outputDir,
                "--base-package=" + basePackage
        };
    }

    private static String capture(ByteArrayOutputStream buffer) {
        return buffer.toString(StandardCharsets.UTF_8);
    }

    @Nested
    @DisplayName("exit code 0 — happy path")
    class HappyPath {

        @Test
        @DisplayName("valid args + populated metadata dir → exit 0, output written, stderr empty")
        void happyPath() throws IOException {
            DomainMetadata domain = DomainMetadata.builder("Product", "com.shop.domain")
                    .module("catalog").path("/products").build();
            mapper.writeValue(metadataDir.resolve("Product.json").toFile(), domain);

            ByteArrayOutputStream err = new ByteArrayOutputStream();
            int exitCode = CodegenMain.runOrPrintError(
                    argsFor(metadataDir, outputDir, "com.shop"),
                    new PrintStream(err, true, StandardCharsets.UTF_8));

            assertThat(exitCode).isZero();
            assertThat(capture(err)).isEmpty();
            assertThat(outputDir.resolve("com/shop/Application.java")).exists();
        }

        @Test
        @DisplayName("valid args + empty metadata dir → exit 0, no error output")
        void emptyMetadataExitsZero() {
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            int exitCode = CodegenMain.runOrPrintError(
                    argsFor(metadataDir, outputDir, "com.shop"),
                    new PrintStream(err, true, StandardCharsets.UTF_8));

            assertThat(exitCode).isZero();
            assertThat(capture(err)).isEmpty();
        }
    }

    @Nested
    @DisplayName("exit code 1 — argument errors")
    class ArgErrors {

        @Test
        @DisplayName("missing --metadata-dir → exit 1, message + usage hint on stderr")
        void missingMetadataDir() {
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            int exitCode = CodegenMain.runOrPrintError(
                    new String[]{"--output-dir=" + outputDir},
                    new PrintStream(err, true, StandardCharsets.UTF_8));

            assertThat(exitCode).isOne();
            String stderr = capture(err);
            assertThat(stderr).contains("--metadata-dir");
            assertThat(stderr).contains("Usage: CodegenMain");
            assertThat(stderr).contains("--output-dir");
        }

        @Test
        @DisplayName("missing --output-dir → exit 1, message + usage hint on stderr")
        void missingOutputDir() {
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            int exitCode = CodegenMain.runOrPrintError(
                    new String[]{"--metadata-dir=" + metadataDir},
                    new PrintStream(err, true, StandardCharsets.UTF_8));

            assertThat(exitCode).isOne();
            assertThat(capture(err)).contains("--output-dir").contains("Usage: CodegenMain");
        }

        @Test
        @DisplayName("empty args → exit 1")
        void emptyArgs() {
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            int exitCode = CodegenMain.runOrPrintError(
                    new String[]{},
                    new PrintStream(err, true, StandardCharsets.UTF_8));

            assertThat(exitCode).isOne();
        }
    }

    @Nested
    @DisplayName("exit code 1 — pipeline failure")
    class PipelineFailure {

        @Test
        @DisplayName("malformed JSON in metadata dir → exit 1, no stderr (failure goes to logger)")
        void malformedJson() throws IOException {
            Files.writeString(metadataDir.resolve("Broken.json"), "{not valid json");

            ByteArrayOutputStream err = new ByteArrayOutputStream();
            int exitCode = CodegenMain.runOrPrintError(
                    argsFor(metadataDir, outputDir, "com.shop"),
                    new PrintStream(err, true, StandardCharsets.UTF_8));

            assertThat(exitCode).isOne();
            // Pipeline failures go through System.Logger, not the injected stderr.
            // We only assert exit code here; logger output is JDK-implementation
            // specific and not part of the contract.
        }
    }
}
