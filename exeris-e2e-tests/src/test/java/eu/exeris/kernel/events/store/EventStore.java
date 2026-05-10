package eu.exeris.kernel.events.store;

import java.util.List;
import java.util.UUID;

/**
 * Test stub for the kernel event store SPI. Generated publishers append events
 * via {@link #append} and read the current stream version via
 * {@link #getStreamVersion}.
 */
public interface EventStore {

    long getStreamVersion(UUID aggregateId, UUID tenantId);

    void append(UUID aggregateId, long expectedVersion, List<DomainEvent> events);
}
