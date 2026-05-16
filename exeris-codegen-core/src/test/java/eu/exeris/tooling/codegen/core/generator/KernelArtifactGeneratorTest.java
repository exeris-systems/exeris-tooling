package eu.exeris.tooling.codegen.core.generator;

import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator.ArtifactType;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the {@link KernelArtifactGenerator} interface's default-method contract
 * ({@link KernelArtifactGenerator#supports} and {@link KernelArtifactGenerator#priority})
 * and the {@link ArtifactType} enum constants.
 *
 * <p>Concrete implementations live in {@code exeris-codegen-java}; this module
 * only owns the SPI surface, so the test verifies behaviour against a minimal
 * inline stub.
 */
@DisplayName("KernelArtifactGenerator")
class KernelArtifactGeneratorTest {

    /** Minimal stub — overrides only the required methods. */
    private static final class StubGenerator implements KernelArtifactGenerator {
        @Override
        public GeneratedFile generate(DomainMetadata metadata) {
            return new GeneratedFile("p", "C", "src", ArtifactType.SERVICE);
        }

        @Override
        public ArtifactType artifactType() {
            return ArtifactType.SERVICE;
        }
    }

    /** Stub that overrides both default methods. */
    private static final class OverrideGenerator implements KernelArtifactGenerator {
        @Override
        public GeneratedFile generate(DomainMetadata metadata) {
            return null;
        }

        @Override
        public ArtifactType artifactType() {
            return ArtifactType.SAGA;
        }

        @Override
        public boolean supports(DomainMetadata metadata) {
            return metadata != null && "Order".equals(metadata.entityName());
        }

        @Override
        public int priority() {
            return 50;
        }
    }

    @Test
    @DisplayName("Default supports(metadata) returns true regardless of input")
    void defaultSupportsReturnsTrue() {
        KernelArtifactGenerator gen = new StubGenerator();
        assertThat(gen.supports(DomainMetadata.builder("Order", "p").build())).isTrue();
        assertThat(gen.supports(null)).isTrue();
    }

    @Test
    @DisplayName("Default priority() returns 100")
    void defaultPriorityReturns100() {
        KernelArtifactGenerator gen = new StubGenerator();
        assertThat(gen.priority()).isEqualTo(100);
    }

    @Test
    @DisplayName("Implementations may override supports() and priority()")
    void overridesAreHonoured() {
        OverrideGenerator gen = new OverrideGenerator();
        assertThat(gen.priority()).isEqualTo(50);
        assertThat(gen.supports(DomainMetadata.builder("Order", "p").build())).isTrue();
        assertThat(gen.supports(DomainMetadata.builder("Other", "p").build())).isFalse();
        assertThat(gen.supports(null)).isFalse();
    }

    @Test
    @DisplayName("ArtifactType covers the full SPI-aligned artifact set")
    void artifactTypeEnumValues() {
        // Lock the public-API enum surface — any addition/removal/rename is
        // a contract break that must be acknowledged in this test.
        assertThat(ArtifactType.values()).containsExactly(
                ArtifactType.REPOSITORY,
                ArtifactType.SERVICE,
                ArtifactType.CONTROLLER,
                ArtifactType.CLIENT,
                ArtifactType.EVENT,
                ArtifactType.EVENT_HANDLER,
                ArtifactType.GRAPH_SYNC,
                ArtifactType.SAGA,
                ArtifactType.APPLICATION,
                ArtifactType.CONFIGURATION,
                ArtifactType.OPENAPI_SPEC);
    }

    @Test
    @DisplayName("ArtifactType.valueOf round-trips all values")
    void artifactTypeRoundTripsAllValues() {
        for (ArtifactType t : ArtifactType.values()) {
            assertThat(ArtifactType.valueOf(t.name())).isSameAs(t);
        }
    }
}
