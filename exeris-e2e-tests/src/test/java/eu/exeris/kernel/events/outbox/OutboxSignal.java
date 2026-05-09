package eu.exeris.kernel.events.outbox;

/**
 * Test stub for the outbox-signal SPI. Generated publishers call
 * {@link #signalNewData()} after appending events to the store.
 */
public interface OutboxSignal {

    void signalNewData();
}
