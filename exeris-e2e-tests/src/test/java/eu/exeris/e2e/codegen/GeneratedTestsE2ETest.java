package eu.exeris.e2e.codegen;

import eu.exeris.e2e.codegen.compile.ProcessorCompiler;
import eu.exeris.tooling.codegen.java.CodegenPipeline;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The T2 gate: the generated tests are compiled <b>and executed</b> against the generated code
 * they cover.
 *
 * <p>Compiling them would only prove they are syntactically valid against the current emitters. A
 * test emitter whose output is never run is the inert-output failure mode this repo refuses
 * everywhere else — a green build would say nothing about whether {@code OrderHandlerTest} passes,
 * and a wrong expected status would ship silently. So this walks the whole path:
 *
 * <pre>
 *   @ExerisDomain source
 *        → javac + processor            (real metadata)
 *        → CodegenPipeline.run          (handler + service + repository + …)
 *        → CodegenPipeline.runTests     (the generated tests, ADR-058)
 *        → javac over BOTH trees        (they must compile together)
 *        → JUnit Platform launcher      (they must pass)
 * </pre>
 *
 * <p>It also pins the ADR-058 dependency contract in the only way that cannot drift: the emitted
 * trees are compiled against a classpath carrying the kernel SPI, JUnit and AssertJ — and nothing
 * else. An emitter that starts reaching for a mocking framework fails here.
 */
@Tag("e2e")
@Tag("codegen")
@DisplayName("Generated tests (T2): the emitted tests compile against the emitted code and pass")
class GeneratedTestsE2ETest {

    private static final String BASE_PACKAGE = "com.shop";

    @TempDir
    static Path workspace;

    private static Path entityClasses;
    private static Path generatedMain;
    private static Path generatedTests;
    private static Path compiled;

    @BeforeAll
    static void generateAndCompile() throws IOException {
        entityClasses = workspace.resolve("target/classes");
        Path classes = entityClasses;
        generatedMain = workspace.resolve("src/main/generated/java");
        generatedTests = workspace.resolve("src/test/generated/java");
        compiled = workspace.resolve("target/test-classes");

        ProcessorCompiler.compile(workspace.resolve("src"), classes, null, sources());

        CodegenPipeline pipeline = CodegenPipeline.createDefault();
        Path metadataDir = classes.resolve("exeris-metadata");
        pipeline.run(metadataDir, generatedMain, BASE_PACKAGE);
        pipeline.runTests(metadataDir, generatedTests, BASE_PACKAGE);

        compile(javaSourcesUnder(generatedMain, generatedTests), compiled);
    }

    @Test
    @DisplayName("the emitted tests land in the TEST root, never in the main one")
    void testsLandInTheirOwnRoot() {
        // The whole reason runTests takes a separate output root: a test under src/main/generated
        // would compile into the application artefact and drag JUnit onto its runtime classpath.
        assertThat(generatedTests.resolve("com/shop/handler/OrderHandlerTest.java")).exists();
        assertThat(generatedTests.resolve("com/shop/testsupport/RecordingHttpExchange.java")).exists();
        assertThat(generatedMain.resolve("com/shop/handler/OrderHandlerTest.java")).doesNotExist();
        assertThat(generatedMain.resolve("com/shop/handler/OrderHandler.java")).exists();
    }

    @Test
    @DisplayName("each tree owns its own T13 manifest, so pruning one can never touch the other")
    void eachRootHasItsOwnManifest() {
        assertThat(generatedMain.resolve(".exeris-codegen-manifest")).exists();
        assertThat(generatedTests.resolve(".exeris-codegen-manifest")).exists();
    }

    @Test
    @DisplayName("the emitted tests pass when run against the emitted code")
    void generatedTestsPass() throws Exception {
        // Both roots: the generated tree AND the @ExerisDomain entity the processor compiled.
        try (URLClassLoader appLoader = new URLClassLoader(
                new URL[]{compiled.toUri().toURL(), entityClasses.toUri().toURL()},
                GeneratedTestsE2ETest.class.getClassLoader())) {

            LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                    .selectors(DiscoverySelectors.selectClass(
                            Class.forName("com.shop.handler.OrderHandlerTest", true, appLoader)))
                    .build();

            Launcher launcher = LauncherFactory.create();
            SummaryGeneratingListener listener = new SummaryGeneratingListener();
            ClassLoader previous = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(appLoader);
            try {
                launcher.execute(request, listener);
            } finally {
                Thread.currentThread().setContextClassLoader(previous);
            }

            TestExecutionSummary summary = listener.getSummary();
            assertThat(summary.getTestsFailedCount())
                    .as("generated-test failures:%n%s", render(summary))
                    .isZero();
            // Guard against a vacuous pass: an emitter that stopped emitting @Test methods would
            // otherwise "succeed" with zero executed tests.
            assertThat(summary.getTestsSucceededCount()).isEqualTo(5);
        }
    }

    private static String render(TestExecutionSummary summary) {
        StringWriter out = new StringWriter();
        summary.printFailuresTo(new PrintWriter(out), 20);
        return out.toString();
    }

    /**
     * Compiles the generated sources against the ADR-058 contract classpath.
     *
     * <p>The classpath is <b>named</b>, not inherited. Handing javac
     * {@code System.getProperty("java.class.path")} would make the "and nothing else" half of the
     * contract accidentally true: it holds only for as long as nobody adds a mocking framework to
     * this module, and the day someone does, an emitter could start importing it and this gate
     * would stay green. Naming the permitted artefacts one anchor class at a time makes the
     * contract the thing under test.
     */
    private static void compile(List<String> files, Path outputDir) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("a JDK (not a JRE) is required").isNotNull();
        Files.createDirectories(outputDir);

        List<String> args = new ArrayList<>(List.of(
                "-d", outputDir.toString(),
                "-classpath", contractClasspath(),
                // The kernel SPI ships preview-tagged class files; javac refuses to load them
                // without this, exactly as InMemoryJavaCompiler does for the same reason.
                "--enable-preview", "--release", "26",
                "-nowarn"));
        args.addAll(files);

        ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
        int rc = compiler.run(null, null, diagnostics, args.toArray(String[]::new));
        assertThat(rc).as("generated sources must compile:%n%s", diagnostics).isZero();
    }

    /**
     * Everything the emitted trees are allowed to see, and nothing more.
     *
     * <p>Two groups, for two different reasons. The kernel entries are what the generated
     * <em>main</em> code targets — a downstream app has them because that is what it runs on. The
     * JUnit and AssertJ entries are the ADR-058 §2 dependency contract for the generated
     * <em>tests</em>: turning {@code -Dexeris.tests=true} on must cost a consumer those two
     * test-scope dependencies and no others, because tooling emits no {@code pom.xml} and so cannot
     * declare them.
     */
    private static String contractClasspath() {
        List<String> entries = new ArrayList<>(List.of(
                // What the generated main code binds.
                codeSourceOf("eu.exeris.kernel.spi.http.HttpExchange"),
                codeSourceOf("eu.exeris.kernel.core.bootstrap.KernelBootstrap"),
                // Eight emitters put an slf4j Logger in their output, so generated main code has a
                // third-party compile dependency no ADR names. It reaches a downstream app only
                // through the kernel's driver tier (exeris-kernel-community pulls slf4j-api;
                // neither SPI nor Core does), which is exactly the kind of fact an inherited
                // classpath hides — this line is what made it visible.
                codeSourceOf("org.slf4j.Logger"),
                // What the generated tests may import — ADR-058 §2.
                codeSourceOf("org.junit.jupiter.api.Test"),
                codeSourceOf("org.assertj.core.api.Assertions"),
                // The @ExerisDomain entity the processor compiled; generated code binds it.
                entityClasses.toString()));
        return String.join(File.pathSeparator, entries);
    }

    /** Resolves one permitted artefact to the jar (or classes dir) it was loaded from. */
    private static String codeSourceOf(String className) {
        try {
            CodeSource source = Class.forName(className).getProtectionDomain().getCodeSource();
            assertThat(source).as("no code source for contract anchor %s", className).isNotNull();
            return Path.of(source.getLocation().toURI()).toString();
        } catch (ClassNotFoundException | URISyntaxException e) {
            throw new IllegalStateException("contract-classpath anchor is missing: " + className, e);
        }
    }

    private static List<String> javaSourcesUnder(Path... roots) throws IOException {
        List<String> files = new ArrayList<>();
        for (Path root : roots) {
            try (Stream<Path> tree = Files.walk(root)) {
                tree.filter(p -> p.toString().endsWith(".java")).map(Path::toString).forEach(files::add);
            }
        }
        return files;
    }

    private static Map<String, String> sources() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("com/shop/domain/Order.java",
                """
                package com.shop.domain;

                import eu.exeris.sdk.annotation.ExerisDomain;
                import eu.exeris.sdk.annotation.Field;

                import java.util.UUID;

                @ExerisDomain(module = "sales", path = "/orders")
                public class Order {

                    private UUID id;

                    @Field(label = "Order Number", required = true)
                    private String orderNumber;

                    public UUID getId() {
                        return id;
                    }

                    public void setId(UUID id) {
                        this.id = id;
                    }

                    public String getOrderNumber() {
                        return orderNumber;
                    }

                    public void setOrderNumber(String orderNumber) {
                        this.orderNumber = orderNumber;
                    }
                }
                """);
        return sources;
    }
}
