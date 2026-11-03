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

import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 *        → javac + processor            (real metadata)
 *        → CodegenPipeline.run / runTests
 *        → javac over both emitted trees
 *        → the same again in a second workspace, compared file by file
 * </pre>
 *
 * <p>The emitted trees do not compile against the example entities; the diagnostics are
 * pinned to two known gaps in {@link #emittedTreesFailOnlyOnTheKnownEntityShapeGaps}, so a new
 * kind of failure still fails the gate.
 *
 * <p>The guides quote these examples, so an example the pipeline cannot carry is a guide that
 * does not work. Every scenario directory at the tag is classified here: a domain scenario joins
 * the generated application, and a scenario the pipeline refuses is asserted to be refused with
 * its exact reason. A scenario this class does not name fails {@link #everyScenarioIsClassified}.
 *
 * <p>The Java pipeline places an entity's handler, service and repository by replacing the
 * {@code .domain} suffix of the entity's package, and refuses an entity whose package has none.
 * The examples' packages ({@code eu.exeris.sdk.examples.<scenario>}) have none, so the corpus as
 * written is asserted to be refused, and the application is generated from a copy in which each
 * scenario's sources move to {@code eu.exeris.sdk.examples.<scenario>.domain} and nothing else
 * changes.
 *
 * <p>The metadata of the generated application stays at
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

    /** A javac "cannot find symbol" on a method: its name, its argument types, the type searched. */
    private static final Pattern MISSING_METHOD =
            Pattern.compile("symbol:\\s+method (\\w+)\\(([^)]*)\\)\\s+location: (?:.* of type|class) ([\\w.]+)");

    private static final Pattern ACCESSOR = Pattern.compile("(get|set|is)[A-Z]\\w*");

    @TempDir
    static Path scratch;

    private static Path sourceRoot;
    private static Build app;

    /** One run of the pipeline over the domain corpus, in its own workspace. */
    private record Build(Path metadata, Path generatedMain, Path generatedTests, EmittedJavac.Result javac) {
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
        app = build(WORKSPACE.resolve("app"), underDomainPackages(sourcesOf(DOMAIN_SCENARIOS)));
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
    @DisplayName("the processor writes one metadata file per entity and per view of the corpus")
    void processorCoversTheCorpus() throws IOException {
        try (Stream<Path> files = Files.list(app.metadata())) {
            assertThat(files.map(p -> p.getFileName().toString()).sorted())
                    .containsExactly("Category.json", "Customer.json", "Employee.json", "Invoice.json",
                            "InvoiceLine.json", "Order.json", "Product.json", "view_EmployeeDirectory.json");
        }
    }

    @Test
    @DisplayName("the corpus as written is refused: its entity packages carry no .domain suffix")
    void corpusAsWrittenIsRefusedForItsPackageLayout() throws IOException {
        Path workspace = scratch.resolve("as-written");
        Path classes = workspace.resolve("target/classes");
        ProcessorCompiler.compile(workspace.resolve("src/main/java"), classes, null, sourcesOf(DOMAIN_SCENARIOS));

        assertThatThrownBy(() -> CodegenPipeline.createDefault()
                .run(classes.resolve("exeris-metadata"), workspace.resolve("src/main/generated/java"), BASE_PACKAGE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("Domain package 'eu.exeris.sdk.examples.graph' for entity 'Category'"
                        + " does not end with '.domain'.");
    }

    @Test
    @DisplayName("every entity of the corpus is served by an emitted handler, service and repository")
    void everyEntityIsServed() {
        ENTITIES.forEach((scenario, entities) -> {
            Path base = app.generatedMain().resolve(SdkExamplesSource.SCENARIO_PACKAGE).resolve(scenario);
            for (String entity : entities) {
                assertThat(base.resolve("handler/" + entity + "Handler.java")).exists();
                assertThat(base.resolve("service/" + entity + "Service.java")).exists();
                assertThat(base.resolve("repository/" + entity + "Repository.java")).exists();
            }
        });
    }

    @Test
    @DisplayName("the emitted trees fail to compile on exactly two gaps between the example entities and the emitters")
    void emittedTreesFailOnlyOnTheKnownEntityShapeGaps() {
        // The emitted repositories, handlers, event publishers, graph sync and tests bind an entity
        // through JavaBean accessors, and the example entities declare private fields and no
        // accessors. The emitted repositories also read an entity-typed reference field
        // (@Relationship MANY_TO_ONE, @GraphEdge) back through the target's valueOf(String), which
        // an entity class does not declare. Every diagnostic must be one of those two, on an example
        // entity; anything else is a new defect. When the emitted trees compile, this test fails:
        // it is then replaced by a clean-compile assertion and a run of the emitted tests.
        EmittedJavac.Result javac = app.javac();
        assertThat(javac.success())
                .as("the emitted trees compile: assert a clean compile and run the emitted tests instead")
                .isFalse();

        Set<String> entities = new TreeSet<>();
        ENTITIES.values().forEach(entities::addAll);
        Set<String> withoutAccessor = new TreeSet<>();
        Set<String> readThroughValueOf = new TreeSet<>();
        List<String> other = new ArrayList<>();
        for (Diagnostic<? extends JavaFileObject> d : javac.diagnostics()) {
            if (d.getKind() != Diagnostic.Kind.ERROR && d.getKind() != Diagnostic.Kind.WARNING
                    && d.getKind() != Diagnostic.Kind.MANDATORY_WARNING) {
                continue;
            }
            Matcher m = MISSING_METHOD.matcher(d.getMessage(Locale.ROOT));
            String type = m.find() ? m.group(3).substring(m.group(3).lastIndexOf('.') + 1) : null;
            if (d.getKind() == Diagnostic.Kind.ERROR && type != null && entities.contains(type)
                    && ACCESSOR.matcher(m.group(1)).matches()) {
                withoutAccessor.add(type);
            } else if (d.getKind() == Diagnostic.Kind.ERROR && type != null && entities.contains(type)
                    && "valueOf".equals(m.group(1)) && m.group(2).endsWith("String")) {
                readThroughValueOf.add(type);
            } else {
                other.add(d.getKind() + " " + d.getSource().getName() + ":" + d.getLineNumber() + " "
                        + d.getMessage(Locale.ROOT));
            }
        }
        assertThat(other).as("diagnostics outside the two known gaps").isEmpty();
        assertThat(withoutAccessor).as("entities the emitted code calls a missing accessor on")
                .containsExactlyElementsOf(entities);
        assertThat(readThroughValueOf).as("reference targets read back through valueOf(String)")
                .containsExactly("Category", "Customer", "Invoice");
    }

    @Test
    @DisplayName("a second run over the same sources writes byte-identical metadata and emitted trees")
    void generationIsDeterministic() throws IOException {
        Build rerun = build(WORKSPACE.resolve("rerun"), underDomainPackages(sourcesOf(DOMAIN_SCENARIOS)));
        assertThat(contents(rerun.metadata())).isEqualTo(contents(app.metadata()));
        assertThat(contents(rerun.generatedMain())).isEqualTo(contents(app.generatedMain()));
        assertThat(contents(rerun.generatedTests())).isEqualTo(contents(app.generatedTests()));
    }

    @Test
    @DisplayName("capability: LedgerCap requires AuditTrail, which no module of the scenario provides")
    void capabilityScenarioIsRefusedOnItsUnprovidedRequirement() throws IOException {
        // The scenario declares a capability's composition surface on its own; the provider of its
        // required service is another capability that the example does not ship. The pipeline
        // refuses an unsatisfied non-optional @Requires, and leaves the optional one a warning.
        Path workspace = scratch.resolve("capability");
        Path classes = workspace.resolve("target/classes");
        ProcessorCompiler.compile(workspace.resolve("src/main/java"), classes, null, sourcesOf(REFUSED_SCENARIOS));
        Path metadata = classes.resolve("exeris-metadata");
        try (Stream<Path> files = Files.list(metadata)) {
            assertThat(files.map(p -> p.getFileName().toString())).containsExactly("capability_LedgerCap.json");
        }

        assertThatThrownBy(() -> CodegenPipeline.createDefault()
                .run(metadata, workspace.resolve("src/main/generated/java"), BASE_PACKAGE))
                .isInstanceOfSatisfying(CapabilityGraphException.class, e -> assertThat(e.problems())
                        .containsExactly("module eu.exeris.sdk.examples.capability.LedgerCap @Requires service"
                                + " eu.exeris.sdk.examples.capability.AuditTrail version [1.0.0,2.0.0)"
                                + " but no @CapabilityModule provides it"));
    }

    /** Processor, pipeline and javac over {@code sources}, in a workspace that starts empty. */
    private static Build build(Path workspace, Map<String, String> sources) throws IOException {
        deleteTree(workspace);
        Path entityClasses = workspace.resolve("target/classes");
        Path metadata = entityClasses.resolve("exeris-metadata");
        Path generatedMain = workspace.resolve("src/main/generated/java");
        Path generatedTests = workspace.resolve("src/test/generated/java");
        Path compiled = workspace.resolve("target/app-classes");

        ProcessorCompiler.compile(workspace.resolve("src/main/java"), entityClasses, null, sources);
        CodegenPipeline pipeline = CodegenPipeline.createDefault();
        pipeline.run(metadata, generatedMain, BASE_PACKAGE);
        pipeline.runTests(metadata, generatedTests, BASE_PACKAGE);

        List<String> files = new ArrayList<>();
        javaSourcesUnder(generatedMain).forEach(p -> files.add(p.toString()));
        javaSourcesUnder(generatedTests).forEach(p -> files.add(p.toString()));
        EmittedJavac.Result javac = EmittedJavac.compile(files, compiled,
                System.getProperty("java.class.path") + File.pathSeparator + entityClasses);
        return new Build(metadata, generatedMain, generatedTests, javac);
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

    /**
     * {@code sources} with each scenario package {@code eu.exeris.sdk.examples.<scenario>} moved to
     * {@code eu.exeris.sdk.examples.<scenario>.domain}: the file moves into the subdirectory and its
     * package declaration names the subpackage. The examples reference no type of another
     * scenario, so no import changes.
     */
    private static Map<String, String> underDomainPackages(Map<String, String> sources) {
        Map<String, String> moved = new LinkedHashMap<>();
        sources.forEach((path, text) -> {
            int slash = path.lastIndexOf('/');
            String pkg = path.substring(0, slash).replace('/', '.');
            String declaration = "package " + pkg + ";";
            assertThat(text).as("package declaration of %s", path).contains(declaration)
                    .doesNotContain("import eu.exeris.sdk.examples.");
            moved.put(path.substring(0, slash) + "/domain" + path.substring(slash),
                    text.replace(declaration, "package " + pkg + ".domain;"));
        });
        return moved;
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
