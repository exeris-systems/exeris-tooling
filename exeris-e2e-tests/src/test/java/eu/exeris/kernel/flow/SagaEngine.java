package eu.exeris.kernel.flow;

import eu.exeris.kernel.flow.model.SagaDefinition;
import eu.exeris.kernel.flow.model.SagaSnapshot;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Test stub for the saga engine SPI. Generated saga registrars call
 * {@link #register(SagaDefinition)} on construction; {@code start} kicks off
 * a saga instance and {@code getSnapshot} is the read-side lookup.
 */
public interface SagaEngine {

    void register(SagaDefinition definition);

    UUID start(String sagaName, UUID sagaId, Map<String, String> context);

    Optional<SagaSnapshot> getSnapshot(UUID sagaId);
}
