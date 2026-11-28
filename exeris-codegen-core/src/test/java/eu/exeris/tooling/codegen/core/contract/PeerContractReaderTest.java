package eu.exeris.tooling.codegen.core.contract;

import eu.exeris.sdk.sourcemodel.mutation.SchemaVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PeerContractReader — one rule set for a directory and a jar")
class PeerContractReaderTest {

    private static final String MANIFEST = """
            {"schemaVersion":2,"stamp":{"validated":true,"compositionVersion":"1.2.0","contentBinding":"sha256:x"},
             "modules":[],"somethingNew":{"a":1}}
            """;

    private final PeerContractReader reader = new PeerContractReader("1");

    private static String entity(String pkg, String name, String schema) {
        return "{\"entityName\":\"" + name + "\",\"packageName\":\"" + pkg + "\""
                + (schema == null ? "" : ",\"schemaVersion\":\"" + schema + "\"") + "}";
    }

    private static Map<String, String> contract() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("cap-manifest.json", MANIFEST);
        files.put("Zeta.json", entity("com.b.domain", "Zeta", "1"));
        files.put("Order.json", entity("com.b.domain", "Order", "1"));
        files.put("enum_Status.json", "{\"qualifiedName\":\"com.b.domain.Status\"}");
        files.put("enum_Kind.json", "{\"qualifiedName\":\"com.b.domain.Kind\"}");
        return files;
    }

    private static void write(Path dir, Map<String, String> files) throws IOException {
        Files.createDirectories(dir);
        for (Map.Entry<String, String> e : files.entrySet()) {
            Files.writeString(dir.resolve(e.getKey()), e.getValue());
        }
    }

    private static Path jar(Path file, Map<String, String> entries) throws IOException {
        try (OutputStream out = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> e : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return file;
    }

    @Test
    @DisplayName("reads a contract directory; entities and enums are sorted by qualified name")
    void readsDirectory(@TempDir Path tmp) throws IOException {
        write(tmp, contract());

        PeerContract c = reader.read("billing", tmp);

        assertThat(c.name()).isEqualTo("billing");
        assertThat(c.compositionVersion()).isEqualTo("1.2.0");
        assertThat(c.contentBinding()).isEqualTo("sha256:x");
        assertThat(c.entities()).extracting(PeerContract.File::qualifiedName)
                .containsExactly("com.b.domain.Order", "com.b.domain.Zeta");
        assertThat(c.enums()).extracting(PeerContract.File::qualifiedName)
                .containsExactly("com.b.domain.Kind", "com.b.domain.Status");
        assertThat(c.warnings()).isEmpty();
    }

    @Test
    @DisplayName("a directory that holds META-INF/exeris/contract/ is the same contract")
    void readsClassOutputRoot(@TempDir Path tmp) throws IOException {
        write(tmp.resolve("META-INF/exeris/contract"), contract());

        assertThat(reader.read("billing", tmp).entities()).hasSize(2);
    }

    @Test
    @DisplayName("a jar is read in memory from META-INF/exeris/contract/ only, and the same contract results")
    void readsJar(@TempDir Path tmp) throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        contract().forEach((k, v) -> entries.put("META-INF/exeris/contract/" + k, v));
        entries.put("Stray.json", entity("com.x", "Stray", "1"));
        entries.put("META-INF/exeris/contract/nested/Deep.json", entity("com.x", "Deep", "1"));
        entries.put("eu/acme/Foo.class", "bytes");
        Path jar = jar(tmp.resolve("billing.jar"), entries);

        PeerContract c = reader.read("billing", jar);

        assertThat(c.entities()).extracting(PeerContract.File::qualifiedName)
                .containsExactly("com.b.domain.Order", "com.b.domain.Zeta");
        assertThat(c.enums()).hasSize(2);
        try (var listing = Files.list(tmp)) {
            assertThat(listing.count()).as("nothing is extracted next to the jar").isEqualTo(1);
        }
    }

    @Test
    @DisplayName("members of the manifest this reader does not know, and files it does not read, are ignored")
    void ignoresUnknown(@TempDir Path tmp) throws IOException {
        Map<String, String> files = contract();
        files.put("events.json", "{\"schemaVersion\":1,\"events\":[]}");
        files.put("view_Page.json", "{\"viewName\":\"Page\"}");
        files.put("capability_Billing.json", "{\"qualifiedName\":\"x\"}");
        files.put("notes.txt", "not json at all");
        write(tmp, files);

        PeerContract c = reader.read("billing", tmp);

        assertThat(c.entities()).hasSize(2);
        assertThat(c.manifest().has("somethingNew")).isTrue();
    }

    @Test
    @DisplayName("a v1 manifest is refused, naming the peer")
    void refusesV1(@TempDir Path tmp) throws IOException {
        Map<String, String> files = contract();
        files.put("cap-manifest.json", "{\"schemaVersion\":1}");
        write(tmp, files);

        assertThatThrownBy(() -> reader.read("billing", tmp))
                .isInstanceOf(PeerContractException.class)
                .hasMessageContaining("peer 'billing'")
                .hasMessageContaining("schemaVersion 1")
                .hasMessageContaining("floor of 2");
    }

    @Test
    @DisplayName("a manifest with no integer schemaVersion, or unreadable JSON, is refused")
    void refusesUnreadableManifest(@TempDir Path tmp) throws IOException {
        write(tmp.resolve("a"), Map.of("cap-manifest.json", "{\"schemaVersion\":\"2\"}"));
        write(tmp.resolve("b"), Map.of("cap-manifest.json", "{not json"));

        assertThatThrownBy(() -> reader.read("billing", tmp.resolve("a")))
                .hasMessageContaining("peer 'billing'").hasMessageContaining("no integer schemaVersion");
        assertThatThrownBy(() -> reader.read("billing", tmp.resolve("b")))
                .hasMessageContaining("peer 'billing'").hasMessageContaining("not readable JSON");
    }

    @Test
    @DisplayName("a missing manifest is refused: metadata alone is not a contract")
    void refusesMissingManifest(@TempDir Path tmp) throws IOException {
        Map<String, String> files = contract();
        files.remove("cap-manifest.json");
        write(tmp.resolve("META-INF/exeris/contract"), files);

        assertThatThrownBy(() -> reader.read("billing", tmp))
                .isInstanceOf(PeerContractException.class)
                .hasMessageContaining("peer 'billing'")
                .hasMessageContaining("no cap-manifest.json");
    }

    @Test
    @DisplayName("a jar with no contract entries is refused as having no manifest")
    void refusesJarWithoutContract(@TempDir Path tmp) throws IOException {
        Path jar = jar(tmp.resolve("plain.jar"), Map.of("eu/acme/Foo.class", "bytes"));

        assertThatThrownBy(() -> reader.read("billing", jar))
                .hasMessageContaining("peer 'billing'").hasMessageContaining("no cap-manifest.json");
    }

    @Test
    @DisplayName("a directory with neither a manifest nor META-INF/exeris/contract/ names the missing contract")
    void refusesDirectoryWithoutContract(@TempDir Path tmp) {
        assertThatThrownBy(() -> reader.read("billing", tmp))
                .hasMessageContaining("peer 'billing'")
                .hasMessageContaining("META-INF/exeris/contract")
                .hasMessageContaining("process-classes");
    }

    @Test
    @DisplayName("a missing path and a file that is not a jar are refused")
    void refusesMissingPathAndNonJar(@TempDir Path tmp) throws IOException {
        Path file = Files.writeString(tmp.resolve("contract.json"), "{}");

        assertThatThrownBy(() -> reader.read("billing", tmp.resolve("absent")))
                .hasMessageContaining("peer 'billing'").hasMessageContaining("does not exist");
        assertThatThrownBy(() -> reader.read("billing", file))
                .hasMessageContaining("peer 'billing'").hasMessageContaining("not a .jar");
    }

    @Test
    @DisplayName("an unreadable jar is refused with the peer's name")
    void refusesCorruptJar(@TempDir Path tmp) throws IOException {
        Path jar = Files.writeString(tmp.resolve("broken.jar"), "this is not a zip");

        assertThatThrownBy(() -> reader.read("billing", jar))
                .isInstanceOf(PeerContractException.class).hasMessageContaining("peer 'billing'");
    }

    @Test
    @DisplayName("an entity with no schemaVersion is refused; one with a different schemaVersion is a warning")
    void entitySchemaVersion(@TempDir Path tmp) throws IOException {
        Map<String, String> skewed = contract();
        skewed.put("Order.json", entity("com.b.domain", "Order", "0"));
        write(tmp.resolve("skew"), skewed);
        Map<String, String> bare = contract();
        bare.put("Order.json", entity("com.b.domain", "Order", null));
        write(tmp.resolve("bare"), bare);

        Path skewDir = tmp.resolve("skew");
        Path bareDir = tmp.resolve("bare");

        PeerContract c = reader.read("billing", skewDir);

        assertThat(c.warnings()).singleElement().asString()
                .contains("peer 'billing'").contains("Order").contains("schemaVersion 0").contains("reads 1");
        assertThatThrownBy(() -> reader.read("billing", bareDir))
                .isInstanceOf(PeerContractException.class)
                .hasMessageContaining("peer 'billing'").hasMessageContaining("Order")
                .hasMessageContaining("no schemaVersion");
    }

    @Test
    @DisplayName("an entity file that is not JSON is refused")
    void refusesBrokenEntity(@TempDir Path tmp) throws IOException {
        Map<String, String> files = contract();
        files.put("Order.json", "{oops");
        write(tmp, files);

        assertThatThrownBy(() -> reader.read("billing", tmp))
                .hasMessageContaining("peer 'billing'").hasMessageContaining("Order.json");
    }

    @Test
    @DisplayName("the default reader compares against the SDK's current schema version")
    void defaultReaderUsesSdkVersion(@TempDir Path tmp) throws IOException {
        Map<String, String> files = contract();
        files.put("Order.json", entity("com.b.domain", "Order", "no-such-version"));
        files.put("Zeta.json", entity("com.b.domain", "Zeta", SchemaVersion.CURRENT));
        write(tmp, files);

        assertThat(new PeerContractReader().read("billing", tmp).warnings())
                .singleElement().asString().contains("Order");
    }
}
