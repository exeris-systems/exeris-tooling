package eu.exeris.e2e.codegen;

import eu.exeris.tooling.codegen.core.generator.BackendGenerator.ArtifactType;
import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.tooling.codegen.java.kernel.KernelGeneratorStrategy;
import org.junit.jupiter.api.*;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("e2e")
@Tag("codegen")
@DisplayName("Kernel Codegen E2E Tests")
class KernelCodegenE2ETest {

    private static DomainMetadata orderMetadata;
    private static DomainMetadata productMetadata;

    @BeforeAll
    static void setupMetadata() {
        orderMetadata = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders")
                .module("sales")
                .description("Sales order entity")
                .tenantScoped(true)
                .audited(true)
                .build();

        productMetadata = DomainMetadata.builder("Product", "com.shop.domain")
                .path("/products")
                .module("catalog")
                .build();
    }

    @Nested
    @DisplayName("Kernel Backend")
    class KernelBackendTests {
        private final KernelGeneratorStrategy strategy = new KernelGeneratorStrategy();

        @Test
        @DisplayName("Should generate at minimum Handler, Service, Repository, Migration artifacts")
        void shouldGenerateCoreArtifacts() {
            List<GeneratedFile> files = strategy.generate(orderMetadata);
            assertThat(files).extracting(GeneratedFile::artifactType)
                    .contains(ArtifactType.CONTROLLER, ArtifactType.SERVICE, ArtifactType.REPOSITORY, ArtifactType.CONFIGURATION);
        }

        @Test
        @DisplayName("Handler should use Kernel Http3ServerExchange")
        void handlerShouldUseKernelRuntime() {
            List<GeneratedFile> files = strategy.generate(orderMetadata);
            String handler = files.stream().filter(f -> f.artifactType() == ArtifactType.CONTROLLER)
                    .findFirst().orElseThrow().content();
            assertThat(handler).contains("Http3ServerExchange", "handleGetAll", "handleCreate");
        }

        @Test
        @DisplayName("Should use canonical naming convention")
        void shouldUseCanonicalNaming() {
            List<GeneratedFile> files = strategy.generate(productMetadata);
            assertThat(files.stream().filter(f -> f.artifactType() == ArtifactType.SERVICE).findFirst().orElseThrow().className())
                    .isEqualTo("ProductService");
            assertThat(files.stream().filter(f -> f.artifactType() == ArtifactType.REPOSITORY).findFirst().orElseThrow().className())
                    .isEqualTo("ProductRepository");
        }
    }
}
