package eu.exeris.tooling.codegen.core.generator;

import eu.exeris.tooling.codegen.core.PluggableBackend;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;

/**
 * Base interface for all backend-specific code generators.
 * <p>
 * Each generator produces code for a specific backend (KERNEL, SPRING, etc.)
 * and a specific artifact type (Controller, Service, Repository, etc.).
 *
 * <h2>Implementation Guidelines</h2>
 * <ul>
 *   <li>Generators must be stateless</li>
 *   <li>Generated code must be readable (Glass Box principle)</li>
 *   <li>No cross-backend dependencies (Sovereignty First)</li>
 * </ul>
 *
 * @author Exeris Team
 * @since 0.2.0
 */
public interface BackendGenerator {

    /**
     * Generate code for the given domain metadata.
     *
     * @param metadata the domain metadata to generate from
     * @return generated file containing code
     */
    GeneratedFile generate(DomainMetadata metadata);

    /**
     * The backend this generator targets.
     *
     * @return the target backend
     */
    PluggableBackend backend();

    /**
     * The type of artifact this generator produces.
     *
     * @return artifact type (e.g., CONTROLLER, SERVICE, REPOSITORY)
     */
    ArtifactType artifactType();

    /**
     * Check if this generator supports the given metadata.
     * <p>
     * For example, a SagaGenerator only supports metadata with saga configuration.
     *
     * @param metadata the domain metadata
     * @return true if this generator can process the metadata
     */
    default boolean supports(DomainMetadata metadata) {
        return true;
    }

    /**
     * Priority for ordering generators. Lower values run first.
     *
     * @return priority (default 100)
     */
    default int priority() {
        return 100;
    }

    /**
     * Types of artifacts that can be generated.
     */
    enum ArtifactType {
        // Core artifacts
        ENTITY,
        REPOSITORY,
        SERVICE,
        SERVICE_INTERFACE,
        CONTROLLER,
        DTO,
        MAPPER,
        CLIENT,  // HTTP/3 client for service-to-service communication

        // Event-driven artifacts
        EVENT,           // Domain event classes and publisher
        EVENT_HANDLER,
        PROJECTION,
        QUERY_HANDLER,
        COMMAND_HANDLER,

        // Graph artifacts
        GRAPH_SYNC,      // Graph synchronization service

        // Saga orchestration
        SAGA,            // Saga definition and executor
        SAGA_ORCHESTRATOR,

        // Infrastructure
        CONFIGURATION,
        SECURITY_CONFIG,
        ROUTING,
        APPLICATION_BOOTSTRAP,
        OPENAPI_SPEC,  // OpenAPI 3.1 specification (YAML)

        // Polyfills
        SAGA_POLYFILL,
        EVENT_STORE_POLYFILL,
        TRANSACTION_MANAGER_POLYFILL
    }
}

