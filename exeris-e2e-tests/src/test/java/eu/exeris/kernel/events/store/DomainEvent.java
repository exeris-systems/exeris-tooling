package eu.exeris.kernel.events.store;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Test stub for the kernel domain event envelope. Generated event publishers
 * assemble instances via the fluent {@link #builder()} API; generated event
 * handlers read fields via the accessor methods.
 */
public final class DomainEvent {

    private DomainEvent() {
    }

    public static Builder builder() {
        return new Builder();
    }

    public String aggregateType() { return null; }
    public String eventType() { return null; }
    public UUID aggregateId() { return null; }
    public UUID tenantId() { return null; }
    public Map<String, Object> payload() { return null; }

    public static final class Builder {
        public Builder eventId(UUID v) { return this; }
        public Builder streamId(UUID v) { return this; }
        public Builder aggregateType(String v) { return this; }
        public Builder aggregateId(UUID v) { return this; }
        public Builder eventType(String v) { return this; }
        public Builder payload(Map<String, Object> v) { return this; }
        public Builder tenantId(UUID v) { return this; }
        public Builder occurredAt(Instant v) { return this; }
        public DomainEvent build() { return new DomainEvent(); }
    }
}
