package eu.exeris.tooling.codegen.maven;

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

    public DetachResult {
        moved = List.copyOf(moved);
        conflicts = List.copyOf(conflicts);
    }

    /** {@code true} when no files were promoted (nothing to detach, or all conflicted). */
    public boolean isEmpty() {
        return moved.isEmpty();
    }
}
