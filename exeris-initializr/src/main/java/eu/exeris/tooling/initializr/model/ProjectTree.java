package eu.exeris.tooling.initializr.model;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The files of one generated project, in the order they are written, each path once.
 *
 * @param files the files; the tree keeps an unmodifiable copy
 * @since 0.10
 */
public record ProjectTree(List<ScaffoldFile> files) {

    /**
     * Copies the files and checks that no path appears twice.
     *
     * @throws NullPointerException     if {@code files} or one of its elements is {@code null}
     * @throws IllegalArgumentException if two files share a path
     */
    public ProjectTree {
        files = List.copyOf(Objects.requireNonNull(files, "files"));
        Set<String> seen = new HashSet<>();
        for (ScaffoldFile file : files) {
            if (!seen.add(file.path())) {
                throw new IllegalArgumentException("two files share the path " + file.path());
            }
        }
    }

    /**
     * Finds the file at a path.
     *
     * @param path the path relative to the project root, with forward slashes
     * @return the file, or empty when the tree has none at {@code path}
     * @since 0.10
     */
    public Optional<ScaffoldFile> find(String path) {
        return files.stream().filter(f -> f.path().equals(path)).findFirst();
    }

    /**
     * Returns the paths of every file, in the order the files are written.
     *
     * @return the paths
     * @since 0.10
     */
    public List<String> paths() {
        return files.stream().map(ScaffoldFile::path).toList();
    }
}
