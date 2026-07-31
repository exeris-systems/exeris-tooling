package eu.exeris.e2e.caps;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Ordered event log the G3 sample caps write to as the conductor drives them.
 *
 * <p>Lives on the <b>test</b> classpath on purpose. The sample caps are compiled into a temp
 * directory and loaded by a child {@code URLClassLoader}; parent-first delegation means those
 * cap classes link against <em>this</em> class, so the static log they append to is the same
 * one the assertions read. A recorder compiled alongside the caps would be a different class
 * in a different loader and would silently record into the void.
 *
 * <p>Thread-safe because the conductor runs {@code drain} on its own thread
 * ({@code DrainRunner.production()}), so the writes are not all on the test thread.
 */
public final class CapLifecycleRecorder {

    private static final List<String> EVENTS = new CopyOnWriteArrayList<>();

    private CapLifecycleRecorder() {
    }

    /** Appends one lifecycle event, e.g. {@code "vault:initialize"}. */
    public static void record(String event) {
        EVENTS.add(event);
    }

    /** The events recorded so far, in call order. */
    public static List<String> events() {
        return List.copyOf(EVENTS);
    }

    public static void reset() {
        EVENTS.clear();
    }
}
