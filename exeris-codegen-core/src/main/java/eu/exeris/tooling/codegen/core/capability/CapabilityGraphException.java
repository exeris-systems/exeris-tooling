package eu.exeris.tooling.codegen.core.capability;

import java.util.List;

/**
 * Thrown when the capability graph cannot be resolved: an unsatisfied
 * non-optional {@code @Requires}, a version-range mismatch, or a dependency
 * cycle. Carries every problem found (not just the first) so a build failure
 * surfaces the whole picture at once.
 *
 * @since 0.5.0
 */
public final class CapabilityGraphException extends RuntimeException {

    // Non-transient so problems() is retained if this exception is ever serialized.
    // RuntimeException is Serializable; the List.copyOf instance is serializable on
    // the JDK in practice — this class declares no formal Serializable contract.
    private final List<String> problems;

    public CapabilityGraphException(List<String> problems) {
        super("Capability graph could not be resolved:\n  - " + String.join("\n  - ", problems));
        this.problems = List.copyOf(problems);
    }

    /** The individual problems, in deterministic order. */
    public List<String> problems() {
        return problems;
    }
}
