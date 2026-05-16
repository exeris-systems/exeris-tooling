package eu.exeris.e2e.codegen;

import eu.exeris.e2e.codegen.compile.InMemoryJavaCompiler;
import eu.exeris.sdk.sourcemodel.ast.DomainEventMetadata;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.FieldMetadata;
import eu.exeris.sdk.sourcemodel.ast.GraphEdgeMetadata;
import eu.exeris.sdk.sourcemodel.ast.GraphMetadata;
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
 * <p>The strategy currently registers the SPI-aligned subset: Handler,
 * Service, Repository, Event, EventHandler, GraphSync, Saga, Flyway,
 * OpenAPI. Generated Java imports {@code eu.exeris.kernel.spi.http.*},
 * {@code eu.exeris.kernel.spi.memory.*}, {@code eu.exeris.kernel.spi.events.*},
 * {@code eu.exeris.kernel.spi.graph.*}, and {@code eu.exeris.kernel.spi.flow.*};
 * the real {@code exeris-kernel-spi:0.7.0} artifact (plus Jackson 3) is on
 * the test classpath via {@code exeris-tooling-bom}.
 */
@Tag("e2e")
@Tag("codegen")
@Tag("compile")
@DisplayName("Kernel Codegen Compile Gate")
class KernelCodegenCompileTest {

    private static final String DOMAIN_PACKAGE = "eu.exeris.e2e.compileapp.domain";
    private static final String ENTITY_NAME = "Order";

    @Test
    @DisplayName("Generated kernel artifacts compile (SPI-aligned subset)")
    void generatedArtifactsCompile() {
        DomainMetadata metadata = DomainMetadata.builder(ENTITY_NAME, DOMAIN_PACKAGE)
                .path("/orders")
                .module("sales")
                .description("Compile-test order entity")
                .tenantScoped(true)
                .audited(true)
                .softDelete(true)
                .fields(List.of(
                        FieldMetadata.builder("orderNumber", "String")
                                .required(true)
                                .unique(true)
                                .searchable(true)
                                .build(),
                        FieldMetadata.builder("customerName", "String")
                                .required(true)
                                .searchable(true)
                                .build(),
                        FieldMetadata.builder("amount", "BigDecimal")
                                .required(true)
                                .build(),
                        FieldMetadata.builder("tags", "List<java.util.UUID>")
                                .build(),
                        // Enum field: exercises the Repository generator's
                        // fall-through emit path. Must be FQCN so JavaPoet
                        // can inject the matching import.
                        FieldMetadata.builder("status",
                                        DOMAIN_PACKAGE + ".OrderStatus")
                                .build()))
                .events(List.of(
                        DomainEventMetadata.simple("OrderCreated"),
                        DomainEventMetadata.withTopic("OrderShipped", "orders.shipped")))
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
                import java.util.List;
                import java.util.UUID;

                public class %s {

                    private UUID id;
                    private String orderNumber;
                    private String customerName;
                    private BigDecimal amount;
                    private List<UUID> tags;
                    private OrderStatus status;
                    private UUID tenantId;
                    private Instant createdAt;
                    private Instant updatedAt;
                    private boolean deleted;

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

                    public OrderStatus getStatus() { return status; }
                    public void setStatus(OrderStatus status) { this.status = status; }

                    public UUID getTenantId() { return tenantId; }
                    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }

                    public Instant getCreatedAt() { return createdAt; }
                    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

                    public Instant getUpdatedAt() { return updatedAt; }
                    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

                    public boolean isDeleted() { return deleted; }
                    public void setDeleted(boolean deleted) { this.deleted = deleted; }
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
