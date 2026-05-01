package eu.exeris.tooling.codegen.java.kernel;

import eu.exeris.tooling.codegen.core.PluggableBackend;
import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import eu.exeris.tooling.codegen.core.generator.GeneratorRegistry;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;

import java.util.List;

/**
 * Kernel Generator Strategy - orchestrates all Kernel-specific generators.
 */
public class KernelGeneratorStrategy {

    private final GeneratorRegistry registry;

    public KernelGeneratorStrategy() {
        this.registry = new GeneratorRegistry();

        // Core CRUD generators
        registry.register(new KernelHandlerGenerator());
        registry.register(new KernelServiceGenerator());
        registry.register(new KernelRepositoryGenerator());
        registry.register(new KernelFlywayGenerator());
        registry.register(new KernelClientGenerator());     // HTTP/3 client generator
        registry.register(new KernelOpenApiGenerator());    // OpenAPI 3.1 spec generator

        // Event-driven generators
        registry.register(new KernelEventGenerator());      // Domain events + publisher
        registry.register(new KernelEventHandlerGenerator()); // Event handlers + saga triggers

        // Graph integration generators
        registry.register(new KernelGraphSyncGenerator());  // Graph sync service

        // Saga orchestration generators
        registry.register(new KernelSagaGenerator());       // Saga definition + executor
    }

    public List<GeneratedFile> generate(DomainMetadata metadata) {
        return registry.generateAll(metadata, PluggableBackend.KERNEL);
    }

    public GeneratorRegistry getRegistry() { return registry; }
}

