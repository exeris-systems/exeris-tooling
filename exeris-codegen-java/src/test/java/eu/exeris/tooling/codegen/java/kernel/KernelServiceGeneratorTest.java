package eu.exeris.tooling.codegen.java.kernel;

import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator.ArtifactType;
import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Per-generator test for {@link KernelServiceGenerator}. The emitted
 * service is a pure CRUD-delegation POJO over {@code *Repository}
 * (no direct Kernel API surface).
 */
@DisplayName("KernelServiceGenerator")
class KernelServiceGeneratorTest {

    private KernelGeneratorStrategy strategy;

    @BeforeEach
    void setup() {
        strategy = new KernelGeneratorStrategy();
    }

    @Test
    @DisplayName("Should generate Service for domain entity")
    void shouldGenerateService() {
        DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain")
                .build();

        List<GeneratedFile> files = strategy.generate(metadata);

        GeneratedFile service = files.stream()
                .filter(f -> f.artifactType() == ArtifactType.SERVICE)
                .findFirst()
                .orElseThrow();

        assertThat(service.className()).isEqualTo("OrderService");
        assertThat(service.packageName()).isEqualTo("com.example.service");
        assertThat(service.content())
                .contains("public class OrderService")
                .contains("OrderRepository repository")
                .contains("findById")
                .contains("save")
                .contains("delete");
    }
}
