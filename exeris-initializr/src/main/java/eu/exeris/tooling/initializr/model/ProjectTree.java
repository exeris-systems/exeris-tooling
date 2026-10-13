package eu.exeris.tooling.initializr.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * An immutable in-memory tree of scaffolded files.
 */
public record ProjectTree(List<ScaffoldFile> files) {

    public ProjectTree {
        Objects.requireNonNull(files, "files must not be null");
        files = List.copyOf(files);
    }

    public static ProjectTree of(List<ScaffoldFile> files) {
        return new ProjectTree(files);
    }

    public Map<String, byte[]> asMap() {
        Map<String, byte[]> map = new LinkedHashMap<>();
        for (ScaffoldFile file : files) {
            map.put(file.path(), file.content());
        }
        return Collections.unmodifiableMap(map);
    }

    public Optional<ScaffoldFile> find(String path) {
        String normalized = path.startsWith("/") ? path.substring(1) : path;
        return files.stream()
                .filter(f -> f.path().equals(normalized))
                .findFirst();
    }

    public boolean hasFile(String path) {
        return find(path).isPresent();
    }

    public int size() {
        return files.size();
    }
}
