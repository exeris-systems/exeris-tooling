package eu.exeris.tooling.initializr.model;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * One file of a generated project: a relative path, its bytes, and whether it is a script to run.
 *
 * <p>The path is relative to the project root, uses forward slashes, and has no empty, {@code .} or
 * {@code ..} segment, so writing it under a root cannot leave that root.
 *
 * @param path       the path relative to the project root, with forward slashes
 * @param content    the file's bytes; the record keeps a copy and returns one
 * @param executable whether the file is a script the owner may execute
 * @since 0.10
 */
public record ScaffoldFile(String path, byte[] content, boolean executable) {

    /**
     * Validates the path and copies the content.
     *
     * @throws NullPointerException     if {@code path} or {@code content} is {@code null}
     * @throws IllegalArgumentException if {@code path} is absolute, uses a backslash, or has an
     *                                  empty, {@code .} or {@code ..} segment
     */
    public ScaffoldFile {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(content, "content");
        if (path.isEmpty() || path.startsWith("/") || path.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("not a relative path with forward slashes: " + path);
        }
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException("path has an empty, '.' or '..' segment: " + path);
            }
        }
        content = content.clone();
    }

    /**
     * Creates a file that is not executable from text, encoded as UTF-8.
     *
     * @param path the path relative to the project root
     * @param text the file's text
     * @return the file
     * @since 0.10
     */
    public static ScaffoldFile text(String path, String text) {
        return new ScaffoldFile(path, text.getBytes(StandardCharsets.UTF_8), false);
    }

    /**
     * Creates an executable script from text, encoded as UTF-8.
     *
     * @param path the path relative to the project root
     * @param text the script's text
     * @return the file, marked executable
     * @since 0.10
     */
    public static ScaffoldFile script(String path, String text) {
        return new ScaffoldFile(path, text.getBytes(StandardCharsets.UTF_8), true);
    }

    /**
     * Returns a copy of the file's bytes.
     *
     * @return the content; changing the array does not change this file
     * @since 0.10
     */
    @Override
    public byte[] content() {
        return content.clone();
    }

    /**
     * Returns the content decoded as UTF-8.
     *
     * @return the file's text
     * @since 0.10
     */
    public String contentAsString() {
        return new String(content, StandardCharsets.UTF_8);
    }

    /**
     * Compares path, executable flag and content, the content byte by byte.
     *
     * @param other the object to compare with
     * @return whether {@code other} is a file with the same path, flag and bytes
     */
    @Override
    public boolean equals(Object other) {
        return other instanceof ScaffoldFile that
                && path.equals(that.path)
                && executable == that.executable
                && Arrays.equals(content, that.content);
    }

    /**
     * Hashes path, executable flag and content, consistently with {@link #equals(Object)}.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(path, executable, Arrays.hashCode(content));
    }

    /**
     * Describes the file by path, size and flag, without its content.
     *
     * @return a one-line description
     */
    @Override
    public String toString() {
        return "ScaffoldFile[path=" + path + ", size=" + content.length + ", executable=" + executable + "]";
    }
}
