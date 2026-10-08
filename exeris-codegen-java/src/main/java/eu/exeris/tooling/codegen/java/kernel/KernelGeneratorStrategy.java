package eu.exeris.tooling.codegen.java.kernel;

import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import eu.exeris.tooling.codegen.core.generator.GeneratorRegistry;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;

import java.util.List;

/**
 * Kernel Generator Strategy — Open-Core SPI/CORE-aligned generators.
 *
 * <h2>Registered set (active)</h2>
 * <p>Active set covers artifacts aligned with Open-Core
 * {@code exeris-kernel-spi} / {@code exeris-kernel-core}:
 * <ul>
 *   <li>{@link KernelHandlerGenerator} — HTTP handlers against {@code spi.http.HttpExchange} /
 *       {@code HttpStatus} / {@code spi.memory.LoanedBuffer}</li>
 *   <li>{@link KernelStreamHandlerGenerator} — SSE live-view stream handlers against
 *       {@code spi.http.{HttpStreamHandler, HttpStreamExchange, StreamEvent}} (only for
 *       {@code @ExerisDomain(realTimeApi)} entities; ADR-043 Slice 1)</li>
 *   <li>{@link KernelActionStreamHandlerGenerator} — per-action SSE stream handlers against the same
 *       streaming SPI (one per {@code @Action(streaming=true)}; ADR-044 Slice 2)</li>
 *   <li>{@link KernelServiceGenerator} — POJO domain services (delegates to {@code *Repository}; no
 *       direct Kernel API surface)</li>
 *   <li>{@link KernelRepositoryGenerator} — repositories against
 *       {@code spi.persistence.{TransactionalExecutor, PersistenceStatement, QueryResult, RowCursor}}</li>
 *   <li>{@link KernelErrorGenerator} — the typed exceptions the repository raises and the handler
 *       maps to a status: {@code <Entity>NotFoundException} for every entity, and the version
 *       conflict, tenant mismatch and shared-scope mismatch exceptions where the entity has a
 *       version, an owner or a shared scope</li>
 *   <li>{@link KernelListQueryGenerator} — the list route's {@code <Entity>ListQuery} and
 *       {@code <Entity>Page} records (plain JDK types; no kernel API surface)</li>
 *   <li>{@link KernelEventGenerator} — domain-event publisher against
 *       {@code spi.events.{EventEngine, EventDescriptor, EventPayload, EventTypeSpec}}</li>
 *   <li>{@link KernelEventHandlerGenerator} — domain-event subscriber against
 *       {@code spi.events.{EventBus, EventHandler, SubscriptionToken}}</li>
 *   <li>{@link KernelGraphSyncGenerator} — graph-sync projection against
 *       {@code spi.graph.{GraphEngine, GraphSession}} +
 *       {@code spi.graph.model.{GraphNodeDescriptor, GraphEdgeDescriptor}}</li>
 *   <li>{@link KernelSagaGenerator} — saga skeleton against
 *       {@code spi.flow.{FlowEngine, FlowDefinitionBuilder}} +
 *       {@code spi.flow.model.{FlowExecutionPlan, FlowContext, FlowOutcome}}</li>
 *   <li>{@link KernelFlywayGenerator} — SQL migrations</li>
 *   <li>{@link KernelSharedScopeMigrationGenerator} — the additive shared-scope read widening of a
 *       {@code DataScope.UNIVERSE} entity; nothing for any other entity</li>
 *   <li>{@link KernelOpenApiGenerator} — OpenAPI 3.1 YAML</li>
 *   <li>{@link KernelClientGenerator} — typed service-to-service HTTP client
 *       against the tier-neutral {@code core.http.client.KernelWebClient}
 *       facade (ADR-034); request/response bodies marshalled by the kernel's
 *       body-codec registries, {@code 404 → Optional.empty()} via
 *       {@code WebClientException.isNotFound()}</li>
 * </ul>
 *
 * <p>{@link KernelClientGenerator} binds to the tier-neutral {@code KernelWebClient}
 * facade in {@code eu.exeris.kernel.core.http.client} (ADR-034). Its
 * {@code get/post/put/delete(path, [body,] Class<T>)} methods are the entity-typed surface
 * the generator targets, so no tooling-side {@code HttpEntityCodec} collaborator is needed.
 *
 * <h2>Project-wide (invoked separately by {@code CodegenPipeline})</h2>
 * <p>{@link KernelApplicationGenerator} is <b>not</b> part of the
 * per-entity strategy: its files are project-wide and need the full domain list, not a single
 * {@link DomainMetadata}. {@code CodegenPipeline} invokes it after the per-entity loop, through
 * {@link KernelApplicationGenerator#generateAll(java.util.List, String, boolean)} for
 * {@code Application.java}, {@code RuntimeComponents.java} and {@code RuntimeLifecycle.java}, and
 * through {@link KernelApplicationGenerator#generateForeignKeys(java.util.List)} for the foreign-key
 * migration. The pipeline also writes {@code cap-manifest.json} itself, and drives the generated-test
 * generators into a separate output root.
 *
 * <p>Which generator writes which path, for the registered set and for every writer outside it, is
 * the generator catalogue: {@code META-INF/exeris/generator-catalogue.json} and
 * {@code docs/generators.md} (ADR-097).
 *
 * <p>The canonical SPI/CORE wiring shape for the emitted artifacts is the
 * working community benchmark app in
 * {@code exeris-benchmarks/targets/exeris-community-app}.
 */
public class KernelGeneratorStrategy {

    private final GeneratorRegistry registry;

    /**
     * Creates the strategy with the full per-entity generator roster registered.
     */
    public KernelGeneratorStrategy() {
        this.registry = new GeneratorRegistry();

        registry.register(new KernelHandlerGenerator());
        registry.register(new KernelStreamHandlerGenerator());
        registry.register(new KernelActionStreamHandlerGenerator());
        registry.register(new KernelServiceGenerator());
        registry.register(new KernelRepositoryGenerator());
        registry.register(new KernelErrorGenerator());
        registry.register(new KernelListQueryGenerator());
        registry.register(new KernelEventGenerator());
        registry.register(new KernelEventHandlerGenerator());
        registry.register(new KernelGraphSyncGenerator());
        registry.register(new KernelSagaGenerator());
        registry.register(new KernelFlywayGenerator());
        registry.register(new KernelSharedScopeMigrationGenerator());
        registry.register(new KernelOpenApiGenerator());
        registry.register(new KernelClientGenerator());
    }

    /**
     * Runs every registered generator that supports the entity.
     *
     * @param metadata the entity to generate for
     * @return the generated files, in the registry's dispatch order
     */
    public List<GeneratedFile> generate(DomainMetadata metadata) {
        return registry.generateAll(metadata);
    }

    /**
     * Returns the registry holding this strategy's generators.
     *
     * @return the registry; shared, not a copy
     */
    public GeneratorRegistry getRegistry() {
        return registry;
    }
}
