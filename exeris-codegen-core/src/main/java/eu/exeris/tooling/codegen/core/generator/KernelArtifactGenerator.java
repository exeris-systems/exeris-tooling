package eu.exeris.tooling.codegen.core.generator;

import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;

/**
 * Base interface for all kernel-target code generators.
 * <p>
 * Each generator produces a specific artifact (handler, service, repository,
 * saga, OpenAPI spec, …) targeting the Exeris kernel directly. Tier separation
 * (community vs. enterprise) is a runtime dependency swap, not a codegen
 * concern.
 *
 * <h2>Implementation Guidelines</h2>
 * <ul>
 *   <li>Generators must be stateless</li>
 *   <li>Generated code must be readable (Glass Box principle)</li>
 * </ul>
 *
 * @since 0.1.0
 */
public interface KernelArtifactGenerator {

    /**
     * Generate code for the given domain metadata.
     *
     * <p>Implementations may return {@code null} as a sentinel to signal
     * that the generator does not apply to the given metadata (e.g.\
     * {@code KernelEventGenerator} returns {@code null} when the domain
     * declares no events). {@link GeneratorRegistry#generateAll} filters
     * these out; the single-generator {@link GeneratorRegistry#generate}
     * helper returns an empty {@link java.util.Optional} for the same.
     *
     * @param metadata the domain metadata to generate from
     * @return generated file containing code, or {@code null} if this
     *         generator does not apply to {@code metadata}
     */
    GeneratedFile generate(DomainMetadata metadata);

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
        REPOSITORY,
        SERVICE,
        CONTROLLER,
        STREAM_HANDLER,  // SSE server-push handler (eu.exeris.kernel.spi.http.HttpStreamHandler), entity-level @ExerisDomain(realTimeApi)
        CLIENT,          // service-to-service client for HTTP-layer communication
        EVENT,           // domain-event publisher (eu.exeris.kernel.spi.events.*)
        EVENT_HANDLER,   // domain-event subscriber (eu.exeris.kernel.spi.events.EventBus)
        GRAPH_SYNC,      // graph-sync projection (eu.exeris.kernel.spi.graph.*)
        SAGA,            // saga skeleton (eu.exeris.kernel.spi.flow.*)
        APPLICATION,     // application bootstrap (Application + RuntimeLifecycle)
        CONFIGURATION,
        OPENAPI_SPEC     // OpenAPI 3.1 specification (YAML)
    }
}

