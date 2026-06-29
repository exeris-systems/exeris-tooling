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
