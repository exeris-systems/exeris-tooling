package eu.exeris.tooling.codegen.core.generator;

import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator.ArtifactType;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("GeneratorRegistry")
class GeneratorRegistryTest {

    /** Configurable stub generator used to drive registry behaviour from tests. */
    private static final class StubGenerator implements KernelArtifactGenerator {
        private final ArtifactType type;
        private final int priority;
        private final boolean supports;
        private final boolean returnsNull;

        StubGenerator(ArtifactType type, int priority, boolean supports, boolean returnsNull) {
            this.type = type;
            this.priority = priority;
            this.supports = supports;
            this.returnsNull = returnsNull;
        }

        static StubGenerator of(ArtifactType type) { return new StubGenerator(type, 100, true, false); }
        static StubGenerator withPriority(ArtifactType type, int p) { return new StubGenerator(type, p, true, false); }
        static StubGenerator unsupported(ArtifactType type) { return new StubGenerator(type, 100, false, false); }
        static StubGenerator returningNull(ArtifactType type) { return new StubGenerator(type, 100, true, true); }

        @Override
        public GeneratedFile generate(DomainMetadata metadata) {
            return returnsNull ? null
                    : new GeneratedFile("p", type.name(), "src", type);
        }

        @Override public ArtifactType artifactType() { return type; }
        @Override public boolean supports(DomainMetadata metadata) { return supports; }
        @Override public int priority() { return priority; }
    }

    private GeneratorRegistry registry;
    private DomainMetadata metadata;

    @BeforeEach
    void setup() {
        registry = new GeneratorRegistry();
        metadata = DomainMetadata.builder("Order", "com.example.domain").build();
    }

    @Test
    @DisplayName("Empty registry: count is zero, generators list empty, generateAll empty")
    void emptyRegistry() {
        assertThat(registry.generatorCount()).isZero();
        assertThat(registry.getGenerators()).isEmpty();
        assertThat(registry.generateAll(metadata)).isEmpty();
    }

    @Test
    @DisplayName("register adds a generator and increments count")
    void registerSingle() {
        registry.register(StubGenerator.of(ArtifactType.SERVICE));

        assertThat(registry.generatorCount()).isEqualTo(1);
        assertThat(registry.getGenerators()).hasSize(1);
    }

    @Test
    @DisplayName("registerAll accepts a varargs list and adds them all")
    void registerAllVarargs() {
        registry.registerAll(
                StubGenerator.of(ArtifactType.SERVICE),
                StubGenerator.of(ArtifactType.REPOSITORY),
                StubGenerator.of(ArtifactType.CONTROLLER));

        assertThat(registry.generatorCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("getGenerators returns them sorted by priority — lower runs first")
    void getGeneratorsSortedByPriority() {
        registry.register(StubGenerator.withPriority(ArtifactType.SERVICE, 200));
        registry.register(StubGenerator.withPriority(ArtifactType.REPOSITORY, 50));
        registry.register(StubGenerator.withPriority(ArtifactType.CONTROLLER, 100));

        assertThat(registry.getGenerators())
                .extracting(KernelArtifactGenerator::artifactType)
                .containsExactly(
                        ArtifactType.REPOSITORY, // priority 50
                        ArtifactType.CONTROLLER, // priority 100
                        ArtifactType.SERVICE);   // priority 200
    }

    @Test
    @DisplayName("getGenerator(artifactType) returns the matching generator wrapped in Optional")
    void getGeneratorByType() {
        StubGenerator svc = StubGenerator.of(ArtifactType.SERVICE);
        registry.register(svc);
        registry.register(StubGenerator.of(ArtifactType.REPOSITORY));

        assertThat(registry.getGenerator(ArtifactType.SERVICE)).hasValue(svc);
    }

    @Test
    @DisplayName("getGenerator(artifactType) returns empty when no generator matches")
    void getGeneratorByTypeAbsent() {
        registry.register(StubGenerator.of(ArtifactType.SERVICE));

        assertThat(registry.getGenerator(ArtifactType.SAGA)).isEmpty();
    }

    @Test
    @DisplayName("generateAll runs every supports() generator, dropping null returns")
    void generateAllDispatchesAndFiltersNulls() {
        registry.registerAll(
                StubGenerator.of(ArtifactType.SERVICE),
                StubGenerator.returningNull(ArtifactType.REPOSITORY),
                StubGenerator.unsupported(ArtifactType.SAGA),
                StubGenerator.of(ArtifactType.CONTROLLER));

        List<GeneratedFile> out = registry.generateAll(metadata);

        // null + unsupported both filtered → only SERVICE + CONTROLLER survive.
        assertThat(out)
                .extracting(GeneratedFile::artifactType)
                .containsExactlyInAnyOrder(ArtifactType.SERVICE, ArtifactType.CONTROLLER);
    }

    @Test
    @DisplayName("generate(metadata, artifactType) returns a present file when generator matches")
    void generateSingleArtifactType() {
        registry.register(StubGenerator.of(ArtifactType.SERVICE));

        assertThat(registry.generate(metadata, ArtifactType.SERVICE))
                .isPresent()
                .map(GeneratedFile::artifactType)
                .hasValue(ArtifactType.SERVICE);
    }

    @Test
    @DisplayName("generate(metadata, artifactType) returns empty when the generator declines")
    void generateSingleUnsupported() {
        registry.register(StubGenerator.unsupported(ArtifactType.SERVICE));

        assertThat(registry.generate(metadata, ArtifactType.SERVICE)).isEmpty();
    }

    @Test
    @DisplayName("generate(metadata, artifactType) returns empty when no generator is registered for the type")
    void generateSingleAbsent() {
        registry.register(StubGenerator.of(ArtifactType.SERVICE));

        assertThat(registry.generate(metadata, ArtifactType.SAGA)).isEmpty();
    }

    @Test
    @DisplayName("generate(metadata, artifactType) returns empty when the matching supporting generator emits null")
    void generateSingleNullReturn() {
        // Generator is registered for SERVICE and supports() the metadata,
        // but generate() returns null (the sentinel for "does not apply").
        // The Optional.map chain in GeneratorRegistry#generate maps that
        // null to an empty Optional — verifying the contract on the
        // single-dispatch path mirrors the null filtering on generateAll.
        registry.register(StubGenerator.returningNull(ArtifactType.SERVICE));

        assertThat(registry.generate(metadata, ArtifactType.SERVICE)).isEmpty();
    }

    @Test
    @DisplayName("clear() drops all registered generators")
    void clearResetsRegistry() {
        registry.registerAll(
                StubGenerator.of(ArtifactType.SERVICE),
                StubGenerator.of(ArtifactType.REPOSITORY));

        registry.clear();

        assertThat(registry.generatorCount()).isZero();
        assertThat(registry.getGenerators()).isEmpty();
    }
}
