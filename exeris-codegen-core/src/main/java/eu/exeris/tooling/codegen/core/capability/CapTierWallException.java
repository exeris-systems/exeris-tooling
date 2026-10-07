package eu.exeris.tooling.codegen.core.capability;

import java.util.List;

/**
 * Thrown when the cap-tier Wall is breached — ADR-024 validation predicate 4 (ADR-055).
 *
 * <p>Distinct from {@link CapabilityGraphException} on purpose: predicates 1–3 are
 * <em>composition</em> failures (this cap set does not fit together), while predicate 4
 * is a failure of <em>one cap in isolation</em> (this cap reaches somewhere it must not).
 * They have different audiences — the composition author versus the cap author — and a
 * shared exception type would blur that in the build output.
 *
 * <p>Carries every violation found rather than the first, so a cap author sees the whole
 * boundary problem in one build.
 *
 * @since 0.7
 */
public final class CapTierWallException extends RuntimeException {

    /**
     * Every violation found, in deterministic order. Non-transient so {@link #violations()}
     * survives serialization, as in {@link CapabilityGraphException}.
     */
    private final List<WallViolation> violations;

    /**
     * Creates the exception with a message listing every violation, one per line.
     *
     * @param violations the violations found, in deterministic order
     */
    public CapTierWallException(List<WallViolation> violations) {
        super("Cap-tier Wall violated (ADR-024 predicate 4) — "
                + violations.size() + " forbidden reference(s):\n  - "
                + String.join("\n  - ", violations.stream().map(WallViolation::message).toList()));
        this.violations = List.copyOf(violations);
    }

    /**
     * Returns the individual violations.
     *
     * @return the violations, unmodifiable, in deterministic order
     */
    public List<WallViolation> violations() {
        return violations;
    }
}
