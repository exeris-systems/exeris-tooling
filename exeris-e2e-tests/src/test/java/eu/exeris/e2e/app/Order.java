package eu.exeris.e2e.app;

import eu.exeris.sdk.annotation.ExerisDomain;
import eu.exeris.sdk.annotation.Field;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Sample Order entity for E2E testing.
 * This class is processed by Exeris annotation processor to generate:
 * - REST Controller
 * - Service Layer
 * - Repository
 * - DTOs
 */
@ExerisDomain(
    module = "sales",
    path = "/orders",
    description = "Order entity for E2E testing",
    tenantScoped = true,
    audited = true,
    softDelete = true
)
public class Order {

    @Field(label = "ID", required = true)
    private UUID id;

    @Field(label = "Order Number", required = true, unique = true, searchable = true)
    private String orderNumber;

    @Field(label = "Customer Name", required = true, searchable = true)
    private String customerName;

    @Field(label = "Amount", required = true)
    private BigDecimal amount;

    @Field(label = "Status", required = true, filterable = true)
    private OrderStatus status;

    @Field(label = "Tenant ID")
    private UUID tenantId;

    @Field(label = "Created At")
    private Instant createdAt;

    @Field(label = "Updated At")
    private Instant updatedAt;

    // Constructors
    public Order() {}

    public Order(String orderNumber, String customerName, BigDecimal amount) {
        this.id = UUID.randomUUID();
        this.orderNumber = orderNumber;
        this.customerName = customerName;
        this.amount = amount;
        this.status = OrderStatus.PENDING;
        this.createdAt = Instant.now();
    }

    // Getters and Setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getOrderNumber() { return orderNumber; }
    public void setOrderNumber(String orderNumber) { this.orderNumber = orderNumber; }

    public String getCustomerName() { return customerName; }
    public void setCustomerName(String customerName) { this.customerName = customerName; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public OrderStatus getStatus() { return status; }
    public void setStatus(OrderStatus status) { this.status = status; }

    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public enum OrderStatus {
        PENDING, CONFIRMED, SHIPPED, DELIVERED, CANCELLED
    }
}

