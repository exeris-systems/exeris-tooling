package eu.exeris.tooling.codegen.java;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.exeris.sdk.sourcemodel.ast.CapabilityModuleMetadata;
import eu.exeris.sdk.sourcemodel.ast.ProvidesMetadata;
import eu.exeris.sdk.sourcemodel.ast.RequiresMetadata;
import eu.exeris.tooling.codegen.core.capability.CapabilityGraphException;
import eu.exeris.tooling.codegen.core.capability.CapabilityModuleDescriptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CodegenPipeline.writeContract — the backend's contract artifact (ADR-048 Amendment 3)")
class ContractArtifactTest {

    private static final String ENTITY = """
            {"entityName":"Order","packageName":"com.shop.domain","schemaVersion":"1","sourceDigest":"sha256:ab"}
            """;
    private static final String ENUM = "{\"qualifiedName\":\"com.shop.domain.Status\",\"values\":[\"NEW\"]}\n";

    @TempDir
    Path metadataDir;

    @TempDir
    Path contractDir;

    private CodegenPipeline pipeline;
    private final ObjectMapper mapper = CodegenPipeline.defaultMapper();

    @BeforeEach
    void setUp() {
        pipeline = CodegenPipeline.createDefault();
    }

    private Path contract() {
        return contractDir.resolve("META-INF/exeris/contract");
    }

    private void capability(String qName, List<ProvidesMetadata> provides, List<RequiresMetadata> requires)
            throws IOException {
        String simple = qName.substring(qName.lastIndexOf('.') + 1);
        String pkg = qName.substring(0, qName.lastIndexOf('.'));
        CapabilityModuleDescriptor descriptor = new CapabilityModuleDescriptor(simple, pkg, qName,
                CapabilityModuleMetadata.builder().provides(provides).requires(requires).build());
        mapper.writeValue(metadataDir.resolve("capability_" + simple + ".json").toFile(), descriptor);
    }

    private static List<String> names(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    @DisplayName("a build with no capability module publishes the graph of the empty composition")
    void emptyComposition() throws IOException {
        Files.writeString(metadataDir.resolve("Order.json"), ENTITY);

        int written = pipeline.writeContract(metadataDir, contract());

        assertThat(written).isEqualTo(2);
        JsonNode manifest = mapper.readTree(contract().resolve("cap-manifest.json").toFile());
        assertThat(manifest.path("schemaVersion").intValue()).isEqualTo(2);
        assertThat(manifest.path("stamp").path("validated").booleanValue()).isTrue();
        assertThat(manifest.path("stamp").path("contentBinding").asText()).startsWith("sha256:");
        assertThat(manifest.path("modules")).isEmpty();
        assertThat(manifest.path("resolutions")).isEmpty();
        assertThat(manifest.path("initOrder")).isEmpty();
        assertThat(manifest.path("warnings")).isEmpty();
    }

    @Test
    @DisplayName("entity and enum files are copied byte for byte; view, capability and unrelated files are not")
    void copiesEntitiesAndEnumsOnly() throws IOException {
        Files.writeString(metadataDir.resolve("Order.json"), ENTITY);
        Files.writeString(metadataDir.resolve("enum_Status.json"), ENUM);
        Files.writeString(metadataDir.resolve("view_OrderPage.json"), "{\"viewName\":\"OrderPage\"}");
        Files.writeString(metadataDir.resolve("notes.json"), "{\"unrelated\":true}");
        Files.writeString(metadataDir.resolve("readme.txt"), "x");
        capability("com.app.Billing", List.of(ProvidesMetadata.of("com.api.PaymentApi", "1.0")), List.of());

        pipeline.writeContract(metadataDir, contract());

        assertThat(names(contract())).containsExactly("Order.json", "cap-manifest.json", "enum_Status.json");
        assertThat(Files.readAllBytes(contract().resolve("Order.json")))
                .isEqualTo(Files.readAllBytes(metadataDir.resolve("Order.json")));
        assertThat(Files.readAllBytes(contract().resolve("enum_Status.json")))
                .isEqualTo(Files.readAllBytes(metadataDir.resolve("enum_Status.json")));
    }

    @Test
    @DisplayName("its manifest is byte-identical to the one exeris:generate writes for the same metadata")
    void manifestEqualsGenerateManifest(@TempDir Path generated) throws IOException {
        capability("com.app.Billing", List.of(ProvidesMetadata.of("com.api.PaymentApi", "1.0")), List.of());
        capability("com.app.Checkout", List.of(), List.of(RequiresMetadata.of("com.api.PaymentApi", "[1.0,2.0)")));

        pipeline.run(metadataDir, generated, "com.app");
        pipeline.writeContract(metadataDir, contract());

        assertThat(Files.readAllBytes(contract().resolve("cap-manifest.json")))
                .isEqualTo(Files.readAllBytes(generated.resolve("cap-manifest.json")));
    }

    @Test
    @DisplayName("writing twice gives the same entries with the same bytes")
    void deterministic(@TempDir Path other) throws IOException {
        Files.writeString(metadataDir.resolve("Order.json"), ENTITY);
        Files.writeString(metadataDir.resolve("enum_Status.json"), ENUM);
        capability("com.app.Billing", List.of(ProvidesMetadata.of("com.api.PaymentApi", "1.0")), List.of());

        pipeline.writeContract(metadataDir, contract());
        pipeline.writeContract(metadataDir, other.resolve("META-INF/exeris/contract"));

        List<String> files = names(contract());
        assertThat(names(other.resolve("META-INF/exeris/contract"))).isEqualTo(files);
        for (String file : files) {
            assertThat(Files.readAllBytes(other.resolve("META-INF/exeris/contract").resolve(file)))
                    .as(file).isEqualTo(Files.readAllBytes(contract().resolve(file)));
        }
    }

    @Test
    @DisplayName("a file absent from the metadata is absent from the contract: the directory is written anew")
    void staleEntriesAreRemoved() throws IOException {
        Files.createDirectories(contract());
        Files.writeString(contract().resolve("Gone.json"), ENTITY);
        Files.writeString(metadataDir.resolve("Order.json"), ENTITY);

        pipeline.writeContract(metadataDir, contract());

        assertThat(names(contract())).containsExactly("Order.json", "cap-manifest.json");
    }

    @Test
    @DisplayName("a directory that is not META-INF/exeris/contract is refused and nothing in it is deleted")
    void refusesOtherDirectories() throws IOException {
        Path elsewhere = Files.createDirectories(contractDir.resolve("src"));
        Files.writeString(elsewhere.resolve("Keep.java"), "class Keep {}");

        assertThatThrownBy(() -> pipeline.writeContract(metadataDir, elsewhere))
                .isInstanceOf(IOException.class).hasMessageContaining("META-INF/exeris/contract");

        assertThat(elsewhere.resolve("Keep.java")).exists();
    }

    @Test
    @DisplayName("an absent metadata directory publishes the empty composition with no entities")
    void absentMetadata() throws IOException {
        int written = pipeline.writeContract(metadataDir.resolve("missing"), contract());

        assertThat(written).isEqualTo(1);
        assertThat(names(contract())).containsExactly("cap-manifest.json");
    }

    @Test
    @DisplayName("a graph that does not resolve throws and leaves the previous contract in place")
    void unresolvedGraphWritesNothing() throws IOException {
        Files.createDirectories(contract());
        Files.writeString(contract().resolve("cap-manifest.json"), "previous");
        capability("com.app.Checkout", List.of(), List.of(RequiresMetadata.of("com.api.PaymentApi")));

        Path target = contract();

        assertThatThrownBy(() -> pipeline.writeContract(metadataDir, target))
                .isInstanceOf(CapabilityGraphException.class);

        assertThat(Files.readString(contract().resolve("cap-manifest.json"))).isEqualTo("previous");
    }
}
