package eu.exeris.tooling.initializr.engine;

import eu.exeris.tooling.initializr.model.ProjectTree;
import eu.exeris.tooling.initializr.model.ScaffoldFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ZipArchiveGeneratorTest {

    private static final ProjectTree TREE = new ProjectTree(List.of(
            ScaffoldFile.text("pom.xml", "<project/>"),
            ScaffoldFile.text("src/main/java/A.java", "class A {}")));

    @Test
    @DisplayName("writes each file under the root, in the tree's order, with its content")
    void entries() throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zip(TREE)), StandardCharsets.UTF_8)) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                entries.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        assertThat(entries).containsExactly(
                Map.entry("demo/pom.xml", "<project/>"),
                Map.entry("demo/src/main/java/A.java", "class A {}"));
    }

    @Test
    @DisplayName("gives the same bytes for the same tree, whenever it runs")
    void deterministic() throws IOException {
        assertThat(zip(TREE)).isEqualTo(zip(TREE));
    }

    @Test
    @DisplayName("leaves the caller's stream open")
    void keepsStreamOpen() throws IOException {
        boolean[] closed = {false};
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        OutputStream tracking = new OutputStream() {
            @Override
            public void write(int b) {
                bytes.write(b);
            }

            @Override
            public void close() {
                closed[0] = true;
            }
        };
        ZipArchiveGenerator.write(TREE, "demo", tracking);
        assertThat(closed[0]).isFalse();
        assertThat(bytes.size()).isPositive();
    }

    @Test
    @DisplayName("refuses a root that is blank or more than one path segment")
    void refusesBadRoot() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThatThrownBy(() -> ZipArchiveGenerator.write(TREE, " ", out)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ZipArchiveGenerator.write(TREE, "a/b", out)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ZipArchiveGenerator.write(TREE, "a\\b", out)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ZipArchiveGenerator.write(TREE, null, out)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {".", "..", "...", "demo.", "demo ", "a:b", "C:", "demo:", "CON", "nul", "Com1", "aux.txt", "LPT9.x"})
    @DisplayName("refuses a root made only of dots, ending in a dot or a space, holding a colon, or starting with a Windows device name")
    void refusesUnextractableRoot(String root) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThatThrownBy(() -> ZipArchiveGenerator.write(TREE, root, out)).isInstanceOf(IllegalArgumentException.class);
        assertThat(out.size()).isZero();
    }

    @Test
    @DisplayName("accepts a root with an inner dot or space")
    void acceptsInnerDot() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ZipArchiveGenerator.write(TREE, "my.app v1", out);
        ZipArchiveGenerator.write(TREE, "console", out);
        assertThat(out.size()).isPositive();
    }

    private static byte[] zip(ProjectTree tree) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ZipArchiveGenerator.write(tree, "demo", out);
        return out.toByteArray();
    }
}
