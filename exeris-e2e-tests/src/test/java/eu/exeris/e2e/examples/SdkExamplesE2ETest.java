package eu.exeris.e2e.examples;

import eu.exeris.e2e.codegen.compile.EmittedJavac;
import eu.exeris.e2e.codegen.compile.ProcessorCompiler;
import eu.exeris.tooling.codegen.core.capability.CapabilityGraphException;
import eu.exeris.tooling.codegen.java.CodegenPipeline;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.platform.engine.discovery.ClassSelector;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

/**
 * The SDK's example corpus, at the tag of the SDK version this build compiles against, through
 * the Java half of the pipeline:
 *
 * <pre>
 *   exeris-sdk-examples sources (tag v&lt;sdk version&gt;)
 *        → javac + processor            (real metadata, no processor error or warning)
 *        → CodegenPipeline.run / runTests
 *        → javac over both emitted trees (no error or warning)
 *        → JUnit Platform launcher      (the emitted tests pass)
 *        → the same again in a second workspace, compared file by file
 * </pre>
 *
 * <p>The guides quote these examples, so an example the pipeline cannot carry is a guide that
 * does not work. Every scenario directory at the tag is classified here: a domain scenario joins
 * the generated application, and a scenario the pipeline refuses is asserted to be refused with
 * its exact reason. A scenario this class does not name fails {@link #everyScenarioIsClassified}.
 *
 * <p>The Java application and the frontend half are generated from every domain scenario. The
 * metadata of the domain scenarios stays at
 * {@code target/sdk-examples/app/target/classes/exeris-metadata} for the frontend half,
 * {@code exeris-codegen-ts/scripts/gen-sdk-examples-app.mjs}, which generates the Angular
 * application from it.
 *
 * <p>Without a source for the tag the class is skipped, except under {@code CI=true}, where it
 * fails: a CI run that skipped would report a gate it never ran.
 */
@Tag("e2e")
@Tag("codegen")
@DisplayName("SDK examples: the example corpus at the pinned SDK tag through the processor and the Java pipeline")
class SdkExamplesE2ETest {

    /** The package the generated application's own classes hang off. */
    private static final String BASE_PACKAGE = "eu.exeris.sdk.examples.app";

    /** Scenarios whose entities and views together form the generated application. */
    private static final List<String> DOMAIN_SCENARIOS = List.of("graph", "invoicing", "ordering", "presentation");

    /** Scenarios the pipeline refuses, each asserted by its own test. */
    private static final List<String> REFUSED_SCENARIOS = List.of("capability");

    /** Where the generated application is built, under this module's directory. */
    private static final Path WORKSPACE = Path.of("target", "sdk-examples");

    /** Every entity of the domain scenarios, by the scenario that declares it. */
    private static final Map<String, List<String>> ENTITIES = Map.of(
            "graph", List.of("Category", "Product"),
            "invoicing", List.of("Customer", "Invoice", "InvoiceLine"),
            "ordering", List.of("Order"),
            "presentation", List.of("Employee"));

    /**
     * The test cases the emitted tests of the application run, every one of which must pass. The
     * five {@code ApplicationRoutePolicyTest} cases are among them; no example entity declares
     * access or a stream, so the route-policy test has no generated-table case and no handler test
     * a stream case.
     */
    private static final long GENERATED_TEST_CASES = 352;

    @TempDir
    static Path scratch;

    private static Path sourceRoot;
    private static Build app;

    /** One processor run: its outcome and the metadata directory it wrote. */
    private record Extraction(ProcessorCompiler.Result result, Path metadata, Path classes) {
    }

    /** One run of the pipeline over the domain scenarios, in its own workspace. */
    private record Build(Extraction extraction, Path generatedMain, Path generatedTests, Path compiled,
                         EmittedJavac.Result javac) {
    }

    @BeforeAll
    static void generateTheApplication() throws IOException {
        String version = SdkExamplesSource.pinnedVersion();
        List<String> why = new ArrayList<>();
        Path export = Files.createDirectories(scratch.resolve("sdk-v" + version));
        sourceRoot = SdkExamplesSource.resolve(version, export, why).orElse(null);
        if (sourceRoot == null) {
            String message = "no exeris-sdk source at tag v" + version + ": " + String.join("; ", why)
                    + ". Set -D" + SdkExamplesSource.DIR_PROPERTY + "=<exeris-sdk checkout at v" + version + ">.";
            if ("true".equals(System.getenv("CI"))) {
                fail(message);
            }
            Assumptions.abort(message);
        }
        app = build(WORKSPACE.resolve("app"), sourcesOf(DOMAIN_SCENARIOS));
    }

    @Test
    @DisplayName("every scenario at the tag is either part of the application or an asserted refusal")
    void everyScenarioIsClassified() throws IOException {
        List<String> classified = new ArrayList<>(DOMAIN_SCENARIOS);
        classified.addAll(REFUSED_SCENARIOS);
        try (Stream<Path> dirs = Files.list(sourceRoot.resolve(SdkExamplesSource.SCENARIO_PACKAGE))) {
            assertThat(dirs.filter(Files::isDirectory).map(p -> p.getFileName().toString()).sorted())
                    .as("scenario packages with main sources at the pinned tag")
                    .containsExactlyInAnyOrderElementsOf(classified);
        }
    }

    @Test
    @DisplayName("the processor accepts the corpus with no error or warning and writes one metadata file "
            + "per entity and per view")
    void processorAcceptsTheCorpus() throws IOException {
        assertThat(problems(app.extraction().result())).as("processor errors and warnings").isEmpty();
        assertThat(app.extraction().result().success()).isTrue();
        try (Stream<Path> files = Files.list(app.extraction().metadata())) {
            assertThat(files.map(p -> p.getFileName().toString()).sorted())
                    .containsExactly("Category.json", "Customer.json", "Employee.json", "Invoice.json",
                            "InvoiceLine.json", "Order.json", "Product.json", "view_EmployeeDirectory.json");
        }
    }

    @Test
    @DisplayName("every entity of the corpus is served by an emitted handler, service and repository")
    void everyEntityIsServed() {
        DOMAIN_SCENARIOS.forEach(scenario -> {
            List<String> entities = ENTITIES.get(scenario);
            Path base = app.generatedMain().resolve(SdkExamplesSource.SCENARIO_PACKAGE).resolve(scenario);
            for (String entity : entities) {
                assertThat(base.resolve("handler/" + entity + "Handler.java")).exists();
                assertThat(base.resolve("service/" + entity + "Service.java")).exists();
                assertThat(base.resolve("repository/" + entity + "Repository.java")).exists();
            }
        });
    }

    @Test
    @DisplayName("the emitted main and test trees compile against the example entities with no error or warning")
    void emittedTreesCompileClean() {
        assertThat(app.javac().clean()).as("emitted trees compile without errors or warnings:%n%s",
                app.javac().render()).isTrue();
    }

    @Test
    @DisplayName("the emitted tests pass when run against the emitted code and the example entities")
    void generatedTestsPass() throws Exception {
        assertThat(app.javac().clean()).as("emitted trees compile:%n%s", app.javac().render()).isTrue();
        try (URLClassLoader appLoader = new URLClassLoader(
                new URL[]{app.compiled().toUri().toURL(), app.extraction().classes().toUri().toURL()},
                SdkExamplesE2ETest.class.getClassLoader())) {
            List<ClassSelector> selectors = new ArrayList<>();
            for (String testClass : emittedTestClasses(app.generatedTests())) {
                selectors.add(DiscoverySelectors.selectClass(Class.forName(testClass, true, appLoader)));
            }
            assertThat(selectors).as("emitted test classes").isNotEmpty();
            LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request().selectors(selectors).build();

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
            assertThat(summary.getTestsFailedCount()).as("emitted-test failures:%n%s", render(summary)).isZero();
            assertThat(summary.getTestsSucceededCount()).as("emitted test cases that passed")
                    .isEqualTo(GENERATED_TEST_CASES);
        }
    }

    @Test
    @DisplayName("a second run over the same sources writes byte-identical metadata and emitted trees")
    void generationIsDeterministic() throws IOException {
        Build rerun = build(WORKSPACE.resolve("rerun"), sourcesOf(DOMAIN_SCENARIOS));
        assertThat(contents(rerun.extraction().metadata())).isEqualTo(contents(app.extraction().metadata()));
        assertThat(contents(rerun.generatedMain())).isEqualTo(contents(app.generatedMain()));
        assertThat(contents(rerun.generatedTests())).isEqualTo(contents(app.generatedTests()));
    }

    @Test
    @DisplayName("capability: LedgerCap requires AuditTrail, which no module of the scenario provides")
    void capabilityScenarioIsRefusedOnItsUnprovidedRequirement() throws IOException {
        // The scenario declares one capability's composition surface; the provider of its required
        // service is another capability, which the example does not ship. The pipeline refuses an
        // unsatisfied non-optional @Requires, and leaves the optional one a warning.
        Extraction capability = extract(scratch.resolve("capability"), sourcesOf(REFUSED_SCENARIOS));
        assertThat(problems(capability.result())).as("processor errors and warnings").isEmpty();
        try (Stream<Path> files = Files.list(capability.metadata())) {
            assertThat(files.map(p -> p.getFileName().toString())).containsExactly("capability_LedgerCap.json");
        }

        assertThatThrownBy(() -> CodegenPipeline.createDefault()
                .run(capability.metadata(), scratch.resolve("capability/src/main/generated/java"), BASE_PACKAGE))
                .isInstanceOfSatisfying(CapabilityGraphException.class, e -> assertThat(e.problems())
                        .containsExactly("module eu.exeris.sdk.examples.capability.LedgerCap @Requires service"
                                + " eu.exeris.sdk.examples.capability.AuditTrail version [1.0.0,2.0.0)"
                                + " but no @CapabilityModule provides it"));
    }

    /** Runs the processor alone over {@code sources}, in a workspace that starts empty. */
    private static Extraction extract(Path workspace, Map<String, String> sources) throws IOException {
        deleteTree(workspace);
        Path classes = workspace.resolve("target/classes");
        ProcessorCompiler.Result result = ProcessorCompiler.run(workspace.resolve("src/main/java"), classes, null,
                sources, List.of());
        return new Extraction(result, classes.resolve("exeris-metadata"), classes);
    }

    /** Processor, pipeline and javac over {@code sources}, in a workspace that starts empty. */
    private static Build build(Path workspace, Map<String, String> sources) throws IOException {
        Extraction extraction = extract(workspace, sources);
        assertThat(problems(extraction.result())).as("processor errors and warnings").isEmpty();
        Path generatedMain = workspace.resolve("src/main/generated/java");
        Path generatedTests = workspace.resolve("src/test/generated/java");
        Path compiled = workspace.resolve("target/app-classes");

        CodegenPipeline pipeline = CodegenPipeline.createDefault();
        pipeline.run(extraction.metadata(), generatedMain, BASE_PACKAGE);
        pipeline.runTests(extraction.metadata(), generatedTests, BASE_PACKAGE);

        List<String> files = new ArrayList<>();
        javaSourcesUnder(generatedMain).forEach(p -> files.add(p.toString()));
        javaSourcesUnder(generatedTests).forEach(p -> files.add(p.toString()));
        EmittedJavac.Result javac = EmittedJavac.compile(files, compiled,
                System.getProperty("java.class.path") + File.pathSeparator + extraction.classes());
        return new Build(extraction, generatedMain, generatedTests, compiled, javac);
    }

    /** The errors and warnings of a processor run, one line each. */
    private static List<String> problems(ProcessorCompiler.Result result) {
        List<String> problems = new ArrayList<>();
        for (Diagnostic<? extends JavaFileObject> d : result.diagnostics()) {
            if (d.getKind() == Diagnostic.Kind.ERROR || d.getKind() == Diagnostic.Kind.WARNING
                    || d.getKind() == Diagnostic.Kind.MANDATORY_WARNING) {
                problems.add(d.getKind() + " " + d.getMessage(Locale.ROOT));
            }
        }
        return problems;
    }

    /** The binary name of every emitted test class: a top-level type in a {@code *Test.java} file. */
    private static List<String> emittedTestClasses(Path generatedTests) throws IOException {
        List<String> classes = new ArrayList<>();
        for (Path file : javaSourcesUnder(generatedTests)) {
            String relative = generatedTests.relativize(file).toString().replace(File.separatorChar, '/');
            if (relative.endsWith("Test.java")) {
                classes.add(relative.substring(0, relative.length() - ".java".length()).replace('/', '.'));
            }
        }
        return classes;
    }

    private static String render(TestExecutionSummary summary) {
        StringWriter out = new StringWriter();
        summary.printFailuresTo(new PrintWriter(out), 20);
        return out.toString();
    }

    /** The main sources of {@code scenarios}, relative path below the source root → text. */
    private static Map<String, String> sourcesOf(List<String> scenarios) throws IOException {
        Map<String, String> sources = new LinkedHashMap<>();
        for (String scenario : scenarios) {
            Path dir = sourceRoot.resolve(SdkExamplesSource.SCENARIO_PACKAGE).resolve(scenario);
            for (Path file : javaSourcesUnder(dir)) {
                String relative = sourceRoot.relativize(file).toString().replace(File.separatorChar, '/');
                sources.put(relative, Files.readString(file));
            }
        }
        return sources;
    }

    /** Every regular file under {@code root}, relative path → text, for an exact comparison. */
    private static Map<String, String> contents(Path root) throws IOException {
        Map<String, String> contents = new TreeMap<>();
        try (Stream<Path> tree = Files.walk(root)) {
            for (Path file : tree.filter(Files::isRegularFile).toList()) {
                contents.put(root.relativize(file).toString().replace(File.separatorChar, '/'),
                        Files.readString(file));
            }
        }
        return contents;
    }

    private static List<Path> javaSourcesUnder(Path root) throws IOException {
        try (Stream<Path> tree = Files.walk(root)) {
            return tree.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> tree = Files.walk(root)) {
            for (Path p : tree.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }
}
