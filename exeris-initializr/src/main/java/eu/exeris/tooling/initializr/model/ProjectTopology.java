package eu.exeris.tooling.initializr.model;

import java.util.Arrays;
import java.util.Optional;

/**
 * The three shapes an Exeris application takes (ADR-091 Amendment 1), as the initializr writes them.
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
    E2E("e2e"),

    /**
     * An npm project that runs the TypeScript emitter on a backend's published contract artifact
     * (ADR-048), with no Java of its own. Amendment 1, shape 2.
     */
    FRONTEND_ONLY("frontend-only");

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
     * Returns whether the project carries a Maven backend.
     *
     * @return {@code true} for {@link #BACKEND_ONLY} and {@link #E2E}
     * @since 0.10
     */
    public boolean hasBackend() {
        return this != FRONTEND_ONLY;
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
