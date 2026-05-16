package eu.exeris.tooling.codegen.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("MetadataLoader")
class MetadataLoaderTest {

    /** Lightweight payload — Jackson 2 deserialises by no-arg ctor + setters.
     *  Package-private: Jackson reaches the no-arg constructor via reflection
     *  (setAccessible(true)), so wider visibility isn't required. */
    static final class SampleMeta {
        private String name;
        private int value;
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public int getValue() { return value; }
        public void setValue(int value) { this.value = value; }
    }

    @TempDir Path tempDir;

    private MetadataLoader loader;

    @BeforeEach
    void setup() {
        loader = new MetadataLoader(tempDir);
    }

    @Test
    @DisplayName("Constructor rejects null classesDir with NullPointerException")
    void constructorRejectsNullClassesDir() {
        assertThatThrownBy(() -> new MetadataLoader(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("classesDir");
    }

    @Test
    @DisplayName("loadAll returns empty list when metadata directory does not exist")
    void loadAllReturnsEmptyWhenDirMissing() throws IOException {
        assertThat(loader.loadAll(SampleMeta.class)).isEmpty();
    }

    @Test
    @DisplayName("loadAll deserialises every .json file in the metadata directory")
    void loadAllReadsJsonFiles() throws IOException {
        Path metaDir = Files.createDirectories(tempDir.resolve(MetadataLoader.METADATA_DIR));
        Files.writeString(metaDir.resolve("Foo.json"), "{\"name\":\"Foo\",\"value\":1}");
        Files.writeString(metaDir.resolve("Bar.json"), "{\"name\":\"Bar\",\"value\":2}");
        // Non-JSON file is ignored.
        Files.writeString(metaDir.resolve("README.txt"), "not a metadata file");

        List<SampleMeta> all = loader.loadAll(SampleMeta.class);

        assertThat(all)
                .hasSize(2)
                .extracting(SampleMeta::getName)
                .containsExactlyInAnyOrder("Foo", "Bar");
    }

    @Test
    @DisplayName("loadAll tolerates unknown JSON properties (FAIL_ON_UNKNOWN_PROPERTIES disabled)")
    void loadAllTolaratesUnknownProperties() throws IOException {
        Path metaDir = Files.createDirectories(tempDir.resolve(MetadataLoader.METADATA_DIR));
        Files.writeString(metaDir.resolve("Foo.json"),
                "{\"name\":\"Foo\",\"value\":1,\"extraField\":\"ignored\"}");

        List<SampleMeta> all = loader.loadAll(SampleMeta.class);

        assertThat(all).hasSize(1);
        assertThat(all.get(0).getName()).isEqualTo("Foo");
    }

    @Test
    @DisplayName("load(entityName, class) reads the matching JSON file")
    void loadByEntityName() throws IOException {
        Path metaDir = Files.createDirectories(tempDir.resolve(MetadataLoader.METADATA_DIR));
        Files.writeString(metaDir.resolve("Order.json"), "{\"name\":\"Order\",\"value\":42}");

        SampleMeta meta = loader.load("Order", SampleMeta.class);

        assertThat(meta.getName()).isEqualTo("Order");
        assertThat(meta.getValue()).isEqualTo(42);
    }

    @Test
    @DisplayName("load(entityName, class) throws IOException when the file is missing")
    void loadByEntityNameThrowsOnMissing() {
        assertThatThrownBy(() -> loader.load("Missing", SampleMeta.class))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Metadata file not found");
    }

    @Test
    @DisplayName("exists(entityName) returns true when the JSON file is present, false otherwise")
    void existsReportsFilePresence() throws IOException {
        Path metaDir = Files.createDirectories(tempDir.resolve(MetadataLoader.METADATA_DIR));
        Files.writeString(metaDir.resolve("Present.json"), "{\"name\":\"Present\",\"value\":0}");

        assertThat(loader.exists("Present")).isTrue();
        assertThat(loader.exists("Absent")).isFalse();
    }

    @Test
    @DisplayName("listEntities returns empty list when the metadata directory is missing")
    void listEntitiesEmptyWhenDirMissing() throws IOException {
        assertThat(loader.listEntities()).isEmpty();
    }

    @Test
    @DisplayName("listEntities returns the entity name for every .json file (without extension)")
    void listEntitiesReturnsFileBasenames() throws IOException {
        Path metaDir = Files.createDirectories(tempDir.resolve(MetadataLoader.METADATA_DIR));
        Files.writeString(metaDir.resolve("Foo.json"), "{\"name\":\"Foo\",\"value\":0}");
        Files.writeString(metaDir.resolve("Bar.json"), "{\"name\":\"Bar\",\"value\":0}");
        Files.writeString(metaDir.resolve("notes.txt"), "ignored");

        assertThat(loader.listEntities()).containsExactlyInAnyOrder("Foo", "Bar");
    }
}
