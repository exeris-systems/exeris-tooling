package eu.exeris.tooling.codegen.maven.internal;

import java.nio.file.Path;
import java.util.List;

/**
 * Outcome of an {@link DetachService#detach} run.
 *
 * @param moved            relative paths (under the target root) of files
 *                         promoted from generated → owned source
 * @param conflicts        relative paths that already existed at the target and
 *                         were therefore <b>not</b> overwritten (the generated
 *                         copy is left in place for the user to reconcile)
 * @param gitignoreUpdated {@code true} if a generated-output entry was removed
 *                         from {@code .gitignore}
 */
public record DetachResult(List<Path> moved, List<Path> conflicts, boolean gitignoreUpdated) {

    /**
     * Creates a result holding unmodifiable copies of both path lists.
     *
     * @throws NullPointerException if {@code moved} or {@code conflicts}, or any element of
     *         either, is {@code null}
     */
    public DetachResult {
        moved = List.copyOf(moved);
        conflicts = List.copyOf(conflicts);
    }

    /**
     * Reports whether the run promoted nothing, either because there was nothing to detach or
     * because every file conflicted.
     *
     * @return {@code true} when no file was promoted
     */
    public boolean isEmpty() {
        return moved.isEmpty();
    }
}
