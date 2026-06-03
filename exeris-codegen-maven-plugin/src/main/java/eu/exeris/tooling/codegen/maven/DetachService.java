package eu.exeris.tooling.codegen.maven;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * L2 detachment logic, factored out of {@link DetachMojo} so it is unit-testable
 * without a Maven session (the mojo is a thin shell, mirroring
 * {@code CodegenMain} → {@code CodegenPipeline}).
 *
 * <p>"Detach" promotes generated sources from the regenerated tree
 * ({@code src/main/generated/java}, L1) into the owned source tree
 * ({@code src/main/java}, L2), then prunes the now-empty generated tree and
 * removes its {@code .gitignore} entry so the promoted files are tracked. After
 * a detach the user owns the code and drops the {@code exeris:generate}
 * execution — regeneration no longer overwrites it.
 *
 * <h2>Idempotency &amp; safety</h2>
 * <ul>
 *   <li>A missing or empty generated root is a no-op (re-running detach is safe).</li>
 *   <li>A file that already exists at the target is reported as a <em>conflict</em>
 *       and left in place — the user's owned copy is never overwritten.</li>
 *   <li>The operation moves byte-for-byte; it does not re-emit or reformat.</li>
 * </ul>
 */
public class DetachService {

    /**
     * Promotes every regular file under {@code generatedRoot} into
     * {@code targetRoot}, preserving the relative (package) structure, and
     * optionally strips {@code ignoreEntry} from {@code gitignore}.
     *
     * @param generatedRoot the L1 generated-source root (e.g. {@code src/main/generated/java}); may not exist
     * @param targetRoot    the owned-source root (e.g. {@code src/main/java}); created if absent
     * @param gitignore     the {@code .gitignore} to clean, or {@code null} to skip
     * @param ignoreEntry   the line to remove from {@code .gitignore} (trimmed, slash-insensitive), or {@code null}
     * @return a {@link DetachResult} describing what moved, what conflicted, and whether {@code .gitignore} changed
     * @throws IOException on filesystem failure
     */
    public DetachResult detach(Path generatedRoot, Path targetRoot, Path gitignore, String ignoreEntry)
            throws IOException {
        List<Path> moved = new ArrayList<>();
        List<Path> conflicts = new ArrayList<>();

        if (generatedRoot != null && Files.isDirectory(generatedRoot)) {
            movePromotableFiles(generatedRoot, targetRoot, moved, conflicts);
            pruneEmptyDirs(generatedRoot);
        }

        boolean gitignoreUpdated = stripIgnoreEntry(gitignore, ignoreEntry);

        return new DetachResult(moved, conflicts, gitignoreUpdated);
    }

    private void movePromotableFiles(Path generatedRoot, Path targetRoot,
                                     List<Path> moved, List<Path> conflicts) throws IOException {
        List<Path> sources;
        try (Stream<Path> walk = Files.walk(generatedRoot)) {
            // Deterministic order so a partial failure is reproducible and the
            // moved/conflicts lists are stable across runs.
            sources = walk.filter(Files::isRegularFile).sorted().toList();
        }

        for (Path source : sources) {
            Path relative = generatedRoot.relativize(source);
            Path target = targetRoot.resolve(relative);
            if (Files.exists(target)) {
                conflicts.add(relative);
                continue;
            }
            Files.createDirectories(target.getParent());
            Files.move(source, target);
            moved.add(relative);
        }
    }

    private void pruneEmptyDirs(Path generatedRoot) throws IOException {
        List<Path> dirs;
        try (Stream<Path> walk = Files.walk(generatedRoot)) {
            // Deepest-first so children are removed before parents.
            dirs = walk.filter(Files::isDirectory)
                    .sorted(Comparator.reverseOrder())
                    .toList();
        }
        for (Path dir : dirs) {
            try (Stream<Path> entries = Files.list(dir)) {
                if (entries.findAny().isEmpty()) {
                    Files.delete(dir);
                }
            }
        }
    }

    private boolean stripIgnoreEntry(Path gitignore, String ignoreEntry) throws IOException {
        if (gitignore == null || ignoreEntry == null || !Files.isRegularFile(gitignore)) {
            return false;
        }
        String wanted = normalizeIgnore(ignoreEntry);
        List<String> lines = Files.readAllLines(gitignore);
        List<String> kept = lines.stream()
                .filter(line -> !normalizeIgnore(line).equals(wanted))
                .toList();
        if (kept.size() == lines.size()) {
            return false;
        }
        Files.write(gitignore, kept);
        return true;
    }

    /** Trims whitespace, a leading {@code /}, and a trailing {@code /} so
     *  {@code "/src/main/generated/"} and {@code "src/main/generated"} match. */
    private static String normalizeIgnore(String entry) {
        String s = entry.strip();
        if (s.startsWith("/")) {
            s = s.substring(1);
        }
        if (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }
}
