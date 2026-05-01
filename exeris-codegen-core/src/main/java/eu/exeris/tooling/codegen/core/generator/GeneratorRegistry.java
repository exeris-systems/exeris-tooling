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
 * Holds an ordered list of {@link BackendGenerator} instances and dispatches
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
 * @since 0.2.0
 */
public class GeneratorRegistry {

    private final List<BackendGenerator> generators = new ArrayList<>();

    public void register(BackendGenerator generator) {
        generators.add(generator);
    }

    public void registerAll(BackendGenerator... toRegister) {
        for (BackendGenerator g : toRegister) {
            register(g);
        }
    }

    /**
     * @return all generators sorted by priority (lower runs first).
     */
    public List<BackendGenerator> getGenerators() {
        return generators.stream()
                .sorted(Comparator.comparingInt(BackendGenerator::priority))
                .toList();
    }

    public Optional<BackendGenerator> getGenerator(BackendGenerator.ArtifactType artifactType) {
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
                                            BackendGenerator.ArtifactType artifactType) {
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
