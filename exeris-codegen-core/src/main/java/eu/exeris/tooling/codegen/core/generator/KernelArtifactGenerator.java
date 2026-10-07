package eu.exeris.tooling.codegen.core.generator;

import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;

import java.util.List;

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
 * @since 0.1
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
     * Generate <em>zero or more</em> files for the given domain metadata.
     *
     * <p>The default delegates to {@link #generate(DomainMetadata)}: a
     * {@code null} sentinel yields an empty list, a non-{@code null} result a
     * singleton. Generators whose driver is a <em>collection</em> within the
     * metadata (e.g.\ ADR-044 Slice 2: one {@code HttpStreamHandler} per
     * {@code @Action(streaming)} action) override this to emit one file per
     * element. Both the {@link GeneratorRegistry#generateAll} dispatch and the
     * build-time pipeline iterate this method, so a multi-file generator does
     * not need a bespoke call site.
     *
     * <p>Output ordering must be deterministic — iterate the source collection
     * in its declared order (hard constraint #3).
     *
     * @param metadata the domain metadata to generate from
     * @return the emitted files; never {@code null}, possibly empty
     */
    default List<GeneratedFile> generateMultiple(DomainMetadata metadata) {
        GeneratedFile file = generate(metadata);
        return file == null ? List.of() : List.of(file);
    }

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
        /** Per-entity repository over the kernel persistence SPI. */
        REPOSITORY,
        /** Per-entity service carrying the domain's operations. */
        SERVICE,
        /** Per-entity HTTP handler that routes requests to the service. */
        CONTROLLER,
        /**
         * Entity-level SSE live-view handler ({@code eu.exeris.kernel.spi.http.HttpStreamHandler}),
         * emitted for {@code @ExerisDomain(realTimeApi)} (ADR-043).
         */
        STREAM_HANDLER,
        /**
         * Per-action SSE stream handler ({@code eu.exeris.kernel.spi.http.HttpStreamHandler}),
         * emitted for {@code @Action(streaming)} (ADR-044); distinct from {@link #STREAM_HANDLER}
         * so the per-type registry lookup is unambiguous.
         */
        ACTION_STREAM_HANDLER,
        /** Service-to-service client for HTTP-layer communication. */
        CLIENT,
        /** Domain-event publisher ({@code eu.exeris.kernel.spi.events.*}). */
        EVENT,
        /** Domain-event subscriber ({@code eu.exeris.kernel.spi.events.EventBus}). */
        EVENT_HANDLER,
        /** Graph-sync projection ({@code eu.exeris.kernel.spi.graph.*}). */
        GRAPH_SYNC,
        /** Saga skeleton ({@code eu.exeris.kernel.spi.flow.*}). */
        SAGA,
        /** Application bootstrap: {@code Application}, {@code RuntimeComponents} and {@code RuntimeLifecycle}. */
        APPLICATION,
        /**
         * Per-entity list-route types ({@code <Entity>ListQuery}, {@code <Entity>Page}): the parsed
         * page/sort/filter query and the page envelope the list route answers with.
         */
        LIST_QUERY,
        /**
         * Per-entity write-rejection exceptions ({@code <Entity>NotFoundException},
         * {@code <Entity>VersionConflictException}) raised by the repository and mapped to 404/409
         * by the handler (ADR-076).
         */
        DOMAIN_ERROR,
        /** Non-Java configuration artefact, such as a Flyway SQL migration. */
        CONFIGURATION,
        /** OpenAPI 3.1 specification (YAML). */
        OPENAPI_SPEC,
        /**
         * Generated test for a generated artefact (ADR-058); it lands in the test output root,
         * never in {@code src/main}.
         */
        TEST
    }
}

