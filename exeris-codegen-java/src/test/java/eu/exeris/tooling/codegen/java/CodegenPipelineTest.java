package eu.exeris.tooling.codegen.java;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.exeris.sdk.sourcemodel.ast.CapabilityModuleMetadata;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.EnumMetadata;
import eu.exeris.sdk.sourcemodel.ast.FieldMetadata;
import eu.exeris.sdk.sourcemodel.ast.ProvidesMetadata;
import eu.exeris.sdk.sourcemodel.ast.RequiresMetadata;
import eu.exeris.tooling.codegen.core.capability.CapTierWallException;
import eu.exeris.tooling.codegen.core.capability.CapabilityGraphException;
import eu.exeris.tooling.codegen.core.capability.CapabilityModuleDescriptor;
import eu.exeris.tooling.codegen.core.driver.RequiredDrivers;
import eu.exeris.tooling.codegen.core.driver.RuntimeDriverCheck;
import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import eu.exeris.tooling.codegen.core.generator.GeneratorRegistry;
import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator;
import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator.ArtifactType;
import eu.exeris.tooling.codegen.java.kernel.KernelApplicationGenerator;
import eu.exeris.tooling.codegen.java.kernel.KernelGeneratorStrategy;
import eu.exeris.tooling.codegen.java.kernel.RequiredCompileArtifacts;
import eu.exeris.tooling.diagnostics.DiagnosticId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers {@link CodegenPipeline} — the production pipeline split out of the
 * {@link CodegenMain} CLI shell.
 *
 * <p>Tests use the real {@link KernelGeneratorStrategy} +
 * {@link KernelApplicationGenerator} (so generator wiring is exercised
 * end-to-end), and write actual JSON to {@link TempDir} so the metadata-load
 * branches are real. Two narrow tests use a custom-injected mapper / registry
 * to hit error and counting branches that would otherwise be unreachable.
 */
class CodegenPipelineTest {

    @TempDir
    Path metadataDir;

    @TempDir
    Path outputDir;

    private CodegenPipeline pipeline;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        pipeline = CodegenPipeline.createDefault();
        mapper = CodegenPipeline.defaultMapper();
    }

    private void writeDomainJson(String fileName, DomainMetadata domain) throws IOException {
        mapper.writeValue(metadataDir.resolve(fileName).toFile(), domain);
    }

    private DomainMetadata productDomain() {
        return DomainMetadata.builder("Product", "com.shop.domain")
                .module("catalog")
                .path("/products")
                .build();
    }

    private void writeCapabilityJson(String name, CapabilityModuleDescriptor descriptor) throws IOException {
        mapper.writeValue(metadataDir.resolve("capability_" + name + ".json").toFile(), descriptor);
    }

    private CapabilityModuleDescriptor desc(String qName,
                                            List<ProvidesMetadata> provides,
                                            List<RequiresMetadata> requires) {
        String simple = qName.substring(qName.lastIndexOf('.') + 1);
        String pkg = qName.substring(0, qName.lastIndexOf('.'));
        return new CapabilityModuleDescriptor(simple, pkg, qName,
                CapabilityModuleMetadata.builder().provides(provides).requires(requires).build());
    }

    /** Same, with a {@code @CapabilityLifecycle} owner attached. */
    private CapabilityModuleDescriptor desc(String qName,
                                            List<ProvidesMetadata> provides,
                                            List<RequiresMetadata> requires,
                                            String lifecycleOwner) {
        String simple = qName.substring(qName.lastIndexOf('.') + 1);
        String pkg = qName.substring(0, qName.lastIndexOf('.'));
        return new CapabilityModuleDescriptor(simple, pkg, qName,
                CapabilityModuleMetadata.builder().provides(provides).requires(requires)
                        .lifecycleOwner(lifecycleOwner).build());
    }

    @Nested
    @DisplayName("generated tests (T2 / ADR-058)")
    class GeneratedTests {

        @TempDir
        Path testOutputDir;

        @Test
        @DisplayName("emits the shared double once and the per-entity tests, into the TEST root")
        void emitsTestsIntoTheTestRoot() throws IOException {
            writeDomainJson("Product.json", productDomain());

            int written = pipeline.runTests(metadataDir, testOutputDir, "com.shop");

            // Five project-wide doubles, plus the handler, service and repository tests for the
            // one entity. No saga test: productDomain() declares no saga.
            assertThat(written).isEqualTo(8);
            assertThat(testOutputDir.resolve("com/shop/testsupport/RecordingHttpExchange.java")).exists();
            assertThat(testOutputDir.resolve("com/shop/testsupport/RecordingPersistence.java")).exists();
            assertThat(testOutputDir.resolve("com/shop/testsupport/RecordingFlow.java")).exists();
            assertThat(testOutputDir.resolve("com/shop/testsupport/RecordingRequestBody.java")).exists();
            assertThat(testOutputDir.resolve("com/shop/testsupport/RecordingEventEngine.java")).exists();
            assertThat(testOutputDir.resolve("com/shop/handler/ProductHandlerTest.java")).exists();
            assertThat(testOutputDir.resolve("com/shop/service/ProductServiceTest.java")).exists();
            assertThat(testOutputDir.resolve("com/shop/repository/ProductRepositoryTest.java")).exists();
            // The main root is a different tree entirely — runTests must not have touched it.
            assertThat(outputDir.resolve("com/shop")).doesNotExist();
        }

        @Test
        @DisplayName("the test root gets its own T13 manifest, so pruning is scoped to it")
        void testRootOwnsItsManifest() throws IOException {
            writeDomainJson("Product.json", productDomain());

            pipeline.runTests(metadataDir, testOutputDir, "com.shop");

            assertThat(testOutputDir.resolve(".exeris-codegen-manifest")).exists();
        }

        @Test
        @DisplayName("auto-detects the base package when the caller does not pass one")
        void autoDetectsBasePackage() throws IOException {
            writeDomainJson("Product.json", productDomain());

            pipeline.runTests(metadataDir, testOutputDir, null);

            // productDomain() lives in com.shop.domain, so the support package hangs off com.shop.
            assertThat(testOutputDir.resolve("com/shop/testsupport/RecordingHttpExchange.java")).exists();
        }

        @Test
        @DisplayName("zero domains writes nothing and prunes nothing — a committed test tree survives")
        void zeroDomainsIsANoOp() throws IOException {
            // Empty metadata is overwhelmingly a masked compile failure, and pruning on it would
            // delete a committed generated-test tree.
            Path owned = testOutputDir.resolve("com/shop/handler/ProductHandlerTest.java");
            Files.createDirectories(owned.getParent());
            Files.writeString(owned, "class ProductHandlerTest {}");
            Files.writeString(testOutputDir.resolve(".exeris-codegen-manifest"),
                    "# Exeris Tooling generated-output manifest - DO NOT EDIT MANUALLY\n"
                            + "com/shop/handler/ProductHandlerTest.java\n");

            assertThat(pipeline.runTests(metadataDir, testOutputDir, "com.shop")).isZero();

            assertThat(owned).exists();
        }
    }

    @Nested
    @DisplayName("happy paths")
    class HappyPaths {

        @Test
        @DisplayName("writes at least the bootstrap pair plus per-entity files for one domain")
        void singleDomainGeneratesFiles() throws IOException {
            writeDomainJson("Product.json", productDomain());

            int filesGenerated = pipeline.run(metadataDir, outputDir, "com.shop");

            assertThat(filesGenerated).isGreaterThan(0);
            // The bootstrap trio is emitted by KernelApplicationGenerator under the
            // caller-supplied base package.
            assertThat(outputDir.resolve("com/shop/Application.java")).exists();
            assertThat(outputDir.resolve("com/shop/RuntimeComponents.java")).exists();
            assertThat(outputDir.resolve("com/shop/RuntimeLifecycle.java")).exists();
        }

        @Test
        @DisplayName("creates outputDir if it does not yet exist")
        void createsOutputDir() throws IOException {
            writeDomainJson("Product.json", productDomain());
            Path nestedOutput = outputDir.resolve("nested/missing");

            int filesGenerated = pipeline.run(metadataDir, nestedOutput, "com.shop");

            assertThat(nestedOutput).isDirectory();
            assertThat(filesGenerated).isGreaterThan(0);
        }

        @Test
        @DisplayName("auto-detects the base package from the entity package when null is passed")
        void autoDetectsBasePackage() throws IOException {
            // packageName ends in .domain — auto-detect strips that suffix.
            DomainMetadata domain = DomainMetadata.builder("Product", "com.shop.domain")
                    .module("catalog")
                    .path("/products")
                    .build();
            writeDomainJson("Product.json", domain);

            pipeline.run(metadataDir, outputDir, null);

            assertThat(outputDir.resolve("com/shop/Application.java")).exists();
        }

        @Test
        @DisplayName("explicit basePackage overrides the would-be auto-detected one")
        void explicitBasePackageWins() throws IOException {
            writeDomainJson("Product.json", productDomain());

            pipeline.run(metadataDir, outputDir, "io.override");

            assertThat(outputDir.resolve("io/override/Application.java")).exists();
            assertThat(outputDir.resolve("com/shop/Application.java")).doesNotExist();
        }

        @Test
        @DisplayName("multiple domains all flow through the per-entity loop")
        void multipleDomains() throws IOException {
            writeDomainJson("Product.json", DomainMetadata.builder("Product", "com.shop.domain")
                    .module("catalog").path("/products").build());
            writeDomainJson("Order.json", DomainMetadata.builder("Order", "com.shop.domain")
                    .module("ordering").path("/orders").build());

            int filesGenerated = pipeline.run(metadataDir, outputDir, "com.shop");

            // Two entities × per-entity generators + one bootstrap trio
            // (Application + RuntimeComponents + RuntimeLifecycle).
            assertThat(filesGenerated).isGreaterThan(4);
        }
    }

    /**
     * The directory listing's order is the filesystem's, so neither the domain order nor the
     * auto-detected base package may follow it. Each test writes the same metadata into two
     * directories in opposite creation orders and requires identical results.
     */
    @Nested
    @DisplayName("metadata load order and the auto-detected base package")
    class LoadOrder {

        @TempDir
        Path otherMetadataDir;

        @TempDir
        Path otherOutputDir;

        private final List<DomainMetadata> twoPackages = List.of(
                DomainMetadata.builder("Order", "com.shop.order.domain").path("/orders").build(),
                DomainMetadata.builder("Invoice", "com.shop.billing.domain").path("/invoices").build(),
                DomainMetadata.builder("Customer", "com.shop.order.domain").path("/customers").build());

        private void writeAll(Path dir, List<DomainMetadata> domains) throws IOException {
            for (DomainMetadata domain : domains) {
                mapper.writeValue(dir.resolve(domain.entityName() + ".json").toFile(), domain);
            }
        }

        private List<String> qualifiedNames(List<DomainMetadata> domains) {
            return domains.stream().map(d -> d.packageName() + "." + d.entityName()).toList();
        }

        @Test
        @DisplayName("loads domains by fully-qualified name, whatever order the files were written in")
        void loadsInQualifiedNameOrder() throws IOException {
            writeAll(metadataDir, twoPackages);
            writeAll(otherMetadataDir, twoPackages.reversed());

            List<String> expected = List.of(
                    "com.shop.billing.domain.Invoice",
                    "com.shop.order.domain.Customer",
                    "com.shop.order.domain.Order");
            assertThat(qualifiedNames(pipeline.loadMetadata(metadataDir))).isEqualTo(expected);
            assertThat(qualifiedNames(pipeline.loadMetadata(otherMetadataDir))).isEqualTo(expected);
        }

        @Test
        @DisplayName("entities in several packages: the same tree, byte for byte, with the bootstrap in the first domain's package")
        void sameTreeWhateverTheWriteOrder() throws IOException {
            writeAll(metadataDir, twoPackages);
            writeAll(otherMetadataDir, twoPackages.reversed());

            pipeline.run(metadataDir, outputDir, null);
            pipeline.run(otherMetadataDir, otherOutputDir, null);

            // com.shop.billing.domain.Invoice sorts first by qualified name, although
            // Customer.json sorts first by file name.
            assertThat(outputDir.resolve("com/shop/billing/Application.java")).exists();
            assertThat(outputDir.resolve("com/shop/billing/RuntimeComponents.java")).exists();
            assertThat(outputDir.resolve("com/shop/billing/RuntimeLifecycle.java")).exists();
            assertThat(outputDir.resolve("com/shop/order/Application.java")).doesNotExist();
            assertSameTree(outputDir, otherOutputDir);
        }

        @Test
        @DisplayName("generated tests: the testsupport package does not depend on the write order either")
        void sameTestTreeWhateverTheWriteOrder() throws IOException {
            writeAll(metadataDir, twoPackages);
            writeAll(otherMetadataDir, twoPackages.reversed());

            pipeline.runTests(metadataDir, outputDir, null);
            pipeline.runTests(otherMetadataDir, otherOutputDir, null);

            assertThat(outputDir.resolve("com/shop/billing/testsupport/RecordingHttpExchange.java")).exists();
            assertSameTree(outputDir, otherOutputDir);
        }

        @Test
        @DisplayName("one package: the base package is that package with .domain removed")
        void onePackage() {
            assertThat(CodegenPipeline.autoDetectBasePackage(List.of(
                    DomainMetadata.builder("Product", "com.shop.domain").build(),
                    DomainMetadata.builder("Order", "com.shop.domain").build())))
                    .isEqualTo("com.shop");
        }

        @Test
        @DisplayName("several packages: the first domain by qualified name, in either input order")
        void firstDomainByQualifiedName() {
            DomainMetadata order = DomainMetadata.builder("Order", "org.shop.domain").build();
            DomainMetadata invoice = DomainMetadata.builder("Invoice", "com.billing.domain").build();

            assertThat(CodegenPipeline.autoDetectBasePackage(List.of(order, invoice))).isEqualTo("com.billing");
            assertThat(CodegenPipeline.autoDetectBasePackage(List.of(invoice, order))).isEqualTo("com.billing");
        }

        private void assertSameTree(Path left, Path right) throws IOException {
            List<Path> leftFiles = relativeFiles(left);
            assertThat(relativeFiles(right)).isEqualTo(leftFiles);
            for (Path relative : leftFiles) {
                assertThat(Files.readAllBytes(right.resolve(relative)))
                        .as(relative.toString())
                        .isEqualTo(Files.readAllBytes(left.resolve(relative)));
            }
        }

        private List<Path> relativeFiles(Path root) throws IOException {
            try (var walk = Files.walk(root)) {
                return walk.filter(Files::isRegularFile).map(root::relativize).sorted().toList();
            }
        }
    }

    @Nested
    @DisplayName("empty / missing input")
    class EmptyInputs {

        @Test
        @DisplayName("metadataDir does not exist → returns 0, no exception")
        void missingMetadataDir(@TempDir Path scratch) throws IOException {
            Path nonexistent = scratch.resolve("does-not-exist");

            int filesGenerated = pipeline.run(nonexistent, outputDir, "com.shop");

            assertThat(filesGenerated).isZero();
        }

        @Test
        @DisplayName("metadataDir empty → returns 0, output untouched")
        void emptyMetadataDir() throws IOException {
            int filesGenerated = pipeline.run(metadataDir, outputDir, "com.shop");

            assertThat(filesGenerated).isZero();
            // outputDir is the @TempDir — empty before and (effectively) after.
            try (var stream = Files.list(outputDir)) {
                assertThat(stream.count()).isZero();
            }
        }

        @Test
        @DisplayName("metadataDir holds only non-JSON files → returns 0")
        void onlyNonJsonFiles() throws IOException {
            Files.writeString(metadataDir.resolve("README.txt"), "not metadata");

            int filesGenerated = pipeline.run(metadataDir, outputDir, "com.shop");

            assertThat(filesGenerated).isZero();
        }

        @Test
        @DisplayName("T18: empty metadata + prior manifest → REFUSES to wipe (masked-compile-failure guard), tree survives")
        void emptyMetadataRefusesToWipePriorTree() throws IOException {
            // Simulate a previous generation: a stale file plus the manifest that owns it.
            Path owned = outputDir.resolve("com/shop/repository/OrderRepository.java");
            Files.createDirectories(owned.getParent());
            Files.writeString(owned, "class OrderRepository {}");
            Files.writeString(outputDir.resolve(".exeris-codegen-manifest"),
                    "# Exeris Tooling generated-output manifest - DO NOT EDIT MANUALLY\n"
                            + "com/shop/repository/OrderRepository.java\n");

            // This run finds no metadata (a masked compile failure). The default
            // guard refuses rather than pruning the committed tree.
            assertThatThrownBy(() -> pipeline.run(metadataDir, outputDir, "com.shop"))
                    .isInstanceOf(EmptyMetadataException.class)
                    .hasMessageContaining("Refusing to wipe")
                    .hasMessageContaining("allowEmpty=true");

            // The committed tree is intact — nothing was deleted.
            assertThat(owned).exists();
        }

        @Test
        @DisplayName("T18: allowEmpty=true honours the explicit teardown — empty metadata prunes the prior tree")
        void allowEmptyHonoursIntentionalTeardown() throws IOException {
            Path owned = outputDir.resolve("com/shop/repository/OrderRepository.java");
            Files.createDirectories(owned.getParent());
            Files.writeString(owned, "class OrderRepository {}");
            Files.writeString(outputDir.resolve(".exeris-codegen-manifest"),
                    "# Exeris Tooling generated-output manifest - DO NOT EDIT MANUALLY\n"
                            + "com/shop/repository/OrderRepository.java\n");

            // Opted-in teardown: empty metadata is allowed to prune the prior tree.
            int filesGenerated = pipeline.run(metadataDir, outputDir, "com.shop", true);

            assertThat(filesGenerated).isZero();
            assertThat(owned).doesNotExist();
            assertThat(outputDir.resolve("com/shop/repository")).doesNotExist();
        }
    }

    @Nested
    @DisplayName("runtime-driver gate (T50 / ADR-078)")
    class RuntimeDriverGate {

        /** Stands in for the resolved runtime classpath — jars live here, not in the output tree. */
        @TempDir
        Path classpathDir;

        private Path driverJar(String name, String... spis) throws IOException {
            Path jar = classpathDir.resolve(name);
            try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(
                    Files.newOutputStream(jar))) {
                for (String spi : spis) {
                    zip.putNextEntry(new java.util.zip.ZipEntry("META-INF/services/" + spi));
                    zip.write("com.example.Impl\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    zip.closeEntry();
                }
            }
            return jar;
        }

        private void writeDomain(String entity, String extra) throws IOException {
            Files.writeString(metadataDir.resolve(entity + ".json"),
                    "{\"entityName\":\"" + entity + "\",\"packageName\":\"com.shop.domain\","
                            + "\"path\":\"/" + entity.toLowerCase() + "\"" + extra + "}");
        }

        @Test
        @DisplayName("the whole path: real metadata in, a real classpath scanned, a verdict out")
        void reportsMissingDriversForRealMetadata() throws IOException {
            writeDomain("Product", "");
            // Deliberately an element with classes and no META-INF/services — matching the shape
            // of the pinned exeris-kernel-core jar, which carries zero service registrations.
            // That is what makes this gate non-vacuous rather than a formality.
            Path coreLike = driverJar("core-like.jar");

            RuntimeDriverCheck.Result result =
                    pipeline.verifyRuntimeDrivers(metadataDir, List.of(coreLike));

            assertThat(result.satisfied()).isFalse();
            assertThat(result.missing()).containsExactly(
                    RequiredDrivers.SUBSYSTEM_PROVIDER,
                    RequiredDrivers.PERSISTENCE_PROVIDER,
                    RequiredDrivers.HTTP_PROVIDER);
        }

        @Test
        @DisplayName("a driver registering the required SPIs satisfies it")
        void satisfiedByADriverOnTheClasspath() throws IOException {
            writeDomain("Product", "");
            Path driver = driverJar("driver.jar",
                    RequiredDrivers.SUBSYSTEM_PROVIDER,
                    RequiredDrivers.PERSISTENCE_PROVIDER,
                    RequiredDrivers.HTTP_PROVIDER);

            assertThat(pipeline.verifyRuntimeDrivers(metadataDir, List.of(driver)).satisfied())
                    .isTrue();
        }

        @Test
        @DisplayName("a declared event widens the requirement, and the metadata is what says so")
        void declaredEventsWidenTheRequirement() throws IOException {
            writeDomain("Product", ",\"events\":[{\"name\":\"ProductCreated\"}]");
            Path driver = driverJar("driver.jar",
                    RequiredDrivers.SUBSYSTEM_PROVIDER,
                    RequiredDrivers.PERSISTENCE_PROVIDER,
                    RequiredDrivers.HTTP_PROVIDER);

            RuntimeDriverCheck.Result result =
                    pipeline.verifyRuntimeDrivers(metadataDir, List.of(driver));

            assertThat(result.missing()).containsExactly(RequiredDrivers.EVENT_PROVIDER);
        }

        @Test
        @DisplayName("no metadata is a vacuous verdict — a build with no emitted app needs no driver")
        void noMetadataIsVacuous() throws IOException {
            RuntimeDriverCheck.Result result =
                    pipeline.verifyRuntimeDrivers(metadataDir, List.of());

            assertThat(result.vacuous()).isTrue();
        }
    }

    @Nested
    @DisplayName("requiredCompileArtifacts (T30) — the artefacts the emitted code imports")
    class RequiredCompileArtifactsFromMetadata {

        @TempDir
        Path testOutputDir;

        /** Where each non-JDK package the emitters import comes from. */
        private static final List<String[]> PACKAGE_TO_ARTEFACT = List.of(
                new String[] {"eu.exeris.kernel.spi.", "eu.exeris:exeris-kernel-spi"},
                new String[] {"eu.exeris.kernel.core.", "eu.exeris:exeris-kernel-core"},
                new String[] {"eu.exeris.sdk.composition.runtime.", "eu.exeris:exeris-sdk-composition-runtime"},
                new String[] {"tools.jackson.", "tools.jackson.core:jackson-databind"},
                new String[] {"org.junit.jupiter.", "org.junit.jupiter:junit-jupiter-api"},
                new String[] {"org.assertj.", "org.assertj:assertj-core"});

        private DomainMetadata productWithListField() {
            return DomainMetadata.builder("Product", "com.shop.domain")
                    .module("catalog")
                    .path("/products")
                    .fields(List.of(FieldMetadata.builder("tags", "java.util.List<java.lang.String>").build()))
                    .build();
        }

        private List<String> coordinates(List<RequiredCompileArtifacts.Requirement> required) {
            return required.stream().map(RequiredCompileArtifacts.Requirement::coordinate).toList();
        }

        /** Every coordinate an emitted file under {@code root} imports, by its package. */
        private java.util.Set<String> importedArtefacts(Path root) throws IOException {
            java.util.Set<String> artefacts = new java.util.TreeSet<>();
            List<Path> sources;
            try (java.util.stream.Stream<Path> walk = Files.walk(root)) {
                sources = walk.filter(f -> f.toString().endsWith(".java")).toList();
            }
            for (Path source : sources) {
                for (String line : Files.readAllLines(source)) {
                    if (!line.startsWith("import ")) {
                        continue;
                    }
                    String imported = line.substring("import ".length()).replace("static ", "");
                    if (imported.startsWith("java.") || imported.startsWith("javax.")
                            || imported.startsWith("com.shop.")) {
                        continue;
                    }
                    String artefact = PACKAGE_TO_ARTEFACT.stream()
                            .filter(entry -> imported.startsWith(entry[0]))
                            .map(entry -> entry[1])
                            .findFirst()
                            .orElse("unmapped: " + imported);
                    artefacts.add(artefact);
                }
            }
            return artefacts;
        }

        @Test
        @DisplayName("names exactly the artefacts the emitted main sources and tests import")
        void matchesTheImportsOfTheEmittedTree() throws IOException {
            writeDomainJson("Product.json", productWithListField());
            writeCapabilityJson("Billing",
                    desc("com.app.Billing", List.of(ProvidesMetadata.of("com.api.PaymentApi", "1.0")), List.of()));

            pipeline.run(metadataDir, outputDir, "com.shop");
            pipeline.runTests(metadataDir, testOutputDir, "com.shop");
            java.util.Set<String> emitted = importedArtefacts(outputDir);
            emitted.addAll(importedArtefacts(testOutputDir));

            List<RequiredCompileArtifacts.Requirement> required =
                    pipeline.requiredCompileArtifacts(metadataDir, true);

            assertThat(coordinates(required)).containsExactlyInAnyOrderElementsOf(emitted);
        }

        @Test
        @DisplayName("an uncomposed tree with no List<X> field needs kernel SPI and Core only")
        void plainTreeNeedsTheKernelOnly() throws IOException {
            writeDomainJson("Product.json", productDomain());

            List<RequiredCompileArtifacts.Requirement> required =
                    pipeline.requiredCompileArtifacts(metadataDir, false);

            assertThat(coordinates(required))
                    .containsExactly("eu.exeris:exeris-kernel-spi", "eu.exeris:exeris-kernel-core");
            assertThat(required).allMatch(r -> r.classpath() == RequiredCompileArtifacts.Classpath.MAIN);
        }

        @Test
        @DisplayName("the generated tests add JUnit 5 and AssertJ on the test classpath")
        void generatedTestsAddTheirTwoLibraries() throws IOException {
            writeDomainJson("Product.json", productDomain());

            List<RequiredCompileArtifacts.Requirement> required =
                    pipeline.requiredCompileArtifacts(metadataDir, true);

            assertThat(required).filteredOn(r -> r.classpath() == RequiredCompileArtifacts.Classpath.TEST)
                    .extracting(RequiredCompileArtifacts.Requirement::coordinate)
                    .containsExactly("org.junit.jupiter:junit-jupiter-api", "org.assertj:assertj-core");
        }

        @Test
        @DisplayName("no domain metadata requires nothing, even with capability metadata")
        void noDomainRequiresNothing() throws IOException {
            writeCapabilityJson("Billing",
                    desc("com.app.Billing", List.of(ProvidesMetadata.of("com.api.PaymentApi", "1.0")), List.of()));

            assertThat(pipeline.requiredCompileArtifacts(metadataDir, true)).isEmpty();
        }
    }

    @Nested
    @DisplayName("metadata loader filters")
    class LoaderFilters {

        @Test
        @DisplayName("files prefixed with enum_ are skipped (enum metadata, not domain)")
        void skipsEnumPrefix() throws IOException {
            writeDomainJson("Product.json", productDomain());
            // A would-be-valid domain JSON, but the enum_ prefix marks it as enum
            // metadata which the per-entity pass must NOT process.
            writeDomainJson("enum_Bonus.json", DomainMetadata.builder("Bonus", "com.shop.domain")
                    .module("catalog").path("/bonus").build());

            List<DomainMetadata> loaded = pipeline.loadMetadata(metadataDir);

            assertThat(loaded).extracting(DomainMetadata::entityName).containsExactly("Product");
        }

        @Test
        @DisplayName("a field's enumType is the emitted enum it names, by qualified or simple type, "
                + "and is cleared when it names none")
        void resolvesEnumTypesFromTheEmittedEnums() throws IOException {
            writeDomainJson("Order.json", DomainMetadata.builder("Order", "com.shop.domain")
                    .module("sales").path("/orders")
                    .fields(List.of(
                            FieldMetadata.simple("status", "com.shop.domain.OrderStatus"),
                            FieldMetadata.simple("priority", "Priority"),
                            FieldMetadata.simple("phase", "com.shop.domain.OrderPhase"),
                            FieldMetadata.builder("stale", "String").enumType("com.shop.domain.Gone").build(),
                            FieldMetadata.simple("placedAt", "java.time.OffsetDateTime")))
                    .build());
            mapper.writeValue(metadataDir.resolve("enum_OrderStatus.json").toFile(), new EnumMetadata(
                    "OrderStatus", "com.shop.domain.OrderStatus", "com.shop.domain", null, List.of()));
            mapper.writeValue(metadataDir.resolve("enum_Priority.json").toFile(), new EnumMetadata(
                    "Priority", "com.shop.domain.Priority", "com.shop.domain", null, List.of()));

            DomainMetadata order = pipeline.loadMetadata(metadataDir).getFirst();

            assertThat(order.fields()).extracting(FieldMetadata::name, FieldMetadata::enumType)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple("status", "com.shop.domain.OrderStatus"),
                            org.assertj.core.groups.Tuple.tuple("priority", "com.shop.domain.Priority"),
                            org.assertj.core.groups.Tuple.tuple("phase", null),
                            org.assertj.core.groups.Tuple.tuple("stale", null),
                            org.assertj.core.groups.Tuple.tuple("placedAt", null));
        }

        @Test
        @DisplayName("filter is prefix-based, not substring — entityName containing 'enum' still loads")
        void enumSubstringNotFiltered() throws IOException {
            writeDomainJson("MyEnumLike.json", DomainMetadata.builder("MyEnumLike", "com.shop.domain")
                    .module("catalog").path("/enumlike").build());

            List<DomainMetadata> loaded = pipeline.loadMetadata(metadataDir);

            assertThat(loaded).extracting(DomainMetadata::entityName).containsExactly("MyEnumLike");
        }

        @Test
        @DisplayName("domain JSON with blank entityName is skipped")
        void skipsBlankEntityName() throws IOException {
            // Hand-write JSON with empty entityName — DomainMetadata builder won't accept empty,
            // so we craft the JSON directly.
            Files.writeString(metadataDir.resolve("Bad.json"),
                    "{\"entityName\":\"\",\"packageName\":\"com.shop.domain\",\"module\":\"catalog\"}");
            writeDomainJson("Product.json", productDomain());

            int filesGenerated = pipeline.run(metadataDir, outputDir, "com.shop");

            assertThat(filesGenerated).isGreaterThan(0);
            assertThat(outputDir.resolve("com/shop/Application.java")).exists();
        }
    }

    @Nested
    @DisplayName("error surfaces")
    class ErrorSurfaces {

        @Test
        @DisplayName("malformed JSON propagates IOException out of run()")
        void malformedJson() throws IOException {
            Files.writeString(metadataDir.resolve("Broken.json"), "{not valid json");

            assertThatThrownBy(() -> pipeline.run(metadataDir, outputDir, "com.shop"))
                    .isInstanceOf(IOException.class);
        }

        @Test
        @DisplayName("ctor rejects null collaborators")
        void rejectsNullCollaborators() {
            GeneratorRegistry registry = new KernelGeneratorStrategy().getRegistry();
            KernelApplicationGenerator app = new KernelApplicationGenerator();
            ObjectMapper m = CodegenPipeline.defaultMapper();

            assertThatThrownBy(() -> new CodegenPipeline(null, app, m))
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("registry");
            assertThatThrownBy(() -> new CodegenPipeline(registry, null, m))
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("applicationGenerator");
            assertThatThrownBy(() -> new CodegenPipeline(registry, app, null))
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("mapper");
        }

        @Test
        @DisplayName("run() rejects null metadataDir / outputDir")
        void runRejectsNulls() {
            assertThatThrownBy(() -> pipeline.run(null, outputDir, "com.shop"))
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("metadataDir");
            assertThatThrownBy(() -> pipeline.run(metadataDir, null, "com.shop"))
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("outputDir");
        }
    }

    @Nested
    @DisplayName("writeFile dispatch (extension-based)")
    class WriteFileDispatch {

        /**
         * The single-domain happy-path test already exercises all three
         * branches transitively: a real Product domain produces Java
         * generators (Handler / Service / Repository / EventPublisher /
         * Application), the Flyway generator emits .sql, and the OpenAPI
         * generator emits .yaml — so all three writeFile dispatch arms run.
         * This test asserts that the disk artifacts of each extension type
         * actually land where writeFile says they should.
         */
        @Test
        @DisplayName("Java / SQL / YAML files all materialize at their expected paths")
        void allExtensionsRoutedCorrectly() throws IOException {
            writeDomainJson("Product.json", productDomain());

            pipeline.run(metadataDir, outputDir, "com.shop");

            try (var walk = Files.walk(outputDir)) {
                List<String> emitted = walk
                        .filter(Files::isRegularFile)
                        .map(p -> outputDir.relativize(p).toString())
                        .toList();

                assertThat(emitted).anyMatch(p -> p.endsWith(".java"));
                assertThat(emitted).anyMatch(p -> p.endsWith(".sql"));
                assertThat(emitted).anyMatch(p -> p.endsWith(".yaml"));
            }
        }
    }

    @Nested
    @DisplayName("createDefault wiring")
    class DefaultWiring {

        @Test
        @DisplayName("createDefault produces a usable, non-null pipeline")
        void createDefault() throws IOException {
            CodegenPipeline def = CodegenPipeline.createDefault();
            writeDomainJson("Product.json", productDomain());

            int filesGenerated = def.run(metadataDir, outputDir, "com.shop");

            assertThat(filesGenerated).isGreaterThan(0);
        }

        @Test
        @DisplayName("defaultMapper round-trips DomainMetadata without FAIL_ON_UNKNOWN_PROPERTIES")
        void mapperToleratesUnknownFields() throws IOException {
            // Real metadata files emitted by future-versioned processors may carry
            // fields this codegen doesn't know about — loading must NOT fail.
            // Asserted at the loader seam so the test isolates the mapper config
            // from downstream generator requirements (e.g., non-null fields list).
            Files.writeString(metadataDir.resolve("Product.json"),
                    "{\"entityName\":\"Product\",\"packageName\":\"com.shop.domain\","
                            + "\"module\":\"catalog\",\"path\":\"/products\","
                            + "\"unknownFutureField\":\"ignore me\"}");

            List<DomainMetadata> loaded = pipeline.loadMetadata(metadataDir);

            assertThat(loaded).hasSize(1);
            assertThat(loaded.get(0).entityName()).isEqualTo("Product");
        }
    }

    @Nested
    @DisplayName("custom collaborator injection")
    class CustomCollaborators {

        /**
         * Injects an empty registry to confirm the per-entity loop is a no-op
         * when no generators are registered — only the bootstrap pair survives.
         * Asserts the file-count math separates entity emission from bootstrap.
         */
        @Test
        @DisplayName("empty generator registry → only the bootstrap trio is written")
        void emptyRegistryStillWritesBootstrap() throws IOException {
            GeneratorRegistry empty = new GeneratorRegistry();
            CodegenPipeline customPipeline = new CodegenPipeline(
                    empty,
                    new KernelApplicationGenerator(),
                    CodegenPipeline.defaultMapper()
            );
            writeDomainJson("Product.json", productDomain());

            int filesGenerated = customPipeline.run(metadataDir, outputDir, "com.shop");

            assertThat(filesGenerated).isEqualTo(4);
            assertThat(outputDir.resolve("com/shop/Application.java")).exists();
            assertThat(outputDir.resolve("com/shop/RuntimeComponents.java")).exists();
            assertThat(outputDir.resolve("com/shop/RuntimeLifecycle.java")).exists();
            assertThat(outputDir.resolve("com/shop/GeneratedRoutePolicy.java")).exists();
        }

        @Test
        @DisplayName("a generator that returns null for some entities is skipped without error")
        void nullReturningGenerator() throws IOException {
            GeneratorRegistry single = new GeneratorRegistry();
            single.register(new NullReturningGenerator());
            CodegenPipeline customPipeline = new CodegenPipeline(
                    single,
                    new KernelApplicationGenerator(),
                    CodegenPipeline.defaultMapper()
            );
            writeDomainJson("Product.json", productDomain());

            int filesGenerated = customPipeline.run(metadataDir, outputDir, "com.shop");

            // Null-returning generator emits 0; the bootstrap files emit 4.
            assertThat(filesGenerated).isEqualTo(4);
        }
    }

    @Nested
    @DisplayName("capability manifest (PR-E)")
    class CapabilityManifest {

        @Test
        @DisplayName("capabilities-only project (no @ExerisDomain) still emits cap-manifest.json")
        void capabilitiesOnlyEmitsManifest() throws IOException {
            writeCapabilityJson("Billing",
                    desc("com.app.Billing", List.of(ProvidesMetadata.of("com.api.PaymentApi", "1.0")), List.of()));
            writeCapabilityJson("Checkout",
                    desc("com.app.Checkout", List.of(),
                            List.of(RequiresMetadata.of("com.api.PaymentApi", "[1.0,2.0)"))));

            int filesGenerated = pipeline.run(metadataDir, outputDir, "com.app");

            assertThat(filesGenerated).isEqualTo(1);
            Path manifest = outputDir.resolve("cap-manifest.json");
            assertThat(manifest).exists();
            String json = Files.readString(manifest);
            assertThat(json)
                    .contains("com.app.Billing")
                    .contains("com.app.Checkout")
                    .contains("\"satisfied\" : true")
                    .contains("\"initOrder\"")
                    // ADR-024 obligation 7: the validation stamp the platform asserts
                    .contains("\"stamp\"")
                    .contains("\"validated\" : true")
                    .contains("\"contentBinding\" : \"sha256:");
            // no domain bootstrap when there are no entities
            assertThat(outputDir.resolve("com/app/Application.java")).doesNotExist();
        }

        @Test
        @DisplayName("cap-tier Wall: skipped entirely when the module is not a cap (ADR-055 obligation 3)")
        void wallSkippedForNonCapModule() throws IOException {
            // The gate that keeps this guard off every ordinary generated application: no
            // capability metadata means no cap, and the Wall was never an application's
            // contract. A forbidden class sitting in the output must NOT fail such a build.
            writeClassWithFieldType("com/app/Plain", "org/springframework/context/ApplicationContext");

            assertThat(pipeline.verifyCapTierWall(outputDir, metadataDir)).isZero();
        }

        @Test
        @DisplayName("cap-tier Wall: a cap with nothing compiled gates 0 — unverified, not clean")
        void wallGatesNothingWhenNoClassesFound() throws IOException {
            // The second zero-violation state: cap metadata is present, but the scan found no
            // class file (a relocated or mis-set classesDir — `compile` normally populates it
            // before process-classes). Reporting the module count here would be indistinguishable
            // from a genuine pass, so the count is 0 and the caller gets to say "unverified".
            writeCapabilityJson("Billing",
                    desc("eu.exeris.caps.billing.BillingModule",
                            List.of(ProvidesMetadata.of("com.api.PaymentApi", "1.0")), List.of()));

            // exists but empty, and missing outright — both are vacuous, neither is an error
            assertThat(pipeline.verifyCapTierWall(outputDir, metadataDir)).isZero();
            assertThat(pipeline.verifyCapTierWall(outputDir.resolve("nope"), metadataDir)).isZero();
        }

        @Test
        @DisplayName("cap-tier Wall: a forbidden reference in a real cap module fails (ADR-024 predicate 4)")
        void wallFailsForCapModule() throws IOException {
            writeCapabilityJson("Billing",
                    desc("eu.exeris.caps.billing.BillingModule",
                            List.of(ProvidesMetadata.of("com.api.PaymentApi", "1.0")), List.of()));
            writeClassWithFieldType("eu/exeris/caps/billing/internal/Cap",
                    "org/springframework/context/ApplicationContext");

            assertThatThrownBy(() -> pipeline.verifyCapTierWall(outputDir, metadataDir))
                    .isInstanceOf(CapTierWallException.class)
                    .hasMessageContaining("Cap-tier Wall violated")
                    .hasMessageContaining("eu.exeris.caps.billing.internal.Cap")
                    .hasMessageContaining("org.springframework.context.ApplicationContext");
        }

        /**
         * Writes a minimal class with one field of {@code fieldTypeInternalName}. Hand-built
         * rather than compiled: these two tests exercise the metadata gate and the throw path,
         * not the extraction surface — {@code CapTierWallTest} covers that against real javac
         * output, which is where descriptor-versus-pool fidelity actually matters.
         */
        private void writeClassWithFieldType(String internalName, String fieldTypeInternalName)
                throws IOException {
            ClassDesc owner = ClassDesc.ofInternalName(internalName);
            byte[] bytes = ClassFile.of().build(owner, cb -> cb
                    .withFlags(ClassFile.ACC_PUBLIC)
                    .withField("dep", ClassDesc.ofInternalName(fieldTypeInternalName),
                            ClassFile.ACC_PUBLIC));
            Path target = outputDir.resolve(internalName + ".class");
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
        }

        @Test
        @DisplayName("a @CapabilityLifecycle owner round-trips into cap-manifest.json (the conductor's discovery input)")
        void manifestCarriesLifecycleOwner() throws IOException {
            // cap-manifest.json is the contract with the CompositionConductor: it discovers
            // lifecycle owners by reading this field and instantiating each non-null FQN.
            // A silent serialization drop would surface as a SKU that boots with zero lifecycle
            // hooks — no initialize, no drain — rather than as a build failure. The emitted SKU
            // bootstrap is built directly on this field, and the manifest is serialized straight
            // off CapabilityGraph, independent of the CompositionStamp→CapManifest adapter.
            writeCapabilityJson("Billing",
                    desc("com.app.Billing", List.of(ProvidesMetadata.of("com.api.PaymentApi", "1.0")),
                            List.of(), "com.app.BillingLifecycle"));
            writeCapabilityJson("Audit",
                    desc("com.app.Audit", List.of(ProvidesMetadata.of("com.api.AuditLog", "1.0")), List.of()));

            pipeline.run(metadataDir, outputDir, "com.app");

            String json = Files.readString(outputDir.resolve("cap-manifest.json"));
            assertThat(json).contains("\"lifecycleOwner\" : \"com.app.BillingLifecycle\"");
            // the hook-less module omits the field entirely (@JsonInclude(NON_NULL) on the SDK
            // record), so the conductor sees nothing to instantiate rather than a null entry
            assertThat(json).containsOnlyOnce("\"lifecycleOwner\"");
        }

        @Test
        @DisplayName("T18 (second guard): capabilities present but zero domains + prior domain tree → REFUSES to wipe")
        void capabilitiesPresentButDomainsVanishedRefusesToWipe() throws IOException {
            // A prior run generated a domain (OrderRepository) and owns it via the manifest.
            Path owned = outputDir.resolve("com/app/repository/OrderRepository.java");
            Files.createDirectories(owned.getParent());
            Files.writeString(owned, "class OrderRepository {}");
            Files.writeString(outputDir.resolve(".exeris-codegen-manifest"),
                    "# Exeris Tooling generated-output manifest - DO NOT EDIT MANUALLY\n"
                            + "com/app/repository/OrderRepository.java\n");

            // This run finds capabilities but NO @ExerisDomain (masked compile failure
            // of the domain sources). The second guard refuses rather than letting the
            // trailing prune wipe the committed domain tree.
            writeCapabilityJson("Billing",
                    desc("com.app.Billing", List.of(ProvidesMetadata.of("com.api.PaymentApi", "1.0")), List.of()));

            assertThatThrownBy(() -> pipeline.run(metadataDir, outputDir, "com.app"))
                    .isInstanceOf(EmptyMetadataException.class)
                    .hasMessageContaining("Refusing to wipe");

            // The committed domain tree survives.
            assertThat(owned).exists();
        }

        @Test
        @DisplayName("domains + capabilities both emit (bootstrap pair + manifest)")
        void domainsAndCapabilities() throws IOException {
            writeDomainJson("Product.json", productDomain());
            writeCapabilityJson("Billing",
                    desc("com.app.Billing", List.of(ProvidesMetadata.of("com.api.PaymentApi")), List.of()));

            pipeline.run(metadataDir, outputDir, "com.shop");

            assertThat(outputDir.resolve("com/shop/Application.java")).exists();
            assertThat(outputDir.resolve("cap-manifest.json")).exists();
        }

        @Test
        @DisplayName("GC2: a build with capabilities emits the boot-conductor call site into Application")
        void capabilitiesDriveTheConductorCallSite() throws IOException {
            writeDomainJson("Product.json", productDomain());
            writeCapabilityJson("Billing",
                    desc("com.app.Billing", List.of(ProvidesMetadata.of("com.api.PaymentApi", "1.0")),
                            List.of(), "com.app.BillingLifecycle"));

            pipeline.run(metadataDir, outputDir, "com.shop");

            assertThat(Files.readString(outputDir.resolve("com/shop/Application.java")))
                    .contains("import eu.exeris.sdk.composition.runtime.CompositionConductor")
                    .contains("CompositionConductor.from(capManifest()).start()");
        }

        @Test
        @DisplayName("GC2: a build with no capabilities emits no conductor symbol (no inert wiring)")
        void domainOnlyBuildEmitsNoConductor() throws IOException {
            writeDomainJson("Product.json", productDomain());

            pipeline.run(metadataDir, outputDir, "com.shop");

            assertThat(Files.readString(outputDir.resolve("com/shop/Application.java")))
                    .doesNotContain("CompositionConductor")
                    .doesNotContain("capManifest");
        }

        @Test
        @DisplayName("an unsatisfied required capability fails the run")
        void unsatisfiedFailsRun() throws IOException {
            writeCapabilityJson("Checkout",
                    desc("com.app.Checkout", List.of(),
                            List.of(RequiresMetadata.of("com.api.PaymentApi"))));

            assertThatThrownBy(() -> pipeline.run(metadataDir, outputDir, "com.app"))
                    .isInstanceOf(CapabilityGraphException.class)
                    .hasMessageContaining("com.api.PaymentApi");
            // build failed before writing a manifest
            assertThat(outputDir.resolve("cap-manifest.json")).doesNotExist();
        }

        @Test
        @DisplayName("cap-manifest.json is byte-identical regardless of load order (deterministic)")
        void manifestDeterministic(@TempDir Path outputB) throws IOException {
            writeCapabilityJson("Billing",
                    desc("com.app.Billing", List.of(ProvidesMetadata.of("com.api.PaymentApi", "1.0")), List.of()));
            writeCapabilityJson("Checkout",
                    desc("com.app.Checkout", List.of(),
                            List.of(RequiresMetadata.of("com.api.PaymentApi", "[1.0,2.0)"))));

            pipeline.run(metadataDir, outputDir, "com.app");
            pipeline.run(metadataDir, outputB, "com.app");

            assertThat(Files.readString(outputB.resolve("cap-manifest.json")))
                    .isEqualTo(Files.readString(outputDir.resolve("cap-manifest.json")));
        }

        @Test
        @DisplayName("loadCapabilities ignores enum_/domain JSON and reads only capability_*")
        void loadCapabilitiesFilters() throws IOException {
            writeDomainJson("Product.json", productDomain());
            writeCapabilityJson("Billing",
                    desc("com.app.Billing", List.of(ProvidesMetadata.of("com.api.PaymentApi")), List.of()));

            List<CapabilityModuleDescriptor> caps = pipeline.loadCapabilities(metadataDir);

            assertThat(caps).singleElement()
                    .satisfies(c -> assertThat(c.qualifiedName()).isEqualTo("com.app.Billing"));
            // and the domain loader excludes the capability file
            assertThat(pipeline.loadMetadata(metadataDir))
                    .singleElement()
                    .satisfies(d -> assertThat(d.entityName()).isEqualTo("Product"));
        }
    }

    @Nested
    @DisplayName("deferred capability validation (T18a)")
    class DeferredCapabilityValidation {

        /** An unsatisfiable graph: Checkout requires a service nobody provides. */
        private void writeUnsatisfiableCapability() throws IOException {
            writeCapabilityJson("Checkout",
                    desc("com.app.Checkout", List.of(),
                            List.of(RequiresMetadata.of("com.api.PaymentApi"))));
        }

        /** Simulates a prior successful run that owns cap-manifest.json. */
        private void seedPriorCapManifest(String content) throws IOException {
            Files.writeString(outputDir.resolve("cap-manifest.json"), content);
            Files.writeString(outputDir.resolve(".exeris-codegen-manifest"),
                    "# Exeris Tooling generated-output manifest - DO NOT EDIT MANUALLY\n"
                            + "cap-manifest.json\n");
        }

        @Test
        @DisplayName("deferred failure: run completes, the prior cap-manifest.json survives byte-untouched and stays owned")
        void deferredFailurePreservesPriorManifest() throws IOException {
            seedPriorCapManifest("{\"stale\":true}\n");
            writeUnsatisfiableCapability();

            // deferCapabilityFailure=true: the stale-input deadlock path — no throw.
            int filesGenerated = pipeline.run(metadataDir, outputDir, "com.app", false, true);

            assertThat(filesGenerated).isZero();
            assertThat(Files.readString(outputDir.resolve("cap-manifest.json")))
                    .isEqualTo("{\"stale\":true}\n");
            // still owned by the generated tree (the prune did not orphan it)
            assertThat(Files.readAllLines(outputDir.resolve(".exeris-codegen-manifest")))
                    .contains("cap-manifest.json");
        }

        @Test
        @DisplayName("deferred failure with no prior cap-manifest.json: run completes, nothing is emitted")
        void deferredFailureWithoutPriorManifest() throws IOException {
            writeUnsatisfiableCapability();

            int filesGenerated = pipeline.run(metadataDir, outputDir, "com.app", false, true);

            assertThat(filesGenerated).isZero();
            assertThat(outputDir.resolve("cap-manifest.json")).doesNotExist();
        }

        @Test
        @DisplayName("deferred failure does not block domain emission (the deadlock scenario, domains present)")
        void deferredFailureStillEmitsDomains() throws IOException {
            writeDomainJson("Product.json", productDomain());
            writeUnsatisfiableCapability();

            int filesGenerated = pipeline.run(metadataDir, outputDir, "com.shop", false, true);

            assertThat(filesGenerated).isGreaterThan(0);
            assertThat(outputDir.resolve("com/shop/Application.java")).exists();
            assertThat(outputDir.resolve("cap-manifest.json")).doesNotExist();
        }

        @Test
        @DisplayName("defer=false keeps the fail-fast abort (5-arg explicit strict)")
        void strictModeStillAborts() throws IOException {
            writeUnsatisfiableCapability();

            assertThatThrownBy(() -> pipeline.run(metadataDir, outputDir, "com.app", false, false))
                    .isInstanceOf(CapabilityGraphException.class)
                    .hasMessageContaining("com.api.PaymentApi");
        }

        @Test
        @DisplayName("a VALID graph in deferred mode emits cap-manifest.json normally (no semantic change)")
        void validGraphUnaffectedByDeferredMode() throws IOException {
            writeCapabilityJson("Billing",
                    desc("com.app.Billing", List.of(ProvidesMetadata.of("com.api.PaymentApi", "1.0")), List.of()));

            int filesGenerated = pipeline.run(metadataDir, outputDir, "com.app", false, true);

            assertThat(filesGenerated).isEqualTo(1);
            assertThat(outputDir.resolve("cap-manifest.json")).exists();
        }

        @Test
        @DisplayName("validateCapabilities: valid graph returns the module count and writes nothing")
        void validateCapabilitiesValidGraph() throws IOException {
            writeCapabilityJson("Billing",
                    desc("com.app.Billing", List.of(ProvidesMetadata.of("com.api.PaymentApi", "1.0")), List.of()));
            writeCapabilityJson("Checkout",
                    desc("com.app.Checkout", List.of(),
                            List.of(RequiresMetadata.of("com.api.PaymentApi", "[1.0,2.0)"))));

            int modules = pipeline.validateCapabilities(metadataDir);

            assertThat(modules).isEqualTo(2);
            // validation-only: the output tree is untouched
            try (var stream = Files.list(outputDir)) {
                assertThat(stream.count()).isZero();
            }
        }

        @Test
        @DisplayName("validateCapabilities: invalid graph throws CapabilityGraphException (the authoritative fresh-input gate)")
        void validateCapabilitiesInvalidGraph() throws IOException {
            writeUnsatisfiableCapability();

            assertThatThrownBy(() -> pipeline.validateCapabilities(metadataDir))
                    .isInstanceOf(CapabilityGraphException.class)
                    .hasMessageContaining("com.api.PaymentApi");
        }

        @Test
        @DisplayName("validateCapabilities: no capability metadata → 0, no exception")
        void validateCapabilitiesNothingToValidate(@TempDir Path scratch) throws IOException {
            assertThat(pipeline.validateCapabilities(metadataDir)).isZero();
            assertThat(pipeline.validateCapabilities(scratch.resolve("does-not-exist"))).isZero();
        }

        @Test
        @DisplayName("validateCapabilities rejects null metadataDir")
        void validateCapabilitiesRejectsNull() {
            assertThatThrownBy(() -> pipeline.validateCapabilities(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("metadataDir");
        }
    }

    private static final class NullReturningGenerator implements KernelArtifactGenerator {
        @Override
        public GeneratedFile generate(DomainMetadata metadata) {
            return null;
        }

        @Override
        public ArtifactType artifactType() {
            return ArtifactType.CONTROLLER;
        }
    }

    /**
     * Each warning the pipeline logs carries its {@link DiagnosticId} (ADR-095). The pipeline logs
     * through {@code System.Logger}, which the JDK backs with {@code java.util.logging} when no
     * other {@code LoggerFinder} is installed, so a JUL handler on the pipeline's logger sees
     * every record.
     */
    @Nested
    @DisplayName("diagnostic identifiers on logged warnings")
    class DiagnosticIdentifiers {

        private final Logger julLogger = Logger.getLogger(CodegenPipeline.class.getName());
        private final List<String> warnings = new ArrayList<>();
        private final Handler capture = new Handler() {
            @Override
            public void publish(LogRecord logRecord) {
                if (logRecord.getLevel() == java.util.logging.Level.WARNING) {
                    warnings.add(logRecord.getMessage());
                }
            }

            @Override
            public void flush() {
                // nothing buffered
            }

            @Override
            public void close() {
                // nothing held
            }
        };

        @BeforeEach
        void attach() {
            julLogger.addHandler(capture);
        }

        @AfterEach
        void detach() {
            julLogger.removeHandler(capture);
        }

        @Test
        @DisplayName("no metadata at all → EXT-GEN-3101")
        void noMetadata() throws IOException {
            pipeline.run(metadataDir, outputDir, "com.shop");

            assertThat(warnings).singleElement().asString()
                    .startsWith(DiagnosticId.NO_METADATA_FOUND.format("No domain or capability metadata"));
        }

        @Test
        @DisplayName("an unsatisfied optional @Requires → EXT-GEN-3102, in run and in validateCapabilities")
        void optionalRequirementUnsatisfied() throws IOException {
            writeCapabilityJson("Checkout",
                    desc("com.app.Checkout", List.of(),
                            List.of(RequiresMetadata.optional("com.api.PaymentApi"))));

            pipeline.run(metadataDir, outputDir, "com.app");
            pipeline.validateCapabilities(metadataDir);

            assertThat(warnings).hasSize(2).allSatisfy(w -> assertThat(w)
                    .startsWith(DiagnosticId.OPTIONAL_REQUIREMENT_UNSATISFIED.format("capability: ")));
        }

        @Test
        @DisplayName("a deferred capability-graph failure → EXT-GEN-3103")
        void deferredGraphFailure() throws IOException {
            writeCapabilityJson("Checkout",
                    desc("com.app.Checkout", List.of(),
                            List.of(RequiresMetadata.of("com.api.PaymentApi"))));

            pipeline.run(metadataDir, outputDir, "com.app", false, true);

            assertThat(warnings).singleElement().asString()
                    .startsWith(DiagnosticId.CAPABILITY_GRAPH_DEFERRED.format("Capability graph invalid"));
        }

        @Test
        @DisplayName("an auto-detected base package over entities in several packages → EXT-GEN-3104")
        void basePackageInferred() throws IOException {
            writeDomainJson("Order.json", DomainMetadata.builder("Order", "com.shop.order.domain")
                    .path("/orders").build());
            writeDomainJson("Invoice.json", DomainMetadata.builder("Invoice", "com.shop.billing.domain")
                    .path("/invoices").build());

            pipeline.run(metadataDir, outputDir, null);

            assertThat(warnings).singleElement().asString()
                    .startsWith(DiagnosticId.BASE_PACKAGE_INFERRED.format("No base package given"))
                    .contains("[com.shop.billing, com.shop.order]")
                    .contains("emitted in com.shop.billing ")
                    .contains("exeris.basePackage");
        }

        @Test
        @DisplayName("one entity package, or an explicit base package, prints no EXT-GEN-3104")
        void basePackageNotInferredAcrossPackages() throws IOException {
            writeDomainJson("Product.json", productDomain());
            pipeline.run(metadataDir, outputDir, null);

            writeDomainJson("Invoice.json", DomainMetadata.builder("Invoice", "com.shop.billing.domain")
                    .path("/invoices").build());
            pipeline.run(metadataDir, outputDir, "com.shop");

            assertThat(warnings).isEmpty();
        }

        @Test
        @DisplayName("a cap-tier Wall that scanned nothing → EXT-PLUG-2206, the plugin's identifier for the same event")
        void wallScannedNothing() throws IOException {
            writeCapabilityJson("Billing",
                    desc("eu.exeris.caps.billing.BillingModule",
                            List.of(ProvidesMetadata.of("com.api.PaymentApi", "1.0")), List.of()));

            pipeline.verifyCapTierWall(outputDir, metadataDir);

            assertThat(warnings).singleElement().asString()
                    .startsWith(DiagnosticId.CAP_TIER_WALL_SCANNED_NOTHING.format("Cap-tier Wall scanned nothing"));
        }
    }
}
