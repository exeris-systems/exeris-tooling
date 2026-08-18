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
        // No per-entity wiring (no handler locals, no routes).
        assertThat(lifecycle)
                .doesNotContain("components.orderHandler()")
                .doesNotContain("routerBuilder.route")
                .contains("HttpRouter.Builder routerBuilder = HttpRouter.builder()")
                .contains("HttpRouter router = routerBuilder.build()")
                // The count is known at generation time, so it is baked into the literal rather
                // than left as a System.Logger parameter.
                .contains("Application bootstrap complete: 0 entities wired");
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
                .contains("exchange.respond(HttpStatus.SERVICE_UNAVAILABLE)")
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
    @DisplayName("G2: a composed build conducts the composition inside boot(...) — caps ready "
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
                .contains("try (CompositionConductor conductor = CompositionConductor.from(capManifest()).start())")
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
    @DisplayName("G2: no composition → not one conductor symbol is emitted (no inert wiring), "
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
    @DisplayName("G2: composition changes Application only — RuntimeLifecycle is byte-identical")
    void compositionLeavesTheRuntimeLifecycleUntouched() {
        KernelApplicationGenerator gen = new KernelApplicationGenerator();
        List<DomainMetadata> domains = List.of(DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders").build());

        assertThat(lifecycle(gen.generateAll(domains, "com.example.foundation", true)))
                .isEqualTo(lifecycle(gen.generateAll(domains, "com.example.foundation", false)));
    }

    @Test
    @DisplayName("G2: composed emission is deterministic — byte-identical across runs")
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
                .contains("return new OrderHandler(orderService())")
                // Memoisation, so an accessor is safe to call from an override.
                .contains("if (orderRepository == null) {")
                .contains("orderRepository = createOrderRepository();");
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
                .contains("return new OrderStreamHandler()")
                .contains("protected OrderTrackShipmentStreamHandler "
                        + "createOrderTrackShipmentStreamHandler()")
                .contains("return new OrderTrackShipmentStreamHandler()");

        assertThat(lifecycle(files))
                .contains("OrderStreamHandler orderStreamHandler = components.orderStreamHandler()")
                .contains("OrderTrackShipmentStreamHandler orderTrackShipmentStreamHandler = "
                        + "components.orderTrackShipmentStreamHandler()")
                // The lifecycle calls `new` on nothing the pipeline generated.
                .doesNotContain("= new Order");
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
                // per-action stream handler taken from the T49 seam (constructed no-arg there)
                .contains("OrderTrackShipmentStreamHandler orderTrackShipmentStreamHandler = "
                        + "components.orderTrackShipmentStreamHandler()")
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

    /** Extracts the numeric version from a {@code "V<n>__…"} migration filename. */
    private static long versionNumber(String className) {
        return Long.parseLong(className.substring(1, className.indexOf("__")));
    }
}
