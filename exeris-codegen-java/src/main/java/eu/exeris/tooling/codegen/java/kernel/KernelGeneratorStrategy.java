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
 *   <li>{@link KernelServiceGenerator} — POJO domain services (delegates to {@code *Repository}; no direct Kernel API surface)</li>
 *   <li>{@link KernelRepositoryGenerator} — repositories against {@code spi.persistence.{TransactionalExecutor, PersistenceStatement, QueryResult, RowCursor}}</li>
 *   <li>{@link KernelEventGenerator} — domain-event publisher against {@code spi.events.{EventEngine, EventDescriptor, EventPayload, EventTypeSpec}}</li>
 *   <li>{@link KernelEventHandlerGenerator} — domain-event subscriber against {@code spi.events.{EventBus, EventHandler, SubscriptionToken}}</li>
 *   <li>{@link KernelGraphSyncGenerator} — graph-sync projection against {@code spi.graph.{GraphEngine, GraphSession}} + {@code spi.graph.model.{GraphNodeDescriptor, GraphEdgeDescriptor}}</li>
 *   <li>{@link KernelSagaGenerator} — saga skeleton against {@code spi.flow.{FlowEngine, FlowDefinitionBuilder}} + {@code spi.flow.model.{FlowExecutionPlan, FlowContext, FlowOutcome}}</li>
 *   <li>{@link KernelFlywayGenerator} — SQL migrations</li>
 *   <li>{@link KernelOpenApiGenerator} — OpenAPI 3.1 YAML</li>
 * </ul>
 *
 * <h2>Unregistered (parked — requires upstream OR in-generator design work)</h2>
 * <p>The following generators are kept in tree but <b>not</b> registered.
 * Each one was originally authored against an emission surface that does not
 * match what Open-Core SPI/CORE exposes today.
 * <ul>
 *   <li>{@link KernelClientGenerator} — the kernel 0.8.0-SNAPSHOT SPI for
 *       service-to-service HTTP is
 *       {@code eu.exeris.kernel.spi.http.HttpClientEngine.send(HttpRequest)
 *       → HttpResponse}. Both {@code HttpRequest} and {@code HttpResponse}
 *       are records with a raw {@code LoanedBuffer} body — i.e. the SPI is
 *       send-bytes/receive-bytes, not entity-typed. The current generator
 *       template targets a higher-level {@code .get(path, T.class) /
 *       .post(path, body, T.class)} surface that DOES NOT EXIST in the
 *       0.8.0-SNAPSHOT SPI; {@code CommunityHttpClientEngine} is only the
 *       community-tier IMPLEMENTATION of {@code HttpClientEngine}, not a
 *       new abstraction to bind to. Unparking is blocked on either:
 *       <ol>
 *         <li>a higher-level convenience SPI shipping upstream (an
 *             interface with {@code get/post/patch/delete} that takes a
 *             {@code Class<T>} for decoding), OR</li>
 *         <li>a designed-in-this-repo {@code HttpEntityCodec<T>}
 *             collaborator that the generated client takes as a
 *             constructor arg + uses to encode body → LoanedBuffer
 *             and decode response.body() → T, plus a URL-templating
 *             helper for query params.</li>
 *       </ol>
 *       Either path is a real design exercise, not a rename. A prior
 *       attempt at unparking (PR #60) was reverted because it assumed
 *       a {@code CommunityWebClient} class existed and was the binding
 *       target — neither is true. See PR #60 thread for the full
 *       diagnostic trail.</li>
 * </ul>
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
 * <p>Re-register each one only after its emitted code resolves against
 * {@code exeris-kernel-spi} / {@code exeris-kernel-core}. See the working
 * community benchmark app in {@code exeris-benchmarks/targets/exeris-community-app}
 * for the canonical SPI/CORE wiring shape.
 */
public class KernelGeneratorStrategy {

    private final GeneratorRegistry registry;

    public KernelGeneratorStrategy() {
        this.registry = new GeneratorRegistry();

        registry.register(new KernelHandlerGenerator());
        registry.register(new KernelServiceGenerator());
        registry.register(new KernelRepositoryGenerator());
        registry.register(new KernelEventGenerator());
        registry.register(new KernelEventHandlerGenerator());
        registry.register(new KernelGraphSyncGenerator());
        registry.register(new KernelSagaGenerator());
        registry.register(new KernelFlywayGenerator());
        registry.register(new KernelOpenApiGenerator());
    }

    public List<GeneratedFile> generate(DomainMetadata metadata) {
        return registry.generateAll(metadata);
    }

    public GeneratorRegistry getRegistry() { return registry; }
}
