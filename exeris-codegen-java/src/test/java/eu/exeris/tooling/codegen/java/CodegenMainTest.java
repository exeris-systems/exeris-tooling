package eu.exeris.tooling.codegen.java;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.exeris.sdk.sourcemodel.ast.CapabilityModuleMetadata;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.FieldMetadata;
import eu.exeris.sdk.sourcemodel.ast.RequiresMetadata;
import eu.exeris.tooling.codegen.core.OutputWriter;
import eu.exeris.tooling.codegen.core.capability.CapabilityModuleDescriptor;
import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import eu.exeris.tooling.codegen.core.generator.GeneratorRegistry;
import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator;
import eu.exeris.tooling.codegen.java.kernel.KernelApplicationGenerator;
import eu.exeris.tooling.diagnostics.DiagnosticId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the body of {@link CodegenMain#main(String[])} through
 * {@link CodegenMain#runOrPrintError(String[], PrintStream, PrintStream)}: the arguments, the
 * exit statuses and the identifier each non-zero status prints (ADR-097 obligations 11 to 13).
 * {@code main} itself only hands the status to {@code System.exit}.
 */
class CodegenMainTest {

    @TempDir
    Path metadataDir;
    @TempDir
    Path outputDir;
    @TempDir
    Path testOutputDir;

    private final ObjectMapper mapper = CodegenPipeline.defaultMapper();
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private int run(String... args) {
        return CodegenMain.runOrPrintError(args,
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    private String[] argsFor(String... extra) {
        List<String> args = new ArrayList<>(List.of(
                "--metadata-dir=" + metadataDir, "--output-dir=" + outputDir, "--base-package=com.shop"));
        args.addAll(Arrays.asList(extra));
        return args.toArray(String[]::new);
    }

    private String stderr() {
        return err.toString(StandardCharsets.UTF_8);
    }

    private void writeProduct() throws IOException {
        DomainMetadata domain = DomainMetadata.builder("Product", "com.shop.domain")
                .module("catalog").path("/products").build();
        mapper.writeValue(metadataDir.resolve("Product.json").toFile(), domain);
    }

    /** A tree a previous run owns: one file and the manifest that lists it. */
    private Path ownedTree() throws IOException {
        Path owned = outputDir.resolve("com/shop/repository/OrderRepository.java");
        Files.createDirectories(owned.getParent());
        Files.writeString(owned, "class OrderRepository {}");
        Files.writeString(outputDir.resolve(OutputWriter.MANIFEST_NAME),
                "# Exeris Tooling generated-output manifest - DO NOT EDIT MANUALLY\n"
                        + "com/shop/repository/OrderRepository.java\n");
        return owned;
    }

    @Nested
    @DisplayName("exit status 0")
    class Success {

        @Test
        @DisplayName("valid arguments over populated metadata write the main root and print nothing")
        void happyPath() throws IOException {
            writeProduct();

            assertThat(run(argsFor())).isEqualTo(CodegenMain.EXIT_OK);
            assertThat(stderr()).isEmpty();
            assertThat(out.size()).isZero();
            assertThat(outputDir.resolve("com/shop/Application.java")).exists();
            assertThat(testOutputDir).isEmptyDirectory();
        }

        @Test
        @DisplayName("empty metadata over a tree no run owns is a success that writes nothing")
        void emptyMetadataExitsZero() {
            assertThat(run(argsFor())).isEqualTo(CodegenMain.EXIT_OK);
            assertThat(stderr()).isEmpty();
        }

        @Test
        @DisplayName("--tests also writes the generated tests, with their own manifest, into --test-output-dir")
        void testsWriteTheTestRoot() throws IOException {
            writeProduct();

            assertThat(run(argsFor("--tests", "--test-output-dir=" + testOutputDir))).isEqualTo(CodegenMain.EXIT_OK);
            assertThat(stderr()).isEmpty();
            assertThat(testOutputDir.resolve("com/shop/handler/ProductHandlerTest.java")).exists();
            assertThat(testOutputDir.resolve(OutputWriter.MANIFEST_NAME)).exists();
            assertThat(Files.readString(outputDir.resolve(OutputWriter.MANIFEST_NAME)))
                    .doesNotContain("ProductHandlerTest");
        }

        @Test
        @DisplayName("--allow-empty prunes the tree a previous run owns when no @ExerisDomain is found")
        void allowEmptyPrunesTheOwnedTree() throws IOException {
            Path owned = ownedTree();

            assertThat(run(argsFor("--allow-empty"))).isEqualTo(CodegenMain.EXIT_OK);
            assertThat(owned).doesNotExist();
        }
    }

    @Nested
    @DisplayName("exit status 2 — invalid arguments, EXT-GEN-3002 and the usage text")
    class InvalidArguments {

        private void assertUsageError(int status, String reason) {
            assertThat(status).isEqualTo(CodegenMain.EXIT_USAGE);
            assertThat(stderr())
                    .startsWith(DiagnosticId.CLI_ARGUMENTS_INVALID.format(""))
                    .contains(reason, "Usage: java -jar exeris-codegen-cli");
        }

        @Test
        @DisplayName("missing --metadata-dir")
        void missingMetadataDir() {
            assertUsageError(run("--output-dir=" + outputDir), "--metadata-dir");
        }

        @Test
        @DisplayName("missing --output-dir")
        void missingOutputDir() {
            assertUsageError(run("--metadata-dir=" + metadataDir), "--output-dir");
        }

        @Test
        @DisplayName("no arguments")
        void emptyArgs() {
            assertUsageError(run(), "--metadata-dir");
        }

        @Test
        @DisplayName("an unknown switch is an argument error, and nothing is generated")
        void unknownSwitch() throws IOException {
            writeProduct();

            assertUsageError(run(argsFor("--future-flag")), "Unknown switch: --future-flag");
            try (Stream<Path> written = Files.list(outputDir)) {
                assertThat(written).isEmpty();
            }
        }

        @Test
        @DisplayName("--tests without --test-output-dir")
        void testsWithoutTestOutputDir() {
            assertUsageError(run(argsFor("--tests")), "--tests requires --test-output-dir");
        }
    }

    @Nested
    @DisplayName("exit status 1 — refused or failed, with the event's identifier")
    class RefusedOrFailed {

        @Test
        @DisplayName("a field type the repository cannot persist prints EXT-GEN-3003 and writes nothing")
        void unpersistableFieldTypeIsPrinted() throws IOException {
            DomainMetadata domain = DomainMetadata.builder("Product", "com.shop.domain")
                    .module("catalog").path("/products")
                    .fields(List.of(FieldMetadata.simple(
                            "attributes", "java.util.Map<java.lang.String,java.lang.String>")))
                    .build();
            mapper.writeValue(metadataDir.resolve("Product.json").toFile(), domain);

            assertThat(run(argsFor())).isEqualTo(CodegenMain.EXIT_FAILED);
            assertThat(stderr())
                    .startsWith(DiagnosticId.FIELD_TYPE_NOT_PERSISTABLE.format(""))
                    .contains("com.shop.domain.Product.attributes");
            try (Stream<Path> written = Files.list(outputDir)) {
                assertThat(written).isEmpty();
            }
        }

        @Test
        @DisplayName("empty metadata over an owned tree prints EXT-PLUG-2001, names --allow-empty, and keeps the tree")
        void emptyMetadataOverOwnedTreeIsRefused() throws IOException {
            Path owned = ownedTree();

            assertThat(run(argsFor())).isEqualTo(CodegenMain.EXIT_FAILED);
            assertThat(stderr())
                    .startsWith(DiagnosticId.EMPTY_METADATA_REFUSED.format("Refusing to wipe"))
                    .contains("--allow-empty");
            assertThat(owned).exists();
        }

        @Test
        @DisplayName("an unresolved capability graph prints EXT-PLUG-2201 and is not deferred")
        void unresolvedCapabilityGraph() throws IOException {
            CapabilityModuleDescriptor checkout = new CapabilityModuleDescriptor("Checkout", "com.app",
                    "com.app.Checkout", CapabilityModuleMetadata.builder()
                    .provides(List.of())
                    .requires(List.of(RequiresMetadata.of("com.api.PaymentApi")))
                    .build());
            mapper.writeValue(metadataDir.resolve("capability_Checkout.json").toFile(), checkout);

            assertThat(run(argsFor())).isEqualTo(CodegenMain.EXIT_FAILED);
            assertThat(stderr())
                    .startsWith(DiagnosticId.CAPABILITY_GRAPH_UNRESOLVED.format(""))
                    .contains("com.api.PaymentApi")
                    .doesNotContain(DiagnosticId.CAPABILITY_GRAPH_DEFERRED.code());
            assertThat(outputDir.resolve("cap-manifest.json")).doesNotExist();
        }

        @Test
        @DisplayName("malformed metadata prints EXT-GEN-3001 with the cause")
        void malformedJson() throws IOException {
            Files.writeString(metadataDir.resolve("Broken.json"), "{not valid json");

            assertThat(run(argsFor())).isEqualTo(CodegenMain.EXIT_FAILED);
            assertThat(stderr())
                    .startsWith(DiagnosticId.CLI_GENERATION_FAILED.format("Code generation failed (metadataDir="))
                    .contains("JsonParseException");
        }

        @Test
        @DisplayName("a test root that cannot be written prints EXT-GEN-3001 after the main root is written")
        void unwritableTestRoot() throws IOException {
            writeProduct();
            Path notADirectory = Files.writeString(testOutputDir.resolve("file"), "x");

            assertThat(run(argsFor("--tests", "--test-output-dir=" + notADirectory)))
                    .isEqualTo(CodegenMain.EXIT_FAILED);
            assertThat(stderr())
                    .startsWith(DiagnosticId.CLI_GENERATION_FAILED.format("Test generation failed (testOutputDir="));
            assertThat(outputDir.resolve("com/shop/Application.java")).exists();
        }

        @Test
        @DisplayName("an unexpected failure prints EXT-GEN-3001 and its stack trace")
        void unexpectedFailure() throws IOException {
            writeProduct();
            GeneratorRegistry registry = new GeneratorRegistry();
            registry.register(new ThrowingGenerator());
            CodegenPipeline throwing = new CodegenPipeline(registry, new KernelApplicationGenerator(), mapper);
            CliArgs args = new CliArgs(metadataDir, outputDir, "com.shop");

            int status = CodegenMain.generate(args, throwing, new PrintStream(err, true, StandardCharsets.UTF_8));

            assertThat(status).isEqualTo(CodegenMain.EXIT_FAILED);
            assertThat(stderr())
                    .startsWith(DiagnosticId.CLI_GENERATION_FAILED.format("Code generation failed: "))
                    .contains("\tat ");
        }
    }

    @Nested
    @DisplayName("--print-catalogue")
    class PrintCatalogue {

        @Test
        @DisplayName("writes the committed catalogue to stdout byte for byte, with no other argument")
        void printsTheCatalogue() throws IOException {
            assertThat(run("--print-catalogue")).isEqualTo(CodegenMain.EXIT_OK);

            byte[] committed = Files.readAllBytes(
                    Path.of("src/main/resources/META-INF/exeris/generator-catalogue.json"));
            assertThat(out.toByteArray()).isEqualTo(committed);
            assertThat(stderr()).isEmpty();
        }

        @Test
        @DisplayName("prints the catalogue the classpath carries and generates nothing, whatever else is passed")
        void generatesNothing() throws IOException {
            writeProduct();

            assertThat(run(argsFor("--print-catalogue"))).isEqualTo(CodegenMain.EXIT_OK);

            byte[] resource;
            try (InputStream in = CodegenMain.class.getResourceAsStream(CodegenMain.CATALOGUE_RESOURCE)) {
                assertThat(in).isNotNull();
                resource = in.readAllBytes();
            }
            assertThat(out.toByteArray()).isEqualTo(resource);
            try (Stream<Path> written = Files.list(outputDir)) {
                assertThat(written).isEmpty();
            }
        }

        @Test
        @DisplayName("an unknown switch beside it is still an argument error")
        void unknownSwitchBesideIt() {
            assertThat(run("--print-catalogue", "--pretty")).isEqualTo(CodegenMain.EXIT_USAGE);
            assertThat(out.size()).isZero();
        }
    }

    @Nested
    @DisplayName("version")
    class Version {

        @Test
        @DisplayName("is unknown when the class is not loaded from a jar carrying Implementation-Version")
        void unknownOutsideTheJar() {
            assertThat(CodegenMain.version()).isEqualTo("unknown");
            assertThat(CodegenMain.versionOf(null)).isEqualTo("unknown");
        }

        @Test
        @DisplayName("is the package's Implementation-Version when it has one")
        void fromThePackage() {
            Package withVersion = Arrays.stream(Package.getPackages())
                    .filter(pkg -> pkg.getImplementationVersion() != null && !pkg.getImplementationVersion().isBlank())
                    .findFirst()
                    .orElseThrow();

            assertThat(CodegenMain.versionOf(withVersion)).isEqualTo(withVersion.getImplementationVersion());
        }
    }

    private static final class ThrowingGenerator implements KernelArtifactGenerator {
        @Override
        public GeneratedFile generate(DomainMetadata metadata) {
            throw new IllegalStateException("generator defect");
        }

        @Override
        public ArtifactType artifactType() {
            return ArtifactType.CONTROLLER;
        }
    }
}
