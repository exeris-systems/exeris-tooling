package eu.exeris.tooling.codegen.cli;

import eu.exeris.tooling.codegen.core.OutputWriter;
import eu.exeris.tooling.codegen.java.CodegenPipeline;
import eu.exeris.tooling.diagnostics.DiagnosticId;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shaded {@code exeris-codegen-cli} jar against {@link CodegenPipeline} (ADR-097
 * obligations 10 to 14).
 *
 * <p>Annotated fixtures go through {@code javac} with the real processor, so the metadata is what
 * a consumer's build produces. The jar runs over it as a separate {@code java -jar} process, the
 * pipeline in-process, both with the same explicit base package, and the two output trees, main
 * and test roots with their manifests, must be byte-identical. The jar is the one {@code package}
 * built, named by the {@code exeris.cli.jar} system property.
 */
@DisplayName("ADR-097 — the shaded jar writes what CodegenPipeline writes")
class CodegenCliJarIT {

    private static final String BASE_PACKAGE = "eu.exeris.cli.fixture";
    private static final String DOMAIN = "eu/exeris/cli/fixture/domain/";
    private static final String PROCESSOR = "eu.exeris.tooling.processor.ExerisDomainProcessor";
    private static final String CATALOGUE = "/META-INF/exeris/generator-catalogue.json";

    @TempDir
    static Path workspace;

    private static Path jar;
    private static Path metadata;

    record Result(int status, String stdout, byte[] stdoutBytes, String stderr) {
    }

    @BeforeAll
    static void compileTheFixture() throws IOException {
        jar = Path.of(System.getProperty("exeris.cli.jar", "")).toAbsolutePath();
        assertThat(jar).as("exeris.cli.jar names the shaded jar `package` built").isRegularFile();

        Path classes = workspace.resolve("classes");
        compileWithProcessor(workspace.resolve("src"), classes, sources());
        metadata = classes.resolve("exeris-metadata");
        try (Stream<Path> files = Files.list(metadata)) {
            assertThat(files.map(f -> f.getFileName().toString()))
                    .as("the processor wrote domain and capability metadata")
                    .anyMatch(name -> name.startsWith("capability_"))
                    .anyMatch(name -> name.equals("Beacon.json"));
        }
    }

    @Test
    @DisplayName("main and test roots, manifests included, are byte-identical to the in-process pipeline's")
    void sameOutputAsThePipeline() throws Exception {
        Path inProcessMain = workspace.resolve("in-process/main");
        Path inProcessTest = workspace.resolve("in-process/test");
        CodegenPipeline pipeline = CodegenPipeline.createDefault();
        pipeline.run(metadata, inProcessMain, BASE_PACKAGE, false, false);
        pipeline.runTests(metadata, inProcessTest, BASE_PACKAGE);

        Path jarMain = workspace.resolve("jar/main");
        Path jarTest = workspace.resolve("jar/test");
        Result result = runJar("--metadata-dir=" + metadata, "--output-dir=" + jarMain,
                "--base-package=" + BASE_PACKAGE, "--tests", "--test-output-dir=" + jarTest);

        assertThat(result.status()).as(result.stderr()).isZero();
        assertThat(result.stdout()).isEmpty();
        Map<String, byte[]> main = tree(jarMain);
        Map<String, byte[]> test = tree(jarTest);
        assertThat(main).containsKeys(OutputWriter.MANIFEST_NAME, "cap-manifest.json",
                "eu/exeris/cli/fixture/Application.java");
        assertThat(test).containsKey(OutputWriter.MANIFEST_NAME);
        assertSameTree(main, tree(inProcessMain));
        assertSameTree(test, tree(inProcessTest));
    }

    @Test
    @DisplayName("without --tests the jar writes the main root only, identical to run(...)")
    void mainRootOnly() throws Exception {
        Path inProcessMain = workspace.resolve("main-only/in-process");
        CodegenPipeline.createDefault().run(metadata, inProcessMain, BASE_PACKAGE, false, false);

        Path jarMain = workspace.resolve("main-only/jar");
        Result result = runJar("--metadata-dir=" + metadata, "--output-dir=" + jarMain,
                "--base-package=" + BASE_PACKAGE);

        assertThat(result.status()).as(result.stderr()).isZero();
        assertSameTree(tree(jarMain), tree(inProcessMain));
    }

    @Test
    @DisplayName("logs the version from the jar's manifest, which the generated files do not carry")
    void logsTheManifestVersion() throws Exception {
        String version = System.getProperty("exeris.cli.version");
        assertThat(version).isNotBlank();
        Path out = workspace.resolve("version/main");

        Result result = runJar("--metadata-dir=" + metadata, "--output-dir=" + out,
                "--base-package=" + BASE_PACKAGE);

        assertThat(result.status()).as(result.stderr()).isZero();
        assertThat(result.stderr()).contains("Exeris Java Code Generator " + version + " starting");
        for (Map.Entry<String, byte[]> file : tree(out).entrySet()) {
            assertThat(new String(file.getValue(), StandardCharsets.UTF_8)).as(file.getKey())
                    .doesNotContain(version);
        }
    }

    @Test
    @DisplayName("--print-catalogue writes the bundled catalogue byte for byte and exits 0")
    void printsTheCatalogue() throws Exception {
        Result result = runJar("--print-catalogue");

        assertThat(result.status()).as(result.stderr()).isZero();
        byte[] resource;
        try (InputStream in = CodegenPipeline.class.getResourceAsStream(CATALOGUE)) {
            assertThat(in).isNotNull();
            resource = in.readAllBytes();
        }
        assertThat(result.stdoutBytes()).isEqualTo(resource);
    }

    @Test
    @DisplayName("an unknown switch exits 2 with EXT-GEN-3002, and a refusal exits 1 with its identifier")
    void exitStatuses() throws Exception {
        Result unknown = runJar("--metadata-dir=" + metadata, "--output-dir=" + workspace.resolve("x"),
                "--no-such-switch");
        assertThat(unknown.status()).isEqualTo(2);
        assertThat(unknown.stderr()).contains(DiagnosticId.CLI_ARGUMENTS_INVALID.format("Unknown switch: --no-such-switch"));
        assertThat(workspace.resolve("x")).doesNotExist();

        Path owned = workspace.resolve("refused");
        Path empty = Files.createDirectories(workspace.resolve("empty-metadata"));
        assertThat(runJar("--metadata-dir=" + metadata, "--output-dir=" + owned).status()).isZero();
        Result refused = runJar("--metadata-dir=" + empty, "--output-dir=" + owned);
        assertThat(refused.status()).isEqualTo(1);
        assertThat(refused.stderr()).contains(DiagnosticId.EMPTY_METADATA_REFUSED.format(""));
        assertThat(owned.resolve(OutputWriter.MANIFEST_NAME)).exists();
    }

    @Test
    @DisplayName("the jar's manifest names CodegenMain and the version, and it carries no signature files")
    void jarShape() throws IOException {
        try (JarFile file = new JarFile(jar.toFile())) {
            Attributes main = file.getManifest().getMainAttributes();
            assertThat(main.getValue(Attributes.Name.MAIN_CLASS))
                    .isEqualTo("eu.exeris.tooling.codegen.java.CodegenMain");
            assertThat(main.getValue(Attributes.Name.IMPLEMENTATION_VERSION))
                    .isEqualTo(System.getProperty("exeris.cli.version"));
            List<String> names = file.stream().map(entry -> entry.getName()).toList();
            assertThat(names)
                    .contains("META-INF/exeris/generator-catalogue.json", "META-INF/LICENSE", "META-INF/NOTICE")
                    .noneMatch(name -> name.matches("META-INF/[^/]+\\.(SF|DSA|RSA)"))
                    .doesNotContain("module-info.class");
        }
    }

    // ------------------------------------------------------------------ helpers

    private static Result runJar(String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-jar");
        command.add(jar.toString());
        command.addAll(Arrays.asList(args));
        Path stdout = Files.createTempFile(workspace, "stdout", ".txt");
        Path stderr = Files.createTempFile(workspace, "stderr", ".txt");
        Process process = new ProcessBuilder(command)
                .redirectOutput(stdout.toFile())
                .redirectError(stderr.toFile())
                .start();
        assertThat(process.waitFor(5, TimeUnit.MINUTES)).as("the jar exits").isTrue();
        byte[] out = Files.readAllBytes(stdout);
        return new Result(process.exitValue(), new String(out, StandardCharsets.UTF_8), out,
                Files.readString(stderr, StandardCharsets.UTF_8));
    }

    /** Every regular file under {@code root}, by forward-slash relative path, in path order. */
    private static Map<String, byte[]> tree(Path root) throws IOException {
        Map<String, byte[]> files = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                files.put(root.relativize(file).toString().replace('\\', '/'), Files.readAllBytes(file));
            }
        }
        return files;
    }

    private static void assertSameTree(Map<String, byte[]> actual, Map<String, byte[]> expected) {
        assertThat(actual.keySet()).containsExactlyElementsOf(expected.keySet());
        for (Map.Entry<String, byte[]> file : expected.entrySet()) {
            assertThat(actual.get(file.getKey())).as(file.getKey()).isEqualTo(file.getValue());
        }
    }

    private static void compileWithProcessor(Path srcRoot, Path outputDir, Map<String, String> sources)
            throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("a JDK, not a JRE, runs this test").isNotNull();
        Files.createDirectories(outputDir);
        String classpath = System.getProperty("java.class.path");
        List<String> args = new ArrayList<>(List.of(
                "-d", outputDir.toString(),
                "-classpath", classpath,
                "-processorpath", classpath,
                "-processor", PROCESSOR,
                "-nowarn"));
        for (Map.Entry<String, String> source : sources.entrySet()) {
            Path file = srcRoot.resolve(source.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue());
            args.add(file.toString());
        }
        assertThat(compiler.run(null, null, null, args.toArray(String[]::new)))
                .as("fixture compilation (javac output above)").isZero();
    }

    /**
     * A build that reaches every kind of output: a {@code realTimeApi} entity with a domain event,
     * a saga and a streaming action; a versioned {@code TENANT} entity; a {@code @Graph} entity
     * with a {@code MANY_TO_ONE} to another entity of the build; and a capability module.
     */
    private static Map<String, String> sources() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put(DOMAIN + "Beacon.java", """
                package eu.exeris.cli.fixture.domain;

                import eu.exeris.sdk.annotation.Action;
                import eu.exeris.sdk.annotation.DomainEvent;
                import eu.exeris.sdk.annotation.ExerisDomain;
                import eu.exeris.sdk.annotation.Field;
                import eu.exeris.sdk.annotation.Saga;
                import eu.exeris.sdk.annotation.SagaStep;

                import java.util.UUID;

                @ExerisDomain(module = "live", path = "/beacons", realTimeApi = true)
                @DomainEvent(name = "BeaconLit", topic = "live.lit", trigger = DomainEvent.Trigger.CREATE)
                @Saga(name = "BeaconSaga", timeout = "PT5M", maxRetries = 2)
                public class Beacon {
                    private UUID id;
                    @Field(label = "Label", required = true) private String label;
                    @Action(name = "track", label = "Track", streaming = true)
                    public void track() { }
                    @SagaStep(order = 0, name = "relay", service = "relay", command = "Relay")
                    public void relay() { }
                }
                """);
        sources.put(DOMAIN + "Ledger.java", """
                package eu.exeris.cli.fixture.domain;

                import eu.exeris.sdk.annotation.ExerisDomain;
                import eu.exeris.sdk.annotation.Field;

                import java.util.UUID;

                @ExerisDomain(module = "books", path = "/ledgers", dataScope = ExerisDomain.DataScope.TENANT,
                        versioned = true)
                public class Ledger {
                    private UUID id;
                    @Field(label = "Name", required = true) private String name;
                    @Field(label = "Tenant") private UUID tenantId;
                    @Field(label = "Version") private long version;
                }
                """);
        sources.put(DOMAIN + "Customer.java", """
                package eu.exeris.cli.fixture.domain;

                import eu.exeris.sdk.annotation.ExerisDomain;
                import eu.exeris.sdk.annotation.Field;

                import java.util.UUID;

                @ExerisDomain(module = "sales", path = "/customers")
                public class Customer {
                    private UUID id;
                    @Field(label = "Name") private String name;
                }
                """);
        sources.put(DOMAIN + "Shipment.java", """
                package eu.exeris.cli.fixture.domain;

                import eu.exeris.sdk.annotation.ExerisDomain;
                import eu.exeris.sdk.annotation.Field;
                import eu.exeris.sdk.annotation.Graph;
                import eu.exeris.sdk.annotation.Relationship;

                import java.util.UUID;

                @ExerisDomain(module = "sales", path = "/shipments")
                @Graph(nodeClass = "Shipment")
                public class Shipment {
                    private UUID id;
                    @Field(label = "Reference") private String reference;
                    @Relationship(targetEntity = Customer.class, displayField = "name")
                    private UUID customerId;
                }
                """);
        sources.put("eu/exeris/cli/fixture/caps/AuditModule.java", """
                package eu.exeris.cli.fixture.caps;

                import eu.exeris.sdk.annotation.capability.CapabilityModule;

                @CapabilityModule
                public class AuditModule {
                }
                """);
        return sources;
    }
}
