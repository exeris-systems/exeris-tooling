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
 *   <li>{@link KernelServiceGenerator} — POJO domain services (no Kernel API surface)</li>
 *   <li>{@link KernelRepositoryGenerator} — plain-JDBC repositories (no Kernel API surface)</li>
 *   <li>{@link KernelEventGenerator} — domain-event publisher against {@code spi.events.{EventEngine, EventDescriptor, EventPayload, EventTypeSpec}}</li>
 *   <li>{@link KernelEventHandlerGenerator} — domain-event subscriber against {@code spi.events.{EventBus, EventHandler, SubscriptionToken}}</li>
 *   <li>{@link KernelFlywayGenerator} — SQL migrations</li>
 *   <li>{@link KernelOpenApiGenerator} — OpenAPI 3.1 YAML</li>
 * </ul>
 *
 * <h2>Unregistered (pending migration to Open-Core SPI/CORE)</h2>
 * <p>The following generators are kept in tree but <b>not</b> registered.
 * Each one was originally authored against an emission surface that does not
 * match the names/paths exposed by Open-Core SPI/CORE today. The required
 * abstractions exist in Open-Core — the generators just need rewriting
 * against the real SPI/CORE types. Per-generator migration target:
 * <ul>
 *   <li>{@link KernelClientGenerator} → SPI HTTP/transport client (TBD against the actually exposed client SPI; align with the working benchmark app)</li>
 *   <li>{@link KernelGraphSyncGenerator} → {@code spi.graph.{GraphEngine, GraphSession}} + {@code spi.graph.model.{GraphEdgeDescriptor, GraphTraversal}}</li>
 *   <li>{@link KernelSagaGenerator} → {@code spi.flow.FlowEngine} + {@code spi.flow.model.{FlowContext, FlowDefinition, FlowExecutionPlan, FlowOutcome, FlowSnapshot, FlowSnapshotStore, FlowState, FlowStepAction}}</li>
 *   <li>{@link KernelApplicationGenerator} → {@code core.bootstrap.KernelBootstrap} + {@code spi.bootstrap.BootstrapSelector} + {@code spi.context.KernelProviders} + {@code core.http.routing.HttpRouter}</li>
 * </ul>
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
        registry.register(new KernelFlywayGenerator());
        registry.register(new KernelOpenApiGenerator());
    }

    public List<GeneratedFile> generate(DomainMetadata metadata) {
        return registry.generateAll(metadata);
    }

    public GeneratorRegistry getRegistry() { return registry; }
}
