package eu.exeris.tooling.codegen.core.generator;

import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Registry for kernel-target code generators.
 * <p>
 * Holds an ordered list of {@link KernelArtifactGenerator} instances and dispatches
 * generation across them. Single-target: there is no per-backend keying — every
 * registered generator targets the Exeris kernel.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * GeneratorRegistry registry = new GeneratorRegistry();
 * registry.register(new KernelHandlerGenerator());
 * registry.register(new KernelServiceGenerator());
 *
 * List<GeneratedFile> files = registry.generateAll(metadata);
 * }</pre>
 *
 * @since 0.1.0
 */
public class GeneratorRegistry {

    private final List<KernelArtifactGenerator> generators = new ArrayList<>();

    public void register(KernelArtifactGenerator generator) {
        generators.add(generator);
    }

    public void registerAll(KernelArtifactGenerator... toRegister) {
        for (KernelArtifactGenerator g : toRegister) {
            register(g);
        }
    }

    /**
     * @return all generators sorted by priority (lower runs first).
     */
    public List<KernelArtifactGenerator> getGenerators() {
        return generators.stream()
                .sorted(Comparator.comparingInt(KernelArtifactGenerator::priority))
                .toList();
    }

    public Optional<KernelArtifactGenerator> getGenerator(KernelArtifactGenerator.ArtifactType artifactType) {
        return getGenerators().stream()
                .filter(g -> g.artifactType() == artifactType)
                .findFirst();
    }

    public List<GeneratedFile> generateAll(DomainMetadata metadata) {
        return getGenerators().stream()
                .filter(g -> g.supports(metadata))
                .map(g -> g.generate(metadata))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    public Optional<GeneratedFile> generate(DomainMetadata metadata,
                                            KernelArtifactGenerator.ArtifactType artifactType) {
        return getGenerator(artifactType)
                .filter(g -> g.supports(metadata))
                .map(g -> g.generate(metadata));
    }

    public int generatorCount() {
        return generators.size();
    }

    public void clear() {
        generators.clear();
    }
}
