package eu.exeris.tooling.codegen.core.capability;

import java.util.List;

/**
 * Thrown when the capability graph cannot be resolved: an unsatisfied
 * non-optional {@code @Requires}, a version-range mismatch, or a dependency
 * cycle. Carries every problem found (not just the first) so a build failure
 * surfaces the whole picture at once.
 *
 * @since 0.5
 */
public final class CapabilityGraphException extends RuntimeException {

    /**
     * Every problem found, in deterministic order. Non-transient so {@link #problems()} is
     * retained if this exception is serialized; the class declares no formal
     * {@code Serializable} contract beyond the one {@code RuntimeException} carries.
     */
    private final List<String> problems;

    /**
     * Creates the exception with a message listing every problem, one per line.
     *
     * @param problems the problems found, in deterministic order; must not be empty
     */
    public CapabilityGraphException(List<String> problems) {
        super("Capability graph could not be resolved:\n  - " + String.join("\n  - ", problems));
        this.problems = List.copyOf(problems);
    }

    /**
     * Returns the individual problems, each a one-line description.
     *
     * @return the problems, unmodifiable, in deterministic order
     */
    public List<String> problems() {
        return problems;
    }
}
