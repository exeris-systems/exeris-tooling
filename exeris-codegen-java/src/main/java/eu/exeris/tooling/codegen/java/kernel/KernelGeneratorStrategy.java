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
 *   <li>{@link KernelHandlerGenerator} — HTTP handlers against {@code spi.http.HttpExchange} / {@code HttpStatus} / {@code spi.memory.LoanedBuffer}</li>
 *   <li>{@link KernelStreamHandlerGenerator} — SSE live-view stream handlers against {@code spi.http.{HttpStreamHandler, HttpStreamExchange, StreamEvent}} (only for {@code @ExerisDomain(realTimeApi)} entities; ADR-043 Slice 1)</li>
 *   <li>{@link KernelServiceGenerator} — POJO domain services (delegates to {@code *Repository}; no direct Kernel API surface)</li>
 *   <li>{@link KernelRepositoryGenerator} — repositories against {@code spi.persistence.{TransactionalExecutor, PersistenceStatement, QueryResult, RowCursor}}</li>
 *   <li>{@link KernelEventGenerator} — domain-event publisher against {@code spi.events.{EventEngine, EventDescriptor, EventPayload, EventTypeSpec}}</li>
 *   <li>{@link KernelEventHandlerGenerator} — domain-event subscriber against {@code spi.events.{EventBus, EventHandler, SubscriptionToken}}</li>
 *   <li>{@link KernelGraphSyncGenerator} — graph-sync projection against {@code spi.graph.{GraphEngine, GraphSession}} + {@code spi.graph.model.{GraphNodeDescriptor, GraphEdgeDescriptor}}</li>
 *   <li>{@link KernelSagaGenerator} — saga skeleton against {@code spi.flow.{FlowEngine, FlowDefinitionBuilder}} + {@code spi.flow.model.{FlowExecutionPlan, FlowContext, FlowOutcome}}</li>
 *   <li>{@link KernelFlywayGenerator} — SQL migrations</li>
 *   <li>{@link KernelOpenApiGenerator} — OpenAPI 3.1 YAML</li>
 *   <li>{@link KernelClientGenerator} — typed service-to-service HTTP client
 *       against the tier-neutral {@code core.http.client.KernelWebClient}
 *       facade (ADR-034); request/response bodies marshalled by the kernel's
 *       body-codec registries, {@code 404 → Optional.empty()} via
 *       {@code WebClientException.isNotFound()}</li>
 * </ul>
 *
 * <p>{@link KernelClientGenerator} was unparked once ADR-034 landed the
 * convenience {@code KernelWebClient} facade
 * ({@code get/post/patch/delete(path, [body,] Class<T>)}) in
 * {@code eu.exeris.kernel.core.http.client}. That facade is the entity-typed
 * surface the generator always targeted, so no tooling-side {@code HttpEntityCodec}
 * collaborator was needed — registration only. (A prior unpark attempt, PR #60,
 * was reverted because it bound to a non-existent {@code CommunityWebClient};
 * ADR-034's tier-neutral facade is the correct binding target.)
 *
 * <h2>Project-wide (invoked separately by {@code CodegenMain})</h2>
 * <p>{@link KernelApplicationGenerator} is <b>not</b> part of the
 * per-entity strategy — it emits two project-wide files
 * ({@code Application.java} + {@code RuntimeLifecycle.java}) and
 * therefore needs the full domain list, not a single
 * {@link DomainMetadata}. {@code CodegenMain} invokes it explicitly
 * after the per-entity loop via
 * {@link KernelApplicationGenerator#generateAll(java.util.List, String)}.
 *
 * <p>The canonical SPI/CORE wiring shape for the emitted artifacts is the
 * working community benchmark app in
 * {@code exeris-benchmarks/targets/exeris-community-app}.
 */
public class KernelGeneratorStrategy {

    private final GeneratorRegistry registry;

    public KernelGeneratorStrategy() {
        this.registry = new GeneratorRegistry();

        registry.register(new KernelHandlerGenerator());
        registry.register(new KernelStreamHandlerGenerator());
        registry.register(new KernelServiceGenerator());
        registry.register(new KernelRepositoryGenerator());
        registry.register(new KernelEventGenerator());
        registry.register(new KernelEventHandlerGenerator());
        registry.register(new KernelGraphSyncGenerator());
        registry.register(new KernelSagaGenerator());
        registry.register(new KernelFlywayGenerator());
        registry.register(new KernelOpenApiGenerator());
        registry.register(new KernelClientGenerator());
    }

    public List<GeneratedFile> generate(DomainMetadata metadata) {
        return registry.generateAll(metadata);
    }

    public GeneratorRegistry getRegistry() { return registry; }
}
