package eu.exeris.tooling.codegen.core.generator;

import eu.exeris.tooling.codegen.core.PluggableBackend;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Registry for all backend generators.
 * <p>
 * Manages registration and lookup of generators by backend and artifact type.
 * Supports generating all artifacts for a given domain and backend.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * GeneratorRegistry registry = new GeneratorRegistry();
 * registry.register(new SpringControllerGenerator());
 * registry.register(new SpringServiceGenerator());
 *
 * List<GeneratedFile> files = registry.generateAll(metadata, PluggableBackend.SPRING);
 * }</pre>
 *
 * @author Exeris Team
 * @since 0.2.0
 */
public class GeneratorRegistry {

    private final Map<PluggableBackend, List<BackendGenerator>> generators = new ConcurrentHashMap<>();

    /**
     * Register a generator.
     *
     * @param generator the generator to register
     */
    public void register(BackendGenerator generator) {
        generators.computeIfAbsent(generator.backend(), k -> new ArrayList<>())
                  .add(generator);
    }

    /**
     * Register multiple generators.
     *
     * @param generators the generators to register
     */
    public void registerAll(BackendGenerator... generators) {
        for (BackendGenerator generator : generators) {
            register(generator);
        }
    }

    /**
     * Get all generators for a backend.
     *
     * @param backend the target backend
     * @return list of generators, sorted by priority
     */
    public List<BackendGenerator> getGenerators(PluggableBackend backend) {
        return generators.getOrDefault(backend, List.of()).stream()
                .sorted(Comparator.comparingInt(BackendGenerator::priority))
                .toList();
    }

    /**
     * Get a specific generator by backend and artifact type.
     *
     * @param backend      the target backend
     * @param artifactType the artifact type
     * @return optional generator
     */
    public Optional<BackendGenerator> getGenerator(PluggableBackend backend,
                                                    BackendGenerator.ArtifactType artifactType) {
        return getGenerators(backend).stream()
                .filter(g -> g.artifactType() == artifactType)
                .findFirst();
    }

    /**
     * Generate all artifacts for a domain using the specified backend.
     *
     * @param metadata the domain metadata
     * @param backend  the target backend
     * @return list of generated files
     */
    public List<GeneratedFile> generateAll(DomainMetadata metadata, PluggableBackend backend) {
        return getGenerators(backend).stream()
                .filter(g -> g.supports(metadata))
                .map(g -> g.generate(metadata))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * Generate a specific artifact type for a domain.
     *
     * @param metadata     the domain metadata
     * @param backend      the target backend
     * @param artifactType the artifact type to generate
     * @return optional generated file
     */
    public Optional<GeneratedFile> generate(DomainMetadata metadata,
                                            PluggableBackend backend,
                                            BackendGenerator.ArtifactType artifactType) {
        return getGenerator(backend, artifactType)
                .filter(g -> g.supports(metadata))
                .map(g -> g.generate(metadata));
    }

    /**
     * Get all registered backends.
     *
     * @return set of backends with registered generators
     */
    public Set<PluggableBackend> registeredBackends() {
        return Collections.unmodifiableSet(generators.keySet());
    }

    /**
     * Get count of registered generators for a backend.
     *
     * @param backend the backend
     * @return number of generators
     */
    public int generatorCount(PluggableBackend backend) {
        return generators.getOrDefault(backend, List.of()).size();
    }

    /**
     * Clear all registered generators.
     */
    public void clear() {
        generators.clear();
    }

    /**
     * Create a registry with default generators for all backends.
     *
     * @return configured registry
     */
    public static GeneratorRegistry createDefault() {
        GeneratorRegistry registry = new GeneratorRegistry();
        // Generators will be registered by each backend module
        // via ServiceLoader or explicit registration
        return registry;
    }
}

