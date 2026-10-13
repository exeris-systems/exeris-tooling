package eu.exeris.tooling.initializr.model;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * A single generated file in the scaffolded project tree.
 */
public record ScaffoldFile(String path, byte[] content) {

    public ScaffoldFile {
        Objects.requireNonNull(path, "path must not be null");
        Objects.requireNonNull(content, "content must not be null");
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
    }

    public static ScaffoldFile text(String path, String text) {
        return new ScaffoldFile(path, text.getBytes(StandardCharsets.UTF_8));
    }

    public String contentAsString() {
        return new String(content, StandardCharsets.UTF_8);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ScaffoldFile that)) {
            return false;
        }
        return Objects.equals(path, that.path) && Arrays.equals(content, that.content);
    }

    @Override
    public int hashCode() {
        int result = Objects.hashCode(path);
        result = 31 * result + Arrays.hashCode(content);
        return result;
    }

    @Override
    public String toString() {
        return "ScaffoldFile{" +
                "path='" + path + '\'' +
                ", size=" + content.length + " bytes" +
                '}';
    }
}
