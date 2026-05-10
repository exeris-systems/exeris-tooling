package eu.exeris.kernel.flow.model;

/**
 * Test stub for the saga snapshot record. Generated step handlers read the
 * opaque JSON state via {@link #opaqueState()} and use {@link #sagaIdMost()}
 * for log correlation.
 */
public final class SagaSnapshot {

    private final long sagaIdMost;
    private final byte[] opaqueState;

    public SagaSnapshot(long sagaIdMost, byte[] opaqueState) {
        this.sagaIdMost = sagaIdMost;
        this.opaqueState = opaqueState;
    }

    public long sagaIdMost() {
        return sagaIdMost;
    }

    public byte[] opaqueState() {
        return opaqueState;
    }
}
