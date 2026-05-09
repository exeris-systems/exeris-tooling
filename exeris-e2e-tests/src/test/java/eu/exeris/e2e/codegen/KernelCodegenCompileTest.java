package eu.exeris.e2e.codegen;

import eu.exeris.e2e.codegen.compile.InMemoryJavaCompiler;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.FieldMetadata;
import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import eu.exeris.tooling.codegen.java.kernel.KernelGeneratorStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Compile-test gate: feeds a representative {@link DomainMetadata} through the
 * Kernel generator strategy and runs {@code javac} over the union of generated
 * sources + the source domain entity, against the kernel SPI stubs sitting on
 * the test classpath. Substring assertions in {@link KernelCodegenE2ETest}
 * cannot catch broken imports or referenced symbols that no longer exist —
 * this test does.
 */
@Tag("e2e")
@Tag("codegen")
@Tag("compile")
@DisplayName("Kernel Codegen Compile Gate")
class KernelCodegenCompileTest {

    private static final String DOMAIN_PACKAGE = "eu.exeris.e2e.compileapp.domain";
    private static final String ENTITY_NAME = "Order";

    @Test
    @DisplayName("Generated kernel artifacts compile against kernel SPI stubs")
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
                                .build()))
                .build();

        List<GeneratedFile> generated = new KernelGeneratorStrategy().generate(metadata);

        InMemoryJavaCompiler compiler = new InMemoryJavaCompiler()
                .addSource(DOMAIN_PACKAGE + "." + ENTITY_NAME, sourceEntity());

        for (GeneratedFile file : generated) {
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
}
