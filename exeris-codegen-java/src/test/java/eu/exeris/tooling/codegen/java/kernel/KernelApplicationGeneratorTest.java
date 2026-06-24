package eu.exeris.tooling.codegen.java.kernel;

import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import eu.exeris.sdk.sourcemodel.ast.ActionMetadata;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Per-generator test for {@link KernelApplicationGenerator}.
 *
 * <p>Unlike the per-entity generators, {@code KernelApplicationGenerator}
 * is project-wide: it emits {@code Application.java} +
 * {@code RuntimeLifecycle.java} once per project, taking the full domain
 * list as input. It is invoked directly by {@code CodegenMain}, not via
 * {@link KernelGeneratorStrategy}.
 */
@DisplayName("KernelApplicationGenerator")
class KernelApplicationGeneratorTest {

    @Test
    @DisplayName("generate(metadata) returns null — Application is project-wide, not per-entity")
    void singleEntityGenerateReturnsNull() {
        KernelApplicationGenerator gen = new KernelApplicationGenerator();
        DomainMetadata metadata = DomainMetadata.builder("Order", "com.example.domain").build();
        assertThat(gen.generate(metadata)).isNull();
    }

    @Test
    @DisplayName("generateAll with an empty domain list still emits both files (no entity wiring, no routes)")
    void shouldEmitBothFilesForEmptyDomainList() {
        KernelApplicationGenerator gen = new KernelApplicationGenerator();
        List<GeneratedFile> files = gen.generateAll(List.of(), "com.example.foundation");
        assertThat(files).hasSize(2);

        String lifecycle = files.stream()
                .filter(f -> "RuntimeLifecycle".equals(f.className()))
                .findFirst().orElseThrow().content();
        // No per-entity wiring (no Repository/Service/Handler lines).
        assertThat(lifecycle)
                .doesNotContain("Repository(transactionalExecutor)")
                .doesNotContain("Service(")
                .doesNotContain("Handler(")
                .doesNotContain("routerBuilder.route")
                .contains("HttpRouter.Builder routerBuilder = HttpRouter.builder()")
                .contains("HttpRouter router = routerBuilder.build()")
                .contains("Application bootstrap complete: {} entities wired");
    }

    @Test
    @DisplayName("generateAll rejects domain packages that do not end with '.domain'")
    void shouldRejectNonDomainPackageSuffix() {
        KernelApplicationGenerator gen = new KernelApplicationGenerator();
        DomainMetadata bad = DomainMetadata.builder("Order", "com.example.order").build();
        List<DomainMetadata> domains = List.of(bad);

        assertThatThrownBy(() -> gen.generateAll(domains, "com.example.foundation"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("com.example.order")
                .hasMessageContaining(".domain");
    }

    @Test
    @DisplayName("generateAll emits Application + RuntimeLifecycle against Open-Core SPI")
    void shouldGenerateApplicationAndLifecycle() {
        KernelApplicationGenerator gen = new KernelApplicationGenerator();
        DomainMetadata order = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders").build();
        DomainMetadata product = DomainMetadata.builder("Product", "com.example.domain")
                .path("/products").build();

        List<GeneratedFile> files = gen.generateAll(List.of(order, product),
                "com.example.foundation");

        assertThat(files).hasSize(2);
        GeneratedFile application = files.stream()
                .filter(f -> "Application".equals(f.className()))
                .findFirst().orElseThrow();
        GeneratedFile lifecycle = files.stream()
                .filter(f -> "RuntimeLifecycle".equals(f.className()))
                .findFirst().orElseThrow();

        assertThat(application.packageName()).isEqualTo("com.example.foundation");
        assertThat(application.content())
                .contains("import eu.exeris.kernel.core.bootstrap.KernelBootstrap")
                .contains("import eu.exeris.kernel.core.persistence.TransactionOrchestrator")
                .contains("import eu.exeris.kernel.spi.bootstrap.BootstrapSelector")
                .contains("import eu.exeris.kernel.spi.context.KernelProviders")
                .contains("import eu.exeris.kernel.spi.http.HttpHandler")
                .contains("import eu.exeris.kernel.spi.http.HttpKernelProviders")
                .contains("import eu.exeris.kernel.spi.http.HttpStatus")
                .contains("import eu.exeris.kernel.spi.persistence.TransactionalExecutor")
                .contains("public class Application")
                .contains("public static void main(String[] args)")
                .doesNotContain("public static void main(String[] args) throws Exception")
                .contains("new Application().run()")
                .contains("KernelBootstrap.builder()")
                .contains("BootstrapSelector.forNames(subsystems().split")
                .doesNotContain("SUBSYSTEMS.split")
                .contains("protected String subsystems()")
                .contains(".boot(() -> new RuntimeLifecycle(handlerSlot, transactionalExecutor()).run())")
                .contains("exchange.respond(HttpStatus.SERVICE_UNAVAILABLE)")
                .contains("protected TransactionalExecutor transactionalExecutor()")
                .contains("return new TransactionOrchestrator(KernelProviders.persistenceEngine())")
                .doesNotContain("import javax.sql")
                .doesNotContain("protected DataSource dataSource()");

        assertThat(lifecycle.packageName()).isEqualTo("com.example.foundation");
        assertThat(lifecycle.content())
                .contains("import eu.exeris.kernel.core.http.routing.HttpRouter")
                .contains("import eu.exeris.kernel.spi.http.HttpMethod")
                .contains("import eu.exeris.kernel.spi.persistence.TransactionalExecutor")
                .contains("public final class RuntimeLifecycle")
                .contains("OrderRepository orderRepository = new OrderRepository(transactionalExecutor)")
                .contains("OrderService orderService = new OrderService(orderRepository)")
                .contains("OrderHandler orderHandler = new OrderHandler(orderService)")
                .contains("ProductRepository productRepository = new ProductRepository(transactionalExecutor)")
                .contains("HttpRouter.Builder routerBuilder = HttpRouter.builder()")
                .contains("routerBuilder.route(HttpMethod.GET, \"/orders\", orderHandler::handleGetAll)")
                .contains("routerBuilder.route(HttpMethod.POST, \"/orders\", orderHandler::handleCreate)")
                .contains("routerBuilder.route(HttpMethod.PUT, \"/orders/{id}\", orderHandler::handleUpdate)")
                // T23: the HttpRouter INSTANCE is published (not a router::handle
                // lambda) so the kernel stream dispatcher's `instanceof HttpRouter`
                // sees it and streamRoute(...) registrations resolve on a real boot.
                .contains("handlerSlot.set(router)")
                .doesNotContain("handlerSlot.set(router::handle)")
                .contains("CountDownLatch shutdownLatch = new CountDownLatch(1)")
                .contains("Runtime.getRuntime().addShutdownHook")
                .doesNotContain("import javax.sql")
                .doesNotContain("private final DataSource");
    }

    @Test
    @DisplayName("T1: registers a POST {base}/{id}/actions/{kebab(name)} route per @Action")
    void shouldRegisterActionRoutes() {
        KernelApplicationGenerator gen = new KernelApplicationGenerator();
        DomainMetadata order = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders")
                .actions(List.of(
                        ActionMetadata.builder("cancel").methodName("cancel").build(),
                        // camelCase identity → kebab URL segment; handler method PascalCased
                        ActionMetadata.builder("markUrgent").methodName("flagUrgent").build()))
                .build();

        GeneratedFile lifecycle = gen.generateAll(List.of(order), "com.example.foundation")
                .stream().filter(f -> "RuntimeLifecycle".equals(f.className()))
                .findFirst().orElseThrow();

        assertThat(lifecycle.content())
                // path matches OpenApiPathsBuilder byte-for-byte; verb is POST (as OpenAPI emits)
                .contains("routerBuilder.route(HttpMethod.POST, \"/orders/{id}/actions/cancel\", orderHandler::handleCancel)")
                .contains("routerBuilder.route(HttpMethod.POST, \"/orders/{id}/actions/mark-urgent\", orderHandler::handleMarkUrgent)");
    }

    @Test
    @DisplayName("ADR-044 Slice 2: a @Action(streaming) action registers streamRoute(POST, …/actions/…) ONLY, "
            + "instantiates the per-action stream handler, and does not also emit a respond-once route")
    void shouldRegisterStreamingActionAsStreamRoute() {
        KernelApplicationGenerator gen = new KernelApplicationGenerator();
        DomainMetadata order = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders")
                .actions(List.of(
                        ActionMetadata.builder("cancel").methodName("cancel").build(),
                        ActionMetadata.builder("trackShipment").methodName("trackShipment")
                                .streaming(true)
                                .streamEventType("ShipmentMoved")
                                .build()))
                .build();

        GeneratedFile lifecycle = gen.generateAll(List.of(order), "com.example.foundation")
                .stream().filter(f -> "RuntimeLifecycle".equals(f.className()))
                .findFirst().orElseThrow();

        String content = lifecycle.content();
        assertThat(content)
                // per-action stream handler instantiated no-arg
                .contains("OrderTrackShipmentStreamHandler orderTrackShipmentStreamHandler = "
                        + "new OrderTrackShipmentStreamHandler()")
                // registered via the typed streamRoute(...), POST, at the action path
                .contains("routerBuilder.streamRoute(HttpMethod.POST, "
                        + "\"/orders/{id}/actions/track-shipment\", orderTrackShipmentStreamHandler::handle)")
                // non-streaming action keeps its respond-once route
                .contains("routerBuilder.route(HttpMethod.POST, \"/orders/{id}/actions/cancel\", "
                        + "orderHandler::handleCancel)");
        // the streaming action does NOT also get a respond-once route(...)
        assertThat(content)
                .doesNotContain("routerBuilder.route(HttpMethod.POST, "
                        + "\"/orders/{id}/actions/track-shipment\"");
    }
}
