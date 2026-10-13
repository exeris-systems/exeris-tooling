package eu.exeris.tooling.initializr.model;

import java.util.Arrays;
import java.util.Optional;

/**
 * The shapes of an Exeris application the initializr writes: Amendment 1's shapes 1 and 3 of
 * ADR-091. Shape 2, a frontend with no backend of its own, is not written (ADR-091 Amendment 5).
 *
 * @since 0.10
 */
public enum ProjectTopology {

    /**
     * A backend: one Maven project on {@code exeris-app-parent} and {@code exeris-app-starter}, with
     * one {@code @ExerisDomain} entity. Amendment 1, shape 1.
     */
    BACKEND_ONLY("backend-only"),

    /**
     * A backend under {@code backend/} and, under {@code frontend/}, an npm project that runs the
     * TypeScript emitter on the backend build's own metadata. Amendment 1, shape 3, in its same-build
     * form.
     */
    E2E("e2e");

    private final String cliValue;

    ProjectTopology(String cliValue) {
        this.cliValue = cliValue;
    }

    /**
     * Returns the value that selects this topology on the command line ({@code --topology=<value>}).
     *
     * @return the lower-case, hyphenated value, for example {@code backend-only}
     * @since 0.10
     */
    public String cliValue() {
        return cliValue;
    }

    /**
     * Finds the topology a command-line value names, matched exactly.
     *
     * @param value the value given to {@code --topology}
     * @return the topology, or empty when {@code value} names none
     * @since 0.10
     */
    public static Optional<ProjectTopology> fromCliValue(String value) {
        return Arrays.stream(values()).filter(t -> t.cliValue.equals(value)).findFirst();
    }
}
