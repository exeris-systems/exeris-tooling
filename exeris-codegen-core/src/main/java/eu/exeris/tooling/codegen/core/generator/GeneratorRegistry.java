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
 * {@snippet lang="java" :
 * GeneratorRegistry registry = new GeneratorRegistry();
 * registry.register(new KernelHandlerGenerator());
 * registry.register(new KernelServiceGenerator());
 *
 * List<GeneratedFile> files = registry.generateAll(metadata);
 * }
 *
 * @since 0.1
 */
public class GeneratorRegistry {

    private final List<KernelArtifactGenerator> generators = new ArrayList<>();

    /**
     * Creates an empty registry.
     */
    public GeneratorRegistry() {
        // generators are added through register(...)
    }

    /**
     * Adds a generator. Registration order does not decide dispatch order; {@link #getGenerators()}
     * sorts by priority.
     *
     * @param generator the generator to add
     */
    public void register(KernelArtifactGenerator generator) {
        generators.add(generator);
    }

    /**
     * Adds every given generator, as {@link #register} would one at a time.
     *
     * @param toRegister the generators to add
     */
    public void registerAll(KernelArtifactGenerator... toRegister) {
        for (KernelArtifactGenerator g : toRegister) {
            register(g);
        }
    }

    /**
     * Returns the registered generators in dispatch order: ascending
     * {@link KernelArtifactGenerator#priority()}, registration order among equal priorities.
     *
     * @return an unmodifiable, priority-sorted snapshot of the registered generators
     */
    public List<KernelArtifactGenerator> getGenerators() {
        return generators.stream()
                .sorted(Comparator.comparingInt(KernelArtifactGenerator::priority))
                .toList();
    }

    /**
     * Finds the first generator, in dispatch order, that produces the given artefact type.
     *
     * @param artifactType the artefact type to look up
     * @return the generator, or empty when none is registered for {@code artifactType}
     */
    public Optional<KernelArtifactGenerator> getGenerator(KernelArtifactGenerator.ArtifactType artifactType) {
        return getGenerators().stream()
                .filter(g -> g.artifactType() == artifactType)
                .findFirst();
    }

    /**
     * Runs every generator that {@linkplain KernelArtifactGenerator#supports supports} the
     * metadata, in dispatch order, and collects the files each one emits; a {@code null} result
     * is dropped.
     *
     * @param metadata the domain metadata to generate from
     * @return the generated files, in dispatch order
     */
    public List<GeneratedFile> generateAll(DomainMetadata metadata) {
        return getGenerators().stream()
                .filter(g -> g.supports(metadata))
                // generateMultiple(...) lets a single generator emit N files
                // (e.g. one stream handler per @Action(streaming); ADR-044
                // Slice 2). The default wraps the single generate(...) result,
                // so single-file generators are unaffected.
                .flatMap(g -> g.generateMultiple(metadata).stream())
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * Runs the generator registered for one artefact type, if it supports the metadata.
     *
     * @param metadata     the domain metadata to generate from
     * @param artifactType the artefact type to generate
     * @return the generated file, or empty when no generator is registered for the type, it does
     *         not support the metadata, or it returns {@code null}
     */
    public Optional<GeneratedFile> generate(DomainMetadata metadata,
                                            KernelArtifactGenerator.ArtifactType artifactType) {
        return getGenerator(artifactType)
                .filter(g -> g.supports(metadata))
                .map(g -> g.generate(metadata));
    }

    /**
     * Returns how many generators are registered.
     *
     * @return the number of registered generators
     */
    public int generatorCount() {
        return generators.size();
    }

    /**
     * Removes every registered generator.
     */
    public void clear() {
        generators.clear();
    }
}
