package eu.exeris.tooling.codegen.maven.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DetachService — L2 promotion of generated sources")
class DetachServiceTest {

    private final DetachService service = new DetachService();

    private static Path write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        return Files.writeString(file, content);
    }

    @Test
    @DisplayName("promotes files preserving package structure, prunes the emptied tree")
    void promotesPreservingStructure(@TempDir Path tmp) throws IOException {
        Path gen = tmp.resolve("src/main/generated/java");
        Path target = tmp.resolve("src/main/java");
        write(gen.resolve("com/shop/OrderClient.java"), "class OrderClient {}");
        write(gen.resolve("com/shop/OrderHandler.java"), "class OrderHandler {}");

        DetachResult result = service.detach(gen, target, null, null);

        assertThat(result.moved())
                .containsExactly(Path.of("com/shop/OrderClient.java"),
                        Path.of("com/shop/OrderHandler.java"));
        assertThat(result.conflicts()).isEmpty();
        assertThat(result.isEmpty()).isFalse();
        assertThat(target.resolve("com/shop/OrderClient.java")).exists();
        assertThat(target.resolve("com/shop/OrderHandler.java")).content().isEqualTo("class OrderHandler {}");
        // generated tree fully pruned
        assertThat(gen).doesNotExist();
    }

    @Test
    @DisplayName("never overwrites an owned file — reports it as a conflict and leaves the generated copy")
    void reportsConflictsWithoutOverwriting(@TempDir Path tmp) throws IOException {
        Path gen = tmp.resolve("gen");
        Path target = tmp.resolve("main");
        write(gen.resolve("com/shop/Order.java"), "GENERATED");
        write(target.resolve("com/shop/Order.java"), "HAND-WRITTEN");

        DetachResult result = service.detach(gen, target, null, null);

        assertThat(result.moved()).isEmpty();
        assertThat(result.conflicts()).containsExactly(Path.of("com/shop/Order.java"));
        assertThat(result.isEmpty()).isTrue();
        // owned file untouched, generated copy left in place for reconciliation
        assertThat(target.resolve("com/shop/Order.java")).content().isEqualTo("HAND-WRITTEN");
        assertThat(gen.resolve("com/shop/Order.java")).content().isEqualTo("GENERATED");
    }

    @Test
    @DisplayName("missing generated root is a safe no-op (idempotent re-run)")
    void missingGeneratedRootIsNoOp(@TempDir Path tmp) throws IOException {
        DetachResult result = service.detach(tmp.resolve("absent"), tmp.resolve("main"), null, null);

        assertThat(result.moved()).isEmpty();
        assertThat(result.conflicts()).isEmpty();
        assertThat(result.gitignoreUpdated()).isFalse();
    }

    @Test
    @DisplayName("strips the generated entry from .gitignore (slash-insensitive)")
    void stripsGitignoreEntry(@TempDir Path tmp) throws IOException {
        Path gen = tmp.resolve("gen");
        write(gen.resolve("A.java"), "class A {}");
        Path gitignore = tmp.resolve(".gitignore");
        Files.write(gitignore, List.of("target/", "/src/main/generated/", "*.iml"));

        DetachResult result = service.detach(gen, tmp.resolve("main"), gitignore, "src/main/generated/");

        assertThat(result.gitignoreUpdated()).isTrue();
        assertThat(Files.readAllLines(gitignore)).containsExactly("target/", "*.iml");
    }

    @Test
    @DisplayName("leaves .gitignore untouched when the entry is absent")
    void gitignoreUnchangedWhenEntryAbsent(@TempDir Path tmp) throws IOException {
        Path gen = tmp.resolve("gen");
        write(gen.resolve("A.java"), "class A {}");
        Path gitignore = tmp.resolve(".gitignore");
        Files.write(gitignore, List.of("target/", "*.iml"));

        DetachResult result = service.detach(gen, tmp.resolve("main"), gitignore, "src/main/generated/");

        assertThat(result.gitignoreUpdated()).isFalse();
        assertThat(Files.readAllLines(gitignore)).containsExactly("target/", "*.iml");
    }

    @Test
    @DisplayName("a non-existent .gitignore is tolerated (no update)")
    void missingGitignoreTolerated(@TempDir Path tmp) throws IOException {
        Path gen = tmp.resolve("gen");
        write(gen.resolve("A.java"), "class A {}");

        DetachResult result = service.detach(gen, tmp.resolve("main"),
                tmp.resolve(".gitignore"), "src/main/generated/");

        assertThat(result.moved()).containsExactly(Path.of("A.java"));
        assertThat(result.gitignoreUpdated()).isFalse();
    }
}
