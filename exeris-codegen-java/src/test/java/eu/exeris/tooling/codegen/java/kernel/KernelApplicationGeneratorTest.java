package eu.exeris.tooling.codegen.java.kernel;

import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator.ArtifactType;
import eu.exeris.sdk.sourcemodel.ast.ActionMetadata;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.FieldMetadata;
import eu.exeris.sdk.sourcemodel.ast.RelationshipMetadata;
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
    @DisplayName("generateAll with an empty domain list still emits all three files "
            + "(no entity wiring, no routes)")
    void shouldEmitBothFilesForEmptyDomainList() {
        KernelApplicationGenerator gen = new KernelApplicationGenerator();
        List<GeneratedFile> files = gen.generateAll(List.of(), "com.example.foundation");
        assertThat(files).hasSize(3);

        String lifecycle = files.stream()
                .filter(f -> "RuntimeLifecycle".equals(f.className()))
                .findFirst().orElseThrow().content();
        // No per-entity wiring. Asserted against the emission *pattern* rather than against
        // a named entity: with a zero-entity list, "no OrderHandler" is true for reasons that
        // have nothing to do with the generator behaving.
        assertThat(lifecycle)
                // every per-entity local is `<Entity>Handler <entity>Handler = components.<...>()`,
                // so the capitalised `Handler` is what marks it per-entity. The app-level seams
                // (configureRoutes, decorate) also read `components.` and are emitted for a
                // zero-entity app on purpose — a bare `= components.` would catch those too.
                .doesNotContain("Handler = components.")
                .doesNotContain("routerBuilder.route")
                .contains("HttpRouter.Builder routerBuilder = HttpRouter.builder()")
                .contains("HttpRouter router = routerBuilder.build()")
                // The count is known at generation time, so it is baked into the literal rather
                // than left as a System.Logger parameter.
                .contains("Application bootstrap complete: 0 entities wired")
                // the seam is still emitted and still invoked — an empty domain list is not
                // a reason to drop the consumer's hook
                .contains("components.configureRoutes(routerBuilder)");

        // ...and the components file carries the scaffold with not one component factory.
        // "\n    protected " matches a member declaration at class level; the javadoc lines
        // that mention `protected create*` start with "     * " and are not matched.
        assertThat(components(files))
                .contains("public class RuntimeComponents")
                .contains("public void configureRoutes(HttpRouter.Builder routes)")
                .doesNotContain("\n    protected ");
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
    @DisplayName("generateAll emits Application + RuntimeComponents + RuntimeLifecycle "
            + "against Open-Core SPI")
    void shouldGenerateApplicationAndLifecycle() {
        KernelApplicationGenerator gen = new KernelApplicationGenerator();
        DomainMetadata order = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders").build();
        DomainMetadata product = DomainMetadata.builder("Product", "com.example.domain")
                .path("/products").build();

        List<GeneratedFile> files = gen.generateAll(List.of(order, product),
                "com.example.foundation");

        assertThat(files).hasSize(3);
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
                .contains(".boot(() -> new RuntimeLifecycle(handlerSlot, "
                        + "components(transactionalExecutor())).run())")
                // The kernel is handed the edge handler, built before boot; the 503 lives in it.
                .contains("HttpHandler edgeHandler = RuntimeLifecycle.edgeHandler(handlerSlot)")
                .contains("ScopedValue.where(HttpKernelProviders.HTTP_SERVER_HANDLER, edgeHandler)")
                .doesNotContain("componentsSlot")
                .doesNotContain("forwardingHandler")
                // T49: the seam that lets a consumer install their own components.
                .contains("protected RuntimeComponents components(TransactionalExecutor "
                        + "transactionalExecutor)")
                .contains("return new RuntimeComponents(transactionalExecutor)")
                .contains("protected TransactionalExecutor transactionalExecutor()")
                .contains("return new TransactionOrchestrator(KernelProviders.persistenceEngine())")
                .doesNotContain("import javax.sql")
                .doesNotContain("protected DataSource dataSource()");

        assertThat(lifecycle.packageName()).isEqualTo("com.example.foundation");
        assertThat(lifecycle.content())
                .contains("import eu.exeris.kernel.core.http.routing.HttpRouter")
                .contains("import eu.exeris.kernel.spi.http.HttpMethod")
                // The executor import moved with the construction it served: the lifecycle
                // never names a TransactionalExecutor now, RuntimeComponents does.
                .doesNotContain("import eu.exeris.kernel.spi.persistence.TransactionalExecutor")
                .contains("public final class RuntimeLifecycle")
                // T49: construction moved to RuntimeComponents; the lifecycle only takes the
                // handlers it routes to, and never calls `new` on a generated type again.
                .contains("OrderHandler orderHandler = components.orderHandler()")
                .contains("ProductHandler productHandler = components.productHandler()")
                .doesNotContain("new OrderRepository(")
                .doesNotContain("new OrderService(")
                .doesNotContain("new OrderHandler(")
                .contains("HttpRouter.Builder routerBuilder = HttpRouter.builder()")
                .contains("routerBuilder.route(HttpMethod.GET, \"/orders\", orderHandler::handleGetAll)")
                .contains("routerBuilder.route(HttpMethod.POST, \"/orders\", orderHandler::handleCreate)")
                .contains("routerBuilder.route(HttpMethod.PUT, \"/orders/{id}\", orderHandler::handleUpdate)")
                // The router reaches the slot through the decorate hook. The slot is what the
                // edge handler forwards to — 503 while it is empty.
                .contains("HttpHandler handler = components.decorate(router)")
                .contains("handlerSlot.set(handler);")
                .doesNotContain("handlerSlot.set(router::handle)")
                .contains("public static HttpHandler edgeHandler(AtomicReference<HttpHandler> handlerSlot)")
                .contains("exchange.respond(HttpStatus.SERVICE_UNAVAILABLE)")
                .contains("CountDownLatch shutdownLatch = new CountDownLatch(1)")
                .contains("Runtime.getRuntime().addShutdownHook")
                .doesNotContain("import javax.sql")
                .doesNotContain("private final DataSource");
    }

    @Test
    @DisplayName("GC2: a composed build conducts the composition inside boot(...) — caps ready "
            + "before the handler slot, drained after the latch")
    void composedApplicationDrivesTheBootConductor() {
        KernelApplicationGenerator gen = new KernelApplicationGenerator();
        DomainMetadata order = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders").build();

        String application = application(gen.generateAll(List.of(order),
                "com.example.foundation", true));

        assertThat(application)
                .contains("import eu.exeris.sdk.composition.runtime.CompositionConductor")
                // The conductor wraps the lifecycle: start() (initialize + ready for every
                // cap) precedes RuntimeLifecycle.run(), which is what sets the handler slot,
                // and close() (drain + terminate) runs after run() returns from its latch —
                // both still inside boot(...), i.e. after KERNEL READY and before the kernel
                // stops. That ordering is the whole point of the call site (ADR-024).
                .contains("try (CompositionConductor _ = CompositionConductor.from(capManifest()).start())")
                .contains("new RuntimeLifecycle(handlerSlot, "
                        + "components(transactionalExecutor())).run();")
                // ...and NOT the bare, unconducted boot line.
                .doesNotContain(".boot(() -> new RuntimeLifecycle(handlerSlot, "
                        + "components(transactionalExecutor())).run())")
                .contains("protected Path capManifest()")
                .contains("import java.nio.file.Path")
                .contains("return Path.of(System.getProperty(\"exeris.capManifest\", \"cap-manifest.json\"))");
    }

    @Test
    @DisplayName("GC2: no composition → not one conductor symbol is emitted (no inert wiring), "
            + "and the two-argument overload is that cap-less default")
    void uncomposedApplicationCarriesNoConductorSymbol() {
        KernelApplicationGenerator gen = new KernelApplicationGenerator();
        List<DomainMetadata> domains = List.of(DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders").build());

        String viaOverload = application(gen.generateAll(domains, "com.example.foundation"));
        String viaFlag = application(gen.generateAll(domains, "com.example.foundation", false));

        assertThat(viaOverload).isEqualTo(viaFlag);
        assertThat(viaOverload)
                .doesNotContain("CompositionConductor")
                .doesNotContain("capManifest")
                .doesNotContain("cap-manifest.json")
                .doesNotContain("java.nio.file.Path")
                .contains(".boot(() -> new RuntimeLifecycle(handlerSlot, "
                        + "components(transactionalExecutor())).run())");
    }

    @Test
    @DisplayName("T30: the Application Javadoc lists composition-runtime as a compile requirement, "
            + "not a runtime one")
    void compositionRuntimeIsNamedAsACompileRequirement() {
        KernelApplicationGenerator gen = new KernelApplicationGenerator();
        List<DomainMetadata> domains = List.of(DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders").build());

        String composed = application(gen.generateAll(domains, "com.example.foundation", true));
        String compile = composed.substring(composed.indexOf("Compile classpath requirements"),
                composed.indexOf("Runtime classpath requirements"));
        assertThat(compile)
                .contains("{@code eu.exeris:exeris-kernel-spi} and\n * {@code eu.exeris:exeris-kernel-core}")
                .contains("{@code eu.exeris:exeris-sdk-composition-runtime}: this class imports")
                .doesNotContain("jackson-databind");
        String runtime = composed.substring(composed.indexOf("Runtime classpath requirements"),
                composed.indexOf("exeris-app-starter"));
        assertThat(runtime)
                .doesNotContain("composition-runtime")
                .contains("{@code eu.exeris:exeris-kernel-community} at runtime scope");

        assertThat(application(gen.generateAll(domains, "com.example.foundation", false)))
                .contains("Compile classpath requirements")
                .doesNotContain("composition-runtime");
    }

    @Test
    @DisplayName("T30: the Application Javadoc names Jackson 3 only when a repository in the tree imports it")
    void jacksonIsNamedOnlyWhenARepositoryImportsIt() {
        KernelApplicationGenerator gen = new KernelApplicationGenerator();
        DomainMetadata withoutList = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders")
                .fields(List.of(FieldMetadata.builder("total", "BigDecimal").build()))
                .build();
        DomainMetadata withList = DomainMetadata.builder("Tagged", "com.example.domain")
                .path("/tagged")
                .fields(List.of(FieldMetadata.builder("tags", "List<String>").build()))
                .build();
        assertThat(KernelRepositoryGenerator.importsJackson(withList)).isTrue();
        assertThat(KernelRepositoryGenerator.importsJackson(withoutList)).isFalse();

        assertThat(application(gen.generateAll(List.of(withoutList), "com.example.foundation", false)))
                .doesNotContain("jackson-databind");
        assertThat(application(gen.generateAll(List.of(withoutList, withList),
                        "com.example.foundation", false)))
                .contains("{@code tools.jackson.core:jackson-databind} (Jackson 3)")
                .contains("that repository's Javadoc names it.");
    }

    @Test
    @DisplayName("GC2: composition changes Application only — RuntimeLifecycle is byte-identical")
    void compositionLeavesTheRuntimeLifecycleUntouched() {
        KernelApplicationGenerator gen = new KernelApplicationGenerator();
        List<DomainMetadata> domains = List.of(DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders").build());

        assertThat(lifecycle(gen.generateAll(domains, "com.example.foundation", true)))
                .isEqualTo(lifecycle(gen.generateAll(domains, "com.example.foundation", false)));
    }

    @Test
    @DisplayName("GC2: composed emission is deterministic — byte-identical across runs")
    void composedEmissionIsDeterministic() {
        KernelApplicationGenerator gen = new KernelApplicationGenerator();
        List<DomainMetadata> domains = List.of(
                DomainMetadata.builder("Order", "com.example.domain").path("/orders").build(),
                DomainMetadata.builder("Product", "com.example.domain").path("/products").build());

        assertThat(application(gen.generateAll(domains, "com.example.foundation", true)))
                .isEqualTo(application(new KernelApplicationGenerator()
                        .generateAll(domains, "com.example.foundation", true)));
    }

    @Test
    @DisplayName("T49: RuntimeComponents gives every generated component a field, a memoising "
            + "accessor and an overridable factory, and the defaults chain through the accessors")
    void componentsExposesAnOverridableFactoryPerComponent() {
        KernelApplicationGenerator gen = new KernelApplicationGenerator();
        DomainMetadata order = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders").build();

        String components = components(gen.generateAll(List.of(order), "com.example.foundation"));

        assertThat(components)
                // Not final — the whole point is that a consumer subclasses it.
                .contains("public class RuntimeComponents")
                .doesNotContain("public final class RuntimeComponents")
                .contains("public RuntimeComponents(TransactionalExecutor transactionalExecutor)")
                // field + public accessor + protected factory, per component
                .contains("private OrderRepository orderRepository;")
                .contains("public OrderRepository orderRepository()")
                .contains("protected OrderRepository createOrderRepository()")
                .contains("private OrderService orderService;")
                .contains("public OrderService orderService()")
                .contains("protected OrderService createOrderService()")
                .contains("private OrderHandler orderHandler;")
                .contains("public OrderHandler orderHandler()")
                .contains("protected OrderHandler createOrderHandler()")
                // The default construction reads its dependency through the ACCESSOR, not a
                // field or a local. That indirection is what makes one override take effect
                // everywhere downstream: override createOrderRepository() and the service
                // built by the untouched createOrderService() gets the replacement.
                .contains("return new OrderRepository(transactionalExecutor())")
                .contains("return new OrderService(orderRepository())")
                // T43-follow-up: the factory resolves the ScopedValue here, inside the bootstrap
                // callback where it is bound, and hands the instance to the handler.
                .contains("return new OrderHandler(orderService(), KernelProviders.MEMORY_ALLOCATOR.get())")
                // Memoisation, so an accessor is safe to call from an override.
                .contains("if (orderRepository == null) {")
                .contains("orderRepository = createOrderRepository();");
    }

    @Test
    @DisplayName("T49 residual: decorate wraps the router on the way to the handler slot, "
            + "and defaults to no wrapping at all")
    void decorateHookWrapsTheRouterBeforeItIsServed() {
        KernelApplicationGenerator gen = new KernelApplicationGenerator();
        DomainMetadata order = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders").build();
        List<GeneratedFile> files = gen.generateAll(List.of(order), "com.example.foundation");

        // Until this hook existed a deployment needing a per-request scope had to
        // reimplement run(), because RuntimeLifecycle bound the router itself.
        assertThat(components(files))
                .contains("public HttpHandler decorate(HttpRouter router)")
                .contains("return router");

        String lifecycle = lifecycle(files);
        int build = lifecycle.indexOf("HttpRouter router = routerBuilder.build()");
        int decorate = lifecycle.indexOf("HttpHandler handler = components.decorate(router)");
        int publish = lifecycle.indexOf("handlerSlot.set(handler);");

        assertThat(build).isGreaterThan(-1);
        assertThat(decorate)
                .as("the router has to exist before it can be decorated")
                .isGreaterThan(build);
        assertThat(publish)
                .as("what is published is the decorated handler, not the bare router")
                .isGreaterThan(decorate);
        assertThat(lifecycle)
                .as("publishing the router directly would silently discard the hook")
                .doesNotContain("handlerSlot.set(router)");
    }

    @Test
    @DisplayName("K9: a stream-bearing app refuses a decorate wrapper that is not a "
            + "StreamRouteResolver, naming its class; the router and a resolver are published as is")
    void streamBearingAppRefusesAWrapperThatResolvesNoStreams() {
        KernelApplicationGenerator gen = new KernelApplicationGenerator();
        DomainMetadata live = DomainMetadata.builder("GalacticEra", "com.example.domain")
                .path("/era").realTimeApi(true).build();
        List<GeneratedFile> files = gen.generateAll(List.of(live), "com.example.foundation");
        String run = method(lifecycle(files), "public void run()");

        // The kernel resolves a stream only through StreamRouteResolver on the bound handler, so
        // behind any other wrapper every stream route would be unreachable.
        int decorate = run.indexOf("HttpHandler handler = components.decorate(router);");
        int refusal = run.indexOf("if (router.servesStreams() && !(handler instanceof StreamRouteResolver)) {");
        int publish = run.indexOf("handlerSlot.set(handler);");
        int probe = run.indexOf("requireDecoratedStreamRoute(decorated, HttpMethod.GET, \"/era/stream\");");
        assertThat(refusal).isGreaterThan(decorate);
        assertThat(probe).as("each generated stream route is probed through the published handler")
                .isGreaterThan(refusal);
        assertThat(publish).isGreaterThan(probe);
        assertThat(run).contains("StreamRouteResolver decorated = (StreamRouteResolver) handler;");
        assertThat(method(lifecycle(files), "private static void requireDecoratedStreamRoute("))
                .contains("if (decorated.resolveStream(method, path) == null) {")
                .contains("\", which resolves no stream for \" + method + \" \" + path");
        assertThat(run)
                .contains("throw new IllegalStateException(\"RuntimeComponents.decorate returned \" "
                        + "+ handler.getClass().getName()")
                .contains("Implement StreamRouteResolver on the wrapper and delegate resolveStream to the router,")
                .contains("as the RuntimeComponents.decorate Javadoc shows.")
                .doesNotContain("RouterStreams")
                .doesNotContain("instanceof HttpRouter");
        assertThat(lifecycle(files)).doesNotContain("class RouterStreams");
        // The decorate Javadoc states both cases, and shows the delegating shape.
        assertThat(components(files))
                .contains("A wrapper that implements it, delegating to the router, resolves every")
                .contains("<li>A wrapper that does not implement it is refused at boot when the")
                .contains("router serves any stream route, generated or registered in")
                .doesNotContain("application has generated stream routes")
                .contains("record TenantBinding(HttpRouter router) implements HttpHandler, StreamRouteResolver {")
                .contains("StreamMatch match = router.resolveStream(method, path);")
                .contains("{@link StreamRouteResolver}")
                .doesNotContain("edgeRouter")
                .doesNotContain("outside the wrapper");
    }

    @Test
    @DisplayName("K9: the edge handler forwards requests and stream resolution to the slot, "
            + "503 and no stream while it is empty")
    void edgeHandlerForwardsStreamResolutionToTheSlot() {
        KernelApplicationGenerator gen = new KernelApplicationGenerator();
        DomainMetadata order = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders").build();
        String lifecycle = lifecycle(gen.generateAll(List.of(order), "com.example.foundation"));

        assertThat(method(lifecycle, "public static HttpHandler edgeHandler("))
                .contains("return new EdgeHandler(handlerSlot);");
        // A named class: the kernel tests the bound handler with instanceof StreamRouteResolver,
        // which a lambda never satisfies.
        assertThat(method(lifecycle, "private static final class EdgeHandler"))
                .contains("implements HttpHandler, StreamRouteResolver")
                .contains("HttpHandler handler = handlerSlot.get();")
                .contains("exchange.respond(HttpStatus.SERVICE_UNAVAILABLE);")
                .contains("if (handlerSlot.get() instanceof StreamRouteResolver resolver) {")
                .contains("return resolver.resolveStream(method, path);")
                .contains("return null;");
        assertThat(lifecycle)
                .contains("import eu.exeris.kernel.spi.http.StreamRouteResolver;")
                .contains("import eu.exeris.kernel.spi.http.StreamMatch;")
                .doesNotContain("import eu.exeris.kernel.core.http.routing.StreamMatch");
    }

    @Test
    @DisplayName("K9: an app with no generated stream routes still guards decorate on the built "
            + "router's servesStreams(), so a configureRoutes stream is not hidden by a wrapper")
    void appWithoutGeneratedStreamRoutesGuardsOnServesStreams() {
        KernelApplicationGenerator gen = new KernelApplicationGenerator();
        DomainMetadata order = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders").build();
        List<GeneratedFile> files = gen.generateAll(List.of(order), "com.example.foundation");
        String run = method(lifecycle(files), "public void run()");

        // The guard is the router's own answer: a router with no stream route takes any
        // wrapper, and one whose only streams came from configureRoutes refuses a wrapper
        // that would hide them.
        int build = run.indexOf("HttpRouter router = routerBuilder.build();");
        int decorate = run.indexOf("HttpHandler handler = components.decorate(router);");
        int refusal = run.indexOf("if (router.servesStreams() && !(handler instanceof StreamRouteResolver)) {");
        int publish = run.indexOf("handlerSlot.set(handler);");
        assertThat(decorate).isGreaterThan(build);
        assertThat(refusal).isGreaterThan(decorate);
        assertThat(publish).isGreaterThan(refusal);
        assertThat(run)
                .contains("throw new IllegalStateException(\"RuntimeComponents.decorate returned \" "
                        + "+ handler.getClass().getName()")
                .doesNotContain("requireDecoratedStreamRoute")
                .doesNotContain("StreamRouteResolver decorated");
        assertThat(lifecycle(files)).doesNotContain("private static void requireDecoratedStreamRoute(");
    }

    @Test
    @DisplayName("T49: configureRoutes runs after every generated route and before build(), "
            + "so a hand-written route can add but never displace")
    void configureRoutesHookRunsAfterGeneratedRoutesAndBeforeBuild() {
        KernelApplicationGenerator gen = new KernelApplicationGenerator();
        DomainMetadata order = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders").build();
        List<GeneratedFile> files = gen.generateAll(List.of(order), "com.example.foundation");

        assertThat(components(files))
                .contains("public void configureRoutes(HttpRouter.Builder routes)");

        String lifecycle = lifecycle(files);
        int lastGeneratedRoute = lifecycle.lastIndexOf("routerBuilder.route(");
        int hook = lifecycle.indexOf("components.configureRoutes(routerBuilder)");
        int build = lifecycle.indexOf("HttpRouter router = routerBuilder.build()");

        assertThat(lastGeneratedRoute).isGreaterThan(-1);
        assertThat(hook).isGreaterThan(lastGeneratedRoute);
        assertThat(build).isGreaterThan(hook);
    }

    @Test
    @DisplayName("K9: generated stream routes are registered before configureRoutes, so the "
            + "kernel builder refuses a hand-written one at a generated method and path")
    void configureRoutesMayAddStreamsButNotReplaceAGeneratedOne() {
        KernelApplicationGenerator gen = new KernelApplicationGenerator();
        DomainMetadata live = DomainMetadata.builder("GalacticEra", "com.example.domain")
                .path("/era").realTimeApi(true).build();
        List<GeneratedFile> files = gen.generateAll(List.of(live), "com.example.foundation");
        String run = method(lifecycle(files), "public void run()");

        int stream = run.indexOf("routerBuilder.streamRoute(HttpMethod.GET, \"/era/stream\", "
                + "galacticEraStreamHandler);");
        int hook = run.indexOf("components.configureRoutes(routerBuilder)");
        int build = run.indexOf("HttpRouter router = routerBuilder.build()");
        int decorate = run.indexOf("HttpHandler handler = components.decorate(router)");

        assertThat(stream).isGreaterThan(-1);
        assertThat(hook).isGreaterThan(stream);
        assertThat(build).isGreaterThan(hook);
        assertThat(decorate).isGreaterThan(build);
        // A duplicate stream registration is the kernel builder's to refuse, as it is made.
        assertThat(lifecycle(files)).doesNotContain("requireStreamRoute(");
        // The hook's Javadoc says streams are admitted, and what is refused.
        assertThat(components(files))
                .contains("<p><b>Stream routes too.</b>")
                .contains("stream route already serves is refused by the builder as it is")
                .doesNotContain("last stream registered at an exact path")
                .doesNotContain("Respond-once routes only.");
    }

    @Test
    @DisplayName("T49: the SSE stream handlers go through the seam too — no generated type is "
            + "constructed outside RuntimeComponents")
    void streamHandlersAreBuiltThroughTheSeam() {
        KernelApplicationGenerator gen = new KernelApplicationGenerator();
        DomainMetadata order = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders")
                .realTimeApi(true)
                .actions(List.of(ActionMetadata.builder("trackShipment").streaming(true).build()))
                .build();
        List<GeneratedFile> files = gen.generateAll(List.of(order), "com.example.foundation");

        assertThat(components(files))
                .contains("protected OrderStreamHandler createOrderStreamHandler()")
                // No @DomainEvent → the keep-alive fallback, which subscribes to nothing.
                .contains("return new OrderStreamHandler()")
                .contains("protected OrderTrackShipmentStreamHandler "
                        + "createOrderTrackShipmentStreamHandler()")
                // An action with no body and no triggered event: the service is all it takes.
                .contains("return new OrderTrackShipmentStreamHandler(orderService())");

        // run() takes each stream handler from its accessor, on the boot thread.
        assertThat(lifecycle(files))
                .contains("HttpStreamHandler orderStreamHandler = components.orderStreamHandler();")
                .contains("HttpStreamHandler orderTrackShipmentStreamHandler = "
                        + "components.orderTrackShipmentStreamHandler();")
                // The lifecycle calls `new` on nothing the pipeline generated.
                .doesNotContain("= new Order");
    }

    @Test
    @DisplayName("ADR-044 Amendment 2: a per-action stream handler takes the allocator when its action "
            + "decodes a body, and the publisher and the EventEngine when it triggers events, each "
            + "captured at composition and recorded in the scope ledger")
    void actionStreamHandlerTakesWhatItsActionNeeds() {
        KernelApplicationGenerator gen = new KernelApplicationGenerator();
        DomainMetadata order = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders")
                .dataScope(eu.exeris.sdk.sourcemodel.ast.DataScope.TENANT)
                .actions(List.of(ActionMetadata.builder("trackShipment").methodName("trackShipment")
                        .streaming(true)
                        .params(List.of(eu.exeris.sdk.sourcemodel.ast.ActionParamMetadata
                                .required("note", "java.lang.String")))
                        .build()))
                .events(List.of(eu.exeris.sdk.sourcemodel.ast.DomainEventMetadata.builder("OrderTracked")
                        .trigger(eu.exeris.sdk.sourcemodel.ast.DomainEventMetadata.Trigger.ACTION)
                        .actionName("trackShipment")
                        .build()))
                .build();

        String components = components(gen.generateAll(List.of(order), "com.example.foundation"));

        assertThat(components)
                .contains("return new OrderTrackShipmentStreamHandler(orderService(), "
                        + "KernelProviders.MEMORY_ALLOCATOR.get(), orderEventPublisher(), "
                        + "KernelProviders.eventEngine());")
                .contains("{@link KernelProviders#MEMORY_ALLOCATOR} — read by {@link #createOrderHandler()}, "
                        + "{@link #createOrderTrackShipmentStreamHandler()}")
                .contains("{@link #createOrderTrackShipmentStreamHandler()}</li>")
                .contains("{@link OrderTrackShipmentStreamHandler}{@code .parseBody}")
                .contains("the tenant guard in {@link OrderTrackShipmentStreamHandler}");
    }

    @Test
    @DisplayName("T23 slice B1: the EV1 producer stream handler takes its EventEngine from the "
            + "factory, captured at composition — never resolved on the stream thread")
    void producerStreamHandlerTakesItsEngineAtComposition() {
        KernelApplicationGenerator gen = new KernelApplicationGenerator();
        DomainMetadata order = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders")
                .realTimeApi(true)
                .events(List.of(eu.exeris.sdk.sourcemodel.ast.DomainEventMetadata.simple("OrderCreated")))
                .build();

        // Resolved inside the boot callback, where EVENT_ENGINE is bound. The stream thread
        // binds only MEMORY_ALLOCATOR and the decoder registry.
        assertThat(components(gen.generateAll(List.of(order), "com.example.foundation")))
                .contains("return new OrderStreamHandler(KernelProviders.eventEngine())");
    }

    @Test
    @DisplayName("ADR-044 Amendment 2: the spectate handler takes the service, and the EventEngine when "
            + "the entity declares an event, captured at composition and recorded in the scope ledger")
    void spectateStreamHandlerTakesWhatItForwards() {
        KernelApplicationGenerator gen = new KernelApplicationGenerator();
        DomainMetadata quiet = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders").realTimeApi(true).build();
        DomainMetadata live = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders").realTimeApi(true)
                .events(List.of(eu.exeris.sdk.sourcemodel.ast.DomainEventMetadata.simple("OrderCreated")))
                .build();

        assertThat(components(gen.generateAll(List.of(quiet), "com.example.foundation")))
                .contains("protected OrderSpectateStreamHandler createOrderSpectateStreamHandler()")
                .contains("return new OrderSpectateStreamHandler(orderService());");
        assertThat(components(gen.generateAll(List.of(live), "com.example.foundation")))
                .contains("return new OrderSpectateStreamHandler(orderService(), KernelProviders.eventEngine());")
                .contains("{@link #createOrderSpectateStreamHandler()}");
    }

    @Test
    @DisplayName("ADR-044 Amendment 2: per entity, the live view's exact GET {base}/stream is registered "
            + "before the spectate template GET {base}/{id}/stream, and both after the action streams")
    void spectateRouteFollowsTheLiveView() {
        KernelApplicationGenerator gen = new KernelApplicationGenerator();
        DomainMetadata live = DomainMetadata.builder("GalacticEra", "com.example.domain")
                .path("/era").realTimeApi(true)
                .actions(List.of(ActionMetadata.builder("trackRift").streaming(true).build()))
                .build();
        String run = method(lifecycle(gen.generateAll(List.of(live), "com.example.foundation")),
                "public void run()");

        int action = run.indexOf("routerBuilder.streamRoute(HttpMethod.POST, \"/era/{id}/actions/track-rift\"");
        int liveView = run.indexOf("routerBuilder.streamRoute(HttpMethod.GET, \"/era/stream\"");
        int spectate = run.indexOf("routerBuilder.streamRoute(HttpMethod.GET, \"/era/{id}/stream\"");
        assertThat(action).isGreaterThan(-1);
        assertThat(liveView).isGreaterThan(action);
        assertThat(spectate).isGreaterThan(liveView);
    }

    @Test
    @DisplayName("K9: stream routes are registered in run() on the composed router, every "
            + "generated path byte for byte")
    void streamRoutesAreRegisteredOnTheComposedRouter() {
        KernelApplicationGenerator gen = new KernelApplicationGenerator();
        DomainMetadata live = DomainMetadata.builder("GalacticEra", "com.example.domain")
                .path("/era").realTimeApi(true)
                .actions(List.of(ActionMetadata.builder("trackRift").streaming(true).build()))
                .build();
        String lifecycle = lifecycle(gen.generateAll(List.of(live), "com.example.foundation"));

        assertThat(method(lifecycle, "public void run()"))
                .contains("HttpStreamHandler galacticEraStreamHandler = components.galacticEraStreamHandler();")
                .contains("routerBuilder.streamRoute(HttpMethod.GET, \"/era/stream\", galacticEraStreamHandler);")
                .contains("routerBuilder.streamRoute(HttpMethod.POST, \"/era/{id}/actions/track-rift\", "
                        + "galacticEraTrackRiftStreamHandler);")
                .contains("HttpStreamHandler galacticEraSpectateStreamHandler = "
                        + "components.galacticEraSpectateStreamHandler();")
                .contains("routerBuilder.streamRoute(HttpMethod.GET, \"/era/{id}/stream\", "
                        + "galacticEraSpectateStreamHandler);")
                .contains("requireDecoratedStreamRoute(decorated, HttpMethod.GET, \"/era/{id}/stream\");");
        assertThat(lifecycle)
                .doesNotContain("edgeRouter")
                .doesNotContain("lazyStream")
                .doesNotContain("componentsSlot");
    }

    @Test
    @DisplayName("K9: the handler slot is set last — once it is, every stream route resolves")
    void handlerSlotIsSetAfterTheStreamRoutesAreBuilt() {
        KernelApplicationGenerator gen = new KernelApplicationGenerator();
        DomainMetadata live = DomainMetadata.builder("GalacticEra", "com.example.domain")
                .path("/era").realTimeApi(true).build();
        String run = method(lifecycle(gen.generateAll(List.of(live), "com.example.foundation")),
                "public void run()");

        int built = run.indexOf("HttpStreamHandler galacticEraStreamHandler = components.galacticEraStreamHandler();");
        int decorate = run.indexOf("HttpHandler handler = components.decorate(router)");
        int handler = run.indexOf("handlerSlot.set(");

        assertThat(built).as("the stream handler is built on the boot thread").isGreaterThan(-1);
        assertThat(handler).isGreaterThan(built).isGreaterThan(decorate);
    }

    @Test
    @DisplayName("T23 slice B1: run() builds every publisher before any stream target — the bus "
            + "refuses a subscription to an event type no publisher has registered yet")
    void publishersAreBuiltBeforeAnythingSubscribes() {
        KernelApplicationGenerator gen = new KernelApplicationGenerator();
        // STATE_TRANSITION is published by no handler method (ADR-075), so no handler factory
        // builds this publisher, and only run() registers its event types.
        DomainMetadata era = DomainMetadata.builder("GalacticEra", "com.example.domain")
                .path("/era").realTimeApi(true)
                .events(List.of(eu.exeris.sdk.sourcemodel.ast.DomainEventMetadata.builder("EraTurned")
                        .trigger(eu.exeris.sdk.sourcemodel.ast.DomainEventMetadata.Trigger.STATE_TRANSITION)
                        .build()))
                .build();
        DomainMetadata tag = DomainMetadata.builder("Tag", "com.example.domain").path("/tags").build();
        String run = method(lifecycle(gen.generateAll(List.of(era, tag), "com.example.foundation")),
                "public void run()");

        int publisher = run.indexOf("components.galacticEraEventPublisher();");
        int streamTarget = run.indexOf("components.galacticEraStreamHandler();");
        int published = run.indexOf("handlerSlot.set(");
        assertThat(publisher).as("the publisher is built at composition").isGreaterThan(-1);
        assertThat(streamTarget).isGreaterThan(publisher);
        assertThat(published).isGreaterThan(publisher);
        // An entity with no events has no publisher to build.
        assertThat(run).doesNotContain("tagEventPublisher");
    }

    @Test
    @DisplayName("T48 slice C1: subscribers and saga flows are components — a memoised accessor "
            + "and an overridable factory each, built from the boot-bound engines")
    void subscribersAndSagaFlowsAreComposed() {
        KernelApplicationGenerator gen = new KernelApplicationGenerator();
        String components = components(gen.generateAll(List.of(orderWithEventsAndSaga()),
                "com.example.foundation"));

        assertThat(components)
                .contains("private OrderEventSubscriber orderEventSubscriber;")
                .contains("public OrderEventSubscriber orderEventSubscriber()")
                .contains("protected OrderEventSubscriber createOrderEventSubscriber()")
                .contains("return new OrderEventSubscriber(KernelProviders.eventEngine())")
                // The flow's class name derives from @Saga(name) — the accessor follows it, so a
                // subclass of OrderFulfillmentFlow installs by overriding
                // createOrderFulfillmentFlow().
                .contains("import com.example.saga.OrderFulfillmentFlow;")
                .contains("public OrderFulfillmentFlow orderFulfillmentFlow()")
                .contains("protected OrderFulfillmentFlow createOrderFulfillmentFlow()")
                .contains("return new OrderFulfillmentFlow(KernelProviders.flowEngine())")
                // A payload-bearing publisher takes the registry at composition, where it is bound.
                .contains("return new OrderEventPublisher(KernelProviders.eventEngine(), "
                        + "KernelProviders.eventPayloadCodecRegistry().orElse(null))");
    }

    @Test
    @DisplayName("T48 slice C1: run() initializes every saga, then subscribes every subscriber — after "
            + "the publishers, before either slot — and unsubscribes in reverse after the latch")
    void sagasAndSubscribersAreStartedBeforeTheAppServes() {
        KernelApplicationGenerator gen = new KernelApplicationGenerator();
        DomainMetadata invoice = DomainMetadata.builder("Invoice", "com.example.domain")
                .path("/invoices")
                .events(List.of(eu.exeris.sdk.sourcemodel.ast.DomainEventMetadata.simple("InvoiceIssued")))
                .build();
        String run = method(lifecycle(gen.generateAll(List.of(orderWithEventsAndSaga(), invoice),
                "com.example.foundation")), "public void run()");

        int publishers = run.indexOf("components.invoiceEventPublisher();");
        int saga = run.indexOf("components.orderFulfillmentFlow().initialize();");
        int orderSub = run.indexOf("components.orderEventSubscriber().subscribe();");
        int invoiceSub = run.indexOf("components.invoiceEventSubscriber().subscribe();");
        int handlerSlot = run.indexOf("handlerSlot.set(");
        int latch = run.indexOf("shutdownLatch.await();");
        int invoiceUnsub = run.indexOf("components.invoiceEventSubscriber().unsubscribe();");
        int orderUnsub = run.indexOf("components.orderEventSubscriber().unsubscribe();");

        assertThat(saga).as("the plan is registered at boot").isGreaterThan(publishers);
        assertThat(orderSub).as("subscribe after every publisher registered its types")
                .isGreaterThan(publishers).isGreaterThan(saga);
        assertThat(invoiceSub).isGreaterThan(orderSub);
        assertThat(handlerSlot).as("activation completes before the app serves")
                .isGreaterThan(invoiceSub);
        assertThat(invoiceUnsub).as("released after the latch").isGreaterThan(latch);
        assertThat(orderUnsub).as("in reverse order").isGreaterThan(invoiceUnsub);
    }

    @Test
    @DisplayName("T48 slice C1: an entity with neither events nor a saga adds no activation at all")
    void nothingToStartEmitsNoActivation() {
        KernelApplicationGenerator gen = new KernelApplicationGenerator();
        DomainMetadata tag = DomainMetadata.builder("Tag", "com.example.domain").path("/tags").build();
        List<GeneratedFile> files = gen.generateAll(List.of(tag), "com.example.foundation");

        assertThat(lifecycle(files))
                .doesNotContain(".initialize()")
                .doesNotContain(".subscribe()")
                .doesNotContain(".unsubscribe()");
        assertThat(components(files))
                .doesNotContain("EventSubscriber")
                .doesNotContain("Flow");
    }

    @Test
    @DisplayName("T51: RuntimeComponents publishes COMPOSITION_SCOPES and REQUEST_SCOPES, derived "
            + "from the branches that emit each read, naming every reader")
    void scopeListsAreDerivedFromTheEmission() {
        KernelApplicationGenerator gen = new KernelApplicationGenerator();
        DomainMetadata live = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders")
                .realTimeApi(true)
                .fields(List.of(FieldMetadata.builder("total", "BigDecimal").build()))
                .events(List.of(eu.exeris.sdk.sourcemodel.ast.DomainEventMetadata.builder("OrderCreated")
                        .payloadFields(List.of("total")).build()))
                .sagaMetadata(eu.exeris.sdk.sourcemodel.ast.SagaMetadata.simple("OrderFulfillment"))
                .build();
        DomainMetadata ledger = DomainMetadata.builder("Ledger", "com.example.domain")
                .path("/ledgers")
                .dataScope(eu.exeris.sdk.sourcemodel.ast.DataScope.TENANT)
                .build();
        String components = components(gen.generateAll(List.of(live, ledger), "com.example.foundation"));

        assertThat(components)
                .contains("public static final List<ScopedValue<?>> COMPOSITION_SCOPES = List.of("
                        + "KernelProviders.MEMORY_ALLOCATOR, KernelProviders.EVENT_ENGINE, "
                        + "KernelProviders.FLOW_ENGINE);")
                .contains("public static final List<ScopedValue<?>> REQUEST_SCOPES = List.of("
                        + "HttpKernelProviders.HTTP_REQUEST_BODY_DECODER_REGISTRY, "
                        + "KernelProviders.STORAGE_CONTEXT);")
                // every reader named, from the same branch that emitted the read
                .contains("{@link KernelProviders#MEMORY_ALLOCATOR} — read by "
                        + "{@link #createOrderHandler()}, {@link #createLedgerHandler()}")
                .contains("{@link KernelProviders#EVENT_ENGINE} — read by "
                        + "{@link #createOrderEventPublisher()}, {@link #createOrderEventSubscriber()}, "
                        + "{@link #createOrderStreamHandler()}")
                .contains("{@link KernelProviders#FLOW_ENGINE} — read by {@link #createOrderFulfillmentFlow()}")
                .contains("{@link KernelProviders#STORAGE_CONTEXT} — read by the tenant guard in "
                        + "{@link LedgerHandler}, {@link LedgerRepository}{@code .actingTenantId()}")
                // optional: named, not listed
                .contains("Optional, and so not listed: {@link KernelProviders#EVENT_PAYLOAD_CODEC_REGISTRY}");
    }

    @Test
    @DisplayName("T51: a global entity with no events, stream or saga lists only what it reads")
    void scopeListsShrinkWithTheDomain() {
        KernelApplicationGenerator gen = new KernelApplicationGenerator();
        DomainMetadata tag = DomainMetadata.builder("Tag", "com.example.domain").path("/tags").build();

        assertThat(components(gen.generateAll(List.of(tag), "com.example.foundation")))
                .contains("COMPOSITION_SCOPES = List.of(KernelProviders.MEMORY_ALLOCATOR);")
                .contains("REQUEST_SCOPES = List.of(HttpKernelProviders.HTTP_REQUEST_BODY_DECODER_REGISTRY);")
                .doesNotContain("EVENT_ENGINE")
                .doesNotContain("FLOW_ENGINE")
                .doesNotContain("STORAGE_CONTEXT;")
                .doesNotContain("Optional, and so not listed");
        // No domain at all: nothing is read, and the lists say so.
        assertThat(components(gen.generateAll(List.of(), "com.example.foundation")))
                .contains("COMPOSITION_SCOPES = List.of();")
                .contains("REQUEST_SCOPES = List.of();");
    }

    @Test
    @DisplayName("subsystems() names what the domain uses: a plain entity boots http, persistence "
            + "and crypto, and the Javadoc no longer asks the reader to drop graph by hand")
    void subsystemsAreDerivedFromTheDomain() {
        KernelApplicationGenerator gen = new KernelApplicationGenerator();
        DomainMetadata tag = DomainMetadata.builder("Tag", "com.example.foundation.domain")
                .path("/tags").build();
        String application = application(gen.generateAll(List.of(tag), "com.example.foundation"));

        assertThat(method(application, "\n    protected String subsystems()"))
                .contains("return \"http,persistence,crypto\";");
        assertThat(application)
                .contains("{@code http,persistence,crypto}")
                .contains("return super.subsystems() + \",scheduling\";")
                .doesNotContain("to drop {@code graph}")
                .doesNotContain("http,persistence,graph,flow,events,crypto");

        assertThat(method(application(gen.generateAll(List.of(orderWithEventsAndSaga()),
                "com.example.foundation")), "\n    protected String subsystems()"))
                .contains("return \"http,persistence,flow,events,crypto\";");
    }

    @Test
    @DisplayName("each conditional subsystem is listed exactly when emitted code reads its engine — "
            + "events with eventEngine(), flow with flowEngine(), graph with a GraphSync")
    void subsystemsFollowTheEmittedEngineReads() {
        KernelApplicationGenerator gen = new KernelApplicationGenerator();
        KernelGraphSyncGenerator graphSync = new KernelGraphSyncGenerator();
        DomainMetadata plain = DomainMetadata.builder("Tag", "com.example.domain").path("/tags").build();
        DomainMetadata streamOnly = DomainMetadata.builder("Feed", "com.example.domain")
                .path("/feeds").realTimeApi(true).build();
        DomainMetadata streamWithEvents = DomainMetadata.builder("Beacon", "com.example.domain")
                .path("/beacons").realTimeApi(true)
                .events(List.of(eu.exeris.sdk.sourcemodel.ast.DomainEventMetadata.simple("BeaconPinged")))
                .build();
        DomainMetadata graph = DomainMetadata.builder("Customer", "com.example.domain")
                .path("/customers")
                .graphMetadata(eu.exeris.sdk.sourcemodel.ast.GraphMetadata.simple("Customer"))
                .build();
        DomainMetadata saga = DomainMetadata.builder("Shipment", "com.example.domain")
                .path("/shipments")
                .sagaMetadata(eu.exeris.sdk.sourcemodel.ast.SagaMetadata.simple("ShipmentSaga"))
                .build();

        for (List<DomainMetadata> domains : List.of(List.of(plain), List.of(streamOnly),
                List.of(streamWithEvents), List.of(graph), List.of(saga),
                List.of(plain, graph, saga, streamWithEvents), List.of(orderWithEventsAndSaga()))) {
            List<GeneratedFile> files = gen.generateAll(domains, "com.example.foundation");
            // Code only: the class Javadoc names both engines in its examples.
            String components = components(files).lines()
                    .filter(line -> !line.strip().startsWith("*") && !line.strip().startsWith("/"))
                    .collect(java.util.stream.Collectors.joining("\n"));
            List<String> names = List.of(method(application(files), "\n    protected String subsystems()")
                    .replaceAll("(?s).*return \"([^\"]*)\";.*", "$1").split(","));
            boolean emitsGraphSync = domains.stream().anyMatch(d -> graphSync.generate(d) != null);

            assertThat(names).as("always on, for %s", domains)
                    .contains("http", "persistence", "crypto");
            assertThat(names.contains("events")).as("events for %s", domains)
                    .isEqualTo(components.contains("KernelProviders.eventEngine()"));
            assertThat(names.contains("flow")).as("flow for %s", domains)
                    .isEqualTo(components.contains("KernelProviders.flowEngine()"));
            assertThat(names.contains("graph")).as("graph for %s", domains)
                    .isEqualTo(emitsGraphSync);
        }
    }

    @Test
    @DisplayName("subsystems() is the same string whatever order the domains arrive in")
    void subsystemsDoNotDependOnDomainOrder() {
        KernelApplicationGenerator gen = new KernelApplicationGenerator();
        DomainMetadata graph = DomainMetadata.builder("Customer", "com.example.domain")
                .path("/customers")
                .graphMetadata(eu.exeris.sdk.sourcemodel.ast.GraphMetadata.simple("Customer"))
                .build();
        DomainMetadata saga = DomainMetadata.builder("Shipment", "com.example.domain")
                .path("/shipments")
                .sagaMetadata(eu.exeris.sdk.sourcemodel.ast.SagaMetadata.simple("ShipmentSaga"))
                .build();

        String forward = method(application(gen.generateAll(List.of(saga, graph), "com.example.foundation")),
                "\n    protected String subsystems()");
        String reverse = method(application(gen.generateAll(List.of(graph, saga), "com.example.foundation")),
                "\n    protected String subsystems()");
        assertThat(forward).contains("return \"http,persistence,graph,flow,crypto\";").isEqualTo(reverse);
    }

    private static DomainMetadata orderWithEventsAndSaga() {
        return DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders")
                .fields(List.of(FieldMetadata.builder("total", "BigDecimal").build()))
                .events(List.of(eu.exeris.sdk.sourcemodel.ast.DomainEventMetadata.builder("OrderCreated")
                        .payloadFields(List.of("total")).build()))
                .sagaMetadata(eu.exeris.sdk.sourcemodel.ast.SagaMetadata.simple("OrderFulfillment"))
                .build();
    }

    @Test
    @DisplayName("K9: an app with no stream routes binds the same edge handler — one uniform "
            + "shape — and emits no stream-route check")
    void appWithoutStreamRoutesBindsTheSameEdgeHandler() {
        KernelApplicationGenerator gen = new KernelApplicationGenerator();
        DomainMetadata order = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders").build();
        String lifecycle = lifecycle(gen.generateAll(List.of(order), "com.example.foundation"));

        assertThat(lifecycle)
                .contains("public static HttpHandler edgeHandler(AtomicReference<HttpHandler> handlerSlot)")
                .contains("handlerSlot.set(handler);")
                .doesNotContain("streamRoute(")
                .doesNotContain("requireStreamRoute")
                .doesNotContain("import eu.exeris.kernel.spi.http.HttpStreamHandler");
    }

    @Test
    @DisplayName("K9: RuntimeLifecycle has one constructor, (handlerSlot, components)")
    void lifecycleHasOneConstructor() {
        KernelApplicationGenerator gen = new KernelApplicationGenerator();
        DomainMetadata order = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders").build();
        String lifecycle = lifecycle(gen.generateAll(List.of(order), "com.example.foundation"));

        assertThat(lifecycle)
                .contains("public RuntimeLifecycle(AtomicReference<HttpHandler> handlerSlot,\n"
                        + "            RuntimeComponents components)")
                .doesNotContain("AtomicReference<RuntimeComponents>");
    }

    /**
     * The body of the member whose declaration starts with {@code signature}: from there to the
     * next line that closes a class-level member (four-space indent). Enough for emitted code,
     * whose formatting JavaPoet fixes.
     */
    private static String method(String source, String signature) {
        int start = source.indexOf(signature);
        assertThat(start).as("member %s is emitted", signature).isGreaterThan(-1);
        int end = source.indexOf("\n    }\n", start);
        return source.substring(start, end);
    }

    @Test
    @DisplayName("T49: composition changes Application only — RuntimeComponents is byte-identical")
    void compositionLeavesTheRuntimeComponentsUntouched() {
        KernelApplicationGenerator gen = new KernelApplicationGenerator();
        List<DomainMetadata> domains = List.of(DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders").build());

        assertThat(components(gen.generateAll(domains, "com.example.foundation", true)))
                .isEqualTo(components(gen.generateAll(domains, "com.example.foundation", false)));
    }

    private static String components(List<GeneratedFile> files) {
        return files.stream().filter(f -> "RuntimeComponents".equals(f.className()))
                .findFirst().orElseThrow().content();
    }

    private static String application(List<GeneratedFile> files) {
        return files.stream().filter(f -> "Application".equals(f.className()))
                .findFirst().orElseThrow().content();
    }

    private static String lifecycle(List<GeneratedFile> files) {
        return files.stream().filter(f -> "RuntimeLifecycle".equals(f.className()))
                .findFirst().orElseThrow().content();
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
                // per-action stream handler taken from the RuntimeComponents seam (constructed
                // no-arg there),
                // taken on the boot thread
                .contains("components.orderTrackShipmentStreamHandler();")
                // registered via the typed streamRoute(...), POST, at the action path
                .contains("routerBuilder.streamRoute(HttpMethod.POST, "
                        + "\"/orders/{id}/actions/track-shipment\", orderTrackShipmentStreamHandler)")
                // non-streaming action keeps its respond-once route
                .contains("routerBuilder.route(HttpMethod.POST, \"/orders/{id}/actions/cancel\", "
                        + "orderHandler::handleCancel)");
        // the streaming action does NOT also get a respond-once route(...)
        assertThat(content)
                .doesNotContain("routerBuilder.route(HttpMethod.POST, "
                        + "\"/orders/{id}/actions/track-shipment\"");
    }

    @Test
    @DisplayName("T9: trailing FK migration adds ALTER TABLE … FOREIGN KEY for an in-set MANY_TO_ONE target, "
            + "skips an external (non-generated) target")
    void shouldEmitForeignKeyConstraintForGeneratedTargetAndSkipExternal() {
        KernelApplicationGenerator gen = new KernelApplicationGenerator();
        DomainMetadata order = DomainMetadata.builder("Order", "com.example.domain")
                .relationships(List.of(
                        // target Customer IS generated → constraint emitted.
                        RelationshipMetadata.builder("customer", "Customer")
                                .type(RelationshipMetadata.RelationType.MANY_TO_ONE).build(),
                        // target Warehouse is NOT in the domain set → skipped.
                        RelationshipMetadata.builder("warehouseId", "Warehouse")
                                .type(RelationshipMetadata.RelationType.MANY_TO_ONE).build(),
                        // ONE_TO_MANY never gets an FK on this side.
                        RelationshipMetadata.builder("items", "OrderItem")
                                .type(RelationshipMetadata.RelationType.ONE_TO_MANY).build()))
                .build();
        DomainMetadata customer = DomainMetadata.builder("Customer", "com.example.domain").build();

        GeneratedFile fk = gen.generateForeignKeys(List.of(order, customer));

        // It is a Flyway SQL migration, pinned to tier 3 so it sorts after every CREATE TABLE.
        assertThat(fk).isNotNull();
        assertThat(fk.artifactType()).isEqualTo(ArtifactType.CONFIGURATION);
        assertThat(fk.extension()).isEqualTo("sql");
        assertThat(fk.packageName()).isEqualTo("db/migration");
        assertThat(fk.className()).isEqualTo("V3000000__foreign_keys");

        assertThat(fk.content())
                // in-set target → constraint with correct table/col/target/policy.
                .contains("ALTER TABLE orders ADD CONSTRAINT fk_orders_customer_id "
                        + "FOREIGN KEY (customer_id) REFERENCES customers(id) ON DELETE RESTRICT;")
                // explicit-UUID-FK name normalisation (warehouse_id, not warehouse_id_id) — but skipped anyway.
                .doesNotContain("warehouse_id_id")
                // external target Warehouse is skipped — never reference a non-existent table.
                .doesNotContain("REFERENCES warehouses")
                .doesNotContain("fk_orders_warehouse_id")
                // ONE_TO_MANY emits nothing.
                .doesNotContain("order_item");
    }

    @Test
    @DisplayName("T9: ON DELETE policy follows cascade — CASCADE for ALL/REMOVE, RESTRICT otherwise")
    void shouldChooseDeletePolicyFromCascade() {
        KernelApplicationGenerator gen = new KernelApplicationGenerator();
        DomainMetadata order = DomainMetadata.builder("Order", "com.example.domain")
                .relationships(List.of(
                        RelationshipMetadata.builder("customer", "Customer")
                                .type(RelationshipMetadata.RelationType.MANY_TO_ONE)
                                .cascade(RelationshipMetadata.CascadeType.ALL).build(),
                        RelationshipMetadata.builder("invoice", "Invoice")
                                .type(RelationshipMetadata.RelationType.MANY_TO_ONE)
                                .cascade(RelationshipMetadata.CascadeType.REMOVE).build(),
                        RelationshipMetadata.builder("region", "Region")
                                .type(RelationshipMetadata.RelationType.MANY_TO_ONE)
                                .cascade(RelationshipMetadata.CascadeType.NONE).build()))
                .build();
        DomainMetadata customer = DomainMetadata.builder("Customer", "com.example.domain").build();
        DomainMetadata invoice = DomainMetadata.builder("Invoice", "com.example.domain").build();
        DomainMetadata region = DomainMetadata.builder("Region", "com.example.domain").build();

        String sql = gen.generateForeignKeys(List.of(order, customer, invoice, region)).content();
        assertThat(sql)
                .contains("fk_orders_customer_id FOREIGN KEY (customer_id) "
                        + "REFERENCES customers(id) ON DELETE CASCADE;")
                .contains("fk_orders_invoice_id FOREIGN KEY (invoice_id) "
                        + "REFERENCES invoices(id) ON DELETE CASCADE;")
                .contains("fk_orders_region_id FOREIGN KEY (region_id) "
                        + "REFERENCES regions(id) ON DELETE RESTRICT;");
    }

    @Test
    @DisplayName("T9: target table honours the target entity's tableName override (T6)")
    void shouldResolveTargetTableViaEffectiveTable() {
        KernelApplicationGenerator gen = new KernelApplicationGenerator();
        DomainMetadata order = DomainMetadata.builder("Order", "com.example.domain")
                .relationships(List.of(
                        RelationshipMetadata.builder("customer", "Customer")
                                .type(RelationshipMetadata.RelationType.MANY_TO_ONE).build()))
                .build();
        DomainMetadata customer = DomainMetadata.builder("Customer", "com.example.domain")
                .tableName("legacy_customers").build();

        String sql = gen.generateForeignKeys(List.of(order, customer)).content();
        assertThat(sql)
                .contains("REFERENCES legacy_customers(id)")
                .doesNotContain("REFERENCES customers(id)");
    }

    @Test
    @DisplayName("T9: no in-scope MANY_TO_ONE relationship → no FK migration (additive, returns null)")
    void shouldReturnNullWhenNoForeignKeys() {
        KernelApplicationGenerator gen = new KernelApplicationGenerator();
        DomainMetadata tag = DomainMetadata.builder("Tag", "com.example.domain")
                .fields(List.of(FieldMetadata.builder("label", "String").build()))
                .build();
        // Empty domain set and a relationship-free domain both yield null.
        assertThat(gen.generateForeignKeys(List.of())).isNull();
        assertThat(gen.generateForeignKeys(List.of(tag))).isNull();
    }

    @Test
    @DisplayName("T9: FK emission is deterministic — sorted by (table, constraint), byte-identical across runs")
    void foreignKeyEmissionIsDeterministic() {
        KernelApplicationGenerator gen = new KernelApplicationGenerator();
        DomainMetadata order = DomainMetadata.builder("Order", "com.example.domain")
                .relationships(List.of(
                        RelationshipMetadata.builder("warehouseId", "Warehouse")
                                .type(RelationshipMetadata.RelationType.MANY_TO_ONE).build(),
                        RelationshipMetadata.builder("customer", "Customer")
                                .type(RelationshipMetadata.RelationType.MANY_TO_ONE).build()))
                .build();
        DomainMetadata shipment = DomainMetadata.builder("Shipment", "com.example.domain")
                .relationships(List.of(
                        RelationshipMetadata.builder("order", "Order")
                                .type(RelationshipMetadata.RelationType.MANY_TO_ONE).build()))
                .build();
        DomainMetadata customer = DomainMetadata.builder("Customer", "com.example.domain").build();
        DomainMetadata warehouse = DomainMetadata.builder("Warehouse", "com.example.domain").build();
        List<DomainMetadata> domains = List.of(order, shipment, customer, warehouse);

        String first = gen.generateForeignKeys(domains).content();
        String second = gen.generateForeignKeys(domains).content();
        assertThat(second).isEqualTo(first);

        // Sorted by table first (orders before shipments), then constraint name
        // (fk_orders_customer_id before fk_orders_warehouse_id).
        assertThat(first.indexOf("fk_orders_customer_id"))
                .isLessThan(first.indexOf("fk_orders_warehouse_id"));
        assertThat(first.indexOf("fk_orders_warehouse_id"))
                .isLessThan(first.indexOf("fk_shipments_order_id"));
    }

    @Test
    @DisplayName("T9: the FK migration version sorts strictly after every CREATE TABLE migration (tiers 1 & 2)")
    void fkMigrationVersionSortsAfterCreateTables() {
        KernelApplicationGenerator gen = new KernelApplicationGenerator();
        KernelFlywayGenerator flyway = new KernelFlywayGenerator();

        DomainMetadata order = DomainMetadata.builder("Order", "com.example.domain")
                .tenantScoped(true) // tier 2 create-table
                .relationships(List.of(
                        RelationshipMetadata.builder("customer", "Customer")
                                .type(RelationshipMetadata.RelationType.MANY_TO_ONE).build()))
                .build();
        DomainMetadata customer = DomainMetadata.builder("Customer", "com.example.domain").build(); // tier 1

        long fkVersion = versionNumber(gen.generateForeignKeys(List.of(order, customer)).className());
        long orderCreate = versionNumber(flyway.generate(order).className());
        long customerCreate = versionNumber(flyway.generate(customer).className());

        assertThat(fkVersion).isGreaterThan(orderCreate);
        assertThat(fkVersion).isGreaterThan(customerCreate);
    }

    @Test
    @DisplayName("code examples in the emitted Javadoc are {@snippet} blocks, composed or not")
    void emittedJavadocExamplesAreSnippets() {
        KernelApplicationGenerator gen = new KernelApplicationGenerator();
        List<DomainMetadata> domains = List.of(
                DomainMetadata.builder("Order", "com.example.domain").path("/orders").build());

        for (boolean composed : new boolean[] {false, true}) {
            List<GeneratedFile> files = gen.generateAll(domains, "com.example.foundation", composed);

            assertThat(application(files)).contains("{@snippet :");
            assertThat(components(files)).contains("{@snippet :");
            assertThat(files).allSatisfy(file -> assertThat(file.content())
                    .doesNotContain("<pre>")
                    .doesNotContain("</pre>"));
        }
    }

    /** Extracts the numeric version from a {@code "V<n>__…"} migration filename. */
    private static long versionNumber(String className) {
        return Long.parseLong(className.substring(1, className.indexOf("__")));
    }
}
