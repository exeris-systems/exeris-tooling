package eu.exeris.kernel.security.context;

import java.util.UUID;

/**
 * Test stub for the kernel tenant binding. Generated event publishers read
 * {@link #tenantId()} when assembling the outbox payload.
 */
public final class TenantContext {

    private final UUID tenantId;

    public TenantContext(UUID tenantId) {
        this.tenantId = tenantId;
    }

    public UUID tenantId() {
        return tenantId;
    }
}
