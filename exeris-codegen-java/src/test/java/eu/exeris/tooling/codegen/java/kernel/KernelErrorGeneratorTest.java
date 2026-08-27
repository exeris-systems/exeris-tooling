package eu.exeris.tooling.codegen.java.kernel;

import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator.ArtifactType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Per-generator test for {@link KernelErrorGenerator} (D7 / ADR-076).
 *
 * <p>What is worth pinning here is not the class body — it is four lines — but the two
 * conditions around it: which types exist for which entity, and where they land. Both are
 * read by three other emitters (repository, handler, handler test), so a change to either
 * silently breaks a compile the e2e gate would catch late.
 */
@DisplayName("KernelErrorGenerator")
class KernelErrorGeneratorTest {

    private final KernelErrorGenerator generator = new KernelErrorGenerator();

    private static DomainMetadata order(boolean versioned) {
        return DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders")
                .versioned(versioned)
                .build();
    }

    @Test
    @DisplayName("an unversioned entity gets one type: the missing row is its only write rejection")
    void unversionedEntityGetsOnlyTheNotFoundType() {
        List<GeneratedFile> files = generator.generateMultiple(order(false));

        assertThat(files).hasSize(1);
        GeneratedFile notFound = files.get(0);
        assertThat(notFound.className()).isEqualTo("OrderNotFoundException");
        assertThat(notFound.artifactType()).isEqualTo(ArtifactType.DOMAIN_ERROR);
        assertThat(notFound.content())
                .contains("public class OrderNotFoundException extends RuntimeException")
                .contains("public OrderNotFoundException(UUID id)")
                .contains("super(\"Order not found: \" + id)")
                // The id is kept, not only formatted: a consumer's own catch acts on the value
                // rather than parsing the message, which is the whole point of the type.
                .contains("this.id = id")
                .contains("public UUID id()")
                .contains("private static final long serialVersionUID = 1L");
    }

    @Test
    @DisplayName("a versioned entity gets a second type, because its update can fail two ways at once")
    void versionedEntityAlsoGetsTheConflictType() {
        List<GeneratedFile> files = generator.generateMultiple(order(true));

        assertThat(files).extracting(GeneratedFile::className)
                .containsExactly("OrderNotFoundException", "OrderVersionConflictException");
        assertThat(files.get(1).content())
                .contains("public class OrderVersionConflictException extends RuntimeException")
                .contains("super(\"Order not found or stale version: \" + id)")
                // The javadoc carries the reason the two conditions share one type, because that
                // is the part a reader of the generated app will question.
                .contains("409 Conflict");
    }

    @Test
    @DisplayName("the types land beside the repository that throws them, not in the consumer's "
            + "domain package")
    void typesLandInTheRepositoryPackage() {
        // The domain package holds the consumer's own @ExerisDomain entity, and
        // `OrderNotFoundException` is a name they may well have written there already. The
        // repository package is generated-owned, so nothing of theirs can collide with it.
        assertThat(KernelErrorGenerator.errorPackage(order(false)))
                .isEqualTo("com.example.repository");
        assertThat(generator.generateMultiple(order(true)))
                .allSatisfy(f -> assertThat(f.packageName()).isEqualTo("com.example.repository"));
    }

    @Test
    @DisplayName("versionConflictType is null for an unversioned entity, which is what the three "
            + "readers branch on")
    void conflictTypeIsAbsentWithoutVersioning() {
        assertThat(KernelErrorGenerator.versionConflictType(order(false))).isNull();
        assertThat(KernelErrorGenerator.versionConflictType(order(true))).isNotNull();
        assertThat(KernelErrorGenerator.notFoundType(order(false)).simpleName())
                .isEqualTo("OrderNotFoundException");
    }

    @Test
    @DisplayName("emission is deterministic")
    void emissionIsDeterministic() {
        assertThat(generator.generateMultiple(order(true)))
                .extracting(GeneratedFile::content)
                .isEqualTo(generator.generateMultiple(order(true)).stream()
                        .map(GeneratedFile::content)
                        .toList());
    }

    @Test
    @DisplayName("the single-file entry point returns the type every entity has")
    void singleFileEntryPointReturnsTheNotFoundType() {
        assertThat(generator.generate(order(true)).className())
                .isEqualTo("OrderNotFoundException");
        assertThat(generator.artifactType()).isEqualTo(ArtifactType.DOMAIN_ERROR);
    }
}
