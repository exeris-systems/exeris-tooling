package eu.exeris.tooling.initializr.engine;

import eu.exeris.tooling.initializr.model.ProjectTree;
import eu.exeris.tooling.initializr.model.ScaffoldFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;

class ProjectWriterTest {

    private static final ProjectTree TREE = new ProjectTree(List.of(
            ScaffoldFile.text("README.md", "readme"),
            ScaffoldFile.script("bin/run.sh", "#!/bin/sh\n"),
            ScaffoldFile.text("src/a/B.java", "class B {}")));

    @TempDir
    Path tmp;

    @Test
    @DisplayName("writes every file under a directory it creates, and marks a script executable")
    void writesDirectory() throws IOException {
        Path root = tmp.resolve("new/project");
        ProjectWriter.writeDirectory(TREE, root, false);
        assertThat(root.resolve("README.md")).hasContent("readme");
        assertThat(root.resolve("src/a/B.java")).hasContent("class B {}");
        assumeThat(Files.getFileStore(root).supportsFileAttributeView("posix")).isTrue();
        assertThat(Files.getPosixFilePermissions(root.resolve("bin/run.sh"))).contains(PosixFilePermission.OWNER_EXECUTE);
        assertThat(Files.getPosixFilePermissions(root.resolve("README.md")))
                .doesNotContain(PosixFilePermission.OWNER_EXECUTE);
    }

    @Test
    @DisplayName("writes into an empty directory that exists")
    void writesIntoEmptyDirectory() throws IOException {
        ProjectWriter.writeDirectory(TREE, tmp, false);
        assertThat(tmp.resolve("README.md")).exists();
    }

    @Test
    @DisplayName("refuses a directory that is not empty, and writes nothing into it")
    void refusesNonEmptyDirectory() throws IOException {
        Files.writeString(tmp.resolve("keep.txt"), "mine");
        assertThatThrownBy(() -> ProjectWriter.writeDirectory(TREE, tmp, false))
                .isInstanceOf(TargetExistsException.class)
                .hasMessageContaining("is not empty")
                .satisfies(e -> assertThat(((TargetExistsException) e).target()).isEqualTo(tmp));
        assertThat(tmp.resolve("README.md")).doesNotExist();
    }

    @Test
    @DisplayName("with force, replaces a file at a path the tree writes and leaves every other file")
    void forceWritesOver() throws IOException {
        Files.writeString(tmp.resolve("keep.txt"), "mine");
        Files.writeString(tmp.resolve("README.md"), "old");
        ProjectWriter.writeDirectory(TREE, tmp, true);
        assertThat(tmp.resolve("README.md")).hasContent("readme");
        assertThat(tmp.resolve("keep.txt")).hasContent("mine");
    }

    @Test
    @DisplayName("refuses a target that is a file, even with force")
    void refusesFileAsDirectory() throws IOException {
        Path file = Files.writeString(tmp.resolve("file"), "x");
        assertThatThrownBy(() -> ProjectWriter.writeDirectory(TREE, file, true))
                .isInstanceOf(TargetExistsException.class).hasMessageContaining("not a directory");
    }

    @Test
    @DisplayName("writes an archive whose entries sit under the root, creating its parent directory")
    void writesArchive() throws IOException {
        Path archive = tmp.resolve("out/demo.zip");
        ProjectWriter.writeArchive(TREE, "demo", archive, false);
        assertThat(entries(archive)).containsExactly("demo/README.md", "demo/bin/run.sh", "demo/src/a/B.java");
    }

    @Test
    @DisplayName("refuses an archive that exists unless forced, and leaves it untouched")
    void refusesExistingArchive() throws IOException {
        Path archive = Files.writeString(tmp.resolve("demo.zip"), "not a zip");
        assertThatThrownBy(() -> ProjectWriter.writeArchive(TREE, "demo", archive, false))
                .isInstanceOf(TargetExistsException.class).hasMessageContaining("exists");
        assertThat(archive).hasContent("not a zip");

        ProjectWriter.writeArchive(TREE, "demo", archive, true);
        assertThat(entries(archive)).hasSize(3);
    }

    @Test
    @DisplayName("a write that fails leaves neither an archive nor a staging file")
    void failedWriteLeavesNothing() throws IOException {
        Path archive = tmp.resolve("broken.zip");
        assertThatThrownBy(() -> ProjectWriter.writeArchive(TREE, "a/b", archive, false))
                .isInstanceOf(IllegalArgumentException.class);
        try (Stream<Path> left = Files.list(tmp)) {
            assertThat(left).isEmpty();
        }
    }

    private static List<String> entries(Path archive) throws IOException {
        List<String> names = new ArrayList<>();
        try (InputStream in = Files.newInputStream(archive); ZipInputStream zip = new ZipInputStream(in,
                StandardCharsets.UTF_8)) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                names.add(entry.getName());
            }
        }
        return names;
    }
}
