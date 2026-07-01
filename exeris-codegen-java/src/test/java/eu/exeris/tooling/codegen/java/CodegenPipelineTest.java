package eu.exeris.tooling.codegen.java;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.exeris.sdk.sourcemodel.ast.CapabilityModuleMetadata;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.ProvidesMetadata;
import eu.exeris.sdk.sourcemodel.ast.RequiresMetadata;
import eu.exeris.tooling.codegen.core.capability.CapabilityGraphException;
import eu.exeris.tooling.codegen.core.capability.CapabilityModuleDescriptor;
import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import eu.exeris.tooling.codegen.core.generator.GeneratorRegistry;
import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator;
import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator.ArtifactType;
import eu.exeris.tooling.codegen.java.kernel.KernelApplicationGenerator;
import eu.exeris.tooling.codegen.java.kernel.KernelGeneratorStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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

    @Nested
    @DisplayName("happy paths")
    class HappyPaths {

        @Test
        @DisplayName("writes at least the bootstrap pair plus per-entity files for one domain")
        void singleDomainGeneratesFiles() throws IOException {
            writeDomainJson("Product.json", productDomain());

            int filesGenerated = pipeline.run(metadataDir, outputDir, "com.shop");

            assertThat(filesGenerated).isGreaterThan(0);
            // Bootstrap pair is emitted by KernelApplicationGenerator under the
            // caller-supplied base package.
            assertThat(outputDir.resolve("com/shop/Application.java")).exists();
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
        @DisplayName("auto-detects base package from first domain when null is passed")
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

            // Two entities × per-entity generators + one bootstrap pair (Application + RuntimeLifecycle).
            assertThat(filesGenerated).isGreaterThan(4);
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
        @DisplayName("empty generator registry → only bootstrap pair is written")
        void emptyRegistryStillWritesBootstrap() throws IOException {
            GeneratorRegistry empty = new GeneratorRegistry();
            CodegenPipeline customPipeline = new CodegenPipeline(
                    empty,
                    new KernelApplicationGenerator(),
                    CodegenPipeline.defaultMapper()
            );
            writeDomainJson("Product.json", productDomain());

            int filesGenerated = customPipeline.run(metadataDir, outputDir, "com.shop");

            assertThat(filesGenerated).isEqualTo(2);
            assertThat(outputDir.resolve("com/shop/Application.java")).exists();
            assertThat(outputDir.resolve("com/shop/RuntimeLifecycle.java")).exists();
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

            // Null-returning generator emits 0; bootstrap pair emits 2.
            assertThat(filesGenerated).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("capability manifest (PR-E)")
    class CapabilityManifest {

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
}
