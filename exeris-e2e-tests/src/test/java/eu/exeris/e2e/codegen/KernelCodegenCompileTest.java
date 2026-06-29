package eu.exeris.e2e.codegen;

import eu.exeris.e2e.codegen.compile.InMemoryJavaCompiler;
import eu.exeris.sdk.sourcemodel.ast.ActionMetadata;
import eu.exeris.sdk.sourcemodel.ast.ActionParamMetadata;
import eu.exeris.sdk.sourcemodel.ast.DomainEventMetadata;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.FieldMetadata;
import eu.exeris.sdk.sourcemodel.ast.GraphEdgeMetadata;
import eu.exeris.sdk.sourcemodel.ast.GraphMetadata;
import eu.exeris.sdk.sourcemodel.ast.RelationshipMetadata;
import eu.exeris.sdk.sourcemodel.ast.SagaMetadata;
import eu.exeris.sdk.sourcemodel.ast.SagaStepMetadata;
import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import eu.exeris.tooling.codegen.java.kernel.KernelApplicationGenerator;
import eu.exeris.tooling.codegen.java.kernel.KernelGeneratorStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Compile-test gate: feeds a representative {@link DomainMetadata} through the
 * Kernel generator strategy and runs {@code javac} over the union of generated
 * sources + the source domain entity. Substring assertions in
 * {@link KernelCodegenE2ETest} cannot catch broken imports or referenced symbols
 * that no longer exist — this test does.
 *
 * <p>The strategy registers: Handler, Service, Repository, Event,
 * EventHandler, GraphSync, Saga, Flyway, OpenAPI, Client, StreamHandler. Generated
 * Java imports {@code eu.exeris.kernel.spi.http.*},
 * {@code eu.exeris.kernel.spi.memory.*}, {@code eu.exeris.kernel.spi.events.*},
 * {@code eu.exeris.kernel.spi.graph.*}, {@code eu.exeris.kernel.spi.flow.*},
 * (ADR-036) {@code spi.http.HttpRequestBodyDecoder*} / {@code HttpRequestDecodingContext},
 * and (ADR-043) the streaming SPI {@code spi.http.HttpStreamHandler} /
 * {@code HttpStreamExchange} / {@code StreamEvent}; the real
 * {@code exeris-kernel-spi:0.10.0-SNAPSHOT} artifact (plus Jackson 3) is on
 * the test classpath via {@code exeris-tooling-bom}. The emitted {@code *Client}
 * binds the tier-neutral {@code eu.exeris.kernel.core.http.client.KernelWebClient}
 * facade (ADR-034), stood in at that FQN by a test stub in this module so the
 * gate compiles without pulling kernel-core.
 */
@Tag("e2e")
@Tag("codegen")
@Tag("compile")
@DisplayName("Kernel Codegen Compile Gate")
class KernelCodegenCompileTest {

    private static final String DOMAIN_PACKAGE = "eu.exeris.e2e.compileapp.domain";
    private static final String ENTITY_NAME = "Order";

    @Test
    @DisplayName("Generated kernel artifacts compile (full registered set incl. Client)")
    void generatedArtifactsCompile() {
        DomainMetadata metadata = DomainMetadata.builder(ENTITY_NAME, DOMAIN_PACKAGE)
                .path("/orders")
                .module("sales")
                .description("Compile-test order entity")
                .tenantScoped(true)
                .audited(true)
                .softDelete(true)
                // ADR-043 Slice 1: drives KernelStreamHandlerGenerator + the
                // Application generator's streamRoute(GET, "/orders/stream", ...)
                // registration, so the gate compiles the SSE handler against the
                // real kernel 0.10 streaming SPI (HttpStreamHandler /
                // HttpStreamExchange / StreamEvent).
                .realTimeApi(true)
                .fields(List.of(
                        // T22: a validated field literally named `id`. The handler's
                        // validation guard reads each validated field into a local; if
                        // that local were the bare field name it would emit
                        // `var id = entity.getId()` and clash with handleUpdate's path-id
                        // `UUID id` — uncompilable. This field makes javac the regression
                        // guard for the prefixed-local fix.
                        FieldMetadata.builder("id", "java.util.UUID")
                                .required(true)
                                .build(),
                        // T10: validation rules exercise the handler's server-side
                        // validation guard across every emitted shape — required
                        // null-check, String minLength/maxLength/pattern, and numeric
                        // (BigDecimal) min/max — so the generated checks must compile
                        // against the real getters.
                        FieldMetadata.builder("orderNumber", "String")
                                .required(true)
                                .unique(true)
                                .searchable(true)
                                .minLength(3)
                                .pattern("[A-Z0-9-]+")
                                .build(),
                        FieldMetadata.builder("customerName", "String")
                                .required(true)
                                .searchable(true)
                                .maxLength(120)
                                .build(),
                        FieldMetadata.builder("amount", "BigDecimal")
                                .required(true)
                                .min(0L)
                                .max(1000000L)
                                .build(),
                        FieldMetadata.builder("tags", "List<java.util.UUID>")
                                .build(),
                        // T19b: a LocalDateTime field. The Repository generator must
                        // bridge it through the native getInstant/bindInstant SPI at the
                        // UTC offset — the kernel has no typed LocalDateTime accessor, so
                        // emitting row.getInstant() straight into a LocalDateTime setter
                        // would not compile. This makes javac the regression guard.
                        FieldMetadata.builder("scheduledFor", "java.time.LocalDateTime")
                                .build(),
                        // Enum field: exercises the Repository generator's
                        // fall-through emit path. Must be FQCN so JavaPoet
                        // can inject the matching import.
                        //
                        // T8: marked filterable so the Repository + Service
                        // generators emit findByStatus(OrderStatus) — compiling the
                        // enum-typed finder param + the null-guarded String bind
                        // against the real entity getter and kernel SPI.
                        FieldMetadata.builder("status",
                                        DOMAIN_PACKAGE + ".OrderStatus")
                                .filterable(true)
                                .build(),
                        // EV1/B3: a primitive boolean payload field exercises the
                        // `isX()` accessor in the generated publisher — the gate
                        // javac-compiles entity.isExpedited() against the entity.
                        FieldMetadata.builder("expedited", "boolean")
                                .build()))
                // T8: a MANY_TO_ONE relationship drives the FK finder
                // findByCustomerId(UUID) on the Repository + Service — javac is the
                // regression guard for the generated WHERE customer_id = ? lookup +
                // bindUuid against the kernel persistence SPI.
                .relationships(List.of(
                        RelationshipMetadata.builder("customer", "Customer")
                                .type(RelationshipMetadata.RelationType.MANY_TO_ONE)
                                .build()))
                // T1: actions exercise the server-side dispatch generator end-to-end:
                // a no-arg action, a params action (→ generated request record decoded
                // via the codec SPI), and one whose @Action(name) differs from the JVM
                // method (methodName drives the invocation, name drives the route/URL).
                .actions(List.of(
                        ActionMetadata.builder("cancel").methodName("cancel").build(),
                        ActionMetadata.builder("applyDiscount").methodName("applyDiscount")
                                .params(List.of(
                                        ActionParamMetadata.required("percent", "java.math.BigDecimal"),
                                        ActionParamMetadata.required("reason", "java.lang.String")))
                                .build(),
                        ActionMetadata.builder("markUrgent").methodName("flagUrgent").build(),
                        // ADR-044 Slice 2: a @Action(streaming=true, streamEventType=…)
                        // action drives KernelActionStreamHandlerGenerator
                        // (OrderTrackShipmentStreamHandler) + the Application
                        // generator's streamRoute(POST, "/orders/{id}/actions/
                        // track-shipment", ...) registration, so the gate javac-
                        // compiles the per-action SSE handler against the real
                        // kernel 0.10 streaming SPI.
                        ActionMetadata.builder("trackShipment").methodName("trackShipment")
                                .streaming(true)
                                .streamEventType("ShipmentMoved")
                                .build()))
                .events(List.of(
                        DomainEventMetadata.simple("OrderCreated"),
                        DomainEventMetadata.withTopic("OrderShipped", "orders.shipped"),
                        // EV1 (ADR-046): an event WITH payloadFields drives the
                        // codec-resolved publish path — the generated publisher emits a
                        // redacted <Event>Payload record (customerName is sensitive →
                        // dropped) and resolves EventPayloadCodec via
                        // KernelProviders.eventPayloadCodecRegistry(), so the gate javac-
                        // compiles it against the real kernel codec SPI + jdk.jfr.
                        DomainEventMetadata.builder("OrderPlaced")
                                .payloadFields(List.of("amount", "orderNumber", "customerName", "expedited"))
                                .sensitiveFields(List.of("customerName"))
                                .build()))
                .graphMetadata(new GraphMetadata("Order", List.of(),
                        List.of(new GraphEdgeMetadata("tenantId", "Tenant", "OWNED_BY")),
                        List.of()))
                .sagaMetadata(SagaMetadata.builder("OrderFulfillment")
                        .timeout("PT45M")
                        .maxRetries(5)
                        .steps(List.of(
                                SagaStepMetadata.builder("reserve-inventory", 0)
                                        .compensation("restoreInventory")
                                        .build(),
                                SagaStepMetadata.simple("send-email", 1, null)))
                        .build())
                .build();

        List<GeneratedFile> generated = new KernelGeneratorStrategy().generate(metadata);

        // Application + RuntimeLifecycle are project-wide; not in the
        // strategy. Run the Application generator separately so the
        // compile-gate verifies the full bootstrap stack resolves
        // against the real exeris-kernel-spi and -core artifacts.
        String basePackage = DOMAIN_PACKAGE.replace(".domain", "");
        List<GeneratedFile> applicationFiles =
                new KernelApplicationGenerator().generateAll(List.of(metadata), basePackage);

        InMemoryJavaCompiler compiler = new InMemoryJavaCompiler()
                .addSource(DOMAIN_PACKAGE + "." + ENTITY_NAME, sourceEntity())
                .addSource(DOMAIN_PACKAGE + ".OrderStatus", sourceStatusEnum());

        for (GeneratedFile file : generated) {
            if ("java".equals(file.extension())) {
                compiler.addSource(file.packageName() + "." + file.className(), file.content());
            }
        }
        for (GeneratedFile file : applicationFiles) {
            if ("java".equals(file.extension())) {
                compiler.addSource(file.packageName() + "." + file.className(), file.content());
            }
        }

        InMemoryJavaCompiler.Result result = compiler.compile();
        assertThat(result.success())
                .as("javac output:%n%s", result.renderErrors())
                .isTrue();
    }

    private static String sourceEntity() {
        return """
                package %s;

                import java.math.BigDecimal;
                import java.time.Instant;
                import java.time.LocalDateTime;
                import java.util.List;
                import java.util.UUID;

                public class %s {

                    private UUID id;
                    private String orderNumber;
                    private String customerName;
                    private BigDecimal amount;
                    private List<UUID> tags;
                    private LocalDateTime scheduledFor;
                    private OrderStatus status;
                    private UUID tenantId;
                    private Instant createdAt;
                    private Instant updatedAt;
                    private boolean deleted;
                    private boolean expedited;

                    public UUID getId() { return id; }
                    public void setId(UUID id) { this.id = id; }

                    public String getOrderNumber() { return orderNumber; }
                    public void setOrderNumber(String orderNumber) { this.orderNumber = orderNumber; }

                    public String getCustomerName() { return customerName; }
                    public void setCustomerName(String customerName) { this.customerName = customerName; }

                    public BigDecimal getAmount() { return amount; }
                    public void setAmount(BigDecimal amount) { this.amount = amount; }

                    public List<UUID> getTags() { return tags; }
                    public void setTags(List<UUID> tags) { this.tags = tags; }

                    public LocalDateTime getScheduledFor() { return scheduledFor; }
                    public void setScheduledFor(LocalDateTime scheduledFor) { this.scheduledFor = scheduledFor; }

                    public OrderStatus getStatus() { return status; }
                    public void setStatus(OrderStatus status) { this.status = status; }

                    public boolean isExpedited() { return expedited; }
                    public void setExpedited(boolean expedited) { this.expedited = expedited; }

                    public UUID getTenantId() { return tenantId; }
                    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }

                    public Instant getCreatedAt() { return createdAt; }
                    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

                    public Instant getUpdatedAt() { return updatedAt; }
                    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

                    public boolean isDeleted() { return deleted; }
                    public void setDeleted(boolean deleted) { this.deleted = deleted; }

                    // @Action methods — invoked by the generated action handlers (T1).
                    public void cancel() { this.status = OrderStatus.CANCELLED; }
                    public void applyDiscount(BigDecimal percent, String reason) {
                        if (percent != null) { this.amount = this.amount.subtract(percent); }
                    }
                    public void flagUrgent() { /* @Action(name="markUrgent") */ }
                    // @Action(streaming=true) — served by the per-action stream
                    // handler via streamRoute; the handler generator emits NO
                    // respond-once handle method for it (ADR-044 Slice 2).
                    public void trackShipment() { /* ADR-044 Slice 2 streaming action */ }
                }
                """.formatted(DOMAIN_PACKAGE, ENTITY_NAME);
    }

    private static String sourceStatusEnum() {
        return """
                package %s;

                public enum OrderStatus {
                    PENDING, CONFIRMED, SHIPPED, CANCELLED;
                }
                """.formatted(DOMAIN_PACKAGE);
    }
}
