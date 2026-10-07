package eu.exeris.e2e.codegen;

import eu.exeris.e2e.codegen.compile.ProcessorCompiler;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.tooling.codegen.core.OutputWriter;
import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator;
import eu.exeris.tooling.codegen.java.CodegenPipeline;
import eu.exeris.tooling.codegen.java.kernel.KernelApplicationGenerator;
import eu.exeris.tooling.codegen.java.kernel.KernelGeneratorStrategy;
import eu.exeris.tooling.codegen.java.kernel.KernelHandlerTestGenerator;
import eu.exeris.tooling.codegen.java.kernel.KernelRepositoryTestGenerator;
import eu.exeris.tooling.codegen.java.kernel.KernelSagaTestGenerator;
import eu.exeris.tooling.codegen.java.kernel.KernelServiceTestGenerator;
import eu.exeris.tooling.codegen.java.kernel.KernelTestSupportGenerator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The generator catalogue against what a real run writes (ADR-097 obligation 8).
 *
 * <p>Annotated sources go through {@code javac} and the processor, the metadata through
 * {@link CodegenPipeline#run} and {@link CodegenPipeline#runTests}, and the two
 * {@code .exeris-codegen-manifest} files the run leaves are matched against the catalogue the way
 * ADR-097 tells a consumer to match them: first row in catalogue order whose {@code outputRoot} is
 * the manifest's tree and whose {@code pathPattern} matches.
 *
 * <p>The writer of each path is established without the catalogue: every generator is asked for its
 * own output over the same metadata, and the pipeline's own file is the one that disappears when the
 * capability metadata is withheld. The test holds that attribution to the manifests (same set of
 * paths, one writer each) before it holds the catalogue to the attribution.
 *
 * <p>The base package is passed explicitly: the pipeline's fallback reads it from whichever domain
 * the metadata directory lists first.
 */
@Tag("e2e")
@Tag("codegen")
@DisplayName("ADR-097 — every path a run writes has its generator's row, and every row is reached")
class GeneratorCatalogueConformanceE2ETest {

    private static final String BASE_PACKAGE = "eu.exeris.e2e.catalogue";
    private static final String DOMAIN = "eu/exeris/e2e/catalogue/domain/";
    private static final String CATALOGUE = "/META-INF/exeris/generator-catalogue.json";
    private static final String PIPELINE = CodegenPipeline.class.getSimpleName();

    @TempDir
    static Path workspace;

    private static List<Row> catalogue;
    private static Map<String, String> mainWriters;
    private static Map<String, String> testWriters;
    private static Set<String> mainManifest;
    private static Set<String> testManifest;

    record Row(String id, String generator, String outputRoot, Pattern pattern, List<String> ambiguousWith) {
        boolean matches(String root, String path) {
            return outputRoot.equals(root) && pattern.matcher(path).matches();
        }
    }

    @BeforeAll
    static void runTheFixture() throws IOException {
        catalogue = readCatalogue();

        Path classes = workspace.resolve("target/classes");
        Path metadata = classes.resolve("exeris-metadata");
        Path main = workspace.resolve("src/main/generated/java");
        Path test = workspace.resolve("src/test/generated/java");
        ProcessorCompiler.compile(workspace.resolve("src/main/java"), classes, null, sources());

        CodegenPipeline pipeline = CodegenPipeline.createDefault();
        pipeline.run(metadata, main, BASE_PACKAGE);
        pipeline.runTests(metadata, test, BASE_PACKAGE);
        mainManifest = manifest(main);
        testManifest = manifest(test);

        // The same run without the capability metadata: the paths it does not write are the ones
        // the pipeline writes for the capabilities itself.
        Path domainsOnly = workspace.resolve("domains-only");
        Files.createDirectories(domainsOnly);
        try (Stream<Path> files = Files.list(metadata)) {
            for (Path file : files.toList()) {
                if (!file.getFileName().toString().startsWith("capability_")) {
                    Files.copy(file, domainsOnly.resolve(file.getFileName()));
                }
            }
        }
        Path withoutCaps = workspace.resolve("without-caps");
        pipeline.run(domainsOnly, withoutCaps, BASE_PACKAGE);
        Set<String> pipelineOwn = new TreeSet<>(mainManifest);
        pipelineOwn.removeAll(manifest(withoutCaps));

        List<DomainMetadata> domains = pipeline.loadMetadata(metadata);
        mainWriters = mainWriters(domains, pipelineOwn);
        testWriters = testWriters(domains);
    }

    @Test
    @DisplayName("the fixture reaches the processor: every domain and the capability have metadata")
    void fixtureIsProcessed() {
        assertThat(mainWriters.values()).contains(PIPELINE);
        assertThat(mainManifest).hasSizeGreaterThan(60);
        assertThat(testManifest).isNotEmpty();
    }

    @Test
    @DisplayName("the writers' own output is exactly what each manifest lists")
    void attributionCoversTheManifests() {
        assertThat(mainWriters.keySet()).containsExactlyInAnyOrderElementsOf(mainManifest);
        assertThat(testWriters.keySet()).containsExactlyInAnyOrderElementsOf(testManifest);
    }

    @Test
    @DisplayName("every listed path's first matching row is its writer's row, or names it as ambiguous")
    void everyPathResolvesToItsWriter() {
        List<String> failures = new ArrayList<>();
        check("main", mainWriters, failures);
        check("test", testWriters, failures);
        assertThat(failures).isEmpty();
    }

    @Test
    @DisplayName("every row is the resolved row of at least one listed path")
    void everyRowIsReached() {
        Set<String> reached = new TreeSet<>();
        mainWriters.forEach((path, writer) -> reach(reached, resolve("main", path, writer)));
        testWriters.forEach((path, writer) -> reach(reached, resolve("test", path, writer)));
        assertThat(reached).containsExactlyInAnyOrderElementsOf(catalogue.stream().map(Row::id).toList());
    }

    // ------------------------------------------------------------------ matching

    private static void reach(Set<String> reached, Row row) {
        if (row != null) {
            reached.add(row.id());
        }
    }

    private static void check(String root, Map<String, String> writers, List<String> failures) {
        writers.forEach((path, writer) -> {
            Row first = firstMatch(root, path);
            if (first == null) {
                failures.add(root + ": " + path + " (" + writer + ") matches no row");
            } else if (resolve(root, path, writer) == null) {
                failures.add(root + ": " + path + " (" + writer + ") first matches " + first.id()
                        + ", which is neither " + writer + "'s nor ambiguous with one of its rows");
            }
        });
    }

    /**
     * The row a consumer that knows the writer settles on: the first match when it is the writer's,
     * else the first row the first match lists as ambiguous that is the writer's and matches too.
     */
    private static Row resolve(String root, String path, String writer) {
        Row first = firstMatch(root, path);
        if (first == null) {
            return null;
        }
        if (first.generator().equals(writer)) {
            return first;
        }
        for (String id : first.ambiguousWith()) {
            Row candidate = catalogue.stream().filter(row -> row.id().equals(id)).findFirst().orElseThrow();
            if (candidate.generator().equals(writer) && candidate.matches(root, path)) {
                return candidate;
            }
        }
        return null;
    }

    private static Row firstMatch(String root, String path) {
        for (Row row : catalogue) {
            if (row.matches(root, path)) {
                return row;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ attribution

    private static Map<String, String> mainWriters(List<DomainMetadata> domains, Set<String> pipelineOwn) {
        Map<String, String> writers = new TreeMap<>();
        for (KernelArtifactGenerator generator : new KernelGeneratorStrategy().getRegistry().getGenerators()) {
            for (DomainMetadata domain : domains) {
                for (GeneratedFile file : generator.generateMultiple(domain)) {
                    if (file != null) {
                        attribute(writers, file, generator.getClass().getSimpleName());
                    }
                }
            }
        }
        KernelApplicationGenerator application = new KernelApplicationGenerator();
        for (GeneratedFile file : application.generateAll(domains, BASE_PACKAGE, true)) {
            attribute(writers, file, KernelApplicationGenerator.class.getSimpleName());
        }
        GeneratedFile foreignKeys = application.generateForeignKeys(domains);
        assertThat(foreignKeys).as("the fixture's MANY_TO_ONE yields the foreign-key migration").isNotNull();
        attribute(writers, foreignKeys, KernelApplicationGenerator.class.getSimpleName());

        assertThat(pipelineOwn).as("the pipeline writes a file of its own for the capability").isNotEmpty();
        for (String path : pipelineOwn) {
            assertThat(writers.put(path, PIPELINE)).as(path + " has two writers").isNull();
        }
        return writers;
    }

    private static Map<String, String> testWriters(List<DomainMetadata> domains) {
        Map<String, String> writers = new TreeMap<>();
        for (GeneratedFile file : new KernelTestSupportGenerator().generateAll(BASE_PACKAGE)) {
            attribute(writers, file, KernelTestSupportGenerator.class.getSimpleName());
        }
        KernelHandlerTestGenerator handlerTests = new KernelHandlerTestGenerator();
        KernelServiceTestGenerator serviceTests = new KernelServiceTestGenerator();
        KernelRepositoryTestGenerator repositoryTests = new KernelRepositoryTestGenerator();
        KernelSagaTestGenerator sagaTests = new KernelSagaTestGenerator();
        for (DomainMetadata domain : domains) {
            attribute(writers, handlerTests.generate(domain, BASE_PACKAGE),
                    KernelHandlerTestGenerator.class.getSimpleName());
            attribute(writers, serviceTests.generate(domain), KernelServiceTestGenerator.class.getSimpleName());
            attribute(writers, repositoryTests.generate(domain, BASE_PACKAGE),
                    KernelRepositoryTestGenerator.class.getSimpleName());
            GeneratedFile sagaTest = sagaTests.generate(domain, BASE_PACKAGE);
            if (sagaTest != null) {
                attribute(writers, sagaTest, KernelSagaTestGenerator.class.getSimpleName());
            }
        }
        return writers;
    }

    private static void attribute(Map<String, String> writers, GeneratedFile file, String writer) {
        String path = file.relativePath();
        assertThat(writers.put(path, writer)).as(path + " has two writers").isNull();
    }

    // ------------------------------------------------------------------ inputs

    private static Set<String> manifest(Path root) throws IOException {
        Set<String> paths = new LinkedHashSet<>();
        for (String line : Files.readAllLines(root.resolve(OutputWriter.MANIFEST_NAME))) {
            String path = line.strip();
            if (!path.isEmpty() && !path.startsWith("#")) {
                paths.add(path);
            }
        }
        return paths;
    }

    @SuppressWarnings("unchecked")
    private static List<Row> readCatalogue() throws IOException {
        Map<String, Object> json;
        try (InputStream in = CodegenPipeline.class.getResourceAsStream(CATALOGUE)) {
            assertThat(in).as(CATALOGUE + " in exeris-codegen-java").isNotNull();
            json = JsonMapper.builder().build().readValue(in, Map.class);
        }
        List<Row> rows = new ArrayList<>();
        for (Map<String, Object> row : (List<Map<String, Object>>) json.get("rows")) {
            rows.add(new Row((String) row.get("id"), (String) row.get("generator"),
                    (String) row.get("outputRoot"), Pattern.compile((String) row.get("pathPattern")),
                    (List<String>) row.get("ambiguousWith")));
        }
        return rows;
    }

    /**
     * One build that reaches every row: a versioned and a {@code TENANT} entity, a {@code UNIVERSE}
     * entity with a {@code @SharedScope} field, a {@code realTimeApi} entity (which must be
     * {@code GLOBAL}) with domain events, a saga and a streaming action, a {@code @Graph} entity
     * holding a {@code MANY_TO_ONE} to another entity of the build, and a capability module.
     */
    private static Map<String, String> sources() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put(DOMAIN + "Beacon.java", """
                package eu.exeris.e2e.catalogue.domain;

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
                package eu.exeris.e2e.catalogue.domain;

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
        sources.put(DOMAIN + "Presence.java", """
                package eu.exeris.e2e.catalogue.domain;

                import eu.exeris.sdk.annotation.ExerisDomain;
                import eu.exeris.sdk.annotation.Field;
                import eu.exeris.sdk.annotation.system.SharedScope;
                import eu.exeris.sdk.annotation.system.TenantId;

                import java.util.UUID;

                @ExerisDomain(module = "universe", path = "/presences", dataScope = ExerisDomain.DataScope.UNIVERSE)
                public class Presence {
                    private UUID id;
                    @Field(label = "X") private int x;
                    @Field(label = "Owner") @TenantId private UUID ownerTenantId;
                    @Field(label = "Universe") @SharedScope private UUID universeId;
                }
                """);
        sources.put(DOMAIN + "Customer.java", """
                package eu.exeris.e2e.catalogue.domain;

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
                package eu.exeris.e2e.catalogue.domain;

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
        sources.put("eu/exeris/e2e/catalogue/caps/AuditModule.java", """
                package eu.exeris.e2e.catalogue.caps;

                import eu.exeris.sdk.annotation.capability.CapabilityModule;

                @CapabilityModule
                public class AuditModule {
                }
                """);
        return sources;
    }
}
